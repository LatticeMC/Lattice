#include "world/light/block_light_engine.hpp"

#include <array>
#include <cstdlib>
#include <cstdint>
#include <cstring>

namespace lattice::world::light {
namespace {

struct QueueEntry {
    std::uint32_t index;
    std::uint8_t level;
};

enum RegionColumn : std::uint32_t {
    kRegionCenter = 0,
    kRegionWest,
    kRegionEast,
    kRegionNorth,
    kRegionSouth,
    kRegionColumnCount,
};

struct RegionColumnState {
    const BlockLightColumnView* source = nullptr;
    std::uint8_t* light = nullptr;
};

inline void free_region_lights(
    std::array<RegionColumnState, kRegionColumnCount>& region_columns) noexcept {
    for (auto& region_column : region_columns) {
        std::free(region_column.light);
        region_column.light = nullptr;
    }
}

template <typename T>
struct HeapBuffer {
    T* data = nullptr;
    std::size_t size = 0;
    std::size_t capacity = 0;

    HeapBuffer() = default;
    HeapBuffer(const HeapBuffer&) = delete;
    HeapBuffer& operator=(const HeapBuffer&) = delete;

    ~HeapBuffer() {
        std::free(data);
    }

    [[nodiscard]] bool resize_zeroed(std::size_t new_size) noexcept {
        if (new_size == 0) {
            std::free(data);
            data = nullptr;
            size = 0;
            capacity = 0;
            return true;
        }

        if (new_size > capacity) {
            void* new_ptr = std::realloc(data, new_size * sizeof(T));
            if (!new_ptr) {
                return false;
            }
            data = static_cast<T*>(new_ptr);
            capacity = new_size;
        }

        std::memset(data, 0, new_size * sizeof(T));
        size = new_size;
        return true;
    }

    [[nodiscard]] bool reserve(std::size_t new_capacity) noexcept {
        if (new_capacity <= capacity) {
            return true;
        }

        void* new_ptr = std::realloc(data, new_capacity * sizeof(T));
        if (!new_ptr) {
            return false;
        }

        data = static_cast<T*>(new_ptr);
        capacity = new_capacity;
        return true;
    }

    [[nodiscard]] bool push_back(const T& value) noexcept {
        if (size == capacity) {
            const std::size_t new_capacity = capacity == 0 ? 64 : capacity * 2;
            if (!reserve(new_capacity)) {
                return false;
            }
        }

        data[size++] = value;
        return true;
    }

    [[nodiscard]] T& operator[](std::size_t index) noexcept {
        return data[index];
    }

