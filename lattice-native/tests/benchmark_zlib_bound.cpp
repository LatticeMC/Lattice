#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <libdeflate.h>

#include "io/compression/zlib_codec.hpp"

namespace {
using namespace lattice::io::compression;

struct AllocatorProbe {
    static inline std::size_t allocations = 0;
    static inline std::size_t frees = 0;
    static inline std::size_t bytes = 0;

    static void* allocate(std::size_t size) {
        ++allocations;
        bytes += size;
        return std::malloc(size);
    }
    static void release(void* ptr) {
        if (ptr) ++frees;
        std::free(ptr);
    }
    AllocatorProbe() {
        allocations = frees = bytes = 0;
        libdeflate_set_memory_allocator(allocate, release);
    }
    ~AllocatorProbe() {
        libdeflate_set_memory_allocator(std::malloc, std::free);
    }
};

std::size_t legacy_bound(std::size_t size) {
    auto* compressor = libdeflate_alloc_compressor(kDefaultLevel);
    if (!compressor) return 0;
    const auto bound = libdeflate_zlib_compress_bound(compressor, size);
    libdeflate_free_compressor(compressor);
    return bound;
}
} // namespace

int main() {
    using Clock = std::chrono::steady_clock;
    constexpr int rounds = 512;
    constexpr int warmup = 4;
    constexpr int samples = 21;
    volatile std::size_t sink = 0;
    std::printf("warmup=%d,samples=%d,rounds=%d,level=%d\n",
                warmup, samples, rounds, kDefaultLevel);
    std::puts("kind,size,path,sample,ns,allocs,bytes");
    for (std::size_t size : {1024u, 65536u, 1048576u, 1056768u}) {
        const auto expected = legacy_bound(size);
        if (expected == 0 || zlib_compress_bound(size) != expected) return 1;
        for (int sample = -warmup; sample < samples; ++sample) {
            for (int arm = 0; arm < 2; ++arm) {
                const bool old = ((sample & 1) == 0) == (arm == 0);
                AllocatorProbe probe;
                std::size_t sum = 0;
                const auto start = Clock::now();
                for (int round = 0; round < rounds; ++round) {
                    sum += old ? legacy_bound(size) : zlib_compress_bound(size);
                }
                const auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
                sink = sum;
                if (sum != expected * rounds ||
                    AllocatorProbe::allocations != (old ? rounds : 0) ||
                    AllocatorProbe::frees != AllocatorProbe::allocations) {
                    std::fputs("bound parity or allocation contract failed\n", stderr);
                    return 1;
                }
                if (sample >= 0) {
                    std::printf("bound-sample,%zu,%s,%d,%.3f,%.3f,%.3f\n",
                                size, old ? "legacy" : "current", sample,
                                static_cast<double>(nanos) / rounds,
                                static_cast<double>(AllocatorProbe::allocations) / rounds,
                                static_cast<double>(AllocatorProbe::bytes) / rounds);
                }
            }
        }
    }
    return sink == 0 ? 1 : 0;
}
