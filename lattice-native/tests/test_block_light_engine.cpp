#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <array>
#include <cstdint>

#include "world/light/block_light_engine.hpp"

using namespace lattice::world::light;

namespace {

constexpr int idx(int x, int y, int z) {
    return (y * 16 + z) * 16 + x;
}

struct SectionFixture {
    std::array<std::uint8_t, kBlockLightSectionVolume> opacity{};
    std::array<std::uint8_t, kBlockLightSectionVolume> emission{};
    std::array<std::uint8_t, kBlockLightSectionVolume> flags{};
    std::array<std::uint8_t, kBlockLightSectionVolume> light{};

    SectionFixture() {
        opacity.fill(1);
        emission.fill(0);
        flags.fill(0);
        light.fill(0xCD);
    }

    BlockLightSectionView view(bool with_flags = true) {
        return BlockLightSectionView{
            opacity.data(),
            emission.data(),
            with_flags ? flags.data() : nullptr,
            light.data(),
        };
    }

    BlockLightSectionView read_only_view(bool with_flags = true) {
        return BlockLightSectionView{
            opacity.data(),
            emission.data(),
            with_flags ? flags.data() : nullptr,
            nullptr,
        };
    }
};

struct ColumnFixture {
    SectionFixture low;
    SectionFixture high;
    std::array<BlockLightSectionView, 2> views{};
    BlockLightColumnView column{};

    ColumnFixture() {
        views[0] = low.view();
        views[1] = high.view();
    }

    BlockLightColumnView view() {
        views[0] = low.view();
        views[1] = high.view();
        column = BlockLightColumnView{views.data(), views.size()};
        return column;
    }

    BlockLightColumnView read_only_view() {
        views[0] = low.read_only_view();
        views[1] = high.read_only_view();
        column = BlockLightColumnView{views.data(), views.size()};
        return column;
    }
};

struct NeighborhoodFixture {
    ColumnFixture center;
    ColumnFixture west;
    ColumnFixture east;
    ColumnFixture north;
    ColumnFixture south;
    BlockLightNeighborhoodView neighborhood{};

    BlockLightNeighborhoodView view() {
        center.view();
        west.view();
        east.view();
        north.view();
        south.view();
        neighborhood = BlockLightNeighborhoodView{
            &center.column,
            &west.column,
            &east.column,
            &north.column,
            &south.column,
        };
        return neighborhood;
    }
};

} // namespace

TEST_CASE("block_light_engine: null input rejected") {
    BlockLightSectionView view{};
    auto result = rebuild_block_light_section(view);
    CHECK(result.status == BlockLightStatus::NullInput);
}

TEST_CASE("block_light_engine: empty section clears output") {
    SectionFixture f;
    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(result.lit_cells == 0);
    for (auto v : f.light) CHECK(v == 0);
}

TEST_CASE("block_light_engine: single torch propagates through air") {
    SectionFixture f;
    f.emission[idx(8, 8, 8)] = 14;

    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.light[idx(8, 8, 8)] == 14);
    CHECK(f.light[idx(9, 8, 8)] == 13);
    CHECK(f.light[idx(10, 8, 8)] == 12);
    CHECK(f.light[idx(8, 9, 8)] == 13);
    CHECK(f.light[idx(8, 8, 9)] == 13);
    CHECK(result.emission_sources == 1);
    CHECK(result.propagated_writes > 0);
    CHECK(result.lit_cells > 1);
}

TEST_CASE("block_light_engine: opacity attenuates by target block") {
    SectionFixture f;
    f.emission[idx(1, 1, 1)] = 10;
    f.opacity[idx(2, 1, 1)] = 5;

    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.light[idx(1, 1, 1)] == 10);
    CHECK(f.light[idx(2, 1, 1)] == 5);
    CHECK(f.light[idx(3, 1, 1)] == 4);
}

TEST_CASE("block_light_engine: opaque wall stops forward propagation") {
    SectionFixture f;
    f.emission[idx(1, 1, 1)] = 10;

    // Full YZ wall at x=2 so light can't route around inside the section.
    for (int y = 0; y < 16; ++y) {
        for (int z = 0; z < 16; ++z) {
            f.opacity[idx(2, y, z)] = 15;
        }
    }

    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.light[idx(2, 1, 1)] == 0);
    CHECK(f.light[idx(3, 1, 1)] == 0);
}

TEST_CASE("block_light_engine: propagates vertically across stacked sections") {
    ColumnFixture f;
    f.low.emission[idx(8, 15, 8)] = 12;

    auto result = rebuild_block_light_column(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.low.light[idx(8, 15, 8)] == 12);
    CHECK(f.high.light[idx(8, 0, 8)] == 11);
    CHECK(f.high.light[idx(8, 1, 8)] == 10);
}

