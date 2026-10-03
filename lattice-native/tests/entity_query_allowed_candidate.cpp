// 大 allowed 集合的自有副本排序候选，预处理成本计入每次查询。
#include "world/entity/entity_query.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace entity_query_allowed_candidate {
using namespace lattice::world::entity;
namespace {

struct Match {
    int id;
    double distance_sq;
    std::size_t ordinal;
};

[[nodiscard]] bool intersects(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    const double* box = inputs.entity_aabbs + index * kEntityAabbStride;
    return inputs.query_min_x <= box[3] && inputs.query_max_x >= box[0] &&
           inputs.query_min_y <= box[4] && inputs.query_max_y >= box[1] &&
           inputs.query_min_z <= box[5] && inputs.query_max_z >= box[2];
}

[[nodiscard]] bool type_allowed(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    if (inputs.allowed_type_count == 0) return true;
    return std::binary_search(inputs.allowed_type_ids, inputs.allowed_type_ids + inputs.allowed_type_count,
                              inputs.entity_type_ids[index]);
}

[[nodiscard]] bool predicate_allowed(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    switch (inputs.predicate_kind) {
        case EntityPredicateKind::None:
            return true;
        case EntityPredicateKind::IsAlive:
            return inputs.entity_alive[index] != 0;
        case EntityPredicateKind::IsAliveNotSelf:
            return inputs.entity_alive[index] != 0 && inputs.entity_ids[index] != inputs.excluded_entity_id;
        case EntityPredicateKind::IsAliveNotSpectator:
            return inputs.entity_alive[index] != 0 && inputs.entity_spectator[index] == 0;
        case EntityPredicateKind::IsAliveNotSelfNotSpectator:
            return inputs.entity_alive[index] != 0 &&
                   inputs.entity_ids[index] != inputs.excluded_entity_id &&
                   inputs.entity_spectator[index] == 0;
    }
    return false;
}

[[nodiscard]] double distance_sq(const EntityQueryInputs& inputs, std::size_t index) noexcept {
    const double* pos = inputs.entity_positions + index * kEntityPositionStride;
    const double dx = pos[0] - inputs.ref_x;
    const double dy = pos[1] - inputs.ref_y;
    const double dz = pos[2] - inputs.ref_z;
    return dx * dx + dy * dy + dz * dz;
}

} // namespace

[[nodiscard]] bool nearer(const Match& a, const Match& b) noexcept {
    return a.distance_sq < b.distance_sq ||
           (a.distance_sq == b.distance_sq && a.ordinal > b.ordinal);
}

[[nodiscard]] bool farther(const Match& a, const Match& b) noexcept {
    return a.distance_sq > b.distance_sq ||
           (a.distance_sq == b.distance_sq && a.ordinal < b.ordinal);
}

namespace {
// 三个调用方缓冲区同步交换，不为 top-k 分配候选对象。
struct MatchHeap {
    int* ids;
    double* distances;
    double* ordinals;
    Match get(std::size_t i) const noexcept {
        return {ids[i], distances[i], static_cast<std::size_t>(ordinals[i])};
    }
    void put(std::size_t i, const Match& value) noexcept {
        ids[i] = value.id; distances[i] = value.distance_sq;
        ordinals[i] = static_cast<double>(value.ordinal);
    }
    void swap(std::size_t a, std::size_t b) noexcept {
        const Match value = get(a); put(a, get(b)); put(b, value);
    }
    void sift(std::size_t root, std::size_t count) noexcept {
        while (root < count / 2) {
            std::size_t child = root * 2 + 1;
            if (child + 1 < count && nearer(get(child), get(child + 1))) ++child;
            if (!nearer(get(root), get(child))) break;
            swap(root, child);
            root = child;
        }
    }
    void build(std::size_t count) noexcept {
        for (std::size_t i = count / 2; i > 0; --i) sift(i - 1, count);
    }
    void sort(std::size_t count) noexcept {
        for (std::size_t n = count; n > 1; --n) {
            swap(0, n - 1); sift(0, n - 1);
        }
    }
};
} // namespace

