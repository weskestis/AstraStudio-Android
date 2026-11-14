#include "core/RenderCore.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <memory>
#include <new>
#include <thread>
#include <vector>

extern thread_local bool inRender;
extern std::atomic<int> realtimeAllocations;
namespace {
int assertions = 0;
void check(bool condition, const char* label) {
    ++assertions;
    if (!condition) { std::fprintf(stderr, "FAIL: %s\n", label); std::exit(1); }
}
astra::Command note(uint32_t id, int key = 57, uint64_t frame = astra::kImmediate, int track = 0) {
    return {astra::CommandType::NoteOn, track, key, id, 0.8f, frame, 0};
}
void render(astra::RenderCore& core, float* buffer, int frames) {
    inRender = true;
    core.render(buffer, frames);
    inRender = false;
}
double energy(const float* begin, const float* end) {
    double sum = 0;
    while (begin != end) { sum += *begin * *begin; ++begin; }
    return sum;
}
}

int main() {
    using namespace astra;
    {
        SpscRing<uint32_t, 8> ring;
        for (uint32_t i = 0; i < 8; ++i) check(ring.push(i), "ring exact capacity");
        check(!ring.push(8), "ring rejects overflow");
        uint32_t value = 0;
        for (uint32_t i = 0; i < 8; ++i) { check(ring.pop(value), "ring pop"); check(value == i, "ring FIFO"); }
        check(!ring.pop(value), "ring empty");
    }
    {
        SpscRing<uint32_t, 1024> ring;
        constexpr uint32_t count = 500000;
        std::atomic<bool> correct{true};
        std::thread producer([&] { for (uint32_t i = 0; i < count; ++i) while (!ring.push(i)) std::this_thread::yield(); });
        std::thread consumer([&] {
            for (uint32_t i = 0; i < count; ++i) {
                uint32_t value = 0;
                while (!ring.pop(value)) std::this_thread::yield();
                if (value != i) correct = false;
            }
        });
        producer.join(); consumer.join();
        check(correct, "concurrent ring publication and wraparound");
    }
    std::vector<float> buffer(16384, 10.0f);
    {
        auto core = std::make_unique<RenderCore>();
        check(!core->prepare(std::numeric_limits<double>::quiet_NaN()), "invalid sample rate");
        check(core->prepare(48000), "prepare 48k");
        render(*core, buffer.data(), 517);
        check(energy(buffer.data(), buffer.data() + 1034) == 0, "uninitialized output overwritten with silence");
        check(core->snapshot().frame == 517, "clock honors arbitrary callback length");
        check(!core->enqueue(note(0)), "zero note ID rejected");
        auto bad = note(1); bad.value = std::numeric_limits<float>::quiet_NaN();
        check(!core->enqueue(bad), "NaN rejected");
        bad = note(1); bad.type = static_cast<CommandType>(99);
        check(!core->enqueue(bad), "unknown command rejected");
    }
    {
        auto core = std::make_unique<RenderCore>();
        check(core->enqueue(note(1, 57, 100)), "schedule note");
        check(core->enqueue({CommandType::NoteOff, 0, 0, 1, 0, 500}), "schedule release");
        render(*core, buffer.data(), 4096);
        check(energy(buffer.data(), buffer.data() + 200) == 0, "no note before exact frame");
        check(buffer[202] != 0, "note starts at sample boundary");
        check(energy(buffer.data() + 204, buffer.data() + 900) > 0.001, "voice produces sound");
        check(energy(buffer.data() + 6000, buffer.data() + 8192) == 0, "release finishes");
        check(core->snapshot().activeVoices == 0, "note off retires voice");
        check(core->snapshot().lateCommands == 0, "scheduled events on time");
    }
    {
        auto a = std::make_unique<RenderCore>();
        auto b = std::make_unique<RenderCore>();
        for (auto* c : {a.get(), b.get()}) {
            c->enqueue(note(1, 40, 21));
            c->enqueue(note(2, 47, 99));
            c->enqueue({CommandType::TrackPan, 0, 0, 0, 0.7f, 333});
            c->enqueue({CommandType::NoteOff, 0, 0, 1, 0, 800});
        }
        std::vector<float> whole(8192), split(8192);
        render(*a, whole.data(), 4096);
        int rendered = 0;
        for (int amount : {1, 17, 37, 127, 512, 256, 1024, 2122}) {
            render(*b, split.data() + 2 * rendered, amount);
            rendered += amount;
        }
        check(rendered == 4096 && whole == split, "sample exact behavior independent of callback partitions");
    }
    {
        auto core = std::make_unique<RenderCore>();
        core->enqueue({CommandType::TrackPan, 0, 0, 0, 1});
        core->enqueue(note(1));
        render(*core, buffer.data(), 8192);
        double left = 0, right = 0;
        for (int i = 4096; i < 8192; ++i) { left += buffer[2*i] * buffer[2*i]; right += buffer[2*i+1] * buffer[2*i+1]; }
        check(right > 1 && left < right * 1e-10, "constant-power pan reaches right channel");
        core->enqueue({CommandType::TrackMute, 0, 0, 0, 1});
        render(*core, buffer.data(), 8192);
        check(energy(buffer.data() + 8192, buffer.data() + 16384) < 1e-10, "mute smoothing settles");
        core->enqueue({CommandType::TrackMute, 0, 0, 0, 0});
        core->enqueue({CommandType::TrackSolo, 1, 0, 0, 1});
        render(*core, buffer.data(), 8192);
        check(energy(buffer.data() + 8192, buffer.data() + 16384) < 1e-10, "solo suppresses other tracks");
    }
    {
        auto core = std::make_unique<RenderCore>();
        core->enqueue(note(1, 57, 1000));
        core->enqueue({CommandType::Transport, 0, 0, 0, 0});
        core->enqueue({CommandType::Transport, 0, 0, 0, 1});
        core->enqueue(note(2, 60, 1500));
        render(*core, buffer.data(), 2000);
        check(energy(buffer.data(), buffer.data() + 3000) == 0, "stop cancels older future notes");
        check(core->snapshot().activeVoices == 1 && energy(buffer.data() + 3002, buffer.data() + 4000) > 0, "stop preserves newly submitted pattern");
    }
    {
        auto core = std::make_unique<RenderCore>();
        core->enqueue(note(1));
        render(*core, buffer.data(), 256);
        check(core->snapshot().activeVoices == 1, "voice started before overflow");
        for (std::size_t i = 0; i < kQueueCapacity; ++i) check(core->enqueue({CommandType::MasterGain, 0, 0, 0, 0.7f}), "fill command queue");
        check(!core->enqueue({CommandType::NoteOff, 0, 0, 1, 0}), "overflow is returned");
        render(*core, buffer.data(), 256);
        check(core->snapshot().activeVoices == 0 && core->snapshot().droppedCommands == 1, "overflow panic prevents stuck note");
        check(energy(buffer.data(), buffer.data() + 512) == 0, "panic clears output");
    }
    {
        auto core = std::make_unique<RenderCore>();
        for (int i = 1; i <= 180; ++i) core->enqueue(note(static_cast<uint32_t>(i), 57));
        render(*core, buffer.data(), 4096);
        check(core->snapshot().activeVoices == 128, "voice pool bounded");
        check(core->snapshot().clippedSamples > 0, "overload meter detects sample clamp");
        for (int i = 0; i < 8192; ++i) check(std::isfinite(buffer[static_cast<std::size_t>(i)]) && std::abs(buffer[static_cast<std::size_t>(i)]) <= 0.988554f, "master sample ceiling");
        core->panic();
        render(*core, buffer.data(), 256);
        check(core->snapshot().activeVoices == 0, "explicit panic clears polyphony");
    }
    {
        auto core = std::make_unique<RenderCore>();
        core->enqueue({CommandType::Tempo, 0, 0, 0, 120});
        core->enqueue({CommandType::Transport, 0, 0, 0, 1});
        for (int i = 0; i < 100; ++i) render(*core, buffer.data(), 480);
        check(std::abs(static_cast<double>(core->snapshot().beatMicro) / 1e6 - 2.0) < 0.00001, "tempo integrated on sample clock");
    }
    {
        auto core = std::make_unique<RenderCore>();
        core->enqueue({CommandType::Tempo, 0, 0, 0, 120});
        core->enqueue({CommandType::Transport, 0, 0, 0, 1});
        core->enqueue({CommandType::Tempo, 0, 0, 0, 60, 24000});
        for (int i = 0; i < 100; ++i) render(*core, buffer.data(), 480);
        check(std::abs(static_cast<double>(core->snapshot().beatMicro) / 1e6 - 1.5) < 0.00001, "tempo changes preserve accumulated beat position");
        core->enqueue(note(1, 60, 10));
        render(*core, buffer.data(), 128);
        check(core->snapshot().lateCommands == 1, "past event is counted late");
    }
    {
        auto core = std::make_unique<RenderCore>();
        for (std::size_t batch = 0; batch < kScheduledCapacity / 256 + 1; ++batch) {
            for (int i = 0; i < 256; ++i) core->enqueue(note(static_cast<uint32_t>(batch * 256 + i + 1), 60, 1000000));
            render(*core, buffer.data(), 64);
        }
        check(core->snapshot().droppedCommands > 0, "fixed event heap overflow is observable");
        render(*core, buffer.data(), 128);
        check(core->snapshot().activeVoices == 0, "heap overflow panic clears voices");
    }
    check(realtimeAllocations == 0, "no operator-new allocations during render including event dispatch");
    std::printf("PASS: %d assertions; queue concurrency, scheduling, mixing, safety and realtime allocation checks\n", assertions);
}
