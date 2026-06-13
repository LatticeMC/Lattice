#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <tuple>
#include <unordered_map>
#include <vector>

#include "world/light/chunk_light_provider.hpp"
#include "world/light/level_propagator.hpp"

using namespace lattice::world::light;

// Minimal test-only subclass that stores committed levels in a hashmap.
class TestPropagator final : public LevelPropagator {
public:
    TestPropagator() : LevelPropagator(17, 16, 256) {}

    int get_level(std::int64_t id) const noexcept override {
        auto it = committed_.find(id);
        return it == committed_.end() ? level_count_ : it->second;
    }
    int get_propagated_level(std::int64_t /*src*/, std::int64_t /*tgt*/,
                             int level) noexcept override {
        return level + 1; // simple "1 attenuation per hop"
    }
    void propagate_level(std::int64_t /*src*/, std::int64_t /*tgt*/,
                         int level, bool decrease) noexcept override {
        propagation_log.emplace_back(level, decrease, get_level(last_id_));
        // No neighbour topology in this test — we don't fan out.
    }

    void set_level(std::int64_t id, int level) noexcept override {
        last_id_ = id;
        committed_[id] = level;
    }

    void seed_pending(std::int64_t id, int level) {
        pending_updates_.put(id, static_cast<std::int8_t>(level));
        queue_.enqueue(id, level);
        has_pending_updates_ = true;
    }

    int do_recalculate_level(std::int64_t id, std::int64_t excluded_id,
                             int max_level) noexcept override {
        recalc_log.emplace_back(id, excluded_id, max_level);
        return max_level - 1;
    }

    std::unordered_map<std::int64_t, int> committed_;
    std::vector<std::tuple<int, bool, int>> propagation_log;
    std::vector<std::tuple<std::int64_t, std::int64_t, int>> recalc_log;

private:
    std::int64_t last_id_ = 0;
};

TEST_CASE("level_propagator: empty queue") {
    TestPropagator p;
    CHECK(!p.has_pending_updates());
    CHECK(p.get_pending_update_count() == 0);
    auto rem = p.apply_pending_updates(100);
    CHECK(rem == 100);
}

TEST_CASE("level_propagator: single seed lowers level") {
    TestPropagator p;
    // Seed: id 42, level 0, increase.
    p.update_level(42, 42, 0, false);
    CHECK(p.has_pending_updates());
    auto rem = p.apply_pending_updates(100);
    CHECK(rem < 100);
    CHECK(p.committed_[42] == 0);
}

TEST_CASE("level_propagator: applyPendingUpdates respects max_steps") {
    TestPropagator p;
    p.update_level(1, 1, 5, false);
    p.update_level(2, 2, 5, false);
    p.update_level(3, 3, 5, false);
    CHECK(p.get_pending_update_count() == 3);
    auto rem = p.apply_pending_updates(1);
    CHECK(rem == 0);
    CHECK(p.has_pending_updates());           // still 2 left
    CHECK(p.get_pending_update_count() == 2);
}

TEST_CASE("level_propagator: clamping to [0, level_count - 1]") {
    TestPropagator p;
    // level_count is 17 so valid candidate levels are [0, 16].
    p.update_level(7, 7, 1000, false); // way out of range → clamped to 16
    auto rem = p.apply_pending_updates(5);

    CHECK(rem == 5);
    CHECK(!p.has_pending_updates());
    CHECK(p.committed_.find(7) == p.committed_.end());
}

TEST_CASE("level_propagator: decrease replay clears committed level before propagation") {
    TestPropagator p;
    p.committed_[9] = 1;
    p.seed_pending(9, 5);

    auto rem = p.apply_pending_updates(10);

    CHECK(rem < 10);
    REQUIRE(p.propagation_log.size() == 2);
    CHECK(std::get<0>(p.propagation_log[0]) == 1);
    CHECK(std::get<1>(p.propagation_log[0]) == true);
    CHECK(std::get<2>(p.propagation_log[0]) == p.level_count() - 1);
    CHECK(std::get<0>(p.propagation_log[1]) == 5);
    CHECK(std::get<1>(p.propagation_log[1]) == false);
    CHECK(std::get<2>(p.propagation_log[1]) == 5);
    CHECK(p.committed_[9] == 5);
}

TEST_CASE("level_propagator: weaker candidate keeps brighter pending update") {
    TestPropagator p;
    p.committed_[11] = 4;

    p.update_level(11, 11, 2, false);
    CHECK(p.has_pending_updates());

    p.update_level(11, 11, 4, true);

    CHECK(p.has_pending_updates());
    CHECK(p.get_pending_update_count() == 1);
    auto rem = p.apply_pending_updates(5);
    CHECK(rem < 5);
    CHECK(p.committed_[11] == 2);
}

TEST_CASE("level_propagator: remove_pending_update removes priority bucket entry") {
    TestPropagator p;
    p.committed_[13] = 6;
    p.update_level(13, 13, 4, false);

    CHECK(p.has_pending_updates());
    CHECK(p.get_pending_update_count() == 1);

    p.remove_pending_update(13);

    CHECK(!p.has_pending_updates());
    CHECK(p.get_pending_update_count() == 0);
    auto rem = p.apply_pending_updates(3);
    CHECK(rem == 3);
    CHECK(p.committed_[13] == 6);
}

