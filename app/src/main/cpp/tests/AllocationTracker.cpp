#include <atomic>
#include <cstdlib>
#include <new>

thread_local bool inRender = false;
std::atomic<int> realtimeAllocations{0};

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
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }
void operator delete(void* p, std::align_val_t) noexcept { std::free(p); }
void operator delete[](void* p, std::align_val_t) noexcept { std::free(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { std::free(p); }
