#include "world/entity/pathfinder.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <limits>
#include <cstdint>
#include <utility>

#include "lattice/dispatch.hpp"

namespace lattice::world::entity {
namespace {

// PathType ordinals, mirroring net.minecraft.world.level.pathfinder.PathType.
// Kept in sync with the Java enum declaration order; the Java side passes
// `type.ordinal()` in `path_types`.
constexpr std::int8_t kBlocked = 0;
constexpr std::int8_t kOpen = 1;
constexpr std::int8_t kWalkable = 2;
constexpr std::int8_t kWalkableDoor = 3;
constexpr std::int8_t kTrapdoor = 4;
constexpr std::int8_t kPowderSnow = 5;
constexpr std::int8_t kDangerPowderSnow = 6;
constexpr std::int8_t kFence = 7;
constexpr std::int8_t kLava = 8;
constexpr std::int8_t kWater = 9;
constexpr std::int8_t kWaterBorder = 10;
constexpr std::int8_t kRail = 11;
constexpr std::int8_t kUnpassableRail = 12;
constexpr std::int8_t kDangerFire = 13;
constexpr std::int8_t kDamageFire = 14;
constexpr std::int8_t kDangerOther = 15;
constexpr std::int8_t kDamageOther = 16;
constexpr std::int8_t kDoorOpen = 17;
constexpr std::int8_t kDoorWoodClosed = 18;
constexpr std::int8_t kDoorIronClosed = 19;
constexpr std::int8_t kStickyHoney = 22;
constexpr std::int8_t kDamageCautious = 24;
constexpr std::int8_t kDangerTrapdoor = 25;
constexpr int kPathTypeCount = 26;
constexpr std::size_t kMaskWordBits = 64;
constexpr std::int8_t kClosedFlag = 1;
constexpr std::int8_t kOpenFlag = 2;

[[nodiscard]] bool region_endpoint_fits(int minimum, int size) noexcept {
    const std::int64_t endpoint = static_cast<std::int64_t>(minimum)
        + static_cast<std::int64_t>(size) - 1;
    return endpoint >= std::numeric_limits<int>::min()
        && endpoint <= std::numeric_limits<int>::max();
}

[[nodiscard]] bool valid_inputs(const PathfinderInputs& in) noexcept {
    if (!in.path_types || !in.pathfinding_malus) return false;
    if (!in.target_x || !in.target_y || !in.target_z || in.target_count <= 0) return false;
    if (in.region_size_x <= 0 || in.region_size_y <= 0 || in.region_size_z <= 0) return false;
    if (!region_endpoint_fits(in.region_min_x, in.region_size_x)
            || !region_endpoint_fits(in.region_min_y, in.region_size_y)
            || !region_endpoint_fits(in.region_min_z, in.region_size_z)) return false;
    if (in.config.max_visited_nodes <= 0 || in.config.max_range <= 0.0F) return false;
    return in.pathfinding_malus_count > 0;
}

[[nodiscard]] bool checked_add_i64(std::int64_t lhs, std::int64_t rhs,
                                   std::int64_t& result) noexcept {
    if ((rhs > 0 && lhs > std::numeric_limits<std::int64_t>::max() - rhs)
            || (rhs < 0 && lhs < std::numeric_limits<std::int64_t>::min() - rhs)) return false;
    result = lhs + rhs;
    return true;
}

[[nodiscard]] bool checked_sub_i64(std::int64_t lhs, std::int64_t rhs,
                                   std::int64_t& result) noexcept {
    if ((rhs > 0 && lhs < std::numeric_limits<std::int64_t>::min() + rhs)
            || (rhs < 0 && lhs > std::numeric_limits<std::int64_t>::max() + rhs)) return false;
    result = lhs - rhs;
    return true;
}

[[nodiscard]] bool checked_mul_i64(std::int64_t lhs, std::int64_t rhs,
                                   std::int64_t& result) noexcept {
    if (lhs == 0 || rhs == 0) {
        result = 0;
        return true;
    }
    if (lhs == -1) {
        if (rhs == std::numeric_limits<std::int64_t>::min()) return false;
        result = -rhs;
        return true;
    }
    if (rhs == -1) {
        if (lhs == std::numeric_limits<std::int64_t>::min()) return false;
        result = -lhs;
        return true;
    }
    if (lhs > 0) {
        if (rhs > 0) {
            if (lhs > std::numeric_limits<std::int64_t>::max() / rhs) return false;
        } else if (rhs < std::numeric_limits<std::int64_t>::min() / lhs) return false;
    } else if (rhs > 0) {
        if (lhs < std::numeric_limits<std::int64_t>::min() / rhs) return false;
    } else if (lhs < std::numeric_limits<std::int64_t>::max() / rhs) {
        return false;
    }
    result = lhs * rhs;
    return true;
}

[[nodiscard]] bool checked_state_axis(int region_min, int region_size, int entity_size,
                                      int& state_min, int& state_max,
                                      int& state_size) noexcept {
    if (region_size <= 0 || entity_size <= 0) return false;
    std::int64_t min_value = static_cast<std::int64_t>(region_min);
    std::int64_t max_value = min_value;
    if (!checked_sub_i64(min_value, 1, min_value)
            || !checked_add_i64(max_value, static_cast<std::int64_t>(region_size), max_value)
            || !checked_add_i64(max_value, static_cast<std::int64_t>(entity_size), max_value)
            || !checked_sub_i64(max_value, 1, max_value)) return false;
    if (min_value < std::numeric_limits<int>::min() || min_value > std::numeric_limits<int>::max()
            || max_value < std::numeric_limits<int>::min() || max_value > std::numeric_limits<int>::max()) {
        return false;
    }
    std::int64_t size_value = 0;
    if (!checked_sub_i64(max_value, min_value, size_value)
            || !checked_add_i64(size_value, 1, size_value)
            || size_value <= 0 || size_value > std::numeric_limits<int>::max()) return false;
    state_min = static_cast<int>(min_value);
    state_max = static_cast<int>(max_value);
    state_size = static_cast<int>(size_value);
    return true;
}

[[nodiscard]] int volume(const PathfinderInputs& in) noexcept {
    std::int64_t v = 1;
    if (!checked_mul_i64(v, static_cast<std::int64_t>(in.region_size_x), v)
            || !checked_mul_i64(v, static_cast<std::int64_t>(in.region_size_y), v)
            || !checked_mul_i64(v, static_cast<std::int64_t>(in.region_size_z), v)
            || v <= 0 || v > std::numeric_limits<int>::max()) return -1;
    return static_cast<int>(v);
}

[[nodiscard]] bool in_region(const PathfinderInputs& in, int x, int y, int z) noexcept {
    const std::int64_t max_x = static_cast<std::int64_t>(in.region_min_x) + in.region_size_x;
    const std::int64_t max_y = static_cast<std::int64_t>(in.region_min_y) + in.region_size_y;
    const std::int64_t max_z = static_cast<std::int64_t>(in.region_min_z) + in.region_size_z;
    return static_cast<std::int64_t>(x) >= in.region_min_x
        && static_cast<std::int64_t>(y) >= in.region_min_y
        && static_cast<std::int64_t>(z) >= in.region_min_z
        && static_cast<std::int64_t>(x) < max_x
        && static_cast<std::int64_t>(y) < max_y
        && static_cast<std::int64_t>(z) < max_z;
}

[[nodiscard]] int grid_index(const PathfinderInputs& in, int x, int y, int z) noexcept {
    const int lx = x - in.region_min_x;
    const int ly = y - in.region_min_y;
    const int lz = z - in.region_min_z;
    return (ly * in.region_size_z + lz) * in.region_size_x + lx;
}

[[nodiscard]] std::int8_t path_type_at(const PathfinderInputs& in, int x, int y, int z) noexcept {
    if (!in_region(in, x, y, z)) return kBlocked;
    return in.path_types[grid_index(in, x, y, z)];
}

[[nodiscard]] float malus_for(const PathfinderInputs& in, std::int8_t type) noexcept {
    const int index = static_cast<int>(type);
    if (index < 0 || index >= in.pathfinding_malus_count) return -1.0F;
    return in.pathfinding_malus[index];
}

[[nodiscard]] bool standing_node(const PathfinderInputs& in, int x, int y, int z,
                                 const std::vector<std::uint64_t>& standing,
                                 std::int8_t& out_type, float& out_malus) noexcept {
    if (!in_region(in, x, y, z)) return false;
    const std::size_t index = static_cast<std::size_t>(grid_index(in, x, y, z));
    if (((standing[index >> 6] >> (index & 63)) & 1ULL) == 0ULL) return false;
    out_type = path_type_at(in, x, y, z);
    out_malus = malus_for(in, out_type);
    return true;
}

/// Floor level of a cell, mirroring `WalkNodeEvaluator.getFloorLevel`.
/// Java precomputes this per cell (it already computes it inside
/// findAcceptedNode), so here it is a plain lookup. Cells outside the
/// snapshot report their integer Y, matching an empty collision shape.
[[nodiscard]] float floor_level_at(const PathfinderInputs& in, int x, int y, int z) noexcept {
    if (!in_region(in, x, y, z)) return static_cast<float>(y);
    if (!in.floor_levels) return static_cast<float>(y);
    return in.floor_levels[grid_index(in, x, y, z)];
}

[[nodiscard]] int mirror_section_index(int x, int y, int z) noexcept;

/// `WalkNodeEvaluator.doesBlockHavePartialCollision`.
[[nodiscard]] bool partial_collision(std::int8_t type) noexcept {
    return type == kFence || type == kDoorWoodClosed || type == kDoorIronClosed;
}

/// Port of `WalkNodeEvaluator.findAcceptedNode`.
///
/// The vanilla method is a strictly ordered if/else chain: a jump-up attempt
/// takes priority over any downward search, and each fallback is mutually
/// exclusive. The previous native code approximated this with a
/// down-then-up scan, which resolved a different cell than vanilla on any
/// terrain with steps or gaps and produced `reached native=false
/// vanilla=true` mismatches. This keeps vanilla's branch structure and
/// the float floor-level jump gate.
///
/// `vertical_delta_limit` is vanilla's `i1` (0 when the head cell is blocked
/// or the mob stands in sticky honey), `node_floor_level` the origin cell's
/// floor level. Returns false where vanilla returns null.
[[nodiscard]] bool find_accepted_node(const PathfinderInputs& in, int x, int y, int z,
                                      int vertical_delta_limit, float node_floor_level,
                                      std::int8_t origin_type,
                                      const std::vector<std::uint64_t>& standing,
                                      int& out_y, std::int8_t& out_type,
                                      float& out_malus, bool& out_closed) noexcept {
    out_closed = false;
    if (!in_region(in, x, y, z)) return false;

    // Vanilla gate: floorLevel - nodeFloorLevel > getMobJumpHeight() -> null.
    // getMobJumpHeight() == max(1.125, maxUpStep).
    const float jump_height = std::max(1.125F, in.max_up_step);
    if (floor_level_at(in, x, y, z) - node_floor_level > jump_height) return false;

    const std::int8_t type = path_type_at(in, x, y, z);
    const float malus = malus_for(in, type);

    bool have_node = false;
    if (malus >= 0.0F) {
        out_y = y;
        out_type = type;
        out_malus = malus;
        have_node = true;
    }

    // Vanilla: partial-collision origin requires a collision-free approach.
    // The sweep test needs the mob's real AABB, which the snapshot does not
    // carry, so treat it as blocking and let Java handle those origins.
    if (partial_collision(origin_type) && have_node && out_malus >= 0.0F) {
        return false;
    }

    // `WalkNodeEvaluator` accepts WALKABLE directly. Its amphibious subclass
    // gives WATER the same short-circuit so an in-water step is not treated as
    // a fall or a jump candidate.
    if (type == kWalkable || (in.is_amphibious && type == kWater)) return have_node;

    if ((!have_node || out_malus < 0.0F)
            && vertical_delta_limit > 0
            && (type != kFence || in.can_walk_over_fences)
            && (in.mobs_ignore_rails || type != kUnpassableRail)
            && type != kTrapdoor
            && type != kPowderSnow) {
        // tryJumpOn: recurse one cell up with limit-1. The narrow-mob gap
        // collision check is omitted (needs the real AABB); Java rejects
        // those cases before handing the request to native.
        return find_accepted_node(in, x, y + 1, z, vertical_delta_limit - 1,
                                  node_floor_level, origin_type, standing,
                                  out_y, out_type, out_malus, out_closed);
    }

    if (type == kWater && !in.can_float) {
        // tryFindFirstNonWaterBelow: descend while still water, unbounded by
        // maxFallDistance. Java rejects water snapshots for non-floating mobs,
        // so this only guards against a stale allowlist.
        int cursor = y - 1;
        while (cursor > in.region_min_y) {
            const std::int8_t below = path_type_at(in, x, cursor, z);
            if (below != kWater) return have_node;
            out_y = cursor;
            out_type = below;
            out_malus = std::max(have_node ? out_malus : -1.0F, malus_for(in, below));
            have_node = true;
            --cursor;
        }
        return have_node;
    }

    if (type == kOpen) {
        // tryFindFirstGroundNodeBelow: descend to the first standing cell.
        // Vanilla scans to the world floor, not just maxFallDistance.
        for (int cursor = y - 1; cursor >= in.region_min_y; --cursor) {
            std::int8_t found_type = kBlocked;
            float found_malus = -1.0F;
            if (standing_node(in, x, cursor, z, standing, found_type, found_malus)) {
                out_y = cursor;
                out_type = found_type;
                out_malus = found_malus;
                return true;
            }
            if (path_type_at(in, x, cursor, z) != kOpen) return false;
        }
        return false;
    }

    if (partial_collision(type) && !have_node) {
        // getClosedNode: a node vanilla creates but marks closed.
        out_y = y;
        out_type = type;
        out_malus = malus_for(in, type);
        out_closed = true;
        return true;
    }

    return have_node;
}

/// Resolve the cell a step in some direction lands on, applying vanilla's
/// per-origin jump allowance (`getNeighbors`'s `i1` and floor level).
[[nodiscard]] bool resolve_step_node(const PathfinderInputs& in,
                                     int origin_x, int origin_y, int origin_z,
                                     int x, int z,
                                     const std::vector<std::uint64_t>& standing,
                                     int& out_y, std::int8_t& out_type,
                                     float& out_malus, bool& out_closed) noexcept {
    const std::int8_t origin_type = path_type_at(in, origin_x, origin_y, origin_z);
    const std::int8_t head_type = path_type_at(in, origin_x, origin_y + 1, origin_z);

    int vertical_delta_limit = 0;
    if (malus_for(in, head_type) >= 0.0F && origin_type != kStickyHoney) {
        vertical_delta_limit = static_cast<int>(std::floor(std::max(1.0F, in.max_up_step)));
    }

    const float node_floor_level = floor_level_at(in, origin_x, origin_y, origin_z);
    return find_accepted_node(in, x, origin_y, z, vertical_delta_limit, node_floor_level,
                              origin_type, standing, out_y, out_type, out_malus, out_closed);
}

[[nodiscard]] float distance(int ax, int ay, int az, int bx, int by, int bz) noexcept {
    const float dx = static_cast<float>(bx - ax);
    const float dy = static_cast<float>(by - ay);
    const float dz = static_cast<float>(bz - az);
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

[[nodiscard]] float manhattan(int ax, int ay, int az, int bx, int by, int bz) noexcept {
    return static_cast<float>(std::abs(bx - ax) + std::abs(by - ay) + std::abs(bz - az));
}

[[nodiscard]] float best_h(const PathfinderInputs& in, const PathfinderNode& node,
                           int* best_target) noexcept {
    if (in.target_count == 1) {
        if (best_target) *best_target = 0;
        return distance(node.x, node.y, node.z,
                        in.target_x[0], in.target_y[0], in.target_z[0]);
    }
    float result = std::numeric_limits<float>::max();
    int target_index = -1;
    for (int i = 0; i < in.target_count; ++i) {
        const float h = distance(node.x, node.y, node.z,
                                 in.target_x[i], in.target_y[i], in.target_z[i]);
        if (h < result) {
            result = h;
            target_index = i;
        }
    }
    if (best_target) *best_target = target_index;
    return result;
}

struct MinHeap {
    std::vector<int>* entries = nullptr;
    std::vector<int>* heap_index = nullptr;
    std::vector<PathfinderNode>* nodes = nullptr;

    [[nodiscard]] bool empty() const noexcept { return entries->empty(); }

    void swap_entries(int a, int b) noexcept {
        std::swap((*entries)[a], (*entries)[b]);
        (*heap_index)[(*entries)[a]] = a;
        (*heap_index)[(*entries)[b]] = b;
    }

    void up(int index) noexcept {
        while (index > 0) {
            const int parent = (index - 1) >> 1;
            if (!((*nodes)[(*entries)[index]].f < (*nodes)[(*entries)[parent]].f)) break;
            swap_entries(index, parent);
            index = parent;
        }
    }

    void down(int index) noexcept {
        while (true) {
            const int left = (index << 1) + 1;
            if (left >= static_cast<int>(entries->size())) break;
            const int right = left + 1;
            int best = left;
            if (right < static_cast<int>(entries->size())
                    && !((*nodes)[(*entries)[left]].f < (*nodes)[(*entries)[right]].f)) {
                best = right;
            }
            if (!((*nodes)[(*entries)[best]].f < (*nodes)[(*entries)[index]].f)) break;
            swap_entries(index, best);
            index = best;
        }
    }

    void push(int node_index) noexcept {
        entries->push_back(node_index);
        (*heap_index)[node_index] = static_cast<int>(entries->size()) - 1;
        (*nodes)[node_index].flags |= kOpenFlag;
        up(static_cast<int>(entries->size()) - 1);
    }

    int pop() noexcept {
        const int result = entries->front();
        (*nodes)[result].flags &= static_cast<std::int8_t>(~kOpenFlag);
        (*heap_index)[result] = -1;
        (*entries)[0] = entries->back();
        entries->pop_back();
        if (!entries->empty()) {
            (*heap_index)[(*entries)[0]] = 0;
            down(0);
        }
        return result;
    }

    void change_cost(int node_index) noexcept {
        const int index = (*heap_index)[node_index];
        if (index < 0) return;
        // Every caller reaches this only after a strict g-cost decrease. The
        // node's coordinate and target set are immutable, so its h-cost is
        // unchanged and f can only move toward the heap root.
        up(index);
    }
};

[[nodiscard]] PathfinderResult reconstruct(const std::vector<PathfinderNode>& nodes,
                                           int node_index, int target_index,
                                           bool reached) noexcept {
    PathfinderResult result{};
    result.target_index = target_index;
    result.reached_target = reached;
    int count = 0;
    for (int i = node_index; i >= 0; i = nodes[i].came_from) ++count;
    result.path.resize(static_cast<std::size_t>(count));
    for (int i = count - 1, n = node_index; i >= 0 && n >= 0; --i) {
        result.path[static_cast<std::size_t>(i)] = nodes[static_cast<std::size_t>(n)];
        n = nodes[static_cast<std::size_t>(n)].came_from;
    }
    return result;
}

struct SearchResult {
    PathfinderScratch* scratch = nullptr;
    int end_index = -1;
    int target_index = -1;
    bool reached_target = false;
};

[[nodiscard]] SearchResult empty_search() noexcept {
    return SearchResult{};
}

[[nodiscard]] std::size_t mask_words(std::size_t count) noexcept {
    return (count + (kMaskWordBits - 1)) / kMaskWordBits;
}

void resize_mask(std::vector<std::uint64_t>& mask, std::size_t count) {
    mask.resize(mask_words(count));
}

void mask_set(std::uint64_t* mask, std::size_t index, bool value) noexcept {
    const std::size_t word = index >> 6;
    const std::uint64_t bit = std::uint64_t{1} << (index & 63);
    if (value) {
        mask[word] |= bit;
    } else {
        mask[word] &= ~bit;
    }
}

} // namespace

void build_pathfinder_masks_scalar(const std::int8_t* path_types,
                                   std::size_t count,
                                   const float* pathfinding_malus,
                                   int pathfinding_malus_count,
                                   PathfinderMasks masks) noexcept {
    if (!path_types || !pathfinding_malus || !masks.passable || !masks.standing) return;
    const std::size_t words = mask_words(count);
    std::fill(masks.passable, masks.passable + words, 0ULL);
    std::fill(masks.standing, masks.standing + words, 0ULL);
    for (std::size_t i = 0; i < count; ++i) {
        const int type = static_cast<int>(path_types[i]);
        const bool is_passable = type != kBlocked
            && type >= 0
            && type < pathfinding_malus_count
            && pathfinding_malus[type] >= 0.0F;
        mask_set(masks.passable, i, is_passable);
        mask_set(masks.standing, i, is_passable && type != kOpen);
    }
}

namespace {

using MaskBuilderFn = void (*)(const std::int8_t*, std::size_t, const float*, int,
                               PathfinderMasks) noexcept;

std::atomic<MaskBuilderFn> g_mask_builder{&build_pathfinder_masks_scalar};
std::atomic<bool> g_pathfinder_initialised{false};
std::atomic<bool> g_cardinal_fastpath_enabled{true};
std::atomic<bool> g_cardinal_fastpath_stats_enabled{false};
std::atomic<std::uint64_t> g_cardinal_fastpath_hits{0};
std::atomic<std::uint64_t> g_cardinal_fastpath_candidates{0};
std::atomic<std::uint64_t> g_cardinal_fastpath_out_of_region_candidates{0};
std::atomic<std::uint64_t> g_diagonal_fastpath_hits{0};
std::atomic<std::uint64_t> g_diagonal_fastpath_candidates{0};
std::array<std::atomic<std::uint64_t>, kPathfinderCardinalFastPathBucketCount> g_cardinal_fastpath_bucket_hits{};
std::array<std::atomic<std::uint64_t>, kPathfinderCardinalFastPathBucketCount> g_cardinal_fastpath_bucket_candidates{};

} // namespace

void set_pathfinder_cardinal_fastpath_enabled(bool enabled) noexcept {
    g_cardinal_fastpath_enabled.store(enabled, std::memory_order_release);
}

void set_pathfinder_cardinal_fastpath_stats_enabled(bool enabled) noexcept {
    g_cardinal_fastpath_stats_enabled.store(enabled, std::memory_order_release);
}

void reset_pathfinder_cardinal_fastpath_stats() noexcept {
    g_cardinal_fastpath_hits.store(0, std::memory_order_relaxed);
    g_cardinal_fastpath_candidates.store(0, std::memory_order_relaxed);
    g_cardinal_fastpath_out_of_region_candidates.store(0, std::memory_order_relaxed);
    g_diagonal_fastpath_hits.store(0, std::memory_order_relaxed);
    g_diagonal_fastpath_candidates.store(0, std::memory_order_relaxed);
    for (auto& value : g_cardinal_fastpath_bucket_hits) value.store(0, std::memory_order_relaxed);
    for (auto& value : g_cardinal_fastpath_bucket_candidates) value.store(0, std::memory_order_relaxed);
}

PathfinderCardinalFastPathStats pathfinder_cardinal_fastpath_stats() noexcept {
    return PathfinderCardinalFastPathStats{
        g_cardinal_fastpath_hits.load(std::memory_order_relaxed),
        g_cardinal_fastpath_candidates.load(std::memory_order_relaxed),
        g_cardinal_fastpath_out_of_region_candidates.load(std::memory_order_relaxed),
        g_diagonal_fastpath_hits.load(std::memory_order_relaxed),
        g_diagonal_fastpath_candidates.load(std::memory_order_relaxed)};
}

void copy_pathfinder_cardinal_fastpath_buckets(std::uint64_t* candidates,
                                                std::uint64_t* hits,
                                                std::size_t count) noexcept {
    if (!candidates || !hits || count < kPathfinderCardinalFastPathBucketCount) return;
    for (std::size_t i = 0; i < kPathfinderCardinalFastPathBucketCount; ++i) {
        candidates[i] = g_cardinal_fastpath_bucket_candidates[i].load(std::memory_order_relaxed);
        hits[i] = g_cardinal_fastpath_bucket_hits[i].load(std::memory_order_relaxed);
    }
}

void init_pathfinder_dispatch() noexcept {
    if (g_pathfinder_initialised.load(std::memory_order_acquire)) return;
    MaskBuilderFn fn = &build_pathfinder_masks_scalar;
    const auto& f = lattice::cpu::features();
    (void)f;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    if (f.avx512bw) {
        fn = &build_pathfinder_masks_avx512;
    } else if (f.avx2) {
        fn = &build_pathfinder_masks_avx2;
    }
#elif defined(__aarch64__) || defined(_M_ARM64)
    if (f.neon) fn = &build_pathfinder_masks_neon;
#endif

    g_mask_builder.store(fn, std::memory_order_release);
    g_pathfinder_initialised.store(true, std::memory_order_release);
}

void build_pathfinder_masks(const std::int8_t* path_types,
                            std::size_t count,
                            const float* pathfinding_malus,
                            int pathfinding_malus_count,
                            PathfinderMasks masks) noexcept {
    if (!g_pathfinder_initialised.load(std::memory_order_acquire)) {
        init_pathfinder_dispatch();
    }
    g_mask_builder.load(std::memory_order_acquire)(
        path_types, count, pathfinding_malus, pathfinding_malus_count, masks);
}

namespace {

[[nodiscard]] SearchResult run_search(const PathfinderInputs& in, PathfinderScratch& scratch) noexcept {
    if (!valid_inputs(in)) return empty_search();
    const int grid_volume = volume(in);
    if (grid_volume <= 0) return empty_search();
    const bool fastpath_enabled = g_cardinal_fastpath_enabled.load(std::memory_order_acquire);
    const bool collect_fastpath_stats = g_cardinal_fastpath_stats_enabled.load(std::memory_order_acquire);
    std::uint64_t cardinal_fastpath_hits = 0;
    std::uint64_t cardinal_fastpath_candidates = 0;
    std::uint64_t cardinal_fastpath_out_of_region_candidates = 0;
    std::uint64_t diagonal_fastpath_hits = 0;
    std::uint64_t diagonal_fastpath_candidates = 0;
    std::array<std::uint64_t, kPathfinderCardinalFastPathBucketCount> cardinal_fastpath_bucket_hits{};
    std::array<std::uint64_t, kPathfinderCardinalFastPathBucketCount> cardinal_fastpath_bucket_candidates{};
    auto finish = [&](SearchResult result) noexcept {
        if (collect_fastpath_stats) {
            g_cardinal_fastpath_hits.fetch_add(cardinal_fastpath_hits, std::memory_order_relaxed);
            g_cardinal_fastpath_candidates.fetch_add(cardinal_fastpath_candidates, std::memory_order_relaxed);
            g_cardinal_fastpath_out_of_region_candidates.fetch_add(
                cardinal_fastpath_out_of_region_candidates, std::memory_order_relaxed);
            g_diagonal_fastpath_hits.fetch_add(diagonal_fastpath_hits, std::memory_order_relaxed);
            g_diagonal_fastpath_candidates.fetch_add(diagonal_fastpath_candidates, std::memory_order_relaxed);
            for (std::size_t i = 0; i < kPathfinderCardinalFastPathBucketCount; ++i) {
                if (cardinal_fastpath_bucket_candidates[i] == 0) continue;
                g_cardinal_fastpath_bucket_candidates[i].fetch_add(
                    cardinal_fastpath_bucket_candidates[i], std::memory_order_relaxed);
                g_cardinal_fastpath_bucket_hits[i].fetch_add(
                    cardinal_fastpath_bucket_hits[i], std::memory_order_relaxed);
            }
        }
        return result;
    };

    if (!in.lazy_context) {
        resize_mask(scratch.passable, static_cast<std::size_t>(grid_volume));
        resize_mask(scratch.standing, static_cast<std::size_t>(grid_volume));
        build_pathfinder_masks(in.path_types, static_cast<std::size_t>(grid_volume),
                               in.pathfinding_malus, in.pathfinding_malus_count,
                               PathfinderMasks{scratch.passable.data(), scratch.standing.data()});
    }

    // The start cell comes straight from the mob's position: vanilla's
    // `getStart` reads the block it already stands in rather than running the
    // neighbour acceptance chain, so no jump/fall resolution happens here.
    int start_y = in.start_y;
    std::int8_t start_type = kBlocked;
    float start_malus = -1.0F;
    if (!standing_node(in, in.start_x, in.start_y, in.start_z, scratch.standing,
                       start_type, start_malus)) {
        return empty_search();
    }

    const int max_nodes = std::min(in.config.max_visited_nodes, grid_volume);
    const std::size_t grid_volume_sz = static_cast<std::size_t>(grid_volume);
    if (scratch.grid_to_node.size() < grid_volume_sz) {
        scratch.grid_to_node.resize(grid_volume_sz, -1);
        scratch.grid_stamp.resize(grid_volume_sz, 0);
    }
    if (++scratch.current_stamp == 0) {
        std::fill(scratch.grid_stamp.begin(), scratch.grid_stamp.end(), 0);
        scratch.current_stamp = 1;
    }
    scratch.heap_index.clear();
    scratch.nodes.clear();
    scratch.heap_entries.clear();
    scratch.nodes.reserve(static_cast<std::size_t>(max_nodes));
    scratch.heap_index.reserve(static_cast<std::size_t>(max_nodes));
    scratch.heap_entries.reserve(static_cast<std::size_t>(max_nodes));

    auto get_node = [&](int x, int y, int z, std::int8_t type, float malus) noexcept -> int {
        const int gi = grid_index(in, x, y, z);
        const std::size_t gsi = static_cast<std::size_t>(gi);
        if (scratch.grid_stamp[gsi] == scratch.current_stamp) {
            return scratch.grid_to_node[gsi];
        }
        if (static_cast<int>(scratch.nodes.size()) >= max_nodes) return -1;
        const int index = static_cast<int>(scratch.nodes.size());
        scratch.grid_to_node[gsi] = index;
        scratch.grid_stamp[gsi] = scratch.current_stamp;
        PathfinderNode node{};
        node.x = x;
        node.y = y;
        node.z = z;
        node.type = type;
        node.cost_malus = malus;
        scratch.nodes.push_back(node);
        scratch.heap_index.push_back(-1);
        return index;
    };

    int start_index = get_node(in.start_x, start_y, in.start_z, start_type, start_malus);
    if (start_index < 0) return empty_search();
    int best_target = -1;
    scratch.nodes[start_index].h = best_h(in, scratch.nodes[start_index], &best_target);
    scratch.nodes[start_index].f = scratch.nodes[start_index].h;

    MinHeap heap{};
    heap.heap_index = &scratch.heap_index;
    heap.nodes = &scratch.nodes;
    heap.entries = &scratch.heap_entries;
    heap.push(start_index);

    int best_node = start_index;
    int best_node_target = best_target;
    float best_node_h = scratch.nodes[start_index].h;
    int visited = 0;

    constexpr int dir_x[4] = {0, 1, 0, -1};
    constexpr int dir_z[4] = {-1, 0, 1, 0};

    while (!heap.empty()) {
        if (++visited >= in.config.max_visited_nodes) break;
        const int current_index = heap.pop();
        PathfinderNode& current = scratch.nodes[static_cast<std::size_t>(current_index)];
        current.flags |= kClosedFlag;

        if (in.target_count == 1) {
            if (manhattan(current.x, current.y, current.z,
                          in.target_x[0], in.target_y[0], in.target_z[0]) <= in.config.reach_range) {
                SearchResult result{};
                result.scratch = &scratch;
                result.end_index = current_index;
                result.target_index = 0;
                result.reached_target = true;
                return finish(result);
            }
        } else {
            for (int i = 0; i < in.target_count; ++i) {
                if (manhattan(current.x, current.y, current.z,
                              in.target_x[i], in.target_y[i], in.target_z[i]) <= in.config.reach_range) {
                    SearchResult result{};
                    result.scratch = &scratch;
                    result.end_index = current_index;
                    result.target_index = i;
                    result.reached_target = true;
                    return finish(result);
                }
            }
        }

        if (distance(in.start_x, start_y, in.start_z, current.x, current.y, current.z)
            >= in.config.max_range) {
            continue;
        }

        // Mirror `WalkNodeEvaluator.getNeighbors`: resolve the four cardinal
        // steps first (kept in `cardinal` even when rejected as a move, since
        // the diagonal gate inspects them), then the four diagonals.
        int cardinal[4] = {-1, -1, -1, -1};
        bool cardinal_fastpath_resolved[4] = {};
        const std::int8_t origin_type = path_type_at(in, current.x, current.y, current.z);
        const int origin_type_index = static_cast<int>(origin_type);
        const bool flat_origin = origin_type == kWalkable;
        // A WALKABLE origin can only jump when its head cell is passable. If
        // that vanilla vertical-delta limit is zero, a BLOCKED cardinal
        // neighbour is rejected before any other findAcceptedNode branch.
        const bool flat_origin_no_vertical_delta = fastpath_enabled && flat_origin
            && malus_for(in, path_type_at(in, current.x, current.y + 1, current.z)) < 0.0F;
        const float origin_floor_level = flat_origin
            ? (in.lazy_context
                ? floor_level_at(in, current.x, current.y, current.z)
                : (in.floor_levels
                    ? in.floor_levels[grid_index(in, current.x, current.y, current.z)]
                    : static_cast<float>(current.y)))
            : 0.0F;
        for (int d = 0; d < 4; ++d) {
            if (collect_fastpath_stats) ++cardinal_fastpath_candidates;
            int ny = current.y;
            std::int8_t type = kBlocked;
            float malus = -1.0F;
            bool closed = false;
            const int nx = current.x + dir_x[d];
            const int nz = current.z + dir_z[d];
            bool fast_resolved = false;
            const bool neighbor_in_region = in_region(in, nx, current.y, nz);
            int neighbor_grid = -1;
            const bool inspect_neighbor_type = neighbor_in_region
                && ((fastpath_enabled && flat_origin) || collect_fastpath_stats);
            if (inspect_neighbor_type) {
                neighbor_grid = grid_index(in, nx, current.y, nz);
                type = in.lazy_context
                    ? path_type_at(in, nx, current.y, nz)
                    : in.path_types[neighbor_grid];
                if (collect_fastpath_stats) {
                    const int neighbor_type_index = static_cast<int>(type);
                    if (origin_type_index >= 0 && origin_type_index < 26
                            && neighbor_type_index >= 0 && neighbor_type_index < 26) {
                        ++cardinal_fastpath_bucket_candidates[static_cast<std::size_t>(
                            origin_type_index * 26 + neighbor_type_index)];
                    }
                }
            } else if (collect_fastpath_stats) {
                ++cardinal_fastpath_out_of_region_candidates;
            }
            // Flat WALKABLE -> WALKABLE cardinal steps do not need the jump,
            // fall, or partial-collision chain. Eager snapshots use the one
            // computed grid index for both masks and floor comparison; lazy
            // snapshots keep their accessor path and the same strict guards.
            if (fastpath_enabled && flat_origin && neighbor_in_region) {
                const int neighbor_type_index = static_cast<int>(type);
                const std::size_t bucket = (origin_type_index >= 0 && origin_type_index < 26
                        && neighbor_type_index >= 0 && neighbor_type_index < 26)
                    ? static_cast<std::size_t>(origin_type_index * 26 + neighbor_type_index)
                    : kPathfinderCardinalFastPathBucketCount;
                if (type == kWalkable) {
                    malus = malus_for(in, type);
                    bool is_standing = malus >= 0.0F;
                    if (!in.lazy_context) {
                        const std::size_t grid = static_cast<std::size_t>(neighbor_grid);
                        is_standing = is_standing
                            && ((scratch.standing[grid >> 6] >> (grid & 63)) & 1ULL) != 0ULL;
                    }
                    if (is_standing
                            && (in.lazy_context
                                ? floor_level_at(in, nx, current.y, nz)
                                : (in.floor_levels
                                    ? in.floor_levels[neighbor_grid]
                                    : static_cast<float>(current.y))) == origin_floor_level) {
                        fast_resolved = true;
                        if (collect_fastpath_stats) {
                            ++cardinal_fastpath_hits;
                            if (bucket < kPathfinderCardinalFastPathBucketCount) {
                                ++cardinal_fastpath_bucket_hits[bucket];
                            }
                        }
                    }
                } else if (flat_origin_no_vertical_delta && type == kBlocked) {
                    // With no jump allowance, findAcceptedNode cannot accept
                    // a BLOCKED cell: it has negative malus and all fallback
                    // branches require another path type. Reject directly.
                    continue;
                }
            }
            if (!fast_resolved
                    && !resolve_step_node(in, current.x, current.y, current.z, nx, nz,
                                          scratch.standing, ny, type, malus, closed)) {
                continue;
            }
            const int ni = get_node(nx, ny, nz, type, malus);
            if (ni < 0) continue;
            if (closed) scratch.nodes[static_cast<std::size_t>(ni)].flags |= kClosedFlag;
            cardinal[d] = ni;
            cardinal_fastpath_resolved[d] = fast_resolved;
        }

        int neighbors[10];
        int neighbor_count = 0;
        for (int d = 0; d < 4; ++d) {
            const int ni = cardinal[d];
            if (ni < 0) continue;
            // isNeighborValid: skip closed nodes; a non-negative malus is
            // required unless the current node is itself negative-malus.
            const PathfinderNode& neighbor = scratch.nodes[static_cast<std::size_t>(ni)];
            if ((neighbor.flags & kClosedFlag) != 0) continue;
            if (!(neighbor.cost_malus >= 0.0F || current.cost_malus < 0.0F)) continue;
            neighbors[neighbor_count++] = ni;
        }
        for (int d = 0; d < 4; ++d) {
            // Vanilla pairs each direction with its clockwise neighbour.
            const int a = cardinal[d];
            const int b = cardinal[(d + 1) & 3];
            if (a < 0 || b < 0) continue;

            // isDiagonalValid(root, xNode, zNode)
            const PathfinderNode& na = scratch.nodes[static_cast<std::size_t>(a)];
            const PathfinderNode& nb = scratch.nodes[static_cast<std::size_t>(b)];
            if (na.y > current.y || nb.y > current.y) continue;
            if (na.type == kWalkableDoor || nb.type == kWalkableDoor) continue;
            const bool fence_gap = na.type == kFence && nb.type == kFence && in.bb_width < 0.5F;
            if (!((nb.y < current.y || nb.cost_malus >= 0.0F || fence_gap)
                  && (na.y < current.y || na.cost_malus >= 0.0F || fence_gap))) {
                continue;
            }

            const int dx = dir_x[d] + dir_x[(d + 1) & 3];
            const int dz = dir_z[d] + dir_z[(d + 1) & 3];
            const int nx = current.x + dx;
            const int nz = current.z + dz;
            int ny = current.y;
            std::int8_t type = kBlocked;
            float malus = -1.0F;
            bool closed = false;
            if (collect_fastpath_stats) ++diagonal_fastpath_candidates;
            bool fast_resolved = false;
            if (fastpath_enabled && cardinal_fastpath_resolved[d]
                    && cardinal_fastpath_resolved[(d + 1) & 3]
                    && in_region(in, nx, current.y, nz)) {
                const int diagonal_grid = grid_index(in, nx, current.y, nz);
                type = in.lazy_context
                    ? path_type_at(in, nx, current.y, nz)
                    : in.path_types[diagonal_grid];
                if (type == kWalkable) {
                    malus = malus_for(in, type);
                    bool is_standing = malus >= 0.0F;
                    if (!in.lazy_context) {
                        const std::size_t grid = static_cast<std::size_t>(diagonal_grid);
                        is_standing = is_standing
                            && ((scratch.standing[grid >> 6] >> (grid & 63)) & 1ULL) != 0ULL;
                    }
                    if (is_standing
                            && (in.lazy_context
                                ? floor_level_at(in, nx, current.y, nz)
                                : (in.floor_levels
                                    ? in.floor_levels[diagonal_grid]
                                    : static_cast<float>(current.y))) == origin_floor_level) {
                        fast_resolved = true;
                        if (collect_fastpath_stats) ++diagonal_fastpath_hits;
                    }
                }
            }
            if (!fast_resolved
                    && !resolve_step_node(in, current.x, current.y, current.z,
                                          nx, nz, scratch.standing, ny, type, malus, closed)) {
                continue;
            }
            const int ni = get_node(nx, ny, nz, type, malus);
            if (ni < 0) continue;
            if (closed) scratch.nodes[static_cast<std::size_t>(ni)].flags |= kClosedFlag;
            // isDiagonalValid(node): not closed, not a walkable door, malus >= 0.
            const PathfinderNode& diagonal = scratch.nodes[static_cast<std::size_t>(ni)];
            if ((diagonal.flags & kClosedFlag) != 0) continue;
            if (diagonal.type == kWalkableDoor) continue;
            if (!(diagonal.cost_malus >= 0.0F)) continue;
            neighbors[neighbor_count++] = ni;
        }

        if (in.is_amphibious) {
            // AmphibiousNodeEvaluator first obtains the normal walking
            // neighbours above, then appends water-only vertical moves. Keep
            // the same two acceptance calls and their asymmetric step limits.
            const std::int8_t origin_type = path_type_at(in, current.x, current.y, current.z);
            const std::int8_t head_type = path_type_at(in, current.x, current.y + 1, current.z);
            const int vertical_limit = (malus_for(in, head_type) >= 0.0F && origin_type != kStickyHoney)
                ? static_cast<int>(std::floor(std::max(1.0F, in.max_up_step))) : 0;
            const float node_floor_level = floor_level_at(in, current.x, current.y, current.z);
            for (int direction = 0; direction < 2; ++direction) {
                const int dy = direction == 0 ? 1 : -1;
                if (direction == 1 && origin_type == kTrapdoor) continue;
                int ny = current.y + dy;
                std::int8_t type = kBlocked;
                float malus = -1.0F;
                bool closed = false;
                const int limit = direction == 0 ? std::max(0, vertical_limit - 1) : vertical_limit;
                if (!find_accepted_node(in, current.x, ny, current.z, limit, node_floor_level,
                                        origin_type, scratch.standing, ny, type, malus, closed)) {
                    continue;
                }
                if (type != kWater) continue;
                const int ni = get_node(current.x, ny, current.z, type, malus);
                if (ni < 0) continue;
                if (closed) scratch.nodes[static_cast<std::size_t>(ni)].flags |= kClosedFlag;
                const PathfinderNode& neighbor = scratch.nodes[static_cast<std::size_t>(ni)];
                if ((neighbor.flags & kClosedFlag) != 0) continue;
    const std::int64_t end_x = static_cast<std::int64_t>(snapshot.min_x) + snapshot.size_x;
    const std::int64_t end_y = static_cast<std::int64_t>(snapshot.min_y) + snapshot.size_y;
    const std::int64_t end_z = static_cast<std::int64_t>(snapshot.min_z) + snapshot.size_z;
                if (!(neighbor.cost_malus >= 0.0F || current.cost_malus < 0.0F)) continue;
                neighbors[neighbor_count++] = ni;
            }
        }

        for (int i = 0; i < neighbor_count; ++i) {
            const int ni = neighbors[i];
            PathfinderNode& neighbor = scratch.nodes[static_cast<std::size_t>(ni)];
            if ((neighbor.flags & kClosedFlag) != 0) continue;
            const float step = distance(current.x, current.y, current.z,
                                        neighbor.x, neighbor.y, neighbor.z);
            const float walked = current.walked_distance + step;
            const float g = current.g + step + neighbor.cost_malus;
            if (walked >= in.config.max_range) continue;
            if ((neighbor.flags & kOpenFlag) == 0 || g < neighbor.g) {
                neighbor.came_from = current_index;
                neighbor.walked_distance = walked;
                neighbor.g = g;
[[nodiscard]] const PathfinderStateMirrorSection* snapshot_mirror_section(
        const PathfinderStateSnapshot& snapshot, int x, int y, int z) noexcept {
    if (!snapshot.mirror_sections || snapshot.mirror_section_size_x <= 0
            || snapshot.mirror_section_size_y <= 0 || snapshot.mirror_section_size_z <= 0) return nullptr;
    const int sx = (x >> 4) - snapshot.mirror_section_min_x;
    const int sy = (y >> 4) - snapshot.mirror_section_min_y;
    const int sz = (z >> 4) - snapshot.mirror_section_min_z;
    if (sx < 0 || sy < 0 || sz < 0 || sx >= snapshot.mirror_section_size_x
            || sy >= snapshot.mirror_section_size_y || sz >= snapshot.mirror_section_size_z) return nullptr;
    const std::size_t slot = (static_cast<std::size_t>(sy) * snapshot.mirror_section_size_z
        + static_cast<std::size_t>(sz)) * snapshot.mirror_section_size_x + static_cast<std::size_t>(sx);
    return snapshot.mirror_sections[slot];
}

                // `PathFinder.getBestH` keeps Target.bestNode in the raw
                // distance domain, while the A* f-cost uses that value times
                // FUDGING. Comparing a fudged neighbour h to the unfudged
    if (snapshot.mirror_sections) {
        if (!snapshot_in_bounds(snapshot, x, y, z)) return false;
        const PathfinderStateMirrorSection* section = snapshot_mirror_section(snapshot, x, y, z);
        if (!section) return false;
        const int local = mirror_section_index(x, y, z);
        if (section->valid[local] == 0) return false;
        type = section->raw_path_types[local];
        return type >= kBlocked && type < kPathTypeCount;
    }
                // start h made a partial search keep its start node even when
                // it had already moved closer to the target.
                const float heuristic = best_h(in, neighbor, &best_target);
                neighbor.h = heuristic * in.config.fudge;
                neighbor.f = neighbor.g + neighbor.h;
                if (heuristic < best_node_h) {
                    best_node_h = heuristic;
                    best_node = ni;
                    best_node_target = best_target;
                }
                if ((neighbor.flags & kOpenFlag) != 0) {
    if (snapshot.mirror_sections) {
        if (!snapshot_in_bounds(snapshot, x, y, z)) return false;
        const PathfinderStateMirrorSection* section = snapshot_mirror_section(snapshot, x, y, z);
        if (!section) return false;
        const int local = mirror_section_index(x, y, z);
        if (section->valid[local] == 0) return false;
        height = section->floor_heights[local];
        return std::isfinite(height) && height >= 0.0F && height <= 1.0F;
    }
                    heap.change_cost(ni);
                } else {
                    heap.push(ni);
                }
            }
        }
    }

    SearchResult result{};
    result.scratch = &scratch;
    result.end_index = best_node;
    result.target_index = best_node_target;
    result.reached_target = false;
    return finish(result);
}

[[nodiscard]] bool snapshot_in_bounds(const PathfinderStateSnapshot& snapshot,
                                      int x, int y, int z) noexcept {
    return x >= snapshot.min_x && y >= snapshot.min_y && z >= snapshot.min_z
        && static_cast<std::int64_t>(x) < end_x
        && static_cast<std::int64_t>(y) < end_y
        && static_cast<std::int64_t>(z) < end_z;
}

[[nodiscard]] bool snapshot_index(const PathfinderStateSnapshot& snapshot,
                                  int x, int y, int z, int& index) noexcept {
    if (!snapshot_in_bounds(snapshot, x, y, z)) return false;
    const int lx = x - snapshot.min_x;
    const int ly = y - snapshot.min_y;
    const int lz = z - snapshot.min_z;
    const long long value = (static_cast<long long>(ly) * snapshot.size_z + lz) * snapshot.size_x + lx;
    if (value < 0 || value > std::numeric_limits<int>::max()) return false;
    index = static_cast<int>(value);
    return true;
}

[[nodiscard]] bool snapshot_raw_type(const PathfinderStateSnapshot& snapshot,
                                     int x, int y, int z,
                                     std::int8_t& type) noexcept {
    int index = 0;
    if (!snapshot_index(snapshot, x, y, z, index)) return false;
    const int descriptor = snapshot.cells[index];
    if (descriptor < 0 || descriptor >= snapshot.descriptor_count) return false;
    type = snapshot.raw_path_types[descriptor];
    return type >= kBlocked && type < kPathTypeCount;
}

[[nodiscard]] bool snapshot_floor_height(const PathfinderStateSnapshot& snapshot,
                                         int x, int y, int z,
                                         float& height) noexcept {
    int index = 0;
    if (!snapshot_index(snapshot, x, y, z, index)) return false;
    const int descriptor = snapshot.cells[index];
    if (descriptor < 0 || descriptor >= snapshot.descriptor_count) return false;
    height = snapshot.floor_heights[descriptor];
    return std::isfinite(height) && height >= 0.0F && height <= 1.0F;
}

/// Exact port of WalkNodeEvaluator.getPathTypeStatic/checkNeighbourBlocks,
/// operating only on the raw BlockState descriptor snapshot.
[[nodiscard]] bool static_path_type(const PathfinderStateSnapshot& snapshot,
                                    const PathfinderInputs& in, int x, int y, int z,
                                    std::int8_t& type) noexcept {
    if (!snapshot_raw_type(snapshot, x, y, z, type)) return false;
    if (in.is_amphibious && type == kWater) {
        constexpr int offsets[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (const auto& offset : offsets) {
            std::int8_t neighbour = kBlocked;
            if (!snapshot_raw_type(snapshot, x + offset[0], y + offset[1], z + offset[2], neighbour)) return false;
            if (neighbour == kBlocked) {
                type = kWaterBorder;
                break;
            }
        }
        return true;
    }
    if (type != kOpen || y < in.level_min_y + 1) return true;

    std::int8_t below = kBlocked;
    if (!snapshot_raw_type(snapshot, x, y - 1, z, below)) return false;
    switch (below) {
        case kOpen:
        case kWater:
        case kLava:
        case kWalkable:
            return true;
        case kDamageFire:
            type = kDamageFire;
            return true;
        case kDamageOther:
            type = kDamageOther;
            return true;
        case kStickyHoney:
            type = kStickyHoney;
            return true;
        case kPowderSnow:
            type = kDangerPowderSnow;
            return true;
        case kDamageCautious:
            type = kDamageCautious;
            return true;
        case kTrapdoor:
            type = kDangerTrapdoor;
            return true;
        default:
            break;
    }

    type = kWalkable;
    for (int dx = -1; dx <= 1; ++dx) {
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dz = -1; dz <= 1; ++dz) {
                if (dx == 0 && dz == 0) continue;
                std::int8_t neighbour = kBlocked;
                if (!snapshot_raw_type(snapshot, x + dx, y + dy, z + dz, neighbour)) return false;
                if (neighbour == kDamageOther) {
                    type = kDangerOther;
                    return true;
                }
                if (neighbour == kDamageFire || neighbour == kLava) {
                    type = kDangerFire;
                    return true;
                }
                if (neighbour == kWater) {
                    type = kWaterBorder;
                    return true;
                }
                if (neighbour == kDamageCautious) {
                    type = kDamageCautious;
                    return true;
                }
            }
        }
    }
    return true;
}

[[nodiscard]] bool mob_path_type(const PathfinderInputs& in,
                                 const PathfinderStateSnapshot& snapshot,
                                 int x, int y, int z,
                                 std::int8_t& result) noexcept {
[[nodiscard]] bool single_cell_path_type_from_raw(const PathfinderInputs& in,
                                                 const PathfinderStateSnapshot& snapshot,
                                                 int x, int y, int z, std::int8_t raw_type,
                                                 std::int8_t& result) noexcept {
    // OPEN and amphibious water need contextual classification; rails depend on
    // the mob position. Keep those cases on the canonical evaluator.
    if (raw_type == kOpen || raw_type == kRail || (in.is_amphibious && raw_type == kWater)) {
        return mob_path_type(in, snapshot, x, y, z, result);
    }
    result = raw_type;
    if (result == kDoorWoodClosed && in.can_open_doors && in.can_pass_doors) {
        result = kWalkableDoor;
    } else if (result == kDoorOpen && !in.can_pass_doors) {
        result = kBlocked;
    } else if (result == kRail) {
        std::int8_t at_mob = kBlocked;
        std::int8_t below_mob = kBlocked;
        if (!static_path_type(snapshot, in, in.mob_block_x, in.mob_block_y, in.mob_block_z, at_mob)
                || !static_path_type(snapshot, in, in.mob_block_x, in.mob_block_y - 1,
                                      in.mob_block_z, below_mob)) return false;
        if (at_mob != kRail && below_mob != kRail) result = kUnpassableRail;
    }
    if (result == kFence || result == kUnpassableRail) return true;
    return true;
}

void build_single_cell_type_table(const PathfinderInputs& in,
                                 std::array<std::int8_t, kPathTypeCount>& types) noexcept {
    const float blocked_malus = malus_for(in, kBlocked);
    for (int raw = 0; raw < kPathTypeCount; ++raw) {
        const std::int8_t raw_type = static_cast<std::int8_t>(raw);
        if (raw_type == kOpen || raw_type == kRail || (in.is_amphibious && raw_type == kWater)) {
            types[static_cast<std::size_t>(raw)] = -1;
            continue;
        }
        std::int8_t result = raw_type;
        if (result == kDoorWoodClosed && in.can_open_doors && in.can_pass_doors) {
            result = kWalkableDoor;
        } else if (result == kDoorOpen && !in.can_pass_doors) {
            result = kBlocked;
        }
        if (result != kFence && result != kUnpassableRail) {
            const float type_malus = malus_for(in, result);
            if (!(type_malus < 0.0F || type_malus >= blocked_malus)) result = kBlocked;
        }
        types[static_cast<std::size_t>(raw)] = result;
    }
}

[[nodiscard]] bool materialize_single_cell_mirror_view(const PathfinderInputs& in,
                                                      const PathfinderStateSnapshot& snapshot,
                                                      int count,
                                                      PathfinderScratch& scratch) noexcept {
    std::array<std::int8_t, kPathTypeCount> single_cell_types{};
    build_single_cell_type_table(in, single_cell_types);
    const std::int64_t end_y = static_cast<std::int64_t>(in.region_min_y) + in.region_size_y;
    const std::int64_t end_z = static_cast<std::int64_t>(in.region_min_z) + in.region_size_z;
    const std::int64_t end_x = static_cast<std::int64_t>(in.region_min_x) + in.region_size_x;
    int index = 0;
    for (std::int64_t y_value = in.region_min_y; y_value < end_y; ++y_value) {
        const int y = static_cast<int>(y_value);
        for (std::int64_t z_value = in.region_min_z; z_value < end_z; ++z_value) {
            const int z = static_cast<int>(z_value);
            for (std::int64_t x_value = in.region_min_x; x_value < end_x;) {
                const int x = static_cast<int>(x_value);
                const PathfinderStateMirrorSection* path_section = snapshot_mirror_section(snapshot, x, y, z);
                const PathfinderStateMirrorSection* floor_section =
                    snapshot_mirror_section(snapshot, x, y - 1, z);
                if (!path_section) return false;
                const std::int64_t run = std::min<std::int64_t>(16 - (x & 15), end_x - x_value);
                for (std::int64_t offset = 0; offset < run; ++offset, ++index) {
                    const int cell_x = static_cast<int>(x_value + offset);
                    const int path_local = mirror_section_index(cell_x, y, z);
                    if (path_section->valid[path_local] == 0) return false;
                    const std::int8_t raw_type = path_section->raw_path_types[path_local];
                    if (raw_type < kBlocked || raw_type >= kPathTypeCount) return false;
                    const std::int8_t mapped_type = single_cell_types[static_cast<std::size_t>(raw_type)];
                    std::int8_t type = mapped_type;
                    if (mapped_type < 0
                            && !single_cell_path_type_from_raw(in, snapshot, cell_x, y, z, raw_type, type)) return false;
                    scratch.materialized_path_types[static_cast<std::size_t>(index)] = type;
                    if ((in.can_float || in.is_amphibious) && raw_type == kWater) {
                        scratch.materialized_floor_levels[static_cast<std::size_t>(index)] = static_cast<float>(y) + 0.5F;
                    } else {
                        if (!floor_section) return false;
                        const int floor_local = mirror_section_index(cell_x, y - 1, z);
                        if (floor_section->valid[floor_local] == 0) return false;
                        const float floor_height = floor_section->floor_heights[floor_local];
                        if (!std::isfinite(floor_height) || floor_height < 0.0F || floor_height > 1.0F) return false;
                        scratch.materialized_floor_levels[static_cast<std::size_t>(index)] =
                            static_cast<float>(y - 1) + floor_height;
                    }
                }
                x_value += run;
            }
        }
    }
    return index == count;
}

    bool present[kPathTypeCount]{};
    for (int dx = 0; dx < in.entity_width; ++dx) {
        for (int dy = 0; dy < in.entity_height; ++dy) {
            for (int dz = 0; dz < in.entity_width; ++dz) {
                std::int8_t type = kBlocked;
                if (!static_path_type(snapshot, in, x + dx, y + dy, z + dz, type)) return false;
                if (type == kDoorWoodClosed && in.can_open_doors && in.can_pass_doors) {
                    type = kWalkableDoor;
                } else if (type == kDoorOpen && !in.can_pass_doors) {
                    type = kBlocked;
                } else if (type == kRail) {
                    std::int8_t at_mob = kBlocked;
                    std::int8_t below_mob = kBlocked;
                    if (!static_path_type(snapshot, in, in.mob_block_x, in.mob_block_y, in.mob_block_z, at_mob)
                            || !static_path_type(snapshot, in, in.mob_block_x, in.mob_block_y - 1, in.mob_block_z, below_mob)) {
                        return false;
                    }
                    if (at_mob != kRail && below_mob != kRail) type = kUnpassableRail;
                }
    int index = 0;
                present[static_cast<int>(type)] = true;
            }
        }
    }

    if (present[kFence]) {
        result = kFence;
        return true;
    }
    if (present[kUnpassableRail]) {
        result = kUnpassableRail;
        return true;
    }
    result = kBlocked;
    for (int type = 0; type < kPathTypeCount; ++type) {
        if (!present[type]) continue;
        const float malus = malus_for(in, static_cast<std::int8_t>(type));
        if (malus < 0.0F) {
            result = static_cast<std::int8_t>(type);
            return true;
        }
        if (malus >= malus_for(in, result)) result = static_cast<std::int8_t>(type);
    }
    if (in.entity_width <= 1 && result != kOpen && malus_for(in, result) == 0.0F) {
        std::int8_t origin = kBlocked;
        if (!static_path_type(snapshot, in, x, y, z, origin)) return false;
        if (origin == kOpen) result = kOpen;
    }
    return true;
}

[[nodiscard]] bool materialize_state_snapshot(const PathfinderInputs& in,
                                               const PathfinderStateSnapshot& snapshot,
                                               PathfinderScratch& scratch) noexcept {
    if ((!snapshot.mirror_sections && (!snapshot.cells || !snapshot.raw_path_types
            || !snapshot.floor_heights || snapshot.descriptor_count <= 0)) || snapshot.size_x <= 0
            || snapshot.size_y <= 0 || snapshot.size_z <= 0) return false;
    if (!region_endpoint_fits(in.region_min_x, in.region_size_x)
            || !region_endpoint_fits(in.region_min_y, in.region_size_y)
            || !region_endpoint_fits(in.region_min_z, in.region_size_z)) return false;
    const int count = volume(in);
    if (count <= 0) return false;
    scratch.materialized_path_types.resize(static_cast<std::size_t>(count));
    scratch.materialized_floor_levels.resize(static_cast<std::size_t>(count));
    if (snapshot.mirror_sections && in.entity_width == 1 && in.entity_height == 1) {
        return materialize_single_cell_mirror_view(in, snapshot, count, scratch);
    }
    const std::int64_t end_y = static_cast<std::int64_t>(in.region_min_y) + in.region_size_y;
    const std::int64_t end_z = static_cast<std::int64_t>(in.region_min_z) + in.region_size_z;
    const std::int64_t end_x = static_cast<std::int64_t>(in.region_min_x) + in.region_size_x;
    for (std::int64_t y_value = in.region_min_y; y_value < end_y; ++y_value) {
        const int y = static_cast<int>(y_value);
        for (std::int64_t z_value = in.region_min_z; z_value < end_z; ++z_value) {
            const int z = static_cast<int>(z_value);
            for (std::int64_t x_value = in.region_min_x; x_value < end_x; ++x_value, ++index) {
                const int x = static_cast<int>(x_value);
                std::int8_t type = kBlocked;
                float floor_height = 0.0F;
                std::int8_t raw_type = kBlocked;
                if (!mob_path_type(in, snapshot, x, y, z, type)
                        || !snapshot_raw_type(snapshot, x, y, z, raw_type)) return false;
                scratch.materialized_path_types[static_cast<std::size_t>(index)] = type;
                if ((in.can_float || in.is_amphibious) && raw_type == kWater) {
                    scratch.materialized_floor_levels[static_cast<std::size_t>(index)] = static_cast<float>(y) + 0.5F;
                } else {
                    if (!snapshot_floor_height(snapshot, x, y - 1, z, floor_height)) return false;
                    scratch.materialized_floor_levels[static_cast<std::size_t>(index)] =
                        static_cast<float>(y - 1) + floor_height;
                }
            }
        }
    }
    return true;
}

[[nodiscard]] std::uint64_t mirror_section_key(int x, int y, int z) noexcept {
    const std::uint64_t sx = static_cast<std::uint64_t>(x >> 4) & 0x3FFFFFULL;
    const std::uint64_t sy = static_cast<std::uint64_t>(y >> 4) & 0xFFFFFULL;
    const std::uint64_t sz = static_cast<std::uint64_t>(z >> 4) & 0x3FFFFFULL;
    return (sx << 42) | (sz << 20) | sy;
}

[[nodiscard]] int mirror_section_index(int x, int y, int z) noexcept {
    return (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
}

} // namespace

PathfinderResult find_path(const PathfinderInputs& in) noexcept {
    PathfinderScratch scratch{};
    const SearchResult search = run_search(in, scratch);
    if (search.end_index < 0 || !search.scratch) return PathfinderResult{};
    return reconstruct(search.scratch->nodes, search.end_index, search.target_index, search.reached_target);
}

bool find_path_into(const PathfinderInputs& in, PathfinderOutput& output) noexcept {
    output.path_length = 0;
    output.target_index = -1;
    output.reached_target = false;
    if (!output.coords || output.capacity_nodes <= 0) return false;

    PathfinderScratch scratch{};
    return find_path_into(in, output, scratch);
}

bool find_path_into(const PathfinderInputs& in, PathfinderOutput& output,
                    PathfinderScratch& scratch) noexcept {
    output.path_length = 0;
    output.target_index = -1;
    output.reached_target = false;
    if (!output.coords || output.capacity_nodes <= 0) return false;

    SearchResult search = run_search(in, scratch);
    if (search.end_index < 0 || !search.scratch) return false;

    int count = 0;
    const auto& nodes = search.scratch->nodes;
    for (int i = search.end_index; i >= 0; i = nodes[static_cast<std::size_t>(i)].came_from) {
        ++count;
    }
    if (count > output.capacity_nodes) return false;

    int out_index = count - 1;
    for (int i = search.end_index; i >= 0; i = nodes[static_cast<std::size_t>(i)].came_from) {
        const PathfinderNode& node = nodes[static_cast<std::size_t>(i)];
        const int base = out_index * 3;
        output.coords[base] = node.x;
        output.coords[base + 1] = node.y;
        output.coords[base + 2] = node.z;
        --out_index;
    }

    output.path_length = count;
    output.target_index = search.target_index;
    output.reached_target = search.reached_target;
    return true;
}

bool materialize_pathfinder_state_snapshot(const PathfinderInputs& inputs,
                                           const PathfinderStateSnapshot& snapshot,
                                           PathfinderScratch& scratch) noexcept {
    return materialize_state_snapshot(inputs, snapshot, scratch);
}

void store_pathfinder_state_snapshot(PathfinderStateMirror& mirror, int world_key,
                                     const PathfinderStateSnapshot& snapshot) noexcept {
    if (!snapshot.cells || !snapshot.raw_path_types || !snapshot.floor_heights
            || snapshot.descriptor_count <= 0) return;
    if (mirror.world_key != world_key) {
        mirror.world_key = world_key;
        mirror.sections.clear();
    }
    int index = 0;
    // Cache the section pointer across the innermost run, mirroring
    // load_pathfinder_state_snapshot. The key only changes every sixteen x, so an
    // uncached operator[] here costs one hash lookup per cell: at ~110k cells per
    // upload that measured ~6.0ms against ~0.11ms for the cached load path.
    std::uint64_t cached_key = 0;
    PathfinderStateMirrorSection* cached_section = nullptr;
    const std::int64_t end_y = static_cast<std::int64_t>(snapshot.min_y) + snapshot.size_y;
    const std::int64_t end_z = static_cast<std::int64_t>(snapshot.min_z) + snapshot.size_z;
    const std::int64_t end_x = static_cast<std::int64_t>(snapshot.min_x) + snapshot.size_x;
    for (std::int64_t y_value = snapshot.min_y; y_value < end_y; ++y_value) {
        const int y = static_cast<int>(y_value);
        for (std::int64_t z_value = snapshot.min_z; z_value < end_z; ++z_value) {
            const int z = static_cast<int>(z_value);
            for (std::int64_t x_value = snapshot.min_x; x_value < end_x; ++x_value, ++index) {
                const int x = static_cast<int>(x_value);
                const int descriptor = snapshot.cells[index];
                if (descriptor < 0 || descriptor >= snapshot.descriptor_count) return;
                const std::uint64_t key = mirror_section_key(x, y, z);
                if (cached_section == nullptr || key != cached_key) {
                    cached_section = &mirror.sections[key];
                    cached_key = key;
                }
                const int local = mirror_section_index(x, y, z);
                cached_section->raw_path_types[local] = snapshot.raw_path_types[descriptor];
                cached_section->floor_heights[local] = snapshot.floor_heights[descriptor];
                if (cached_section->valid[local] == 0) {
                    cached_section->valid[local] = 1;
                    ++cached_section->valid_count;
                }
            }
        }
namespace {

[[nodiscard]] bool load_state_mirror_view(const PathfinderStateMirror& mirror, int world_key,
                                          int min_x, int min_y, int min_z,
                                          int size_x, int size_y, int size_z,
                                          PathfinderScratch& scratch,
                                          PathfinderStateSnapshot& snapshot) noexcept {
    if (mirror.world_key != world_key || size_x <= 0 || size_y <= 0 || size_z <= 0) return false;
    const std::int64_t section_min_x = static_cast<std::int64_t>(min_x) >> 4;
    const std::int64_t section_min_y = static_cast<std::int64_t>(min_y) >> 4;
    const std::int64_t section_min_z = static_cast<std::int64_t>(min_z) >> 4;
    const std::int64_t section_max_x = (static_cast<std::int64_t>(min_x) + size_x - 1) >> 4;
    const std::int64_t section_max_y = (static_cast<std::int64_t>(min_y) + size_y - 1) >> 4;
    const std::int64_t section_max_z = (static_cast<std::int64_t>(min_z) + size_z - 1) >> 4;
    const std::int64_t section_x_count = section_max_x - section_min_x + 1;
    const std::int64_t section_y_count = section_max_y - section_min_y + 1;
    const std::int64_t section_z_count = section_max_z - section_min_z + 1;
    if (section_x_count <= 0 || section_x_count > std::numeric_limits<int>::max()
            || section_y_count <= 0 || section_y_count > std::numeric_limits<int>::max()
            || section_z_count <= 0 || section_z_count > std::numeric_limits<int>::max()) return false;
    const int section_size_x = static_cast<int>(section_x_count);
    const int section_size_y = static_cast<int>(section_y_count);
    const int section_size_z = static_cast<int>(section_z_count);
    std::int64_t section_count = 1;
    if (!checked_mul_i64(section_count, section_size_x, section_count)
            || !checked_mul_i64(section_count, section_size_y, section_count)
            || !checked_mul_i64(section_count, section_size_z, section_count)
            || section_count <= 0 || section_count > std::numeric_limits<int>::max()) return false;
    scratch.mirror_sections.resize(static_cast<std::size_t>(section_count));
    std::fill(scratch.mirror_sections.begin(), scratch.mirror_sections.end(), nullptr);
    for (std::int64_t sy = section_min_y; sy <= section_max_y; ++sy) {
        for (std::int64_t sz = section_min_z; sz <= section_max_z; ++sz) {
            for (std::int64_t sx = section_min_x; sx <= section_max_x; ++sx) {
                const int block_x = static_cast<int>(sx * 16);
                const int block_y = static_cast<int>(sy * 16);
                const int block_z = static_cast<int>(sz * 16);
                const auto section = mirror.sections.find(mirror_section_key(block_x, block_y, block_z));
                if (section == mirror.sections.end()) return false;
                const std::size_t slot = (static_cast<std::size_t>(sy - section_min_y) * section_size_z
                    + static_cast<std::size_t>(sz - section_min_z)) * section_size_x
                    + static_cast<std::size_t>(sx - section_min_x);
                scratch.mirror_sections[slot] = &section->second;
            }
        }
    }
    snapshot.cells = nullptr;
    snapshot.raw_path_types = nullptr;
    snapshot.floor_heights = nullptr;
    snapshot.descriptor_count = 0;
    snapshot.min_x = min_x;
    snapshot.min_y = min_y;
    snapshot.min_z = min_z;
    snapshot.size_x = size_x;
    snapshot.size_y = size_y;
    snapshot.size_z = size_z;
    snapshot.mirror_sections = scratch.mirror_sections.data();
    snapshot.mirror_section_min_x = section_min_x;
    snapshot.mirror_section_min_y = section_min_y;
    snapshot.mirror_section_min_z = section_min_z;
    snapshot.mirror_section_size_x = section_size_x;
    snapshot.mirror_section_size_y = section_size_y;
    snapshot.mirror_section_size_z = section_size_z;
    return true;
}

} // namespace

    }
}

std::uint64_t pathfinder_mirror_section_key(int x, int y, int z) noexcept {
    return mirror_section_key(x, y, z);
}

bool state_mirror_covers(const PathfinderStateMirror& mirror, int world_key,
                         int min_x, int min_y, int min_z,
                         int size_x, int size_y, int size_z) noexcept {
    if (mirror.world_key != world_key || size_x <= 0 || size_y <= 0 || size_z <= 0) return false;
    const std::int64_t max_x = static_cast<std::int64_t>(min_x) + size_x - 1;
    const std::int64_t max_y = static_cast<std::int64_t>(min_y) + size_y - 1;
    const std::int64_t max_z = static_cast<std::int64_t>(min_z) + size_z - 1;
    for (std::int64_t sy_value = (min_y >> 4); sy_value <= (max_y >> 4); ++sy_value) {
        const int sy = static_cast<int>(sy_value);
        for (std::int64_t sz_value = (min_z >> 4); sz_value <= (max_z >> 4); ++sz_value) {
            const int sz = static_cast<int>(sz_value);
            for (std::int64_t sx_value = (min_x >> 4); sx_value <= (max_x >> 4); ++sx_value) {
                const int sx = static_cast<int>(sx_value);
                const auto it = mirror.sections.find(mirror_section_key(sx * 16, sy * 16, sz * 16));
                if (it == mirror.sections.end()) return false;
                const PathfinderStateMirrorSection& section = it->second;
                if (section.valid_count == 4096) continue;
                // Partially populated: check only the cells this query needs.
                const std::int64_t section_x = static_cast<std::int64_t>(sx) * 16;
                const std::int64_t section_y = static_cast<std::int64_t>(sy) * 16;
                const std::int64_t section_z = static_cast<std::int64_t>(sz) * 16;
                const std::int64_t lo_x = std::max<std::int64_t>(min_x, section_x);
                const std::int64_t hi_x = std::min(max_x, section_x + 15);
                const std::int64_t lo_y = std::max<std::int64_t>(min_y, section_y);
                const std::int64_t hi_y = std::min(max_y, section_y + 15);
                const std::int64_t lo_z = std::max<std::int64_t>(min_z, section_z);
                const std::int64_t hi_z = std::min(max_z, section_z + 15);
                for (std::int64_t y_value = lo_y; y_value <= hi_y; ++y_value) {
                    const int y = static_cast<int>(y_value);
                    for (std::int64_t z_value = lo_z; z_value <= hi_z; ++z_value) {
                        const int z = static_cast<int>(z_value);
                        for (std::int64_t x_value = lo_x; x_value <= hi_x; ++x_value) {
                            const int x = static_cast<int>(x_value);
                            if (section.valid[mirror_section_index(x, y, z)] == 0) return false;
                        }
                    }
                }
            }
        }
    }
    return true;
}

bool load_pathfinder_state_snapshot(const PathfinderStateMirror& mirror, int world_key,
                                    int min_x, int min_y, int min_z,
                                    int size_x, int size_y, int size_z,
                                    PathfinderScratch& scratch,
                                    PathfinderStateSnapshot& snapshot) noexcept {
    std::int64_t count = 1;
    if (!checked_mul_i64(count, size_x, count)
            || !checked_mul_i64(count, size_y, count)
            || !checked_mul_i64(count, size_z, count)
            || count <= 0 || count > std::numeric_limits<int>::max()) return false;
    if (!load_state_mirror_view(mirror, world_key, min_x, min_y, min_z,
                                size_x, size_y, size_z, scratch, snapshot)) return false;
    scratch.mirror_cells.resize(static_cast<std::size_t>(count));
    scratch.mirror_raw_path_types.resize(static_cast<std::size_t>(count));
    scratch.mirror_floor_heights.resize(static_cast<std::size_t>(count));
    int index = 0;
    const std::int64_t end_y = static_cast<std::int64_t>(min_y) + size_y;
    const std::int64_t end_z = static_cast<std::int64_t>(min_z) + size_z;
    const std::int64_t end_x = static_cast<std::int64_t>(min_x) + size_x;
    for (std::int64_t y_value = min_y; y_value < end_y; ++y_value) {
        const int y = static_cast<int>(y_value);
        for (std::int64_t z_value = min_z; z_value < end_z; ++z_value) {
            const int z = static_cast<int>(z_value);
            for (std::int64_t x_value = min_x; x_value < end_x; ++x_value, ++index) {
                const int x = static_cast<int>(x_value);
                std::int8_t raw_type = kBlocked;
                float floor_height = 0.0F;
                if (!snapshot_raw_type(snapshot, x, y, z, raw_type)
                        || !snapshot_floor_height(snapshot, x, y, z, floor_height)) return false;
                scratch.mirror_cells[static_cast<std::size_t>(index)] = index;
                scratch.mirror_raw_path_types[static_cast<std::size_t>(index)] = raw_type;
                scratch.mirror_floor_heights[static_cast<std::size_t>(index)] = floor_height;
            }
        }
    }
    snapshot.cells = scratch.mirror_cells.data();
    snapshot.raw_path_types = scratch.mirror_raw_path_types.data();
    snapshot.floor_heights = scratch.mirror_floor_heights.data();
    snapshot.descriptor_count = static_cast<int>(count);
    snapshot.mirror_sections = nullptr;
    snapshot.mirror_section_size_x = 0;
    snapshot.mirror_section_size_y = 0;
    snapshot.mirror_section_size_z = 0;
    return true;
}

void invalidate_pathfinder_state_mirror_cell(PathfinderStateMirror& mirror, int world_key,
                                             int x, int y, int z) noexcept {
    if (mirror.world_key != world_key) return;
    const auto section = mirror.sections.find(mirror_section_key(x, y, z));
    if (section == mirror.sections.end()) return;
    std::uint8_t& valid = section->second.valid[mirror_section_index(x, y, z)];
    if (valid == 0) return;
    valid = 0;
    if (--section->second.valid_count == 0) {
        // Drop fully-invalidated sections so a long-lived mirror does not retain
        // an 8 KiB section per position ever touched.
        mirror.sections.erase(section);
    }
}

bool find_path_from_state_mirror_into(const PathfinderInputs& inputs,
                                      const PathfinderStateMirror& mirror, int world_key,
                                      PathfinderOutput& output,
                                      PathfinderScratch& scratch) noexcept {
    int state_min_x = 0, state_max_x = 0, state_size_x = 0;
    int state_min_y = 0, state_max_y = 0, state_size_y = 0;
    int state_min_z = 0, state_max_z = 0, state_size_z = 0;
    if (!checked_state_axis(inputs.region_min_x, inputs.region_size_x, inputs.entity_width,
                            state_min_x, state_max_x, state_size_x)
            || !checked_state_axis(inputs.region_min_y, inputs.region_size_y, inputs.entity_height,
                                   state_min_y, state_max_y, state_size_y)
            || !checked_state_axis(inputs.region_min_z, inputs.region_size_z, inputs.entity_width,
                                   state_min_z, state_max_z, state_size_z)) return false;
    PathfinderStateSnapshot snapshot{};
    if (!load_state_mirror_view(mirror, world_key,
                                state_min_x, state_min_y, state_min_z,
                                state_size_x, state_size_y, state_size_z,
                                scratch, snapshot)) return false;
    PathfinderInputs materialized_inputs = inputs;
    return find_path_from_state_snapshot_into(materialized_inputs, snapshot, output, scratch);
}

bool find_path_from_state_snapshot_into(const PathfinderInputs& inputs,
                                        const PathfinderStateSnapshot& snapshot,
                                        PathfinderOutput& output,
                                        PathfinderScratch& scratch) noexcept {
    output.path_length = 0;
    output.target_index = -1;
    output.reached_target = false;
    if (!output.coords || output.capacity_nodes <= 0 || !inputs.pathfinding_malus) return false;
    if (!materialize_pathfinder_state_snapshot(inputs, snapshot, scratch)) return false;
    PathfinderInputs materialized = inputs;
    materialized.path_types = scratch.materialized_path_types.data();
    materialized.floor_levels = scratch.materialized_floor_levels.data();
    return find_path_into(materialized, output, scratch);
}

} // namespace lattice::world::entity
