#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

#include "lattice/dispatch.hpp"
#include "world/heightmap/heightmap_scan.hpp"

using namespace lattice::world::heightmap;

TEST_CASE("heightmap: no sections returns all default") {
    std::int32_t out[kColumnCount];
    auto n = populate(nullptr, 0, 0, 4, -1, out);
    CHECK(n == 0);
    for (int i = 0; i < kColumnCount; ++i) CHECK(out[i] == -1);
}

TEST_CASE("heightmap: single all-passing section tops out at section_top") {
    // 1 section, storage null (default palette idx 0), mask bit 0 set.
    std::uint64_t mask[4] = {1ULL, 0, 0, 0};
    SectionView sv{};
    sv.passing_mask = mask;
    // storage null, element_bits == 0 → "all default" short-circuit.

    std::int32_t out[kColumnCount];
    auto n = populate(&sv, 1, 0, 1, -64, out);
    CHECK(n == kColumnCount);
    // Each column should report y = section_base_y + 15 = 15.
    for (int i = 0; i < kColumnCount; ++i) CHECK(out[i] == 15);
}

TEST_CASE("heightmap: dispatcher matches scalar for all-passing default section") {
    std::uint64_t mask[1] = {1ULL};
    SectionView sv{};
    sv.passing_mask = mask;

    std::int32_t scalar[kColumnCount];
    std::int32_t dispatched[kColumnCount];
    auto ns = populate_scalar(&sv, 1, -64, 1, -65, scalar);
    auto nd = populate(&sv, 1, -64, 1, -65, dispatched);
    CHECK(nd == ns);
    for (int i = 0; i < kColumnCount; ++i) CHECK(dispatched[i] == scalar[i]);
}

TEST_CASE("heightmap: top section all-air, lower section all-passing") {
    // Two sections; top has mask all-zero (no pass), lower has bit 0 set.
    std::uint64_t mask_top[4] = {0, 0, 0, 0};
    std::uint64_t mask_low[4] = {1ULL, 0, 0, 0};
    SectionView sv[2]{};
    sv[0].passing_mask = mask_low;
    sv[1].passing_mask = mask_top;

    std::int32_t out[kColumnCount];
    auto n = populate(sv, 2, 0, 1, -1, out);
    CHECK(n == kColumnCount);
    // Lower section's top y = base + 15 = 0 + 15 = 15.
    for (int i = 0; i < kColumnCount; ++i) CHECK(out[i] == 15);
}

TEST_CASE("heightmap: explicit storage with mixed palette") {
    // 1 section, 4-bit storage, 16 elements per long, 256 longs per section.
    // Set every cell to palette idx 1 EXCEPT column (0, 0) Y=15 → idx 2.
    // Pass mask: only idx 1 is passing.
    constexpr int kEpl = 16;
    constexpr int kCellsPerSection = 16 * 16 * 16;
    constexpr int kLongs = kCellsPerSection / kEpl; // 256
    std::vector<std::uint64_t> storage(kLongs, 0);
    auto set_cell = [&](int y, int z, int x, int palIdx) {
        const int idx = (y * 16 + z) * 16 + x;
        const int li  = idx / kEpl;
        const int bi  = (idx % kEpl) * 4;
        storage[li] &= ~(std::uint64_t{0xF} << bi);
        storage[li] |= (std::uint64_t(palIdx & 0xF) << bi);
    };
    for (int y = 0; y < 16; ++y)
        for (int z = 0; z < 16; ++z)
            for (int x = 0; x < 16; ++x)
                set_cell(y, z, x, 1);
    set_cell(15, 0, 0, 2); // column (0,0) top cell = palette idx 2

    std::uint64_t mask[4] = {0b10ULL, 0, 0, 0}; // only idx 1 passes
    SectionView sv{};
    sv.storage = storage.data();
    sv.storage_longs = storage.size();
    sv.element_bits = 4;
    sv.passing_mask = mask;

    std::int32_t out[kColumnCount];
    populate(&sv, 1, 0, 4, -1, out);
    // Column (0,0) should report y=14 (not 15, because the cell at y=15 is idx 2).
    CHECK(out[0 * 16 + 0] == 14);
    // Other columns find their top at y=15 (idx 1, passing).
    for (int z = 0; z < 16; ++z) {
        for (int x = 0; x < 16; ++x) {
            if (x == 0 && z == 0) continue;
            CHECK(out[z * 16 + x] == 15);
        }
    }
}