TEST_CASE("block_light_engine: shape occlusion flag forces fallback") {
    SectionFixture f;
    f.flags[idx(1, 1, 1)] = kLightFlagRequiresShapeOcclusion;
    f.light.fill(0xAA);

    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::UnsupportedShapeOcclusion);
    CHECK(f.light[idx(1, 1, 1)] == 0xAA);
}

TEST_CASE("block_light_engine: shape occlusion in any section forces fallback") {
    ColumnFixture f;
    f.high.flags[idx(4, 4, 4)] = kLightFlagRequiresShapeOcclusion;
    f.low.light.fill(0xAA);
    f.high.light.fill(0xBB);

    auto result = rebuild_block_light_column(f.view());
    CHECK(result.status == BlockLightStatus::UnsupportedShapeOcclusion);
    CHECK(f.low.light[idx(0, 0, 0)] == 0xAA);
    CHECK(f.high.light[idx(4, 4, 4)] == 0xBB);
}

TEST_CASE("block_light_engine: west neighbor emission propagates into center") {
    NeighborhoodFixture f;
    f.west.low.emission[idx(15, 8, 8)] = 12;
    f.west.low.light.fill(0xEE);

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.center.low.light[idx(0, 8, 8)] == 11);
    CHECK(f.center.low.light[idx(1, 8, 8)] == 10);
    CHECK(f.west.low.light[idx(15, 8, 8)] == 0xEE);
    CHECK(result.emission_sources == 0);
    CHECK(result.propagated_writes > 0);
    CHECK(result.lit_cells > 0);
}

TEST_CASE("block_light_engine: east neighbor emission propagates into center") {
    NeighborhoodFixture f;
    f.east.low.emission[idx(0, 8, 8)] = 12;

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.center.low.light[idx(15, 8, 8)] == 11);
    CHECK(f.center.low.light[idx(14, 8, 8)] == 10);
}

TEST_CASE("block_light_engine: north neighbor emission propagates into center") {
    NeighborhoodFixture f;
    f.north.low.emission[idx(8, 8, 15)] = 9;

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.center.low.light[idx(8, 8, 0)] == 8);
    CHECK(f.center.low.light[idx(8, 8, 1)] == 7);
}

TEST_CASE("block_light_engine: south neighbor emission propagates into center") {
    NeighborhoodFixture f;
    f.south.low.emission[idx(8, 8, 0)] = 9;

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(f.center.low.light[idx(8, 8, 15)] == 8);
    CHECK(f.center.low.light[idx(8, 8, 14)] == 7);
}

TEST_CASE("block_light_engine: neighbor light output is optional") {
    ColumnFixture center;
    ColumnFixture west;
    west.low.emission[idx(15, 8, 8)] = 12;

    auto center_column = center.view();
    auto west_column = west.read_only_view();
    auto result = rebuild_block_light_neighborhood(
        BlockLightNeighborhoodView{&center_column, &west_column, nullptr, nullptr, nullptr});
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(center.low.light[idx(0, 8, 8)] == 11);
}

TEST_CASE("block_light_engine: center emission source is counted") {
    NeighborhoodFixture f;
    f.center.low.emission[idx(8, 8, 8)] = 15;
    f.west.low.emission[idx(15, 8, 8)] = 12;

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(result.emission_sources == 1);
    CHECK(f.center.low.light[idx(8, 8, 8)] == 15);
    CHECK(f.center.low.light[idx(9, 8, 8)] == 14);
    CHECK(f.center.low.light[idx(0, 8, 8)] == 11);
}

TEST_CASE("block_light_engine: neighbor shape occlusion forces fallback") {
    NeighborhoodFixture f;
    f.west.low.flags[idx(15, 8, 8)] = kLightFlagRequiresShapeOcclusion;
    f.center.low.light.fill(0xAA);

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::UnsupportedShapeOcclusion);
    CHECK(f.center.low.light[idx(0, 0, 0)] == 0xAA);
}

TEST_CASE("block_light_engine: mismatched neighbor section count rejected") {
    SectionFixture center_low;
    SectionFixture center_high;
    SectionFixture west_low;
    std::array<BlockLightSectionView, 2> center_sections{
        center_low.view(),
        center_high.view(),
    };
    std::array<BlockLightSectionView, 1> west_sections{
        west_low.view(),
    };
    BlockLightColumnView center_column{center_sections.data(), center_sections.size()};
    BlockLightColumnView west_column{west_sections.data(), west_sections.size()};

    auto result = rebuild_block_light_neighborhood(
        BlockLightNeighborhoodView{&center_column, &west_column, nullptr, nullptr, nullptr});
    CHECK(result.status == BlockLightStatus::MismatchedSectionCount);
}