TEST_CASE("level_propagator: recalculate_level delegates to subclass hook") {
    TestPropagator p;

    const int result = p.recalculate_level(21, 22, 9);

    CHECK(result == 8);
    REQUIRE(p.recalc_log.size() == 1);
    CHECK(std::get<0>(p.recalc_log[0]) == 21);
    CHECK(std::get<1>(p.recalc_log[0]) == 22);
    CHECK(std::get<2>(p.recalc_log[0]) == 9);
}

TEST_CASE("level_propagator: recalculate_level clamps max_level before delegation") {
    TestPropagator p;

    const int result = p.recalculate_level(31, 32, 999);

    CHECK(result == p.level_count() - 2);
    REQUIRE(p.recalc_log.size() == 1);
    CHECK(std::get<2>(p.recalc_log[0]) == p.level_count() - 1);
}

TEST_CASE("chunk_light_provider: recalculate_level uses optional callback") {
    struct CallbackState {
        std::int64_t seen_id = 0;
        std::int64_t seen_excluded = 0;
        int seen_max_level = -1;
    } state;

    LightProviderCallbacks callbacks{};
    callbacks.user_data = &state;
    callbacks.recalculate_level = [](void* user_data, std::int64_t id,
                                     std::int64_t excluded_id, int max_level) noexcept {
        auto* state = static_cast<CallbackState*>(user_data);
        state->seen_id = id;
        state->seen_excluded = excluded_id;
        state->seen_max_level = max_level;
        return max_level - 2;
    };

    ChunkLightProvider provider(17, 16, 256, callbacks);

    const int result = provider.recalculate_level(41, 42, 11);

    CHECK(result == 9);
    CHECK(state.seen_id == 41);
    CHECK(state.seen_excluded == 42);
    CHECK(state.seen_max_level == 11);
}

TEST_CASE("chunk_light_provider: recalculate_level falls back without callback") {
    LightProviderCallbacks callbacks{};
    ChunkLightProvider provider(17, 16, 256, callbacks);

    const int result = provider.recalculate_level(51, 52, 11);

    CHECK(result == 11);
}

TEST_CASE("level_propagator: source decrease to maximum is a no-op until a neighbor is checked") {
    class RefillPropagator final : public LevelPropagator {
    public:
        RefillPropagator() : LevelPropagator(17, 16, 256) {}

        int get_level(std::int64_t id) const noexcept override {
            auto it = committed.find(id);
            return it == committed.end() ? level_count_ : it->second;
        }

        void set_level(std::int64_t id, int level) noexcept override {
            committed[id] = level;
        }

        int get_propagated_level(std::int64_t source_id, std::int64_t target_id,
                                 int level) noexcept override {
            if (target_id == 2 && (source_id == 10 || source_id == 20)) {
                return level + 1;
            }
            return level_count_;
        }

        int do_recalculate_level(std::int64_t id, std::int64_t excluded_id,
                                 int max_level) noexcept override {
            if (id != 2) return max_level;
            int best = level_count() - 1;
            if (excluded_id != 10) best = calculate_level(best, 6);
            if (excluded_id != 20) best = calculate_level(best, 4);
            return best;
        }

        void propagate_level(std::int64_t source_id, std::int64_t target_id,
                             int level, bool decrease) noexcept override {
            if (target_id != source_id) return;
            if (source_id != 10 && source_id != 20) return;
            update_level(source_id, 2, level, decrease);
        }

        std::unordered_map<std::int64_t, int> committed;
    } p;

    p.update_level(10, 10, 5, false);
    p.update_level(20, 20, 3, false);
    p.apply_pending_updates(20);

    CHECK(p.committed[2] == 4);

    p.update_level(20, 20, 16, true);
    p.apply_pending_updates(20);

    CHECK(p.committed[2] == 4);
}

TEST_CASE("level_propagator: source decrease to maximum does not recalculate neighbor directly") {
    class DecreaseRecalcPropagator final : public LevelPropagator {
    public:
        DecreaseRecalcPropagator() : LevelPropagator(17, 16, 256) {}

        int get_level(std::int64_t id) const noexcept override {
            auto it = committed.find(id);
            return it == committed.end() ? level_count_ : it->second;
        }

        void set_level(std::int64_t id, int level) noexcept override {
            committed[id] = level;
        }

        int get_propagated_level(std::int64_t source_id, std::int64_t target_id,
                                 int level) noexcept override {
            if (source_id == 1 && target_id == 2) return level + 1;
            return level_count_;
        }

        int do_recalculate_level(std::int64_t id, std::int64_t excluded_id,
                                 int max_level) noexcept override {
            recalc_calls.emplace_back(id, excluded_id, max_level);
            return 7;
        }

        void propagate_level(std::int64_t source_id, std::int64_t target_id,
                             int level, bool decrease) noexcept override {
            if (target_id == source_id && source_id == 1) {
                update_level(source_id, 2, level, decrease);
            }
        }

        std::unordered_map<std::int64_t, int> committed;
        std::vector<std::tuple<std::int64_t, std::int64_t, int>> recalc_calls;
    } p;

    p.update_level(1, 1, 5, false);
    p.apply_pending_updates(10);
    CHECK(p.committed[2] == 6);

    p.update_level(1, 1, 16, true);
    p.apply_pending_updates(10);

    CHECK(p.recalc_calls.empty());
    CHECK(p.committed[2] == 6);
}
