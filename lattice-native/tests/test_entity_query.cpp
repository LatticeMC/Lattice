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
