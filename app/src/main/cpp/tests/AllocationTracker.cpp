#include <atomic>
#include <cstdlib>
#include <new>

thread_local bool inRender = false;
std::atomic<int> realtimeAllocations{0};
std::atomic<int> realtimeDeallocations{0};
void trackedFree(void* p) noexcept { if(p&&inRender)realtimeDeallocations.fetch_add(1);std::free(p); }

void* operator new(std::size_t size) {
    if (inRender) realtimeAllocations.fetch_add(1);
    if (void* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void* operator new(std::size_t size, std::align_val_t alignment) {
    if (inRender) realtimeAllocations.fetch_add(1);
    const auto align = static_cast<std::size_t>(alignment);
    const auto rounded = ((size ? size : 1) + align - 1) / align * align;
    if (void* p = std::aligned_alloc(align, rounded)) return p;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size, std::align_val_t alignment) { return ::operator new(size, alignment); }
void operator delete(void* p) noexcept { trackedFree(p); }
void operator delete[](void* p) noexcept { trackedFree(p); }
void operator delete(void* p, std::size_t) noexcept { trackedFree(p); }
void operator delete[](void* p, std::size_t) noexcept { trackedFree(p); }
void operator delete(void* p, std::align_val_t) noexcept { trackedFree(p); }
void operator delete[](void* p, std::align_val_t) noexcept { trackedFree(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { trackedFree(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { trackedFree(p); }

// Delay storage uses nothrow allocation during preparation. Keep every allocation
// family in this tracker so ASan sees matching malloc/free, including failures.
void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
    if (inRender) realtimeAllocations.fetch_add(1);
    return std::malloc(size ? size : 1);
}
void* operator new[](std::size_t size, const std::nothrow_t& tag) noexcept { return ::operator new(size, tag); }
void* operator new(std::size_t size, std::align_val_t alignment, const std::nothrow_t&) noexcept {
    try { return ::operator new(size, alignment); } catch (const std::bad_alloc&) { return nullptr; }
}
void* operator new[](std::size_t size, std::align_val_t alignment, const std::nothrow_t& tag) noexcept { return ::operator new(size, alignment, tag); }
void operator delete(void* p, const std::nothrow_t&) noexcept { trackedFree(p); }
void operator delete[](void* p, const std::nothrow_t&) noexcept { trackedFree(p); }
void operator delete(void* p, std::align_val_t, const std::nothrow_t&) noexcept { trackedFree(p); }
void operator delete[](void* p, std::align_val_t, const std::nothrow_t&) noexcept { trackedFree(p); }
