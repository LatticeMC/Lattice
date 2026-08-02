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

[[nodiscard]] std::int8_t lazy_path_type_at(void* context, int x, int y, int z) noexcept;
[[nodiscard]] float lazy_floor_level_at(void* context, int x, int y, int z) noexcept;

[[nodiscard]] bool valid_inputs(const PathfinderInputs& in) noexcept {
    if ((!in.path_types && !in.lazy_context) || !in.pathfinding_malus) return false;
    if (!in.target_x || !in.target_y || !in.target_z || in.target_count <= 0) return false;
    if (in.region_size_x <= 0 || in.region_size_y <= 0 || in.region_size_z <= 0) return false;
    if (in.config.max_visited_nodes <= 0 || in.config.max_range <= 0.0F) return false;
    return in.pathfinding_malus_count > 0;
}

[[nodiscard]] int volume(const PathfinderInputs& in) noexcept {
    const long long v = static_cast<long long>(in.region_size_x)
        * static_cast<long long>(in.region_size_y)
        * static_cast<long long>(in.region_size_z);
    if (v <= 0 || v > std::numeric_limits<int>::max()) return -1;
    return static_cast<int>(v);
}

[[nodiscard]] bool in_region(const PathfinderInputs& in, int x, int y, int z) noexcept {
    return x >= in.region_min_x && y >= in.region_min_y && z >= in.region_min_z
        && x < in.region_min_x + in.region_size_x
        && y < in.region_min_y + in.region_size_y
        && z < in.region_min_z + in.region_size_z;
}

[[nodiscard]] int grid_index(const PathfinderInputs& in, int x, int y, int z) noexcept {
    const int lx = x - in.region_min_x;
    const int ly = y - in.region_min_y;
    const int lz = z - in.region_min_z;
    return (ly * in.region_size_z + lz) * in.region_size_x + lx;
}

[[nodiscard]] std::int8_t path_type_at(const PathfinderInputs& in, int x, int y, int z) noexcept {
    if (!in_region(in, x, y, z)) return kBlocked;
    if (in.lazy_context) {
        return lazy_path_type_at(in.lazy_context, x, y, z);
    }
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
    if (in.lazy_context) {
        out_type = path_type_at(in, x, y, z);
        out_malus = malus_for(in, out_type);
        return out_type != kOpen && out_malus >= 0.0F;
    }
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
    if (in.lazy_context) {
        return lazy_floor_level_at(in.lazy_context, x, y, z);
    }
    if (!in.floor_levels) return static_cast<float>(y);
    return in.floor_levels[grid_index(in, x, y, z)];
}

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

void mask_clear(std::vector<std::uint64_t>& mask, std::size_t count) {
    mask.assign(mask_words(count), 0ULL);
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

} // namespace

