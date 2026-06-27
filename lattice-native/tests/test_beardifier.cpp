#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/gen/densityfunction/beardifier.hpp"

using namespace lattice::world::gen::densityfunction::beardifier;

TEST_CASE("beardifier: empty inputs return zero") {
    CHECK(compute(nullptr, 0, nullptr, 0, 0, 0, 0) == doctest::Approx(0.0));
}

TEST_CASE("beardifier: bury piece contributes near box") {
    const RigidPiece piece{0, 0, 0, 4, 4, 4, TerrainAdjustment::kBury, 0};
    const double near_value = compute(&piece, 1, nullptr, 0, 2, 2, 2);
    const double far_value  = compute(&piece, 1, nullptr, 0, 50, 50, 50);
    CHECK(near_value > 0.0);
    CHECK(far_value == doctest::Approx(0.0));
}

TEST_CASE("beardifier: junction contributes near source") {
    const Junction junction{10, 20, 30};
    const double near_value = compute(nullptr, 0, &junction, 1, 10, 20, 30);
    const double far_value  = compute(nullptr, 0, &junction, 1, 100, 100, 100);
    CHECK(near_value != doctest::Approx(0.0));
    CHECK(far_value == doctest::Approx(0.0));
}

TEST_CASE("beardifier: piece outside xz kernel radius contributes zero") {
    const RigidPiece piece{0, 0, 0, 4, 20, 4, TerrainAdjustment::kBeardBox, 0};
    const double edge_value = compute(&piece, 1, nullptr, 0, 15, 2, 2);
    const double outside_x  = compute(&piece, 1, nullptr, 0, 16, 2, 2);
    const double outside_z  = compute(&piece, 1, nullptr, 0, 2, 2, 16);
    CHECK(edge_value != doctest::Approx(0.0));
    CHECK(outside_x == doctest::Approx(0.0));
    CHECK(outside_z == doctest::Approx(0.0));
}

TEST_CASE("beardifier: piece outside y kernel radius contributes zero") {
    const RigidPiece piece{0, 0, 0, 4, 20, 4, TerrainAdjustment::kBeardBox, 0};
    const double edge_below    = compute(&piece, 1, nullptr, 0, 2, -11, 2);
    const double outside_below = compute(&piece, 1, nullptr, 0, 2, -12, 2);
    const double edge_above    = compute(&piece, 1, nullptr, 0, 2, 31, 2);
    const double outside_above = compute(&piece, 1, nullptr, 0, 2, 32, 2);
    CHECK(edge_below != doctest::Approx(0.0));
    CHECK(outside_below == doctest::Approx(0.0));
    CHECK(edge_above != doctest::Approx(0.0));
    CHECK(outside_above == doctest::Approx(0.0));
}

TEST_CASE("beardifier: bucketed compute matches linear compute") {
    BeardifierData data;
    data.pieces = {
        RigidPiece{-20, 5, -20, -12, 18, -12, TerrainAdjustment::kBury, 0},
        RigidPiece{0, 0, 0, 4, 20, 4, TerrainAdjustment::kBeardBox, 0},
        RigidPiece{18, 3, 18, 26, 10, 26, TerrainAdjustment::kEncapsulate, 0},
        RigidPiece{31, 8, -3, 36, 16, 2, TerrainAdjustment::kBeardThin, -2},
    };
    data.junctions = {
        Junction{-16, 12, -16},
        Junction{8, 6, 8},
        Junction{28, 9, 28},
    };
    prepare_spatial_buckets(data);

    const double bucketed = compute(data, 19, 7, 19);
    const double linear = compute(data.pieces.data(),
                                  static_cast<int>(data.pieces.size()),
                                  data.junctions.data(),
                                  static_cast<int>(data.junctions.size()),
                                  19, 7, 19);
    CHECK(bucketed == doctest::Approx(linear));
}
