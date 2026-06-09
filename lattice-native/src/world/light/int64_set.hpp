/**
 * @file int64_set.hpp
 * @brief Open-addressing hash set of `int64_t` with linear probing.
 *
 * Used as the per-level bucket of `PendingUpdateQueue`. Vanilla uses
 * `it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet` for this; the
 * iteration order is insertion-stable, but BFS correctness does not
 * depend on iteration order (the propagator converges to the same fixed
 * point regardless), so we drop the linked-list bookkeeping and gain
 * cache locality.
 *
 * Sentinel: `INT64_MIN` is reserved to mean "empty slot". Vanilla's BFS
 * never produces this packed value as a valid block-pos id (the encoding
 * places small unsigned x/y/z components into the bottom bits with
 * specific shifts; the high bits encode signed coordinates that can in
 * principle reach INT64_MIN, but practical worlds never approach it).
 * If a caller tries to insert `INT64_MIN` we simply ignore it — see
 * `insert()`.
 *
 * Operations are noexcept and never throw. Allocation failure causes the
 * set to remain at its previous size; insert() returns false in that case.
 *
 * Single-threaded; the light propagator owns its sets and never shares
 * them across threads.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>

namespace lattice::world::light {

class Int64Set {
public:
    static constexpr std::int64_t kEmpty = INT64_MIN;

    Int64Set() noexcept = default;
    explicit Int64Set(std::size_t initial_capacity) noexcept {
        reserve_pow2(round_up_pow2(initial_capacity < 4 ? 4 : initial_capacity));
    }

    ~Int64Set() noexcept {
        std::free(slots_);
    }

    Int64Set(const Int64Set&) = delete;
    Int64Set& operator=(const Int64Set&) = delete;

    Int64Set(Int64Set&& o) noexcept
        : slots_(o.slots_), mask_(o.mask_), size_(o.size_), max_load_(o.max_load_) {
        o.slots_ = nullptr; o.mask_ = 0; o.size_ = 0; o.max_load_ = 0;
    }
    Int64Set& operator=(Int64Set&& o) noexcept {
        if (this != &o) {
            std::free(slots_);
            slots_ = o.slots_; mask_ = o.mask_; size_ = o.size_; max_load_ = o.max_load_;
            o.slots_ = nullptr; o.mask_ = 0; o.size_ = 0; o.max_load_ = 0;
        }
        return *this;
    }

    [[nodiscard]] std::size_t size() const noexcept { return size_; }
    [[nodiscard]] bool        empty() const noexcept { return size_ == 0; }

    /// Insert `key`. Returns true if newly inserted, false if already present
    /// or if `key == kEmpty` (which is invalid). On allocation failure the
    /// set is left unchanged and false is returned.
    bool insert(std::int64_t key) noexcept {
        if (key == kEmpty) return false;
        if (slots_ == nullptr || size_ >= max_load_) {
            const std::size_t new_cap = (slots_ == nullptr) ? 8 : (mask_ + 1) * 2;
            if (!rehash(new_cap)) return false;
        }
        return insert_into(slots_, mask_, key, &size_);
    }

    /// Remove `key`. Returns true if it was present.
    bool erase(std::int64_t key) noexcept {
        if (key == kEmpty || slots_ == nullptr) return false;
        std::size_t i = hash(key) & mask_;
        for (;;) {
            const std::int64_t s = slots_[i];
            if (s == kEmpty) return false;
            if (s == key) break;
            i = (i + 1) & mask_;
        }
        // Backward-shift deletion to maintain the linear-probing invariant.
        std::size_t j = i;
        for (;;) {
            j = (j + 1) & mask_;
            const std::int64_t s = slots_[j];
            if (s == kEmpty) {
                slots_[i] = kEmpty;
                --size_;
                return true;
            }
            const std::size_t ideal = hash(s) & mask_;
            // If `s`'s ideal slot is between i (exclusive) and j (inclusive)
            // when traversed forward, shifting it back keeps the invariant.
            const bool keep_shift =
                (i <= j) ? (ideal <= i || ideal > j)
                         : (ideal <= i && ideal > j);
            if (keep_shift) {
                slots_[i] = s;
                i = j;
            }
        }
    }

    [[nodiscard]] bool contains(std::int64_t key) const noexcept {
        if (key == kEmpty || slots_ == nullptr) return false;
        std::size_t i = hash(key) & mask_;
        for (;;) {
            const std::int64_t s = slots_[i];
            if (s == kEmpty) return false;
            if (s == key) return true;
            i = (i + 1) & mask_;
        }
    }

    /// Pop any one element. Returns kEmpty if the set is empty.
    /// Iteration order is implementation-defined (linear scan from slot 0);
    /// callers must not rely on it.
    [[nodiscard]] std::int64_t pop_any() noexcept {
        if (size_ == 0) return kEmpty;
        for (std::size_t i = 0; i <= mask_; ++i) {
            if (slots_[i] != kEmpty) {
                const std::int64_t v = slots_[i];
                erase(v);
                return v;
            }
        }
        return kEmpty; // unreachable
    }

    /// Drop all entries without releasing capacity.
    void clear() noexcept {
        if (slots_ != nullptr) {
            for (std::size_t i = 0; i <= mask_; ++i) slots_[i] = kEmpty;
        }
        size_ = 0;
    }

private:
    static std::size_t round_up_pow2(std::size_t x) noexcept {
        std::size_t n = 1;
        while (n < x) n <<= 1;
        return n;
    }

    static std::uint64_t hash(std::int64_t key) noexcept {
        // SplitMix64 finalisation; well-distributed for chunk-pos-shaped
        // long ids that fastutil's HashCommon would also handle.
        std::uint64_t h = static_cast<std::uint64_t>(key);
        h ^= h >> 30; h *= 0xBF58476D1CE4E5B9ULL;
        h ^= h >> 27; h *= 0x94D049BB133111EBULL;
        h ^= h >> 31;
        return h;
    }

    static bool insert_into(std::int64_t* slots, std::size_t mask,
                            std::int64_t key, std::size_t* size) noexcept {
        std::size_t i = hash(key) & mask;
        for (;;) {
            const std::int64_t s = slots[i];
            if (s == kEmpty) {
                slots[i] = key;
                ++*size;
                return true;
            }
            if (s == key) return false; // already present
            i = (i + 1) & mask;
        }
    }

    bool reserve_pow2(std::size_t new_cap) noexcept {
        return rehash(new_cap);
    }

    bool rehash(std::size_t new_cap) noexcept {
        // Caller passes pow-of-2 (or anything that round_up_pow2 fixes).
        new_cap = round_up_pow2(new_cap);
        std::int64_t* fresh =
            static_cast<std::int64_t*>(std::malloc(new_cap * sizeof(std::int64_t)));
        if (!fresh) return false;
        for (std::size_t i = 0; i < new_cap; ++i) fresh[i] = kEmpty;

        const std::size_t new_mask = new_cap - 1;
        std::size_t fresh_size = 0;
        if (slots_ != nullptr) {
            for (std::size_t i = 0; i <= mask_; ++i) {
                const std::int64_t s = slots_[i];
                if (s != kEmpty) insert_into(fresh, new_mask, s, &fresh_size);
            }
            std::free(slots_);
        }

        slots_ = fresh;
        mask_ = new_mask;
        size_ = fresh_size;
        // 0.75 max load.
        max_load_ = (new_cap >> 2) * 3;
        return true;
    }

    std::int64_t* slots_    = nullptr;
    std::size_t   mask_     = 0;
    std::size_t   size_     = 0;
    std::size_t   max_load_ = 0;
};

} // namespace lattice::world::light
