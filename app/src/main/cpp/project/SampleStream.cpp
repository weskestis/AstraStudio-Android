#include "SampleStream.h"
#include <algorithm>
#include <bit>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <sys/stat.h>
namespace astra::project {
namespace {
static_assert(std::atomic<uint64_t>::is_always_lock_free&&std::atomic<int>::is_always_lock_free);
constexpr int pageFrames=4096,pageCount=64,queueSize=256;
constexpr std::size_t cacheBytes=pageFrames*pageCount*2*sizeof(float);
uint16_t u16(const unsigned char* p){return uint16_t(p[0]|(p[1]<<8));}
uint32_t u32(const unsigned char* p){return p[0]|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24);}
struct Layout {int format{},channels{},bits{},rate{},align{};int64_t offset{},frames{};};
bool layout(std::FILE* file,Layout& l){
    unsigned char h[12];if(std::fread(h,1,12,file)!=12||std::memcmp(h,"RIFF",4)||std::memcmp(h+8,"WAVE",4))return false;
    struct stat stat{};if(fstat(fileno(file),&stat))return false;
    const int64_t end=std::min<int64_t>(stat.st_size,int64_t(u32(h+4))+8);uint32_t bytes=0;
    for(int n=0;n<10000;++n){unsigned char c[8];if(ftello(file)+8>end||std::fread(c,1,8,file)!=8)break;
        const auto size=u32(c+4);const int64_t at=ftello(file);if(at+size>end)return false;
        if(!std::memcmp(c,"fmt ",4)){unsigned char f[40]{};const auto count=std::min<uint32_t>(size,40);
            if(size<16||std::fread(f,1,count,file)!=count)return false;
            l.format=u16(f);l.channels=u16(f+2);l.rate=int(u32(f+4));l.align=u16(f+12);l.bits=u16(f+14);
            if(l.format==0xfffe){constexpr unsigned char suffix[12]={0,0,16,0,128,0,0,170,0,56,155,113};
                if(size<40||u16(f+16)<22||std::memcmp(f+28,suffix,12)||u16(f+26)||u16(f+18)!=l.bits)return false;
                l.format=u16(f+24);}
        }else if(!std::memcmp(c,"data",4)){l.offset=at;bytes=size;}
        if(l.offset&&l.format)break;
        if(fseeko(file,at+size+(size&1U),SEEK_SET))return false;
    }
    if(!l.offset||l.rate<8000||l.rate>384000||l.channels<1||l.channels>2||l.align!=l.channels*l.bits/8||
       !((l.format==1&&(l.bits==16||l.bits==24||l.bits==32))||(l.format==3&&l.bits==32))||!bytes||bytes%l.align)return false;
    l.frames=bytes/l.align;return l.frames<=kMaxSourceFrames;
}
bool decode(const unsigned char* raw,float* out,int count,const Layout& l)noexcept{
    for(int i=0;i<count;++i)for(int ch=0;ch<2;++ch){const auto* p=raw+i*l.align+std::min(ch,l.channels-1)*(l.bits/8);float v;
        if(l.format==3)v=std::bit_cast<float>(u32(p));
        else if(l.bits==16)v=static_cast<int16_t>(u16(p))/32768.f;
        else if(l.bits==24){int32_t n=p[0]|(p[1]<<8)|(p[2]<<16);if(n&0x800000)n|=int32_t(0xff000000U);v=n/8388608.f;}
        else v=static_cast<int32_t>(u32(p))/2147483648.f;
        if(!std::isfinite(v))return false;
        out[i*2+ch]=std::clamp(v,-8.f,8.f);
    }
    return true;
}
// Bounded MPSC requests. Producer CAS retries are capped: the callback never waits.
class Requests {
    struct Cell {std::atomic<uint64_t> sequence{};int64_t page{};bool demand{};};
    std::array<Cell,queueSize> cells_;std::atomic<uint64_t> tail_{0};uint64_t head_{};
public:
    Requests(){for(uint64_t i=0;i<queueSize;++i)cells_[i].sequence=i;}
    bool push(int64_t page,bool demand)noexcept{auto pos=tail_.load(std::memory_order_relaxed);
        for(int tries=0;tries<4;++tries){auto& c=cells_[pos%queueSize];const auto seq=c.sequence.load(std::memory_order_acquire);
            if(seq<pos)return false;
            if(seq==pos&&tail_.compare_exchange_weak(pos,pos+1,std::memory_order_relaxed)){c.page=page;c.demand=demand;c.sequence.store(pos+1,std::memory_order_release);return true;}
            pos=tail_.load(std::memory_order_relaxed);
        }return false;
    }
    bool pop(int64_t& page,bool& demand)noexcept{auto& c=cells_[head_%queueSize];if(c.sequence.load(std::memory_order_acquire)!=head_+1)return false;
        page=c.page;demand=c.demand;c.sequence.store(head_+queueSize,std::memory_order_release);++head_;return true;}
};
}
class SampleStream {
    struct Page {std::atomic<int> readers{0};std::atomic<int64_t> key{-1};std::atomic<uint64_t> use{0};uint64_t birth{};std::array<float,pageFrames*2> audio{};};
    std::unique_ptr<std::array<Page,pageCount>> pages_=std::make_unique<std::array<Page,pageCount>>();
    std::array<unsigned char,pageFrames*8> raw_{};Requests requests_;
    std::atomic<uint64_t> epoch_{1},misses_{0},reads_{0};std::atomic<bool> error_{false};
    uint64_t birth_{};std::FILE* file_{};Layout layout_;std::string path_;bool synchronous_{};
    int find(int64_t key)const noexcept{for(int i=0;i<pageCount;++i)if((*pages_)[i].key.load(std::memory_order_acquire)==key)return i;return -1;}
    bool fill(int64_t key,bool demand=false)noexcept{
        if(!good())return false;
        if(key<0||key*pageFrames>=layout_.frames)return true;
        const auto now=epoch_.load(std::memory_order_relaxed);
        if(const int found=find(key);found>=0){if(demand)(*pages_)[found].use.store(now,std::memory_order_relaxed);return true;}
        int victim=-1;uint64_t oldest=UINT64_MAX;
        for(int i=0;i<pageCount;++i){const auto& p=(*pages_)[i];if(p.readers.load(std::memory_order_acquire))continue;
            const auto use=p.use.load(std::memory_order_relaxed);
            // Recent demand pages get a short lease. Speculative lookahead cannot evict an active voice.
            if(!synchronous_&&use&&now-use<=16)continue;
            if(p.birth<oldest){oldest=p.birth;victim=i;}}
        if(victim<0)return false;
        auto& p=(*pages_)[victim];int expected=0;
        if(!p.readers.compare_exchange_strong(expected,-1,std::memory_order_acq_rel))return false;
        const auto touched=p.use.load(std::memory_order_relaxed);
        if(!synchronous_&&touched&&now-touched<=16){p.readers.store(0,std::memory_order_release);return false;}
        p.key.store(-1,std::memory_order_release);
        const int count=int(std::min<int64_t>(pageFrames,layout_.frames-key*pageFrames));
        const bool good=!fseeko(file_,layout_.offset+key*pageFrames*layout_.align,SEEK_SET)&&
            std::fread(raw_.data(),layout_.align,count,file_)==std::size_t(count)&&decode(raw_.data(),p.audio.data(),count,layout_);
        reads_.fetch_add(1,std::memory_order_relaxed);
        if(good){std::fill(p.audio.begin()+count*2,p.audio.end(),0.f);p.birth=++birth_;p.use.store(demand?now:0,std::memory_order_relaxed);p.key.store(key,std::memory_order_release);}
        else error_.store(true,std::memory_order_release);
        p.readers.store(0,std::memory_order_release);return good;
    }
public:
    SampleStream(std::FILE* file,Layout l,std::string path,bool sync):file_(file),layout_(l),path_(std::move(path)),synchronous_(sync){}
    ~SampleStream(){if(file_)std::fclose(file_);}
    void prime(int64_t frame)noexcept{for(int n=0;n<8;++n)fill(frame/pageFrames+n);}
    bool good()const noexcept{return !error_.load(std::memory_order_acquire);}
    uint64_t misses()const noexcept{return misses_.load(std::memory_order_relaxed);}
    uint64_t reads()const noexcept{return reads_.load(std::memory_order_relaxed);}
    void request(int64_t page,bool demand=false)noexcept{if(page>=0&&page*pageFrames<layout_.frames&&find(page)<0)requests_.push(page,demand);}
    void prefetch(int64_t frame,int direction=1)noexcept{const auto page=std::max<int64_t>(0,frame)/pageFrames;if(synchronous_)return;for(int n=0;n<3;++n)request(page+n*direction,n==0);request(page-direction);}
    void service()noexcept{epoch_.fetch_add(1,std::memory_order_relaxed);int64_t key;bool demand;for(int i=0;i<64&&requests_.pop(key,demand);++i)if(!fill(key,demand)&&good())requests_.push(key,demand);}
    bool read(int64_t first,float* out,int count,SampleCursor& cursor)noexcept{
        bool complete=true;
        while(count>0){const auto key=first/pageFrames;const auto offset=int(first%pageFrames);const auto n=std::min(count,pageFrames-offset);
            if(cursor.source!=this||cursor.page!=key){const int direction=cursor.source==this&&key<cursor.page?-1:1;cursor.source=this;cursor.page=key;cursor.slot=-1;if(!synchronous_)prefetch(first,direction);}
            if(synchronous_&&!fill(key))return false;
            const int index=cursor.slot>=0&&(*pages_)[cursor.slot].key.load(std::memory_order_acquire)==key?cursor.slot:find(key);cursor.slot=index;bool copied=false;
            if(index>=0){auto& p=(*pages_)[index];int readers=p.readers.load(std::memory_order_acquire);
                if(readers>=0&&p.readers.compare_exchange_strong(readers,readers+1,std::memory_order_acq_rel)){
                    if(p.key.load(std::memory_order_acquire)==key){std::memcpy(out,p.audio.data()+offset*2,n*2*sizeof(float));p.use.store(epoch_.load(std::memory_order_relaxed),std::memory_order_relaxed);copied=true;}
                    p.readers.fetch_sub(1,std::memory_order_release);
                }}
            if(!copied){std::fill_n(out,n*2,0.f);misses_.fetch_add(n,std::memory_order_relaxed);complete=false;if(!synchronous_&&(++cursor.retry%64)==0)request(key,true);}
            first+=n;out+=n*2;count-=n;
        }return complete;
    }
    std::shared_ptr<SampleStream> offline()const{
        auto* file=std::fopen(path_.c_str(),"rb");if(!file)return {};
        try{auto result=std::make_shared<SampleStream>(file,layout_,path_,true);return result;}catch(...){std::fclose(file);throw;}
    }
};
namespace {
class DiskWorker {
    std::mutex mutex_;std::condition_variable wake_;std::vector<std::weak_ptr<SampleStream>> streams_;std::atomic<bool> stop_{false};std::thread thread_;
public:
    DiskWorker():thread_([this]{std::unique_lock lock(mutex_);while(!stop_.load(std::memory_order_acquire)){
        for(auto it=streams_.begin();it!=streams_.end();){if(auto s=it->lock()){s->service();++it;}else it=streams_.erase(it);}
        wake_.wait_for(lock,std::chrono::milliseconds(streams_.empty()?250:2));}}){}
    ~DiskWorker(){stop_.store(true,std::memory_order_release);wake_.notify_one();thread_.join();}
    void add(const std::shared_ptr<SampleStream>& s){{std::lock_guard lock(mutex_);streams_.push_back(s);}wake_.notify_one();}
};
DiskWorker& worker(){static DiskWorker instance;return instance;}
}
std::size_t Sample::memoryBytes()const noexcept{return stream?cacheBytes:stereo.size()*sizeof(float);}
bool Sample::read(int64_t first,float* out,int count,SampleCursor& c)const noexcept{
    if(count<0||first<0||first>frames()-count){if(count>0)std::fill_n(out,count*2,0.f);return false;}
    if(stream)return stream->read(first,out,count,c);
    std::memcpy(out,stereo.data()+first*2,count*2*sizeof(float));return true;
}
void Sample::prefetch(int64_t frame)const noexcept{if(stream)stream->prefetch(frame);}
bool Sample::failed()const noexcept{return stream&&!stream->good();}
uint64_t Sample::misses()const noexcept{return stream?stream->misses():0;}
uint64_t Sample::reads()const noexcept{return stream?stream->reads():0;}
std::shared_ptr<const Sample> offlineSample(const std::shared_ptr<const Sample>& source){
    if(!source||!source->stream)return source;
    auto s=std::make_shared<Sample>();s->rate=source->rate;s->sourceFrames=source->sourceFrames;s->peaks=source->peaks;s->stream=source->stream->offline();return s->stream?s:nullptr;
}
std::shared_ptr<const Sample> readSample(const char* path,std::size_t budget,bool streaming,const std::atomic<bool>* cancel){
    struct Close {void operator()(std::FILE* f)const noexcept{std::fclose(f);}};
    std::unique_ptr<std::FILE,Close> file(std::fopen(path,"rb"));if(!file)return {};
    Layout l;if(!layout(file.get(),l))return {};
    const auto bytes=uint64_t(l.frames)*8;const bool disk=streaming&&bytes>kResidentThreshold;
    if((disk?cacheBytes:bytes)>budget)return {};
    auto sample=std::make_shared<Sample>();sample->rate=l.rate;sample->sourceFrames=l.frames;
    if(!disk)sample->stereo.resize(l.frames*2);
    std::array<unsigned char,pageFrames*8> raw{};std::array<float,pageFrames*2> decoded{};
    if(fseeko(file.get(),l.offset,SEEK_SET))return {};
    for(int64_t at=0;at<l.frames;){if(cancel&&cancel->load(std::memory_order_relaxed))return {};
        const auto n=int(std::min<int64_t>(pageFrames,l.frames-at));auto* out=disk?decoded.data():sample->stereo.data()+at*2;
        if(std::fread(raw.data(),l.align,n,file.get())!=std::size_t(n)||!decode(raw.data(),out,n,l))return {};
        for(int i=0;i<n;++i){const auto b=int((at+i)*512/l.frames);for(int ch=0;ch<2;++ch){const float v=out[i*2+ch];sample->peaks[b*2]=std::min(sample->peaks[b*2],v);sample->peaks[b*2+1]=std::max(sample->peaks[b*2+1],v);}}
        at+=n;
    }
    if(disk){sample->stream=std::make_shared<SampleStream>(file.get(),l,path,false);file.release();sample->stream->prime(0);
        if(sample->failed())return {};
        worker().add(sample->stream);}
    return sample;
}
}
