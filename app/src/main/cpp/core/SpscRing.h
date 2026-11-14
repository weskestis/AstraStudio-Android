#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace astra {
// Exactly one producer and one consumer. JNI serializes all control producers.
// Each end owns its index; acquire/release publishes the non-atomic payload.
template <typename T, std::size_t Capacity>
class SpscRing final {
    static_assert(Capacity >= 2 && (Capacity & (Capacity - 1)) == 0);
    static_assert(Capacity < (1u << 30));
    static_assert(std::is_trivially_copyable_v<T>);
    static_assert(std::atomic<uint32_t>::is_always_lock_free);
public:
    bool push(const T& value) noexcept {
        const auto write = write_.load(std::memory_order_relaxed);
        if (write - read_.load(std::memory_order_acquire) == Capacity) return false;
        data_[write & (Capacity - 1)] = value;
        write_.store(write + 1, std::memory_order_release);
        return true;
    }
    bool pop(T& value) noexcept {
        const auto read = read_.load(std::memory_order_relaxed);
        if (read == write_.load(std::memory_order_acquire)) return false;
        value = data_[read & (Capacity - 1)];
        read_.store(read + 1, std::memory_order_release);
        return true;
    }
private:
    alignas(64) std::atomic<uint32_t> write_{0};
    alignas(64) std::atomic<uint32_t> read_{0};
    alignas(64) std::array<T, Capacity> data_{};
};
} // namespace astra
