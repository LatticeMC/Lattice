#pragma once
#include "world/gen/densityfunction/density_function.hpp"
namespace lattice::world::gen::densityfunction {
struct CacheStateTestAccess {
    static void eager_clear(CacheState& c) {
#if LATTICE_CACHESTATE_EPOCH
        for (auto& s : c.cache_2d_) { s.value.valid = false; s.epoch = c.evaluation_epoch_; }
        for (auto& s : c.cache_once_) { s.value.valid = false; s.epoch = c.evaluation_epoch_; }
        for (auto& s : c.flat_cache_) { s.value.valid = false; s.epoch = c.evaluation_epoch_; }
        for (auto& s : c.shared_leaf_columns_) { s.value.valid = false; s.epoch = c.evaluation_epoch_; }
        for (auto& s : c.cache_all_in_cell_) { s.value.clear(); s.epoch = c.evaluation_epoch_; }
        for (auto& s : c.cell_arrays_) { s.value = {}; s.epoch = c.binding_epoch_; }
        c.scratch_column_depth = 0;
        c.scratch_x.clear(); c.scratch_y.clear(); c.scratch_z.clear(); c.scratch_value.clear();
        c.is_in_interpolation_loop = false;
        if (c.execution_stats) ++c.execution_stats->cache_clears;
#else
        c.clear();
#endif
    }
#if LATTICE_CACHESTATE_EPOCH
    static void force_wrap(CacheState& c) {
        c.evaluation_epoch_ = UINT64_MAX; c.binding_epoch_ = UINT64_MAX;
    }
#endif
};
}
