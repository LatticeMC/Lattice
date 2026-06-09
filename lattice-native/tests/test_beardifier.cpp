#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include "world/gen/densityfunction/beardifier.hpp"

using namespace lattice::world::gen::densityfunction::beardifier;

TEST_CASE("beardifier: empty inputs return zero") {
    CHECK(compute(nullptr, 0, nullptr, 0, 0, 0, 0) == doctest::Approx(0.0));
}

TEST_CASE("beardifier: bury piece contributes near box") {
    const RigidPiece piece{0, 0, 0, 4, 4, 4, TerrainAdjustment::kBury, 0};
    const double near = compute(&piece, 1, nullptr, 0, 2, 2, 2);
    const double far  = compute(&piece, 1, nullptr, 0, 50, 50, 50);
    CHECK(near > 0.0);
    CHECK(far == doctest::Approx(0.0));
}

TEST_CASE("beardifier: junction contributes near source") {
    const Junction junction{10, 20, 30};
    const double near = compute(nullptr, 0, &junction, 1, 10, 20, 30);
    const double far  = compute(nullptr, 0, &junction, 1, 100, 100, 100);
    CHECK(near != doctest::Approx(0.0));
    CHECK(far == doctest::Approx(0.0));
}