void init_pathfinder_dispatch() noexcept {
    if (g_pathfinder_initialised.load(std::memory_order_acquire)) return;
    MaskBuilderFn fn = &build_pathfinder_masks_scalar;
    const auto& f = lattice::cpu::features();
    (void)f;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    if (f.avx2) fn = &build_pathfinder_masks_avx2;
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

    if (!in.lazy_context) {
        mask_clear(scratch.passable, static_cast<std::size_t>(grid_volume));
        mask_clear(scratch.standing, static_cast<std::size_t>(grid_volume));
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
                return result;
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
                    return result;
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
        for (int d = 0; d < 4; ++d) {
            int ny = current.y;
            std::int8_t type = kBlocked;
            float malus = -1.0F;
            bool closed = false;
            if (!resolve_step_node(in, current.x, current.y, current.z,
                                   current.x + dir_x[d], current.z + dir_z[d],
                                   scratch.standing, ny, type, malus, closed)) {
                continue;
            }
            const int ni = get_node(current.x + dir_x[d], ny, current.z + dir_z[d], type, malus);
            if (ni < 0) continue;
            if (closed) scratch.nodes[static_cast<std::size_t>(ni)].flags |= kClosedFlag;
            cardinal[d] = ni;
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
            int ny = current.y;
            std::int8_t type = kBlocked;
            float malus = -1.0F;
            bool closed = false;
            if (!resolve_step_node(in, current.x, current.y, current.z,
                                   current.x + dx, current.z + dz,
                                   scratch.standing, ny, type, malus, closed)) {
                continue;
            }
            const int ni = get_node(current.x + dx, ny, current.z + dz, type, malus);
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
                // `PathFinder.getBestH` keeps Target.bestNode in the raw
                // distance domain, while the A* f-cost uses that value times
                // FUDGING. Comparing a fudged neighbour h to the unfudged
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
    return result;
}

[[nodiscard]] bool snapshot_in_bounds(const PathfinderStateSnapshot& snapshot,
                                      int x, int y, int z) noexcept {
    return x >= snapshot.min_x && y >= snapshot.min_y && z >= snapshot.min_z
        && x < snapshot.min_x + snapshot.size_x
        && y < snapshot.min_y + snapshot.size_y
        && z < snapshot.min_z + snapshot.size_z;
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
    if (!snapshot.cells || !snapshot.raw_path_types || !snapshot.floor_heights
            || snapshot.descriptor_count <= 0 || snapshot.size_x <= 0
            || snapshot.size_y <= 0 || snapshot.size_z <= 0) return false;
    const int count = volume(in);
    if (count <= 0) return false;
    scratch.materialized_path_types.resize(static_cast<std::size_t>(count));
    scratch.materialized_floor_levels.resize(static_cast<std::size_t>(count));
    int index = 0;
    for (int y = in.region_min_y; y < in.region_min_y + in.region_size_y; ++y) {
        for (int z = in.region_min_z; z < in.region_min_z + in.region_size_z; ++z) {
            for (int x = in.region_min_x; x < in.region_min_x + in.region_size_x; ++x, ++index) {
                std::int8_t type = kBlocked;
                float floor_height = 0.0F;
                if (!mob_path_type(in, snapshot, x, y, z, type)) return false;
                std::int8_t raw_type = kBlocked;
                if (!snapshot_raw_type(snapshot, x, y, z, raw_type)) return false;
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

#if 0 // Replaced by the contiguous materialization path below; retained temporarily for comparison.
[[nodiscard]] bool mirror_raw_type(const PathfinderStateMirror& mirror, int world_key,
                                   int x, int y, int z, std::int8_t& type) noexcept {
    if (mirror.world_key != world_key) return false;
    const auto section = mirror.sections.find(mirror_section_key(x, y, z));
    if (section == mirror.sections.end()) return false;
    const int index = mirror_section_index(x, y, z);
    if (section->second.valid[index] == 0) return false;
    type = section->second.raw_path_types[index];
    return type >= kBlocked && type < kPathTypeCount;
}

[[nodiscard]] bool mirror_floor_height(const PathfinderStateMirror& mirror, int world_key,
                                       int x, int y, int z, float& height) noexcept {
    if (mirror.world_key != world_key) return false;
    const auto section = mirror.sections.find(mirror_section_key(x, y, z));
    if (section == mirror.sections.end()) return false;
    const int index = mirror_section_index(x, y, z);
    if (section->second.valid[index] == 0) return false;
    height = section->second.floor_heights[index];
    return std::isfinite(height) && height >= 0.0F && height <= 1.0F;
}

[[nodiscard]] bool static_path_type_from_mirror(PathfinderStateMirror& mirror, int world_key,
                                                 int level_min_y, int x, int y, int z,
                                                 std::int8_t& type) noexcept {
    if (mirror.world_key != world_key) return false;
    const auto section_it = mirror.sections.find(mirror_section_key(x, y, z));
    if (section_it == mirror.sections.end()) return false;
    const int index = mirror_section_index(x, y, z);
    PathfinderStateMirrorSection& section = section_it->second;
    if (section.valid[index] == 0) return false;
    if (section.static_valid[index] != 0) {
        type = section.static_path_types[index];
        return true;
    }

    type = section.raw_path_types[index];
    if (type < kBlocked || type >= kPathTypeCount) return false;
    if (type != kOpen || y < level_min_y + 1) {
        section.static_path_types[index] = type;
        section.static_valid[index] = 1;
        return true;
    }

    std::int8_t below = kBlocked;
    if (!mirror_raw_type(mirror, world_key, x, y - 1, z, below)) return false;
    switch (below) {
        case kOpen:
        case kWater:
        case kLava:
        case kWalkable:
            break;
        case kDamageFire: type = kDamageFire; break;
        case kDamageOther: type = kDamageOther; break;
        case kStickyHoney: type = kStickyHoney; break;
        case kPowderSnow: type = kDangerPowderSnow; break;
        case kDamageCautious: type = kDamageCautious; break;
        case kTrapdoor: type = kDangerTrapdoor; break;
        default:
            type = kWalkable;
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dz = -1; dz <= 1; ++dz) {
                        if (dx == 0 && dz == 0) continue;
                        std::int8_t neighbour = kBlocked;
                        if (!mirror_raw_type(mirror, world_key, x + dx, y + dy, z + dz, neighbour)) return false;
                        if (neighbour == kDamageOther) { type = kDangerOther; goto finish; }
                        if (neighbour == kDamageFire || neighbour == kLava) { type = kDangerFire; goto finish; }
                        if (neighbour == kWater) { type = kWaterBorder; goto finish; }
                        if (neighbour == kDamageCautious) { type = kDamageCautious; goto finish; }
                    }
                }
            }
            break;
    }
finish:
    section.static_path_types[index] = type;
    section.static_valid[index] = 1;
    return true;
}

[[nodiscard]] bool materialize_state_mirror(const PathfinderInputs& in,
                                             PathfinderStateMirror& mirror, int world_key,
                                             PathfinderScratch& scratch) noexcept {
    // Larger entities combine every occupied cell and retain the exact generic
    // descriptor path below. Common 1x1 walkers can reuse the static result.
    if (in.entity_width != 1 || in.entity_height != 1) return false;
    const int count = volume(in);
    if (count <= 0) return false;
    scratch.materialized_path_types.resize(static_cast<std::size_t>(count));
    scratch.materialized_floor_levels.resize(static_cast<std::size_t>(count));
    int index = 0;
    for (int y = in.region_min_y; y < in.region_min_y + in.region_size_y; ++y) {
        for (int z = in.region_min_z; z < in.region_min_z + in.region_size_z; ++z) {
            for (int x = in.region_min_x; x < in.region_min_x + in.region_size_x; ++x, ++index) {
                std::int8_t type = kBlocked;
                if (!static_path_type_from_mirror(mirror, world_key, in.level_min_y, x, y, z, type)) return false;
                if (type == kDoorWoodClosed && in.can_open_doors && in.can_pass_doors) {
                    type = kWalkableDoor;
                } else if (type == kDoorOpen && !in.can_pass_doors) {
                    type = kBlocked;
                } else if (type == kRail) {
                    std::int8_t at_mob = kBlocked;
                    std::int8_t below_mob = kBlocked;
                    if (!static_path_type_from_mirror(mirror, world_key, in.level_min_y,
                                                       in.mob_block_x, in.mob_block_y, in.mob_block_z, at_mob)
                            || !static_path_type_from_mirror(mirror, world_key, in.level_min_y,
                                                              in.mob_block_x, in.mob_block_y - 1, in.mob_block_z, below_mob)) {
                        return false;
                    }
                    if (at_mob != kRail && below_mob != kRail) type = kUnpassableRail;
                }
                float floor_height = 0.0F;
                if (!mirror_floor_height(mirror, world_key, x, y - 1, z, floor_height)) return false;
                scratch.materialized_path_types[static_cast<std::size_t>(index)] = type;
                scratch.materialized_floor_levels[static_cast<std::size_t>(index)] =
                    static_cast<float>(y - 1) + floor_height;
            }
        }
    }
    return true;
}

#endif

struct LazyPathGrid {
    const PathfinderInputs& in;
    const PathfinderStateMirror& mirror;
    int world_key;
    PathfinderScratch& scratch;
    int state_min_x;
    int state_min_y;
    int state_min_z;
    int state_max_x;
    int state_max_y;
    int state_max_z;
    int section_min_x;
    int section_min_y;
    int section_min_z;
    int section_count_x;
    int section_count_y;
    int section_count_z;
    std::vector<const PathfinderStateMirrorSection*>& sections;
    bool failed = false;

    LazyPathGrid(const PathfinderInputs& inputs, const PathfinderStateMirror& state_mirror,
                 int key, PathfinderScratch& state_scratch) noexcept
        : in(inputs), mirror(state_mirror), world_key(key), scratch(state_scratch),
          state_min_x(inputs.region_min_x - 1), state_min_y(inputs.region_min_y - 1), state_min_z(inputs.region_min_z - 1),
          state_max_x(inputs.region_min_x + inputs.region_size_x + inputs.entity_width - 1),
          state_max_y(inputs.region_min_y + inputs.region_size_y + inputs.entity_height - 1),
          state_max_z(inputs.region_min_z + inputs.region_size_z + inputs.entity_width - 1),
          section_min_x(state_min_x >> 4), section_min_y(state_min_y >> 4), section_min_z(state_min_z >> 4),
          section_count_x((state_max_x >> 4) - section_min_x + 1),
          section_count_y((state_max_y >> 4) - section_min_y + 1),
          section_count_z((state_max_z >> 4) - section_min_z + 1),
          sections(state_scratch.lazy_sections) {}

    [[nodiscard]] std::size_t section_slot(int sx, int sy, int sz) const noexcept {
        return (static_cast<std::size_t>(sy) * section_count_z + sz) * section_count_x + sx;
    }


    [[nodiscard]] bool initialise() noexcept {
        if (mirror.world_key != world_key || section_count_x <= 0 || section_count_y <= 0 || section_count_z <= 0) return false;
        const std::size_t count = static_cast<std::size_t>(section_count_x) * section_count_y * section_count_z;
        sections.resize(count);
        for (int sy = 0; sy < section_count_y; ++sy) for (int sz = 0; sz < section_count_z; ++sz) for (int sx = 0; sx < section_count_x; ++sx) {
            const std::size_t slot = section_slot(sx, sy, sz);
            const auto it = mirror.sections.find(mirror_section_key((section_min_x + sx) << 4,
                                                                     (section_min_y + sy) << 4,
                                                                     (section_min_z + sz) << 4));
            if (it == mirror.sections.end()) return false;
            sections[slot] = &it->second;
        }
        const int cells = volume(in);
        if (cells <= 0) return false;
        scratch.lazy_path_types.resize(static_cast<std::size_t>(cells));
        scratch.lazy_floor_levels.resize(static_cast<std::size_t>(cells));
        if (scratch.lazy_stamp.size() < static_cast<std::size_t>(cells)) scratch.lazy_stamp.resize(static_cast<std::size_t>(cells), 0);
        if (++scratch.current_lazy_stamp == 0) {
            std::fill(scratch.lazy_stamp.begin(), scratch.lazy_stamp.end(), 0);
            scratch.current_lazy_stamp = 1;
        }
        return true;
    }

    [[nodiscard]] bool raw(int x, int y, int z, std::int8_t& type, float* floor = nullptr) noexcept {
        if (x < state_min_x || x > state_max_x || y < state_min_y || y > state_max_y || z < state_min_z || z > state_max_z) return false;
        const int sx = (x >> 4) - section_min_x, sy = (y >> 4) - section_min_y, sz = (z >> 4) - section_min_z;
        const auto* section = sections[section_slot(sx, sy, sz)];
        const int local = mirror_section_index(x, y, z);
        if (section->valid[local] == 0) return false;
        type = section->raw_path_types[local];
        if (type < kBlocked || type >= kPathTypeCount) return false;
        if (floor) *floor = section->floor_heights[local];
        return !floor || (std::isfinite(*floor) && *floor >= 0.0F && *floor <= 1.0F);
    }

    [[nodiscard]] bool static_type(int x, int y, int z, std::int8_t& type) noexcept {
        if (!raw(x, y, z, type)) return false;
        if (in.is_amphibious && type == kWater) {
            constexpr int offsets[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
            for (const auto& offset : offsets) {
                std::int8_t neighbour = kBlocked;
                if (!raw(x + offset[0], y + offset[1], z + offset[2], neighbour)) return false;
                if (neighbour == kBlocked) {
                    type = kWaterBorder;
                    break;
                }
            }
            return true;
        }
        if (type != kOpen || y < in.level_min_y + 1) return true;
        std::int8_t below = kBlocked;
        if (!raw(x, y - 1, z, below)) return false;
        switch (below) {
            case kOpen: case kWater: case kLava: case kWalkable: return true;
            case kDamageFire: type = kDamageFire; return true;
            case kDamageOther: type = kDamageOther; return true;
            case kStickyHoney: type = kStickyHoney; return true;
            case kPowderSnow: type = kDangerPowderSnow; return true;
            case kDamageCautious: type = kDamageCautious; return true;
            case kTrapdoor: type = kDangerTrapdoor; return true;
            default: type = kWalkable; break;
        }
        for (int dx = -1; dx <= 1; ++dx) for (int dy = -1; dy <= 1; ++dy) for (int dz = -1; dz <= 1; ++dz) {
            if (dx == 0 && dz == 0) continue;
            std::int8_t neighbour = kBlocked;
            if (!raw(x + dx, y + dy, z + dz, neighbour)) return false;
            if (neighbour == kDamageOther) { type = kDangerOther; return true; }
            if (neighbour == kDamageFire || neighbour == kLava) { type = kDangerFire; return true; }
            if (neighbour == kWater) { type = kWaterBorder; return true; }
            if (neighbour == kDamageCautious) { type = kDamageCautious; return true; }
        }
        return true;
    }

    [[nodiscard]] bool final_type(int x, int y, int z, std::int8_t& result) noexcept {
        if (!in_region(in, x, y, z)) return false;
        const int index = grid_index(in, x, y, z);
        if (scratch.lazy_stamp[static_cast<std::size_t>(index)] == scratch.current_lazy_stamp) {
            result = scratch.lazy_path_types[static_cast<std::size_t>(index)]; return true;
        }
        bool present[kPathTypeCount]{};
        for (int dx = 0; dx < in.entity_width; ++dx) for (int dy = 0; dy < in.entity_height; ++dy) for (int dz = 0; dz < in.entity_width; ++dz) {
            std::int8_t type = kBlocked;
            if (!static_type(x + dx, y + dy, z + dz, type)) return false;
            if (type == kDoorWoodClosed && in.can_open_doors && in.can_pass_doors) type = kWalkableDoor;
            else if (type == kDoorOpen && !in.can_pass_doors) type = kBlocked;
            else if (type == kRail) {
                std::int8_t at_mob = kBlocked, below_mob = kBlocked;
                if (!static_type(in.mob_block_x, in.mob_block_y, in.mob_block_z, at_mob)
                        || !static_type(in.mob_block_x, in.mob_block_y - 1, in.mob_block_z, below_mob)) return false;
                if (at_mob != kRail && below_mob != kRail) type = kUnpassableRail;
            }
            present[static_cast<int>(type)] = true;
        }
        if (present[kFence]) result = kFence;
        else if (present[kUnpassableRail]) result = kUnpassableRail;
        else {
        result = kBlocked;
        for (int type = 0; type < kPathTypeCount; ++type) if (present[type]) {
            if (malus_for(in, static_cast<std::int8_t>(type)) < 0.0F) { result = static_cast<std::int8_t>(type); break; }
            if (malus_for(in, static_cast<std::int8_t>(type)) >= malus_for(in, result)) result = static_cast<std::int8_t>(type);
        }
        }
        if (in.entity_width <= 1 && result != kOpen && malus_for(in, result) == 0.0F) {
            std::int8_t origin = kBlocked;
            if (!static_type(x, y, z, origin)) return false;
            if (origin == kOpen) result = kOpen;
        }
        scratch.lazy_path_types[static_cast<std::size_t>(index)] = result;
        scratch.lazy_stamp[static_cast<std::size_t>(index)] = scratch.current_lazy_stamp;
        return true;
    }

};

[[nodiscard]] std::int8_t lazy_path_type_at(void* context, int x, int y, int z) noexcept {
    auto& self = *static_cast<LazyPathGrid*>(context);
    std::int8_t type = kBlocked;
    if (!self.final_type(x, y, z, type)) self.failed = true;
    return type;
}

    [[nodiscard]] float lazy_floor_level_at(void* context, int x, int y, int z) noexcept {
        auto& self = *static_cast<LazyPathGrid*>(context);
        std::int8_t type = kBlocked;
        if (!self.raw(x, y, z, type)) {
            self.failed = true;
            return static_cast<float>(y);
        }
        if ((self.in.can_float || self.in.is_amphibious) && type == kWater) {
            return static_cast<float>(y) + 0.5F;
        }
        float floor = 0.0F;
        if (!self.raw(x, y - 1, z, type, &floor)) {
        self.failed = true;
        return static_cast<float>(y);
    }
    return static_cast<float>(y - 1) + floor;
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
    for (int y = snapshot.min_y; y < snapshot.min_y + snapshot.size_y; ++y) {
        for (int z = snapshot.min_z; z < snapshot.min_z + snapshot.size_z; ++z) {
            for (int x = snapshot.min_x; x < snapshot.min_x + snapshot.size_x; ++x, ++index) {
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
    }
}

std::uint64_t pathfinder_mirror_section_key(int x, int y, int z) noexcept {
    return mirror_section_key(x, y, z);
}

bool state_mirror_covers(const PathfinderStateMirror& mirror, int world_key,
                         int min_x, int min_y, int min_z,
                         int size_x, int size_y, int size_z) noexcept {
    if (mirror.world_key != world_key || size_x <= 0 || size_y <= 0 || size_z <= 0) return false;
    const int max_x = min_x + size_x - 1;
    const int max_y = min_y + size_y - 1;
    const int max_z = min_z + size_z - 1;
    for (int sy = (min_y >> 4); sy <= (max_y >> 4); ++sy) {
        for (int sz = (min_z >> 4); sz <= (max_z >> 4); ++sz) {
            for (int sx = (min_x >> 4); sx <= (max_x >> 4); ++sx) {
                const auto it = mirror.sections.find(mirror_section_key(sx << 4, sy << 4, sz << 4));
                if (it == mirror.sections.end()) return false;
                const PathfinderStateMirrorSection& section = it->second;
                if (section.valid_count == 4096) continue;
                // Partially populated: check only the cells this query needs.
                const int lo_x = std::max(min_x, sx << 4);
                const int hi_x = std::min(max_x, (sx << 4) + 15);
                const int lo_y = std::max(min_y, sy << 4);
                const int hi_y = std::min(max_y, (sy << 4) + 15);
                const int lo_z = std::max(min_z, sz << 4);
                const int hi_z = std::min(max_z, (sz << 4) + 15);
                for (int y = lo_y; y <= hi_y; ++y) {
                    for (int z = lo_z; z <= hi_z; ++z) {
                        for (int x = lo_x; x <= hi_x; ++x) {
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
    if (mirror.world_key != world_key || size_x <= 0 || size_y <= 0 || size_z <= 0) return false;
    const long long count = static_cast<long long>(size_x) * size_y * size_z;
    if (count <= 0 || count > std::numeric_limits<int>::max()) return false;
    scratch.mirror_cells.resize(static_cast<std::size_t>(count));
    scratch.mirror_raw_path_types.resize(static_cast<std::size_t>(count));
    scratch.mirror_floor_heights.resize(static_cast<std::size_t>(count));
    int index = 0;
    std::uint64_t cached_key = 0;
    const PathfinderStateMirrorSection* cached_section = nullptr;
    for (int y = min_y; y < min_y + size_y; ++y) {
        for (int z = min_z; z < min_z + size_z; ++z) {
            for (int x = min_x; x < min_x + size_x; ++x, ++index) {
                const std::uint64_t key = mirror_section_key(x, y, z);
                if (cached_section == nullptr || key != cached_key) {
                    const auto section = mirror.sections.find(key);
                    if (section == mirror.sections.end()) return false;
                    cached_key = key;
                    cached_section = &section->second;
                }
                const int local = mirror_section_index(x, y, z);
                if (cached_section->valid[local] == 0) return false;
                scratch.mirror_cells[index] = index;
                scratch.mirror_raw_path_types[index] = cached_section->raw_path_types[local];
                scratch.mirror_floor_heights[index] = cached_section->floor_heights[local];
            }
        }
    }
    snapshot.cells = scratch.mirror_cells.data();
    snapshot.raw_path_types = scratch.mirror_raw_path_types.data();
    snapshot.floor_heights = scratch.mirror_floor_heights.data();
    snapshot.descriptor_count = static_cast<int>(count);
    snapshot.min_x = min_x;
    snapshot.min_y = min_y;
    snapshot.min_z = min_z;
    snapshot.size_x = size_x;
    snapshot.size_y = size_y;
    snapshot.size_z = size_z;
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
    LazyPathGrid lazy{inputs, mirror, world_key, scratch};
    if (lazy.initialise()) {
        PathfinderInputs lazy_inputs = inputs;
        lazy_inputs.path_types = nullptr;
        lazy_inputs.floor_levels = nullptr;
        lazy_inputs.lazy_context = &lazy;
        const bool ok = find_path_into(lazy_inputs, output, scratch);
        if (!lazy.failed) return ok;
    }
    const long long state_min_x = static_cast<long long>(inputs.region_min_x) - 1;
    const long long state_min_y = static_cast<long long>(inputs.region_min_y) - 1;
    const long long state_min_z = static_cast<long long>(inputs.region_min_z) - 1;
    const long long state_size_x = static_cast<long long>(inputs.region_size_x) + inputs.entity_width + 1;
    const long long state_size_y = static_cast<long long>(inputs.region_size_y) + inputs.entity_height + 1;
    const long long state_size_z = static_cast<long long>(inputs.region_size_z) + inputs.entity_width + 1;
    if (state_min_x < std::numeric_limits<int>::min() || state_min_x > std::numeric_limits<int>::max()
            || state_min_y < std::numeric_limits<int>::min() || state_min_y > std::numeric_limits<int>::max()
            || state_min_z < std::numeric_limits<int>::min() || state_min_z > std::numeric_limits<int>::max()
            || state_size_x > std::numeric_limits<int>::max() || state_size_y > std::numeric_limits<int>::max()
            || state_size_z > std::numeric_limits<int>::max()) return false;
    PathfinderStateSnapshot snapshot{};
    if (!load_pathfinder_state_snapshot(mirror, world_key,
                                        static_cast<int>(state_min_x), static_cast<int>(state_min_y), static_cast<int>(state_min_z),
                                        static_cast<int>(state_size_x), static_cast<int>(state_size_y), static_cast<int>(state_size_z),
                                        scratch, snapshot)) return false;
    return find_path_from_state_snapshot_into(inputs, snapshot, output, scratch);
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