template<bool UseHeap>
static std::size_t query_impl(const EntityQueryInputs& inputs,
                           int* matched_entity_ids,
                           double* distances,
                           std::size_t output_capacity) noexcept {
    if (!matched_entity_ids || output_capacity == 0 || inputs.entity_count == 0) return 0;
    if (!inputs.entity_ids || !inputs.entity_type_ids || !inputs.entity_positions ||
        !inputs.entity_aabbs || !inputs.entity_alive || !inputs.entity_spectator) {
        return 0;
    }
    if (inputs.allowed_type_count > 0 && !inputs.allowed_type_ids) return 0;

    if (!inputs.sort_by_distance) {
        std::size_t out = 0;
        for (std::size_t i = 0; i < inputs.entity_count && out < output_capacity; ++i) {
            if (!type_allowed(inputs, i) || !predicate_allowed(inputs, i) || !intersects(inputs, i)) continue;
            matched_entity_ids[out] = inputs.entity_ids[i];
            if (distances) distances[out] = 0.0;
            ++out;
        }
        return out;
    }

    if (!distances) return 0;
    const std::size_t limit = inputs.max_results == 0
        ? output_capacity
        : std::min(inputs.max_results, output_capacity);
    if (limit == 0) return 0;

    double* ordinals = distances + output_capacity;
    std::size_t count = 0;
    bool initial_nan = false;
    bool heap_ready = false;
    MatchHeap heap{matched_entity_ids, distances, ordinals};
    for (std::size_t i = 0; i < inputs.entity_count; ++i) {
        if (!type_allowed(inputs, i) || !predicate_allowed(inputs, i) || !intersects(inputs, i)) continue;
        const Match match{inputs.entity_ids[i], distance_sq(inputs, i), i};
        if (count < limit) {
            const auto out = count++;
            matched_entity_ids[out] = match.id;
            distances[out] = match.distance_sq;
            ordinals[out] = static_cast<double>(match.ordinal);
            if constexpr (UseHeap) initial_nan = initial_nan || std::isnan(match.distance_sq);
            continue;
        }
        if constexpr (UseHeap) if (!initial_nan) {
            if (!heap_ready) { heap.build(count); heap_ready = true; }
            if (nearer(match, heap.get(0))) { heap.put(0, match); heap.sift(0, count); }
            continue;
        }
        // NaN 不形成全序：保留旧槽位、replacement 和插入排序屏障。
        std::size_t farthest = 0;
        for (std::size_t j = 1; j < limit; ++j) {
            const Match current{matched_entity_ids[j], distances[j], static_cast<std::size_t>(ordinals[j])};
            const Match worst{matched_entity_ids[farthest], distances[farthest], static_cast<std::size_t>(ordinals[farthest])};
            if (farther(current, worst)) farthest = j;
        }
        const Match worst{matched_entity_ids[farthest], distances[farthest], static_cast<std::size_t>(ordinals[farthest])};
        if (nearer(match, worst)) {
            matched_entity_ids[farthest] = match.id;
            distances[farthest] = match.distance_sq;
            ordinals[farthest] = static_cast<double>(match.ordinal);
        }
    }

    if constexpr (UseHeap) if (!initial_nan) {
        if (!heap_ready) {
            bool sorted = true;
            for (std::size_t i = 1; i < count; ++i) {
                if (nearer(heap.get(i), heap.get(i - 1))) { sorted = false; break; }
            }
            if (sorted) return count;
            heap.build(count);
        }
        heap.sort(count);
        return count;
    }
    for (std::size_t i = 1; i < count; ++i) {
        Match value{matched_entity_ids[i], distances[i], static_cast<std::size_t>(ordinals[i])};
        std::size_t j = i;
        while (j > 0) {
            const Match previous{matched_entity_ids[j - 1], distances[j - 1], static_cast<std::size_t>(ordinals[j - 1])};
            if (!nearer(value, previous)) break;
            matched_entity_ids[j] = previous.id;
            distances[j] = previous.distance_sq;
            ordinals[j] = static_cast<double>(previous.ordinal);
            --j;
        }
        matched_entity_ids[j] = value.id;
        distances[j] = value.distance_sq;
        ordinals[j] = static_cast<double>(value.ordinal);
    }
    return count;
}

static std::size_t query_sorted(const EntityQueryInputs& inputs, int* ids, double* distances,
                           std::size_t capacity) noexcept {
    const auto limit = inputs.max_results == 0 ? capacity : std::min(inputs.max_results, capacity);
    // 小集合保留测量更稳的线性选择；排序结果仍使用同一 ordinal 契约。
    if (limit <= 16) return query_impl<false>(inputs, ids, distances, capacity);
    return query_impl<true>(inputs, ids, distances, capacity);
}

std::size_t query_entities(const EntityQueryInputs& input, int* ids, double* distances, std::size_t capacity) noexcept {
    if (!input.allowed_type_count) return query_sorted(input, ids, distances, capacity);
    if (!input.allowed_type_ids) return 0;
    std::vector<int> allowed(input.allowed_type_ids, input.allowed_type_ids + input.allowed_type_count);
    std::sort(allowed.begin(), allowed.end());
    auto copy = input; copy.allowed_type_ids = allowed.data();
    return query_sorted(copy, ids, distances, capacity);
}
} // namespace lattice::world::entity
