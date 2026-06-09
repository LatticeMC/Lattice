#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

#include "world/tick/random_tick_filter.hpp"

using namespace lattice::world::tick;

TEST_CASE("random_tick_filter: empty input") {
    std::uint32_t out[1] = {0xDEADBEEF};
    RandomTickFilterInputs in{};
    in.mask_longs_per_section = 1;
    auto n = filter_random_ticks(in, out);
    CHECK(n == 0);
}

TEST_CASE("random_tick_filter: single section, palette idx 0, mask bit 0 set") {
    // Section 0, storage null → all default idx 0. Mask bit 0 set means
    // every candidate passes.
    std::uint64_t mask[4] = {1ULL, 0, 0, 0};
    const std::uint32_t* storages[1] = {nullptr};
    const std::size_t    s_lens[1]   = {0};
    const int            bits[1]     = {0};

    // 3 candidates all in section 0.
    const std::uint32_t cands[3] = {
        (0u << 12) | 0u,        // section 0, local 0
        (0u << 12) | 100u,
        (0u << 12) | 4095u,
    };

    RandomTickFilterInputs in{};
    in.candidates_packed     = cands;
    in.candidate_count       = 3;
    in.section_storages      = reinterpret_cast<const std::uint64_t* const*>(storages);
    in.section_storage_lens  = s_lens;
    in.section_element_bits  = bits;
    in.section_tick_masks    = mask;
    in.mask_longs_per_section = 1;
    in.section_count         = 1;

    std::uint32_t out[3];
    auto n = filter_random_ticks(in, out);
    CHECK(n == 3);
    CHECK(out[0] == 0u);
    CHECK(out[1] == 1u);
    CHECK(out[2] == 2u);
}

TEST_CASE("random_tick_filter: out-of-range section is skipped") {
    std::uint64_t mask[4] = {1ULL, 0, 0, 0};
    const std::uint32_t cands[2] = {
        (5u << 12) | 0u,  // section 5 doesn't exist
        (0u << 12) | 0u,
    };
    RandomTickFilterInputs in{};
    in.candidates_packed     = cands;
    in.candidate_count       = 2;
    in.section_tick_masks    = mask;
    in.mask_longs_per_section = 1;
    in.section_count         = 1;

    std::uint32_t out[2] = {0xFF, 0xFF};
    auto n = filter_random_ticks(in, out);
    CHECK(n == 1);
    CHECK(out[0] == 1u); // index of the second candidate
}

TEST_CASE("random_tick_filter: mask filters by palette index") {
    // 1 section, 4-bit storage with palette idx 1 everywhere, mask only
    // bit 0 set → no candidate passes.
    constexpr int kEpl = 16;
    std::vector<std::uint64_t> storage(4096 / kEpl, 0);
    // fill every 4-bit cell with 1
    for (auto& w : storage) w = 0x1111111111111111ULL;
    const std::uint64_t* storages[1] = {storage.data()};
    const std::size_t    s_lens[1]   = {storage.size()};
    const int            bits[1]     = {4};
    std::uint64_t mask[4] = {0b01ULL, 0, 0, 0}; // only idx 0 passes

    const std::uint32_t cands[2] = {
        (0u << 12) | 0u,
        (0u << 12) | 1u,
    };
    RandomTickFilterInputs in{};
    in.candidates_packed     = cands;
    in.candidate_count       = 2;
    in.section_storages      = storages;
    in.section_storage_lens  = s_lens;
    in.section_element_bits  = bits;
    in.section_tick_masks    = mask;
    in.mask_longs_per_section = 1;
    in.section_count         = 1;

    std::uint32_t out[2];
    auto n = filter_random_ticks(in, out);
    CHECK(n == 0);
}

TEST_CASE("random_tick_filter: palette index above 255 is respected") {
    constexpr int kBits = 9;
    constexpr int kEpl = 64 / kBits;
    std::vector<std::uint64_t> storage((4096 + kEpl - 1) / kEpl, 0);
    const std::uint32_t local_idx = 1234;
    const std::size_t long_idx = local_idx / kEpl;
    const int bit_off = int(local_idx % kEpl) * kBits;
    const std::uint64_t mask_bits = (std::uint64_t{1} << kBits) - 1u;
    storage[long_idx] |= (std::uint64_t{300} & mask_bits) << bit_off;

    const std::uint64_t* storages[1] = {storage.data()};
    const std::size_t s_lens[1] = {storage.size()};
    const int bits[1] = {kBits};
    std::vector<std::uint64_t> mask(5, 0);
    mask[300 >> 6] |= 1ULL << (300 & 63);

    const std::uint32_t cands[1] = {(0u << 12) | local_idx};
    RandomTickFilterInputs in{};
    in.candidates_packed = cands;
    in.candidate_count = 1;
    in.section_storages = storages;
    in.section_storage_lens = s_lens;
    in.section_element_bits = bits;
    in.section_tick_masks = mask.data();
    in.mask_longs_per_section = mask.size();
    in.section_count = 1;

    std::uint32_t out[1] = {0xFF};
    auto n = filter_random_ticks(in, out);
    CHECK(n == 1);
    CHECK(out[0] == 0u);
}
