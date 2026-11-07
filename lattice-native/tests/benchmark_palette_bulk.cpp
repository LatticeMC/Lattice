#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>

#include "lattice/dispatch.hpp"
#include "world/palette/packed_storage.hpp"

namespace {

using BulkGetFn = void (*)(const std::uint64_t*, int, std::size_t,
                           std::size_t, std::uint32_t*) noexcept;
using Clock = std::chrono::steady_clock;

constexpr int kWarmupSamples = 16;
constexpr int kSamples = 21;
constexpr std::size_t kRounds = 4096;
constexpr std::uint32_t kSentinel = 0xdeadbeefU;
volatile std::uint64_t g_checksum_sink = 0;

std::uint64_t output_checksum(const std::uint32_t* values,
                              std::size_t count) noexcept {
    std::uint64_t checksum = 0xcbf29ce484222325ULL;
    for (std::size_t i = 0; i < count; ++i) {
        checksum = (checksum ^ values[i]) * 0x100000001b3ULL;
    }
    return checksum;
}

std::int64_t time_batch(BulkGetFn function, const std::uint64_t* data,
                        int bits, std::size_t start, std::size_t count,
                        std::uint32_t* output) noexcept {
    const auto before = Clock::now();
    for (std::size_t round = 0; round < kRounds; ++round) {
        function(data, bits, start, count, output);
        std::atomic_signal_fence(std::memory_order_seq_cst);
    }
    const auto after = Clock::now();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(after - before).count();
}

bool verify_batch(const std::vector<std::uint64_t>& data,
                  const std::vector<std::uint64_t>& original_data,
                  const std::vector<std::uint32_t>& output,
                  const std::vector<std::uint32_t>& expected,
                  std::uint64_t expected_checksum,
                  std::uint64_t& checksum) noexcept {
    if (output.front() != kSentinel || output.back() != kSentinel) return false;
    for (std::size_t i = 0; i < expected.size(); ++i) {
        if (output[i + 1] != expected[i]) return false;
    }
    if (data.size() != original_data.size()) return false;
    for (std::size_t i = 0; i < data.size(); ++i) {
        if (data[i] != original_data[i]) return false;
    }
    checksum = output_checksum(output.data() + 1, expected.size());
    return checksum == expected_checksum;
}

bool run_case(int bits, std::size_t start, std::size_t count,
              std::mt19937_64& random, bool bmi2, bool bmi2_fast) {
    const std::size_t elements_per_long = 64U / static_cast<unsigned>(bits);
    const std::size_t last_element = start + count;
    const std::size_t long_count = (last_element + elements_per_long - 1) /
                                   elements_per_long;
    std::vector<std::uint64_t> data(long_count);
    for (auto& word : data) word = random();
    const std::vector<std::uint64_t> original_data = data;

    const std::uint64_t mask = (std::uint64_t{1} << bits) - 1;
    std::vector<std::uint32_t> expected(count);
    for (std::size_t i = 0; i < count; ++i) {
        const std::size_t index = start + i;
        const std::size_t word_index = index / elements_per_long;
        const unsigned bit_offset = static_cast<unsigned>(index % elements_per_long) *
                                    static_cast<unsigned>(bits);
        expected[i] = static_cast<std::uint32_t>((data[word_index] >> bit_offset) & mask);
    }
    const std::uint64_t expected_checksum = output_checksum(expected.data(), expected.size());
    std::vector<std::uint32_t> output(count + 2, kSentinel);

    const std::array<BulkGetFn, 2> functions = {
        &lattice::world::palette::bulk_get_scalar,
        &lattice::world::palette::bulk_get,
    };
    constexpr std::array<const char*, 2> names = {"scalar", "bulk"};

    for (int sample = -kWarmupSamples; sample < kSamples; ++sample) {
        const int first = (sample + kWarmupSamples) & 1;
        for (int offset = 0; offset < 2; ++offset) {
            const int implementation = first ^ offset;
            std::fill(output.begin(), output.end(), kSentinel);
            const std::int64_t elapsed = time_batch(
                functions[implementation], data.data(), bits, start, count,
                output.data() + 1);

            std::uint64_t checksum = 0;
            if (!verify_batch(data, original_data, output, expected,
                              expected_checksum, checksum)) {
                std::fprintf(stderr,
                             "palette bulk parity/sentinel/input failure: bits=%d start=%zu count=%zu impl=%s sample=%d\n",
                             bits, start, count, names[implementation], sample);
                return false;
            }
            g_checksum_sink = checksum;
            if (sample >= 0) {
                std::printf("%d,%zu,%zu,%s,%d,%.3f,%llu,%d,%d\n",
                            bits, start, count, names[implementation], sample,
                            static_cast<double>(elapsed) / kRounds,
                            static_cast<unsigned long long>(checksum),
                            bmi2 ? 1 : 0, bmi2_fast ? 1 : 0);
            }
        }
    }
    return true;
}

} // namespace

int main() {
    constexpr std::array<int, 6> bit_widths = {1, 2, 4, 5, 8, 16};
    constexpr std::array<std::size_t, 5> counts = {32, 64, 128, 256, 4096};
    constexpr std::uint64_t kSeed = 0x6c617474696365ULL;
    auto& features = lattice::cpu::initialize();
    std::mt19937_64 random(kSeed);

    std::printf("# seed=%llu,warmup=%d,samples=%d,rounds=%zu\n",
                static_cast<unsigned long long>(kSeed), kWarmupSamples,
                kSamples, kRounds);
    std::printf("# cpu_bmi2=%d,cpu_bmi2_fast=%d,summary=%s\n",
                features.bmi2 ? 1 : 0, features.bmi2_fast ? 1 : 0,
                lattice::cpu::summary());
    std::puts("# scalar is a conservative old bits1/2 core reference; no JNI or staging copies");
    std::puts("bits,start,count,impl,sample,ns_per_call,checksum,cpu_bmi2,cpu_bmi2_fast");

    for (const int bits : bit_widths) {
        const std::size_t elements_per_long = 64U / static_cast<unsigned>(bits);
        const std::array<std::size_t, 3> starts = {
            0, 1, elements_per_long - 1,
        };
        for (const std::size_t start : starts) {
            for (const std::size_t count : counts) {
                if (!run_case(bits, start, count, random,
                              features.bmi2, features.bmi2_fast)) {
                    return 1;
                }
            }
        }
    }
    std::printf("palette-core-complete scenarios=90 parity=passed checksum=%llu\n",
                static_cast<unsigned long long>(g_checksum_sink));
    return 0;
}
