/**
 * @file pending_update_queue.hpp
 * @brief Per-level work queue used by `LevelPropagator`.
 *
 * Mirrors `net.minecraft.world.chunk.light.PendingUpdateQueue`
 * (intermediary `class_8257`).
 *
 * Layout: an array of N `Int64Set`s, one per discrete level in
 * `[0, levelCount)`. `enqueue(id, level)` adds `id` to bucket `level`;
 * `dequeue()` removes any one entry from the lowest non-empty bucket,
 * advancing `min_pending_level_` lazily as buckets drain. `remove(id, level, _)`
 * extracts a specific id from a specific bucket — used by
 * `LevelPropagator` when it changes its mind about an entry's level.
 *
 * Vanilla uses `LongLinkedOpenHashSet` per bucket, which preserves
 * insertion order. We use `Int64Set` (open-addressing, unordered)
 * because BFS correctness does not depend on dequeue order — the
 * propagator converges to the same fixed point regardless of how ties
 * are broken at any given level. (See the `LevelPropagator` reference
 * docs: levels are processed in strict ascending order; within a single
 * level, individual ids commute.)
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <new>      // placement new, std::nothrow

#include "world/light/int64_set.hpp"

namespace lattice::world::light {

class PendingUpdateQueue {
public:
    static constexpr std::int64_t kEmpty = Int64Set::kEmpty;

    PendingUpdateQueue() noexcept = default;

    /// `<init>(levelCount, expectedLevelSize)`. Must be called before use.
    void initialize(int level_count, std::size_t expected_level_size) noexcept {
        destroy();
        if (level_count <= 0) return;
        level_count_ = level_count;
        min_pending_level_ = level_count; // queue starts empty
        buckets_ = static_cast<Int64Set*>(
            ::operator new(sizeof(Int64Set) * level_count, std::nothrow));
        if (!buckets_) {
            level_count_ = 0;
            return;
        }
        for (int i = 0; i < level_count; ++i) {
            new (buckets_ + i) Int64Set(expected_level_size);
        }
    }

    ~PendingUpdateQueue() noexcept { destroy(); }

    PendingUpdateQueue(const PendingUpdateQueue&)            = delete;
    PendingUpdateQueue& operator=(const PendingUpdateQueue&) = delete;

    PendingUpdateQueue(PendingUpdateQueue&& o) noexcept
        : buckets_(o.buckets_),
          level_count_(o.level_count_),
          min_pending_level_(o.min_pending_level_) {
        o.buckets_ = nullptr;
        o.level_count_ = 0;
        o.min_pending_level_ = 0;
    }
    PendingUpdateQueue& operator=(PendingUpdateQueue&& o) noexcept {
        if (this != &o) {
            destroy();
            buckets_ = o.buckets_;
            level_count_ = o.level_count_;
            min_pending_level_ = o.min_pending_level_;
            o.buckets_ = nullptr;
            o.level_count_ = 0;
            o.min_pending_level_ = 0;
        }
        return *this;
    }

    /// `method_50023 isEmpty()`.
    [[nodiscard]] bool is_empty() const noexcept {
        return min_pending_level_ >= level_count_;
    }

    /// `method_50019 dequeue()`. Removes and returns one id from the
    /// lowest non-empty bucket. Returns `kEmpty` if the queue is empty.
    [[nodiscard]] std::int64_t dequeue() noexcept {
        if (min_pending_level_ >= level_count_) return kEmpty;
        const std::int64_t v = buckets_[min_pending_level_].pop_any();
        // Advance min_pending_level_ past now-empty buckets.
        while (min_pending_level_ < level_count_
               && buckets_[min_pending_level_].empty()) {
            ++min_pending_level_;
        }
        return v;
    }

    /// `method_50021 enqueue(id, level)`.
    void enqueue(std::int64_t id, int level) noexcept {
        if (level < 0 || level >= level_count_ || !buckets_) return;
        buckets_[level].insert(id);
        if (level < min_pending_level_) min_pending_level_ = level;
    }

    /// `method_50022 remove(id, level, levelCount)`.
    /// Vanilla's third arg is the "current level cap" used to constrain
    /// which buckets to scan; for a direct id+level remove it's
    /// effectively redundant, but we keep the same shape for parity.
    void remove(std::int64_t id, int level, int /*level_cap*/) noexcept {
        if (level < 0 || level >= level_count_ || !buckets_) return;
        const bool was_min = (level == min_pending_level_);
        if (buckets_[level].erase(id) && was_min && buckets_[level].empty()) {
            // We may have just emptied the lowest bucket.
            while (min_pending_level_ < level_count_
                   && buckets_[min_pending_level_].empty()) {
                ++min_pending_level_;
            }
        }
    }

    /// `method_50020 increaseMinPendingLevel(maxLevel)`. Marks every
    /// bucket below `max_level` as drained without actually emptying
    /// them. Vanilla uses this when the propagator decides further
    /// processing of a level is unnecessary.
    void increase_min_pending_level(int max_level) noexcept {
        if (max_level > min_pending_level_) {
            min_pending_level_ = max_level > level_count_ ? level_count_ : max_level;
        }
    }

    [[nodiscard]] int level_count() const noexcept { return level_count_; }

    /// Diagnostic: total pending count across all levels.
    [[nodiscard]] std::size_t total_size() const noexcept {
        std::size_t n = 0;
        for (int i = 0; i < level_count_; ++i) n += buckets_[i].size();
        return n;
    }

private:
    void destroy() noexcept {
        if (buckets_) {
            for (int i = 0; i < level_count_; ++i) buckets_[i].~Int64Set();
            ::operator delete(buckets_);
            buckets_ = nullptr;
        }
        level_count_ = 0;
        min_pending_level_ = 0;
    }

    Int64Set* buckets_ = nullptr;
    int       level_count_ = 0;
    int       min_pending_level_ = 0;
};

} // namespace lattice::world::light
