#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace lattice::concurrency {

using RowTask = void (*)(void* context, int row, int lane) noexcept;

class AdaptiveRowExecutor final {
public:
    AdaptiveRowExecutor() = default;
    ~AdaptiveRowExecutor();

    AdaptiveRowExecutor(const AdaptiveRowExecutor&) = delete;
    AdaptiveRowExecutor& operator=(const AdaptiveRowExecutor&) = delete;

    // Executes every row exactly once. A return value greater than one means
    // persistent workers participated; one means the caller ran inline.
    int run(int rows, int requested_lanes, void* context, RowTask task);

private:
    struct Job {
        int rows = 0;
        int active_workers = 0;
        int total_workers = 0;
        void* context = nullptr;
        RowTask task = nullptr;
        std::atomic<int> next_row{0};
        std::atomic<int> acknowledged_workers{0};
    };

    void ensure_workers(int count);
    void worker_loop(int index) noexcept;
    static void execute_rows(Job& job, int lane) noexcept;

    std::mutex run_mutex_;
    std::mutex state_mutex_;
    std::condition_variable job_available_;
    std::condition_variable job_complete_;
    std::vector<std::thread> workers_;
    Job* current_job_ = nullptr;
    std::uint64_t generation_ = 0;
    bool stopping_ = false;
};

AdaptiveRowExecutor& worldgen_row_executor();

} // namespace lattice::concurrency
