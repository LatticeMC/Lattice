#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <atomic>
#include <thread>
#include <vector>

#include "core/concurrency/adaptive_row_executor.hpp"

namespace {

struct RowCounters {
    std::vector<std::atomic<int>> hits;

    explicit RowCounters(int rows) : hits(static_cast<std::size_t>(rows)) {}
};

void count_row(void* opaque, int row, int) noexcept {
    auto& counters = *static_cast<RowCounters*>(opaque);
    counters.hits[static_cast<std::size_t>(row)].fetch_add(1, std::memory_order_relaxed);
}

} // namespace

TEST_CASE("adaptive row executor visits every row once") {
    lattice::concurrency::AdaptiveRowExecutor executor;
    RowCounters counters(257);

    const int lanes = executor.run(257, 4, &counters, count_row);

    CHECK(lanes == 4);
    for (const auto& hit : counters.hits) CHECK(hit.load(std::memory_order_relaxed) == 1);
}

TEST_CASE("adaptive row executor falls back inline while occupied") {
    lattice::concurrency::AdaptiveRowExecutor executor;
    RowCounters first(10000);
    RowCounters second(10000);

    std::thread concurrent([&] { executor.run(10000, 4, &first, count_row); });
    const int lanes = executor.run(10000, 4, &second, count_row);
    concurrent.join();

    CHECK((lanes == 1 || lanes == 4));
    for (const auto& hit : first.hits) CHECK(hit.load(std::memory_order_relaxed) == 1);
    for (const auto& hit : second.hits) CHECK(hit.load(std::memory_order_relaxed) == 1);
}
