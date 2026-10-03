#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/entity/entity_query.hpp"

using namespace lattice::world::entity;

namespace {

EntityQueryInputs make_inputs(const int* ids, const int* type_ids,
                              const double* positions, const double* aabbs,
                              const std::uint8_t* alive, const std::uint8_t* spectator,
                              std::size_t count) {
    EntityQueryInputs inputs{};
    inputs.query_min_x = 0.0;
    inputs.query_min_y = 0.0;
    inputs.query_min_z = 0.0;
    inputs.query_max_x = 10.0;
    inputs.query_max_y = 10.0;
    inputs.query_max_z = 10.0;
    inputs.entity_ids = ids;
    inputs.entity_type_ids = type_ids;
    inputs.entity_positions = positions;
    inputs.entity_aabbs = aabbs;
    inputs.entity_alive = alive;
    inputs.entity_spectator = spectator;
    inputs.entity_count = count;
    inputs.predicate_kind = EntityPredicateKind::None;
    return inputs;
}

} // namespace

TEST_CASE("entity_query: empty entity list returns no matches") {
    int output[1] = {-1};
    EntityQueryInputs inputs{};
    CHECK(query_entities(inputs, output, nullptr, 1) == 0);
}

TEST_CASE("entity_query: all overlapping entities match") {
    const int ids[3] = {10, 11, 12};
    const int types[3] = {1, 1, 1};
    const double positions[9] = {1, 1, 1, 2, 2, 2, 3, 3, 3};
    const double aabbs[18] = {
        0, 0, 0, 1, 1, 1,
        2, 2, 2, 3, 3, 3,
        9, 9, 9, 10, 10, 10,
    };
    const std::uint8_t alive[3] = {1, 1, 1};
    const std::uint8_t spectator[3] = {0, 0, 0};
    int output[3] = {-1, -1, -1};

    const auto inputs = make_inputs(ids, types, positions, aabbs, alive, spectator, 3);
    CHECK(query_entities(inputs, output, nullptr, 3) == 3);
    CHECK(output[0] == 10);
    CHECK(output[1] == 11);
    CHECK(output[2] == 12);
}

TEST_CASE("entity_query: AABB overlap filters partial matches") {
    const int ids[3] = {10, 11, 12};
    const int types[3] = {1, 1, 1};
    const double positions[9] = {1, 1, 1, 50, 50, 50, 3, 3, 3};
    const double aabbs[18] = {
        0, 0, 0, 1, 1, 1,
        50, 50, 50, 51, 51, 51,
        3, 3, 3, 4, 4, 4,
    };
    const std::uint8_t alive[3] = {1, 1, 1};
    const std::uint8_t spectator[3] = {0, 0, 0};
    int output[3] = {-1, -1, -1};

    const auto inputs = make_inputs(ids, types, positions, aabbs, alive, spectator, 3);
    CHECK(query_entities(inputs, output, nullptr, 3) == 2);
    CHECK(output[0] == 10);
    CHECK(output[1] == 12);
}

TEST_CASE("entity_query: type and predicate filters apply before output") {
    const int ids[5] = {10, 11, 12, 13, 14};
    const int types[5] = {1, 2, 2, 3, 2};
    const double positions[15] = {1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5};
    const double aabbs[30] = {
        1, 1, 1, 2, 2, 2,
        2, 2, 2, 3, 3, 3,
        3, 3, 3, 4, 4, 4,
        4, 4, 4, 5, 5, 5,
        5, 5, 5, 6, 6, 6,
    };
    const std::uint8_t alive[5] = {1, 1, 0, 1, 1};
    const std::uint8_t spectator[5] = {0, 0, 0, 0, 1};
    const int allowed[1] = {2};
    int output[5] = {-1, -1, -1, -1, -1};

    auto inputs = make_inputs(ids, types, positions, aabbs, alive, spectator, 5);
    inputs.allowed_type_ids = allowed;
    inputs.allowed_type_count = 1;
    inputs.predicate_kind = EntityPredicateKind::IsAliveNotSelfNotSpectator;
    inputs.excluded_entity_id = 11;

    CHECK(query_entities(inputs, output, nullptr, 5) == 0);

    inputs.excluded_entity_id = -1;
    CHECK(query_entities(inputs, output, nullptr, 5) == 1);
    CHECK(output[0] == 11);
}

TEST_CASE("entity_query: distance sorting returns nearest N") {
    const int ids[4] = {10, 11, 12, 13};
    const int types[4] = {1, 1, 1, 1};
    const double positions[12] = {
        8, 0, 0,
        1, 0, 0,
        3, 0, 0,
        2, 0, 0,
    };
    const double aabbs[24] = {
        8, 0, 0, 9, 1, 1,
        1, 0, 0, 2, 1, 1,
        3, 0, 0, 4, 1, 1,
        2, 0, 0, 3, 1, 1,
    };
    const std::uint8_t alive[4] = {1, 1, 1, 1};
    const std::uint8_t spectator[4] = {0, 0, 0, 0};
    int output[2] = {-1, -1};
    double distances[4] = {-1.0, -1.0, -1.0, -1.0};

    auto inputs = make_inputs(ids, types, positions, aabbs, alive, spectator, 4);
    inputs.sort_by_distance = true;
    inputs.max_results = 2;
    inputs.ref_x = 0.0;
    inputs.ref_y = 0.0;
    inputs.ref_z = 0.0;

    CHECK(query_entities(inputs, output, distances, 2) == 2);
    CHECK(output[0] == 11);
    CHECK(output[1] == 13);
    CHECK(distances[0] == doctest::Approx(1.0));
    CHECK(distances[1] == doctest::Approx(4.0));
}

