/**
 * @file long_to_byte_map.hpp
 * @brief Map<long,byte> backing the `pendingUpdates` field of `LevelPropagator`.
 *
 * Vanilla uses `it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap` here.
 * The map's value is the *current* tentative level the propagator
 * believes a given id has — when the BFS revisits an id it consults
 * this map to decide whether the new candidate is an improvement.
 *
 * Sentinel: like `Int64Set`, INT64_MIN is reserved for empty slots. The
 * "default return value" semantics from fastutil (returns a configured
 * sentinel byte when `get` misses) are surfaced here as `get_or_default`.
 *
 * Single-threaded; the propagator owns its map.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace lattice::world::light {

class Long2ByteMap {
public:
    static constexpr std::int64_t kEmptyKey = INT64_MIN;

    Long2ByteMap() noexcept = default;

    explicit Long2ByteMap(std::size_t initial_capacity,
                          std::int8_t default_value = 0) noexcept
        : default_(default_value) {
        rehash(round_up_pow2(initial_capacity < 4 ? 4 : initial_capacity));
    }

    ~Long2ByteMap() noexcept {
        std::free(keys_);
        std::free(vals_);
    }

    Long2ByteMap(const Long2ByteMap&)            = delete;
    Long2ByteMap& operator=(const Long2ByteMap&) = delete;

    Long2ByteMap(Long2ByteMap&& o) noexcept
        : keys_(o.keys_), vals_(o.vals_),
          mask_(o.mask_), size_(o.size_), max_load_(o.max_load_),
          default_(o.default_) {
        o.keys_ = nullptr; o.vals_ = nullptr;
        o.mask_ = 0; o.size_ = 0; o.max_load_ = 0;
    }
    Long2ByteMap& operator=(Long2ByteMap&& o) noexcept {
        if (this != &o) {
            std::free(keys_);
            std::free(vals_);
            keys_ = o.keys_; vals_ = o.vals_;
            mask_ = o.mask_; size_ = o.size_; max_load_ = o.max_load_;
            default_ = o.default_;
            o.keys_ = nullptr; o.vals_ = nullptr;
            o.mask_ = 0; o.size_ = 0; o.max_load_ = 0;
        }
        return *this;
    }

    [[nodiscard]] std::size_t size() const noexcept { return size_; }
    [[nodiscard]] bool empty()       const noexcept { return size_ == 0; }
    void set_default_value(std::int8_t v) noexcept { default_ = v; }
    [[nodiscard]] std::int8_t default_value() const noexcept { return default_; }

    /// Insert or overwrite. Returns the previous value (or `default_` if not
    /// present). Returns `default_` and leaves the map unchanged on
    /// allocation failure (very rare).
    std::int8_t put(std::int64_t key, std::int8_t value) noexcept {
        if (key == kEmptyKey) return default_;
        if (keys_ == nullptr || size_ >= max_load_) {
            const std::size_t new_cap = (keys_ == nullptr) ? 8 : (mask_ + 1) * 2;
            if (!rehash(new_cap)) return default_;
        }
        std::size_t i = hash(key) & mask_;
        for (;;) {
            const std::int64_t k = keys_[i];
            if (k == kEmptyKey) {
                keys_[i] = key;
                vals_[i] = value;
                ++size_;
                return default_;
            }
            if (k == key) {
                const std::int8_t old = vals_[i];
                vals_[i] = value;
                return old;
            }
            i = (i + 1) & mask_;
        }
    }

    /// Look up `key`. Returns `default_value()` when absent.
    [[nodiscard]] std::int8_t get_or_default(std::int64_t key) const noexcept {
        if (key == kEmptyKey || keys_ == nullptr) return default_;
        std::size_t i = hash(key) & mask_;
        for (;;) {
            const std::int64_t k = keys_[i];
            if (k == kEmptyKey) return default_;
            if (k == key)       return vals_[i];
            i = (i + 1) & mask_;
        }
    }

    /// Remove `key`. Returns the removed value, or `default_` if absent.
    std::int8_t remove(std::int64_t key) noexcept {
        if (key == kEmptyKey || keys_ == nullptr) return default_;
        std::size_t i = hash(key) & mask_;
        for (;;) {
            const std::int64_t k = keys_[i];
            if (k == kEmptyKey) return default_;
            if (k == key) break;
            i = (i + 1) & mask_;
        }
        const std::int8_t removed = vals_[i];
        // Backward-shift deletion to maintain linear-probing invariant.
        std::size_t j = i;
        for (;;) {
            j = (j + 1) & mask_;
            const std::int64_t k = keys_[j];
            if (k == kEmptyKey) {
                keys_[i] = kEmptyKey;
                --size_;
                return removed;
            }
            const std::size_t ideal = hash(k) & mask_;
            const bool keep_shift =
                (i <= j) ? (ideal <= i || ideal > j)
                         : (ideal <= i && ideal > j);
            if (keep_shift) {
                keys_[i] = k;
                vals_[i] = vals_[j];
                i = j;
            }
        }
    }

    [[nodiscard]] bool contains_key(std::int64_t key) const noexcept {
        if (key == kEmptyKey || keys_ == nullptr) return false;
        std::size_t i = hash(key) & mask_;
        for (;;) {
            const std::int64_t k = keys_[i];
            if (k == kEmptyKey) return false;
            if (k == key)       return true;
            i = (i + 1) & mask_;
        }
    }

    /// Iterate every (key, value) pair in implementation-defined order.
    /// `Fn` must be invocable as `void(int64_t, int8_t)`.
    template <class Fn>
    void for_each(Fn fn) const noexcept(noexcept(fn(std::int64_t{}, std::int8_t{}))) {
        if (keys_ == nullptr) return;
        for (std::size_t i = 0; i <= mask_; ++i) {
            if (keys_[i] != kEmptyKey) fn(keys_[i], vals_[i]);
        }
    }

    /// Drop all entries without releasing capacity.
    void clear() noexcept {
        if (keys_ != nullptr) {
            for (std::size_t i = 0; i <= mask_; ++i) keys_[i] = kEmptyKey;
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
        std::uint64_t h = static_cast<std::uint64_t>(key);
        h ^= h >> 30; h *= 0xBF58476D1CE4E5B9ULL;
        h ^= h >> 27; h *= 0x94D049BB133111EBULL;
        h ^= h >> 31;
        return h;
    }

    bool rehash(std::size_t new_cap) noexcept {
        new_cap = round_up_pow2(new_cap);
        std::int64_t* fresh_keys =
            static_cast<std::int64_t*>(std::malloc(new_cap * sizeof(std::int64_t)));
        if (!fresh_keys) return false;
        std::int8_t* fresh_vals =
            static_cast<std::int8_t*>(std::malloc(new_cap * sizeof(std::int8_t)));
        if (!fresh_vals) { std::free(fresh_keys); return false; }
        for (std::size_t i = 0; i < new_cap; ++i) fresh_keys[i] = kEmptyKey;
        // Values for empty slots are unused, but zero them for tooling clarity.
        std::memset(fresh_vals, 0, new_cap);

        const std::size_t new_mask = new_cap - 1;
        std::size_t fresh_size = 0;
        if (keys_ != nullptr) {
            for (std::size_t i = 0; i <= mask_; ++i) {
                const std::int64_t k = keys_[i];
                if (k == kEmptyKey) continue;
                std::size_t j = hash(k) & new_mask;
                for (;;) {
                    if (fresh_keys[j] == kEmptyKey) {
                        fresh_keys[j] = k;
                        fresh_vals[j] = vals_[i];
                        ++fresh_size;
                        break;
                    }
                    j = (j + 1) & new_mask;
                }
            }
            std::free(keys_);
            std::free(vals_);
        }

        keys_ = fresh_keys;
        vals_ = fresh_vals;
        mask_ = new_mask;
        size_ = fresh_size;
        max_load_ = (new_cap >> 2) * 3; // 0.75
        return true;
    }

    std::int64_t* keys_     = nullptr;
    std::int8_t*  vals_     = nullptr;
    std::size_t   mask_     = 0;
    std::size_t   size_     = 0;
    std::size_t   max_load_ = 0;
    std::int8_t   default_  = 0;
};

} // namespace lattice::world::light