TEST_CASE("heightmap: dispatcher matches scalar for mixed palette") {
    constexpr int kBits = 4;
    constexpr int kEpl = 64 / kBits;
    constexpr int kCellsPerSection = 16 * 16 * 16;
    constexpr int kLongs = kCellsPerSection / kEpl;
    std::vector<std::uint64_t> storage(kLongs * 2, 0);

    auto set_cell = [&](int section, int y, int z, int x, int palIdx) {
        const int idx = (y * 16 + z) * 16 + x;
        const int li = section * kLongs + idx / kEpl;
        const int bi = (idx % kEpl) * kBits;
        storage[li] &= ~(std::uint64_t{0xF} << bi);
        storage[li] |= (std::uint64_t(palIdx & 0xF) << bi);
    };

    for (int section = 0; section < 2; ++section) {
        for (int y = 0; y < 16; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    set_cell(section, y, z, x, (x + z + y + section) & 3);
                }
            }
        }
    }

    std::uint64_t mask[1] = {0b1010ULL};
    SectionView sv[2]{};
    sv[0].storage = storage.data();
    sv[0].storage_longs = kLongs;
    sv[0].element_bits = kBits;
    sv[0].passing_mask = mask;
    sv[1].storage = storage.data() + kLongs;
    sv[1].storage_longs = kLongs;
    sv[1].element_bits = kBits;
    sv[1].passing_mask = mask;

    std::int32_t scalar[kColumnCount];
    std::int32_t dispatched[kColumnCount];
    auto ns = populate_scalar(sv, 2, -64, 1, -65, scalar);
    auto nd = populate(sv, 2, -64, 1, -65, dispatched);
    CHECK(nd == ns);
    for (int i = 0; i < kColumnCount; ++i) CHECK(dispatched[i] == scalar[i]);
}

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
TEST_CASE("heightmap: avx2 4-bit path matches scalar for mixed palette") {
    if (!lattice::cpu::initialize().avx2) return;

    constexpr int kBits = 4;
    constexpr int kEpl = 64 / kBits;
    constexpr int kCellsPerSection = 16 * 16 * 16;
    constexpr int kLongs = kCellsPerSection / kEpl;
    std::vector<std::uint64_t> storage(kLongs * 3, 0);

    auto set_cell = [&](int section, int y, int z, int x, int palIdx) {
        const int idx = (y * 16 + z) * 16 + x;
        const int li = section * kLongs + idx / kEpl;
        const int bi = (idx % kEpl) * kBits;
        storage[li] &= ~(std::uint64_t{0xF} << bi);
        storage[li] |= (std::uint64_t(palIdx & 0xF) << bi);
    };

    for (int section = 0; section < 3; ++section) {
        for (int y = 0; y < 16; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    set_cell(section, y, z, x, (x * 3 + z * 5 + y + section) & 0xF);
                }
            }
        }
    }

    std::uint64_t mask[1] = {0};
    for (int pal : {1, 3, 6, 9, 12, 15}) mask[0] |= std::uint64_t{1} << pal;

    SectionView sv[3]{};
    for (int section = 0; section < 3; ++section) {
        sv[section].storage = storage.data() + section * kLongs;
        sv[section].storage_longs = kLongs;
        sv[section].element_bits = kBits;
        sv[section].passing_mask = mask;
    }

    std::int32_t scalar[kColumnCount];
    std::int32_t avx2[kColumnCount];
    auto ns = populate_scalar(sv, 3, -64, 1, -65, scalar);
    auto na = populate_avx2(sv, 3, -64, 1, -65, avx2);
    CHECK(na == ns);
    for (int i = 0; i < kColumnCount; ++i) CHECK(avx2[i] == scalar[i]);
}
#endif

TEST_CASE("heightmap: palette index above 255 is respected") {
    constexpr int kBits = 9;
    constexpr int kEpl = 64 / kBits;
    constexpr int kCellsPerSection = 16 * 16 * 16;
    std::vector<std::uint64_t> storage((kCellsPerSection + kEpl - 1) / kEpl, 0);

    auto set_cell = [&](int y, int z, int x, int palIdx) {
        const int idx = (y * 16 + z) * 16 + x;
        const int li  = idx / kEpl;
        const int bi  = (idx % kEpl) * kBits;
        const std::uint64_t mask = (std::uint64_t{1} << kBits) - 1u;
        storage[li] &= ~(mask << bi);
        storage[li] |= (std::uint64_t(palIdx) & mask) << bi;
    };

    set_cell(15, 0, 0, 300);

    std::vector<std::uint64_t> mask(5, 0);
    mask[300 >> 6] |= 1ULL << (300 & 63);

    SectionView sv{};
    sv.storage = storage.data();
    sv.storage_longs = storage.size();
    sv.element_bits = kBits;
    sv.passing_mask = mask.data();

    std::int32_t out[kColumnCount];
    auto n = populate(&sv, 1, 0, mask.size(), -1, out);
    CHECK(n == 1);
    CHECK(out[0] == 15);
    for (int i = 1; i < kColumnCount; ++i) CHECK(out[i] == -1);
}
