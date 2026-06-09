#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

#include "world/palette/packed_storage.hpp"

using namespace lattice::world::palette;

TEST_CASE("packed_storage: get / set round-trip on 4-bit storage") {
    // 16 elements × 4 bits = 64 bits = 1 long. epl = 16.
    std::uint64_t data[2] = {0, 0};
    for (std::size_t i = 0; i < 16; ++i) {
        const std::uint32_t old = set(data, 4, i, static_cast<std::uint32_t>(i));
        CHECK(old == 0);
    }
    for (std::size_t i = 0; i < 16; ++i) {
        CHECK(get(data, 4, i) == static_cast<std::uint32_t>(i));
    }
}

TEST_CASE("packed_storage: 5-bit cross-long boundary") {
    // 12 elements / long at 5 bits (60 bits used, 4 unused). epl=12.
    // We place a value at index 11 (last in first long) and at index 12
    // (first of second long); they should not collide.
    std::vector<std::uint64_t> data(4, 0); // > required_long_count
    set(data.data(), 5, 11, 31);  // 0b11111
    set(data.data(), 5, 12, 1);
    CHECK(get(data.data(), 5, 11) == 31u);
    CHECK(get(data.data(), 5, 12) == 1u);
    // Neighbours stayed zero.
    CHECK(get(data.data(), 5, 10) == 0u);
    CHECK(get(data.data(), 5, 13) == 0u);
}

TEST_CASE("packed_storage: bulk_get matches scalar reference") {
    // 4-bit storage, 32 elements.
    constexpr std::size_t N = 32;
    std::uint64_t data[2] = {0, 0};
    for (std::size_t i = 0; i < N; ++i) {
        set(data, 4, i, static_cast<std::uint32_t>(i & 0xF));
    }

    std::uint32_t expected[N], scalar[N], dispatched[N];
    for (std::size_t i = 0; i < N; ++i) expected[i] = static_cast<std::uint32_t>(i & 0xF);

    bulk_get_scalar(data, 4, 0, N, scalar);
    bulk_get(data, 4, 0, N, dispatched);

    for (std::size_t i = 0; i < N; ++i) {
        CHECK(scalar[i] == expected[i]);
        CHECK(dispatched[i] == expected[i]);
    }
}

TEST_CASE("packed_storage: bulk_set then bulk_get round-trip") {
    constexpr std::size_t N = 64;
    std::vector<std::uint64_t> data(required_long_count(8, N) + 1, 0);
    std::uint32_t input[N];
    for (std::size_t i = 0; i < N; ++i) input[i] = static_cast<std::uint32_t>(i);

    bulk_set(data.data(), 8, 0, N, input);

    std::uint32_t output[N];
    bulk_get(data.data(), 8, 0, N, output);
    for (std::size_t i = 0; i < N; ++i) CHECK(output[i] == input[i]);
}

TEST_CASE("packed_storage: gather_get scattered indices") {
    // 8-bit storage, 16 elements.
    std::uint64_t data[2] = {0, 0};
    for (std::size_t i = 0; i < 16; ++i) {
        set(data, 8, i, static_cast<std::uint32_t>(i * 17 % 256));
    }
    const std::uint32_t indices[4] = {0, 7, 1, 15};
    std::uint32_t out[4] = {0};
    gather_get(data, 2, 8, indices, 4, out);
    CHECK(out[0] == 0u);
    CHECK(out[1] == (7u * 17u) % 256u);
    CHECK(out[2] == 17u);
    CHECK(out[3] == (15u * 17u) % 256u);
}

TEST_CASE("packed_storage: count_unique builds histogram") {
    std::uint64_t data[2] = {0, 0};
    // 4-bit storage, 16 elements: indices 0, 0, 1, 1, 2, 2, ..., 7, 7
    for (std::size_t i = 0; i < 16; ++i) {
        set(data, 4, i, static_cast<std::uint32_t>(i / 2));
    }
    std::uint32_t hist[16] = {0};
    const std::size_t n = count_unique(data, 4, 16, hist, 16);
    CHECK(n == 16);
    for (int v = 0; v < 8; ++v) CHECK(hist[v] == 2u);
    for (int v = 8; v < 16; ++v) CHECK(hist[v] == 0u);
}

TEST_CASE("packed_storage: 9-bit tail element round-trip") {
    constexpr std::size_t N = 4096;
    std::vector<std::uint64_t> data(required_long_count(9, N), 0);
    set(data.data(), 9, N - 1, 511u);
    CHECK(get(data.data(), 9, N - 1) == 511u);
    CHECK(get(data.data(), 9, N - 2) == 0u);
}