TEST_CASE("block_light_engine: incomplete neighborhood rejects diagonal continuation") {
    NeighborhoodFixture f;
    f.west.low.emission[idx(15, 8, 0)] = 15;

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::IncompleteNeighborhood);
    CHECK(f.center.low.light[idx(0, 0, 0)] == 0x00);
}

TEST_CASE("block_light_engine: incomplete neighborhood leaves prior center output untouched") {
    NeighborhoodFixture f;
    f.center.low.light.fill(0xAA);
    f.center.high.light.fill(0xBB);
    f.west.low.emission[idx(15, 8, 0)] = 15;

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::IncompleteNeighborhood);
    CHECK(f.center.low.light[idx(0, 0, 0)] == 0xAA);
    CHECK(f.center.high.light[idx(0, 0, 0)] == 0xBB);
}

TEST_CASE("block_light_engine: center boundary source rejects incomplete single-column rebuild") {
    SectionFixture f;
    f.emission[idx(0, 8, 8)] = 15;
    f.light.fill(0xAA);

    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::IncompleteNeighborhood);
    CHECK(f.light[idx(0, 0, 0)] == 0xAA);
    CHECK(f.light[idx(0, 8, 8)] == 0xAA);
}

TEST_CASE("block_light_engine: center boundary source rejects incomplete neighborhood rebuild") {
    NeighborhoodFixture f;
    f.center.low.emission[idx(0, 8, 8)] = 15;
    f.center.low.light.fill(0xAA);
    f.center.high.light.fill(0xBB);

    auto result = rebuild_block_light_neighborhood(f.view());
    CHECK(result.status == BlockLightStatus::IncompleteNeighborhood);
    CHECK(f.center.low.light[idx(0, 0, 0)] == 0xAA);
    CHECK(f.center.low.light[idx(0, 8, 8)] == 0xAA);
    CHECK(f.center.high.light[idx(0, 0, 0)] == 0xBB);
}

TEST_CASE("block_light_engine: nibble pack rejects null input") {
    std::array<std::uint8_t, kBlockLightSectionVolume> light{};
    std::array<std::uint8_t, kBlockLightSectionNibbleBytes> nibbles{};

    CHECK(pack_block_light_section_nibbles(nullptr, nibbles.data()) == BlockLightStatus::NullInput);
    CHECK(pack_block_light_section_nibbles(light.data(), nullptr) == BlockLightStatus::NullInput);
    CHECK(unpack_block_light_section_nibbles(nullptr, light.data()) == BlockLightStatus::NullInput);
    CHECK(unpack_block_light_section_nibbles(nibbles.data(), nullptr) == BlockLightStatus::NullInput);
}

TEST_CASE("block_light_engine: nibble pack clamps and preserves vanilla order") {
    std::array<std::uint8_t, kBlockLightSectionVolume> light{};
    std::array<std::uint8_t, kBlockLightSectionNibbleBytes> nibbles{};
    light[0] = 3;
    light[1] = 12;
    light[2] = 20;
    light[3] = 1;

    CHECK(pack_block_light_section_nibbles(light.data(), nibbles.data()) == BlockLightStatus::Ok);
    CHECK(nibbles[0] == 0xC3);
    CHECK(nibbles[1] == 0x1F);
}

TEST_CASE("block_light_engine: nibble unpack expands both half bytes") {
    std::array<std::uint8_t, kBlockLightSectionNibbleBytes> nibbles{};
    std::array<std::uint8_t, kBlockLightSectionVolume> light{};
    nibbles[0] = 0xC3;
    nibbles[1] = 0x1F;

    CHECK(unpack_block_light_section_nibbles(nibbles.data(), light.data()) == BlockLightStatus::Ok);
    CHECK(light[0] == 3);
    CHECK(light[1] == 12);
    CHECK(light[2] == 15);
    CHECK(light[3] == 1);
}

TEST_CASE("block_light_engine: rebuild output can be packed into nibbles") {
    SectionFixture f;
    std::array<std::uint8_t, kBlockLightSectionNibbleBytes> nibbles{};
    f.emission[idx(0, 0, 0)] = 15;

    auto result = rebuild_block_light_section(f.view());
    CHECK(result.status == BlockLightStatus::Ok);
    CHECK(pack_block_light_section_nibbles(f.light.data(), nibbles.data()) == BlockLightStatus::Ok);
    CHECK(nibbles[0] == 0xEF);
}
