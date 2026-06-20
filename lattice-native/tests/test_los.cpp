#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <vector>

#include "world/entity/los.hpp"

using namespace lattice::world::entity;

namespace {

struct MaskRegion {
    std::vector<std::int8_t> mask;
    int min_x = 0;
    int min_y = 0;
    int min_z = 0;
    int size_x = 0;
    int size_y = 0;
    int size_z = 0;

    MaskRegion(int sx, int sy, int sz) : mask(static_cast<std::size_t>(sx * sy * sz), 0), size_x(sx), size_y(sy), size_z(sz) {}

    void set(int x, int y, int z) {
        mask[(static_cast<std::size_t>(y) * static_cast<std::size_t>(size_z) + static_cast<std::size_t>(z)) *
             static_cast<std::size_t>(size_x) + static_cast<std::size_t>(x)] = 1;
    }
};

LosInputs inputs_for(const MaskRegion& region,
                     const double* from_x, const double* from_y, const double* from_z,
                     const double* to_x, const double* to_y, const double* to_z,
                     std::size_t count) {
    return LosInputs{
        from_x, from_y, from_z,
        to_x, to_y, to_z,
        count,
        region.mask.data(),
        region.min_x, region.min_y, region.min_z,
        region.size_x, region.size_y, region.size_z,
    };
}

} // namespace

TEST_CASE("los: straight line without blockers") {
    MaskRegion region{16, 4, 4};
    const double fx[1] = {0.5};
    const double fy[1] = {1.5};
    const double fz[1] = {1.5};
    const double tx[1] = {10.5};
    const double ty[1] = {1.5};
    const double tz[1] = {1.5};

    const LosResult result = check_line_of_sight(inputs_for(region, fx, fy, fz, tx, ty, tz, 1), 0);
    CHECK(result.has_line_of_sight);
    CHECK(result.blocks_checked > 0);
}

TEST_CASE("los: straight line blocked") {
    MaskRegion region{16, 4, 4};
    region.set(5, 1, 1);
    const double fx[1] = {0.5};
    const double fy[1] = {1.5};
    const double fz[1] = {1.5};
    const double tx[1] = {10.5};
    const double ty[1] = {1.5};
    const double tz[1] = {1.5};

    CHECK_FALSE(check_line_of_sight(inputs_for(region, fx, fy, fz, tx, ty, tz, 1), 0).has_line_of_sight);
}

TEST_CASE("los: diagonal line without blockers") {
    MaskRegion region{16, 16, 16};
    const double fx[1] = {0.5};
    const double fy[1] = {0.5};
    const double fz[1] = {0.5};
    const double tx[1] = {12.5};
    const double ty[1] = {8.5};
    const double tz[1] = {12.5};

    CHECK(check_line_of_sight(inputs_for(region, fx, fy, fz, tx, ty, tz, 1), 0).has_line_of_sight);
}

TEST_CASE("los: diagonal line blocked") {
    MaskRegion region{16, 16, 16};
    region.set(6, 4, 6);
    const double fx[1] = {0.5};
    const double fy[1] = {0.5};
    const double fz[1] = {0.5};
    const double tx[1] = {12.5};
    const double ty[1] = {8.5};
    const double tz[1] = {12.5};

    CHECK_FALSE(check_line_of_sight(inputs_for(region, fx, fy, fz, tx, ty, tz, 1), 0).has_line_of_sight);
}

TEST_CASE("los: edge aligned ray remains deterministic") {
    MaskRegion region{8, 8, 8};
    region.set(3, 3, 3);
    const double fx[1] = {0.0};
    const double fy[1] = {0.0};
    const double fz[1] = {0.0};
    const double tx[1] = {7.0};
    const double ty[1] = {7.0};
    const double tz[1] = {7.0};

    CHECK_FALSE(check_line_of_sight(inputs_for(region, fx, fy, fz, tx, ty, tz, 1), 0).has_line_of_sight);
}

TEST_CASE("los: long distance ray") {
    MaskRegion region{64, 4, 4};
    const double fx[1] = {0.5};
    const double fy[1] = {1.5};
    const double fz[1] = {1.5};
    const double tx[1] = {55.5};
    const double ty[1] = {1.5};
    const double tz[1] = {1.5};

    CHECK(check_line_of_sight(inputs_for(region, fx, fy, fz, tx, ty, tz, 1), 0).has_line_of_sight);
}

TEST_CASE("los: batch results") {
    MaskRegion region{16, 4, 4};
    region.set(5, 1, 1);
    const double fx[2] = {0.5, 0.5};
    const double fy[2] = {1.5, 2.5};
    const double fz[2] = {1.5, 1.5};
    const double tx[2] = {10.5, 10.5};
    const double ty[2] = {1.5, 2.5};
    const double tz[2] = {1.5, 1.5};
    bool results[2] = {true, false};

    check_line_of_sight_batch(inputs_for(region, fx, fy, fz, tx, ty, tz, 2), results);
    CHECK_FALSE(results[0]);
    CHECK(results[1]);
}