#include "entity_query_reference.hpp"
#include <bit>
#include <limits>
#include <random>
#include <vector>

TEST_CASE("entity_query: ordered legacy differential including NaN barriers") {
    std::mt19937 rng(0x20261003);
    const double specials[] = {0.0, -0.0, 1.0, -1.0, 3.0, 1e200,
        std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()};
    const int allowed[] = {-1, std::numeric_limits<int>::min(), 2, 2, std::numeric_limits<int>::max()};
    for (int trial = 0; trial < 3000; ++trial) {
        const std::size_t n = 1 + rng() % 96;
        const std::size_t capacity = 1 + rng() % (n + 5);
        std::vector<int> ids(n), types(n), actual(capacity, -1), expected(capacity, -1);
        std::vector<double> positions(n * 3), boxes(n * 6), a(capacity * 2, -1), b(capacity * 2, -1);
        std::vector<std::uint8_t> alive(n), spectator(n);
        for (std::size_t i = 0; i < n; ++i) {
            ids[i] = static_cast<int>(i * 3 + 1);
            types[i] = allowed[rng() % 5];
            positions[i * 3] = trial % 3 == 0 ? specials[rng() % 8] : static_cast<double>(rng() % 16);
            boxes[i * 6] = trial % 4 == 0 && i % 2 == 0 ? 20 : 0;
            boxes[i * 6 + 3] = boxes[i * 6 + 4] = boxes[i * 6 + 5] = 1;
            alive[i] = static_cast<std::uint8_t>(rng() % 3);
            spectator[i] = static_cast<std::uint8_t>(rng() % 2);
        }
        auto in = make_inputs(ids.data(), types.data(), positions.data(), boxes.data(), alive.data(), spectator.data(), n);
        in.sort_by_distance = trial % 7 != 0;
        in.predicate_kind = static_cast<EntityPredicateKind>(trial % 6);
        in.max_results = trial % 5 == 0 ? 0 : 1 + rng() % (n + 3);
        in.excluded_entity_id = ids[rng() % n];
        in.allowed_type_count = trial % 3 == 0 ? 5 : 0; in.allowed_type_ids = allowed;
        if (trial % 29 == 0) in.ref_x = specials[6];
        const auto original_positions = positions; const auto original_boxes = boxes;
        const auto original_ids = ids; const auto original_types = types;
        const auto original_alive = alive; const auto original_spectator = spectator;
        const auto want = entity_query_reference::query_entities(in, expected.data(), b.data(), capacity);
        const auto got = query_entities(in, actual.data(), a.data(), capacity);
        REQUIRE(got == want);
        for (std::size_t i = 0; i < got; ++i) {
            CHECK(actual[i] == expected[i]);
            CHECK(std::bit_cast<std::uint64_t>(a[i]) == std::bit_cast<std::uint64_t>(b[i]));
            if (in.sort_by_distance) CHECK(a[capacity + i] == b[capacity + i]);
        }
        CHECK(ids == original_ids); CHECK(types == original_types);
        CHECK(alive == original_alive); CHECK(spectator == original_spectator);
        CHECK(boxes == original_boxes);
        for (std::size_t i = 0; i < positions.size(); ++i)
            REQUIRE(std::bit_cast<std::uint64_t>(positions[i]) == std::bit_cast<std::uint64_t>(original_positions[i]));
    }
}

TEST_CASE("entity_query: full sort and top-k preserve original ordinal ties") {
    const int ids[] = {40, 10, 30, 20, 50}; const int types[] = {1, 1, 1, 1, 1};
    const double boxes[30] = {}; const std::uint8_t alive[] = {1, 1, 1, 1, 1}, spectator[5] = {};
    const double nan = std::numeric_limits<double>::quiet_NaN();
    for (const std::vector<double>& xs : {std::vector<double>{3, nan, 1, 0, 2}, {nan, 3, 1, 0, 2},
             {3, 1, 2, nan, 0}, {1, 1, 1, 1, 1}, {5, 4, 3, 2, 1}, {1, 2, 3, 4, 5}}) {
        double positions[15] = {};
        for (int i = 0; i < 5; ++i) positions[i * 3] = xs[i];
        auto in = make_inputs(ids, types, positions, boxes, alive, spectator, 5); in.sort_by_distance = true;
        for (std::size_t k : {1u, 2u, 4u, 5u, 8u}) {
            in.max_results = k; int a[8], b[8]; double da[16], db[16];
            auto count = query_entities(in, a, da, 8);
            REQUIRE(count == entity_query_reference::query_entities(in, b, db, 8));
            for (std::size_t i = 0; i < count; ++i) {
                CHECK(a[i] == b[i]); CHECK(std::bit_cast<std::uint64_t>(da[i]) == std::bit_cast<std::uint64_t>(db[i]));
                CHECK(da[8 + i] == db[8 + i]);
            }
        }
    }
}
