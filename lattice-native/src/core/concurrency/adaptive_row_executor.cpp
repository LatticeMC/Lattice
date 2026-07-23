#include "core/concurrency/adaptive_row_executor.hpp"

#include <algorithm>

namespace lattice::concurrency {

AdaptiveRowExecutor::~AdaptiveRowExecutor() {
    {
        std::lock_guard lock(state_mutex_);
        stopping_ = true;
        ++generation_;
    }
    job_available_.notify_all();
    for (auto& worker : workers_) {
        if (worker.joinable()) worker.join();
    }
}

int AdaptiveRowExecutor::run(int rows, int requested_lanes, void* context, RowTask task) {
    if (rows <= 0 || task == nullptr) return 0;
    const int lanes = std::clamp(requested_lanes, 1, rows);
    if (lanes == 1) {
        for (int row = 0; row < rows; ++row) task(context, row, 0);
        return 1;
    }

    std::unique_lock run_lock(run_mutex_, std::try_to_lock);
    if (!run_lock.owns_lock()) {
        for (int row = 0; row < rows; ++row) task(context, row, 0);
        return 1;
    }

    ensure_workers(lanes - 1);
    Job job;
    job.rows = rows;
    job.active_workers = lanes - 1;
    job.total_workers = static_cast<int>(workers_.size());
    job.context = context;
    job.task = task;

    {
        std::lock_guard state_lock(state_mutex_);
        current_job_ = &job;
        ++generation_;
    }
    job_available_.notify_all();

    execute_rows(job, 0);

    {
        std::unique_lock state_lock(state_mutex_);
        job_complete_.wait(state_lock, [&job] {
            return job.acknowledged_workers.load(std::memory_order_acquire) == job.total_workers;
        });
        current_job_ = nullptr;
    }
    return lanes;
}

void AdaptiveRowExecutor::ensure_workers(int count) {
    while (static_cast<int>(workers_.size()) < count) {
        const int index = static_cast<int>(workers_.size());
        workers_.emplace_back([this, index] { worker_loop(index); });
    }
}

void AdaptiveRowExecutor::worker_loop(int index) noexcept {
    std::uint64_t observed_generation = 0;
    while (true) {
        Job* job = nullptr;
        {
            std::unique_lock state_lock(state_mutex_);
            job_available_.wait(state_lock, [this, &observed_generation] {
                return stopping_ || generation_ != observed_generation;
            });
            if (stopping_) return;
            observed_generation = generation_;
            job = current_job_;
        }

        if (job != nullptr && index < job->active_workers) {
            execute_rows(*job, index + 1);
        }
        if (job != nullptr
                && job->acknowledged_workers.fetch_add(1, std::memory_order_acq_rel) + 1 == job->total_workers) {
            job_complete_.notify_one();
        }
    }
}

void AdaptiveRowExecutor::execute_rows(Job& job, int lane) noexcept {
    while (true) {
        const int row = job.next_row.fetch_add(1, std::memory_order_relaxed);
        if (row >= job.rows) return;
        job.task(job.context, row, lane);
    }
}

AdaptiveRowExecutor& worldgen_row_executor() {
    static AdaptiveRowExecutor executor;
    return executor;
}

} // namespace lattice::concurrency