    [[nodiscard]] const T& operator[](std::size_t index) const noexcept {
        return data[index];
    }
};

[[nodiscard]] constexpr int cell_index(int x, int y, int z) noexcept {
    return (y * kBlockLightSectionWidth + z) * kBlockLightSectionWidth + x;
}

[[nodiscard]] constexpr int cell_x(int index) noexcept {
    return index & 15;
}

[[nodiscard]] constexpr int cell_z(int index) noexcept {
    return (index >> 4) & 15;
}

[[nodiscard]] constexpr int cell_y(int index) noexcept {
    return index >> 8;
}

[[nodiscard]] constexpr std::uint8_t normalized_opacity(std::uint8_t opacity) noexcept {
    if (opacity == 0) return 1;
    return opacity > 15 ? 15 : opacity;
}

[[nodiscard]] constexpr std::uint8_t normalized_light(std::uint8_t level) noexcept {
    return level > 15 ? 15 : level;
}

[[nodiscard]] constexpr std::uint32_t global_index(std::uint32_t section_index,
                                                   std::uint32_t local_index) noexcept {
    return (section_index << 12) | local_index;
}

[[nodiscard]] constexpr std::uint32_t local_index(std::uint32_t gi) noexcept {
    return gi & 0xFFFu;
}

[[nodiscard]] constexpr std::uint32_t global_section_index(std::uint32_t gi) noexcept {
    return gi >> 12;
}

[[nodiscard]] constexpr std::uint32_t region_global_index(std::uint32_t region_column,
                                                          std::uint32_t section_index,
                                                          std::uint32_t local_cell_index) noexcept {
    return (region_column << 28) | global_index(section_index, local_cell_index);
}

[[nodiscard]] constexpr std::uint32_t region_column_index(std::uint32_t region_index) noexcept {
    return region_index >> 28;
}

[[nodiscard]] constexpr std::uint32_t region_local_global_index(std::uint32_t region_index) noexcept {
    return region_index & 0x0FFFFFFFu;
}

[[nodiscard]] inline const BlockLightSectionView* section_ptr(const BlockLightColumnView& column,
                                                              std::uint32_t gi) noexcept {
    return column.sections + global_section_index(gi);
}

[[nodiscard]] inline std::uint8_t section_opacity(const BlockLightColumnView& column,
                                                  std::uint32_t gi) noexcept {
    const auto* section = section_ptr(column, gi);
    return section->opacity[local_index(gi)];
}

[[nodiscard]] inline std::uint8_t section_emission(const BlockLightColumnView& column,
                                                   std::uint32_t gi) noexcept {
    const auto* section = section_ptr(column, gi);
    return section->emission[local_index(gi)];
}

[[nodiscard]] inline std::uint8_t section_light(const RegionColumnState& column,
                                                std::uint32_t gi) noexcept {
    return column.light[gi];
}

inline void set_section_light(RegionColumnState& column,
                              std::uint32_t gi,
                              std::uint8_t level) noexcept {
    column.light[gi] = level;
}

[[nodiscard]] bool validate_column(const BlockLightColumnView& column,
                                   bool require_output_light) noexcept {
    if (!column.sections || column.section_count == 0) {
        return false;
    }

    for (std::size_t s = 0; s < column.section_count; ++s) {
        const auto& section = column.sections[s];
        if (!section.opacity || !section.emission) {
            return false;
        }
        if (require_output_light && !section.light) {
            return false;
        }
    }
    return true;
}

[[nodiscard]] bool column_requires_shape_occlusion(const BlockLightColumnView& column) noexcept {
    for (std::size_t s = 0; s < column.section_count; ++s) {
        const auto& section = column.sections[s];
        if (!section.flags) continue;
        for (int i = 0; i < kBlockLightSectionVolume; ++i) {
            if ((section.flags[i] & kLightFlagRequiresShapeOcclusion) != 0) {
                return true;
            }
        }
    }
    return false;
}

[[nodiscard]] bool map_region_coordinate(std::uint32_t region_column,
                                         int x,
                                         int z,
                                         int* local_x,
                                         int* local_z) noexcept {
    switch (region_column) {
    case kRegionCenter:
        if (x < 0 || x >= 16 || z < 0 || z >= 16) return false;
        *local_x = x;
        *local_z = z;
        return true;
    case kRegionWest:
        if (x < -16 || x >= 0 || z < 0 || z >= 16) return false;
        *local_x = x + 16;
        *local_z = z;
        return true;
    case kRegionEast:
        if (x < 16 || x >= 32 || z < 0 || z >= 16) return false;
        *local_x = x - 16;
        *local_z = z;
        return true;
    case kRegionNorth:
        if (x < 0 || x >= 16 || z < -16 || z >= 0) return false;
        *local_x = x;
        *local_z = z + 16;
        return true;
    case kRegionSouth:
        if (x < 0 || x >= 16 || z < 16 || z >= 32) return false;
        *local_x = x;
        *local_z = z - 16;
        return true;
    default:
        return false;
    }
}

[[nodiscard]] bool try_map_region_global_index(std::uint32_t region_column,
                                               int x,
                                               int y,
                                               int z,
                                               std::size_t section_count,
                                               std::uint32_t* gi) noexcept {
    if (y < 0 || y >= static_cast<int>(section_count * 16)) {
        return false;
    }

    int local_x = 0;
    int local_z = 0;
    if (!map_region_coordinate(region_column, x, z, &local_x, &local_z)) {
        return false;
    }

    const auto section_index = static_cast<std::uint32_t>(y >> 4);
    const auto local = static_cast<std::uint32_t>(cell_index(local_x, y & 15, local_z));
    *gi = global_index(section_index, local);
    return true;
}

[[nodiscard]] bool any_region_covers_coordinate(
    const std::array<RegionColumnState, kRegionColumnCount>& region_columns,
    int x,
    int y,
    int z,
    std::size_t section_count) noexcept {
    for (std::uint32_t rc = 0; rc < kRegionColumnCount; ++rc) {
        if (!region_columns[rc].source) continue;
        std::uint32_t gi = 0;
        if (try_map_region_global_index(rc, x, y, z, section_count, &gi)) {
            return true;
        }
    }
    return false;
}

[[nodiscard]] BlockLightResult rebuild_block_light_impl(
    std::array<RegionColumnState, kRegionColumnCount>& region_columns,
    std::size_t section_count,
    const BlockLightColumnView& center,
    bool* incomplete_neighborhood,
    bool* out_of_memory) noexcept {
    BlockLightResult result{};
    const std::size_t total_cells =
        section_count * static_cast<std::size_t>(kBlockLightSectionVolume);

    *out_of_memory = false;
    *incomplete_neighborhood = false;

    HeapBuffer<QueueEntry> queue;
    if (!queue.reserve(total_cells)) {
        *out_of_memory = true;
        return result;
    }

    for (std::uint32_t rc = 0; rc < kRegionColumnCount; ++rc) {
        const auto* source = region_columns[rc].source;
        if (!source) continue;

        for (std::uint32_t s = 0; s < source->section_count; ++s) {
            for (std::uint32_t i = 0; i < kBlockLightSectionVolume; ++i) {
                const std::uint32_t gi = global_index(s, i);
                const std::uint8_t emission = normalized_light(section_emission(*source, gi));
                if (emission == 0) continue;
                if (emission <= section_light(region_columns[rc], gi)) continue;
                set_section_light(region_columns[rc], gi, emission);
                if (rc == kRegionCenter) {
                    ++result.emission_sources;
                }
                if (!queue.push_back(QueueEntry{region_global_index(rc, s, i), emission})) {
                    *out_of_memory = true;
                    return result;
                }
            }
        }
    }

    static constexpr int kDx[6] = {0, 0, 0, 0, -1, 1};
    static constexpr int kDy[6] = {-1, 1, 0, 0, 0, 0};
    static constexpr int kDz[6] = {0, 0, -1, 1, 0, 0};

    std::size_t head = 0;
    while (head < queue.size) {
        const QueueEntry entry = queue[head++];
        if (entry.level <= 1) continue;

        const std::uint32_t rc = region_column_index(entry.index);
        const std::uint32_t gi = region_local_global_index(entry.index);
        const std::uint32_t li = local_index(gi);
        const std::uint32_t si = global_section_index(gi);

        int x = cell_x(static_cast<int>(li));
        const int y = static_cast<int>(si) * 16 + cell_y(static_cast<int>(li));
        int z = cell_z(static_cast<int>(li));

        if (rc == kRegionWest) x -= 16;
        if (rc == kRegionEast) x += 16;
        if (rc == kRegionNorth) z -= 16;
        if (rc == kRegionSouth) z += 16;

        for (int d = 0; d < 6; ++d) {
            const int nx = x + kDx[d];
            const int ny = y + kDy[d];
            const int nz = z + kDz[d];

            bool mapped = false;
            for (std::uint32_t nrc = 0; nrc < kRegionColumnCount; ++nrc) {
                if (!region_columns[nrc].source) continue;

                std::uint32_t ngi = 0;
                if (!try_map_region_global_index(nrc, nx, ny, nz, section_count, &ngi)) {
                    continue;
                }

                mapped = true;

                const std::uint8_t opacity =
                    normalized_opacity(section_opacity(*region_columns[nrc].source, ngi));
                if (entry.level <= opacity) continue;

                const std::uint8_t candidate =
                    static_cast<std::uint8_t>(entry.level - opacity);
                if (candidate <= section_light(region_columns[nrc], ngi)) continue;

                set_section_light(region_columns[nrc], ngi, candidate);
                if (nrc == kRegionCenter) {
                    ++result.propagated_writes;
                }
                if (candidate > 1) {
                    if (!queue.push_back(QueueEntry{
                            region_global_index(nrc, global_section_index(ngi), local_index(ngi)),
                            candidate,
                        })) {
                        *out_of_memory = true;
                        return result;
                    }
                }
                break;
            }

            if (!mapped && rc != kRegionCenter
                && any_region_covers_coordinate(region_columns, nx, ny, nz, section_count)) {
                continue;
            }

            if (!mapped && entry.level > 1) {
                *incomplete_neighborhood = true;
                return result;
            }
        }
    }

    std::size_t copied_offset = 0;
    for (std::size_t s = 0; s < section_count; ++s) {
        std::memcpy(center.sections[s].light,
                    region_columns[kRegionCenter].light + copied_offset,
                    kBlockLightSectionVolume * sizeof(std::uint8_t));
        copied_offset += kBlockLightSectionVolume;
    }

    for (std::size_t i = 0; i < total_cells; ++i) {
        if (region_columns[kRegionCenter].light[i] != 0) {
            ++result.lit_cells;
        }
    }
    return result;
}

} // namespace

BlockLightResult rebuild_block_light_column(const BlockLightColumnView& column) noexcept {
    const BlockLightNeighborhoodView neighborhood{&column, nullptr, nullptr, nullptr, nullptr};
    return rebuild_block_light_neighborhood(neighborhood);
}

BlockLightResult rebuild_block_light_neighborhood(const BlockLightNeighborhoodView& neighborhood) noexcept {
    BlockLightResult result{};
    if (!neighborhood.center || !validate_column(*neighborhood.center, true)) {
        result.status = BlockLightStatus::NullInput;
        return result;
    }

    const BlockLightColumnView* columns[kRegionColumnCount] = {
        neighborhood.center,
        neighborhood.west,
        neighborhood.east,
        neighborhood.north,
        neighborhood.south,
    };

    const std::size_t section_count = neighborhood.center->section_count;
    for (std::size_t i = 0; i < kRegionColumnCount; ++i) {
        const auto* column = columns[i];
        if (!column) continue;

        if (!validate_column(*column, i == kRegionCenter)) {
            result.status = BlockLightStatus::NullInput;
            return result;
        }
        if (column->section_count != section_count) {
            result.status = BlockLightStatus::MismatchedSectionCount;
            return result;
        }
        if (column_requires_shape_occlusion(*column)) {
            result.status = BlockLightStatus::UnsupportedShapeOcclusion;
            return result;
        }
    }

    std::array<RegionColumnState, kRegionColumnCount> region_columns{};
    for (std::size_t i = 0; i < kRegionColumnCount; ++i) {
        const auto* column = columns[i];
        if (!column) continue;

        region_columns[i].source = column;
        HeapBuffer<std::uint8_t> buffer;
        if (!buffer.resize_zeroed(
                section_count * static_cast<std::size_t>(kBlockLightSectionVolume))) {
            free_region_lights(region_columns);
            result.status = BlockLightStatus::OutOfMemory;
            return result;
        }
        region_columns[i].light = buffer.data;
        buffer.data = nullptr;
    }

    bool out_of_memory = false;
    bool incomplete_neighborhood = false;
    result = rebuild_block_light_impl(
        region_columns,
        section_count,
        *neighborhood.center,
        &incomplete_neighborhood,
        &out_of_memory);
    if (incomplete_neighborhood) {
        result.status = BlockLightStatus::IncompleteNeighborhood;
    } else if (out_of_memory) {
        result.status = BlockLightStatus::OutOfMemory;
    }

    free_region_lights(region_columns);

    return result;
}

BlockLightResult rebuild_block_light_section(const BlockLightSectionView& section) noexcept {
    const BlockLightColumnView column{&section, 1};
    return rebuild_block_light_column(column);
}

BlockLightStatus pack_block_light_section_nibbles(const std::uint8_t* light,
                                                  std::uint8_t* nibbles) noexcept {
    if (!light || !nibbles) {
        return BlockLightStatus::NullInput;
    }

    for (int i = 0; i < kBlockLightSectionVolume; i += 2) {
        const std::uint8_t lo = normalized_light(light[i]);
        const std::uint8_t hi = normalized_light(light[i + 1]);
        nibbles[i >> 1] = static_cast<std::uint8_t>(lo | (hi << 4));
    }
    return BlockLightStatus::Ok;
}

BlockLightStatus unpack_block_light_section_nibbles(const std::uint8_t* nibbles,
                                                    std::uint8_t* light) noexcept {
    if (!nibbles || !light) {
        return BlockLightStatus::NullInput;
    }

    for (int i = 0; i < kBlockLightSectionVolume; i += 2) {
        const std::uint8_t packed = nibbles[i >> 1];
        light[i] = static_cast<std::uint8_t>(packed & 0x0Fu);
        light[i + 1] = static_cast<std::uint8_t>(packed >> 4);
    }
    return BlockLightStatus::Ok;
}

} // namespace lattice::world::light
