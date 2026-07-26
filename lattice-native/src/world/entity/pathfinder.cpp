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

constexpr std::int8_t kBlocked = 0;
constexpr std::int8_t kOpen = 1;
constexpr std::int8_t kWalkableDoor = 3;
constexpr std::size_t kMaskWordBits = 64;

constexpr std::int8_t kClosedFlag = 1;
constexpr std::int8_t kOpenFlag = 2;

[[nodiscard]] bool valid_inputs(const PathfinderInputs& in) noexcept {
    if (!in.path_types || !in.pathfinding_malus) return false;
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
    return in.path_types[grid_index(in, x, y, z)];
}

[[nodiscard]] float malus_for(const PathfinderInputs& in, std::int8_t type) noexcept {
    const int index = static_cast<int>(type);
    if (index < 0 || index >= in.pathfinding_malus_count) return -1.0F;
    return in.pathfinding_malus[index];
}

[[nodiscard]] bool passable_at(const PathfinderInputs& in,
                               const std::vector<std::uint64_t>& passable,
                               int x, int y, int z) noexcept {
    if (!in_region(in, x, y, z)) return false;
    const std::size_t index = static_cast<std::size_t>(grid_index(in, x, y, z));
    return ((passable[index >> 6] >> (index & 63)) & 1ULL) != 0ULL;
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

[[nodiscard]] bool resolve_standing_node(const PathfinderInputs& in, int x, int y, int z,
                                         const std::vector<std::uint64_t>& passable,
                                         const std::vector<std::uint64_t>& standing,
                                         int& out_y, std::int8_t& out_type,
                                         float& out_malus) noexcept {
    if (standing_node(in, x, y, z, standing, out_type, out_malus)) {
        out_y = y;
        return true;
    }

    if (passable_at(in, passable, x, y, z)) {
        const int max_fall = std::max(0, in.max_fall_distance);
        for (int delta = 1; delta <= max_fall; ++delta) {
            if (standing_node(in, x, y - delta, z, standing, out_type, out_malus)) {
                out_y = y - delta;
                return true;
            }
            if (!passable_at(in, passable, x, y - delta, z)) break;
        }
    }

    const int max_up = std::max(0, static_cast<int>(std::floor(in.max_up_step + 1.0e-4F)));
    for (int delta = 1; delta <= max_up; ++delta) {
        if (standing_node(in, x, y + delta, z, standing, out_type, out_malus)) {
            out_y = y + delta;
            return true;
        }
    }

    return false;
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
    static constexpr int kArity = 4;
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
            const int parent = (index - 1) / kArity;
            if (!((*nodes)[(*entries)[index]].f < (*nodes)[(*entries)[parent]].f)) break;
            swap_entries(index, parent);
            index = parent;
        }
    }

    void down(int index) noexcept {
        while (true) {
            const int first_child = index * kArity + 1;
            if (first_child >= static_cast<int>(entries->size())) break;
            int best = first_child;
            const int child_limit = std::min(first_child + kArity,
                                             static_cast<int>(entries->size()));
            for (int child = first_child + 1; child < child_limit; ++child) {
                if ((*nodes)[(*entries)[child]].f < (*nodes)[(*entries)[best]].f) {
                    best = child;
                }
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
        up(index);
        down((*heap_index)[node_index]);
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

    mask_clear(scratch.passable, static_cast<std::size_t>(grid_volume));
    mask_clear(scratch.standing, static_cast<std::size_t>(grid_volume));
    build_pathfinder_masks(in.path_types, static_cast<std::size_t>(grid_volume),
                           in.pathfinding_malus, in.pathfinding_malus_count,
                           PathfinderMasks{scratch.passable.data(), scratch.standing.data()});

    int start_y = in.start_y;
    std::int8_t start_type = kBlocked;
    float start_malus = -1.0F;
    if (!resolve_standing_node(in, in.start_x, in.start_y, in.start_z, scratch.passable, scratch.standing,
                               start_y, start_type, start_malus)) {
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

    constexpr int dir_x[4] = {1, 0, -1, 0};
    constexpr int dir_z[4] = {0, 1, 0, -1};

    while (!heap.empty() && visited++ < in.config.max_visited_nodes) {
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

        int cardinal[4] = {-1, -1, -1, -1};
        for (int d = 0; d < 4; ++d) {
            int ny = current.y;
            std::int8_t type = kBlocked;
            float malus = -1.0F;
            if (!resolve_standing_node(in, current.x + dir_x[d], current.y,
                                       current.z + dir_z[d], scratch.passable, scratch.standing,
                                       ny, type, malus)) {
                continue;
            }
            const int ni = get_node(current.x + dir_x[d], ny, current.z + dir_z[d], type, malus);
            if (ni < 0) continue;
            cardinal[d] = ni;
        }

        int neighbors[8];
        int neighbor_count = 0;
        for (int d = 0; d < 4; ++d) {
            if (cardinal[d] >= 0) neighbors[neighbor_count++] = cardinal[d];
        }
        for (int d = 0; d < 4; ++d) {
            const int a = cardinal[d];
            const int b = cardinal[(d + 1) & 3];
            if (a < 0 || b < 0) continue;
            if (scratch.nodes[a].y > current.y || scratch.nodes[b].y > current.y) continue;
            if (scratch.nodes[a].type == kWalkableDoor || scratch.nodes[b].type == kWalkableDoor) continue;
            int ny = current.y;
            std::int8_t type = kBlocked;
            float malus = -1.0F;
            if (!resolve_standing_node(in, current.x + dir_x[d] + dir_x[(d + 1) & 3],
                                       current.y, current.z + dir_z[d] + dir_z[(d + 1) & 3],
                                       scratch.passable, scratch.standing, ny, type, malus)) {
                continue;
            }
            const int ni = get_node(current.x + dir_x[d] + dir_x[(d + 1) & 3],
                                    ny, current.z + dir_z[d] + dir_z[(d + 1) & 3],
                                    type, malus);
            if (ni >= 0 && scratch.nodes[ni].type != kWalkableDoor) neighbors[neighbor_count++] = ni;
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
                neighbor.h = best_h(in, neighbor, &best_target) * in.config.fudge;
                neighbor.f = neighbor.g + neighbor.h;
                if (neighbor.h < best_node_h) {
                    best_node_h = neighbor.h;
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

} // namespace lattice::world::entity
