#include "catch.hpp"
#include "unittest.h"

#include "concurrent/thread_pool.h"

#include <atomic>
#include <chrono>
#include <thread>
#include <vector>

using namespace std;

TEST_CASE("ThreadPoolBackpressureTest/try_execute_succeeds_with_empty_queue", "[backpressure]")
{
	turbo::thread_pool pool(2, 1);
	pool.start();

	// With an empty queue, try_execute should always succeed
	assertTrue(pool.try_execute([] { }));
	assertTrue(pool.try_execute([] { }));
	assertTrue(pool.try_execute([] { }));

	pool.stop();
}

TEST_CASE("ThreadPoolBackpressureTest/try_execute_fails_when_queue_saturated", "[backpressure]")
{
	// Use 4 threads to keep the ratio of queued-to-executing high
	unsigned numThreads = 4;
	turbo::thread_pool pool(numThreads, 1);
	// Do NOT start — tasks stay queued, never executed

	std::atomic<bool> dummy{false};
	int successes = 0;
	int failures = 0;

	// Submit many tasks to a non-started pool.
	// Since the pool isn't running, tasks accumulate in the bounded queue.
	// Eventually try_enqueue fails (CannotAlloc — pre-allocated blocks exhausted).
	for (int i = 0; i < 500; ++i) {
		if (pool.try_execute([&dummy] {
			// keep reference alive; will never run
			dummy.load();
		}))
			++successes;
		else
			++failures;
	}

	// The bounded queue must have a finite capacity — some tasks must have been accepted
	assertTrue(successes > 0);
	// And critically, once saturated, try_execute must start returning false
	assertTrue(failures > 0);
}

TEST_CASE("ThreadPoolBackpressureTest/try_execute_recovers_after_drain", "[backpressure]")
{
	unsigned numThreads = 2;
	turbo::thread_pool pool(numThreads, 1);
	// Don't start yet

	// Fill the queue to saturation
	int filled = 0;
	for (int i = 0; i < 500; ++i) {
		if (pool.try_execute([] { std::this_thread::sleep_for(std::chrono::milliseconds(1)); }))
			++filled;
		else
			break;
	}
	assertTrue(filled > 0);

	// Queue is now full — verify try_execute fails
	assertFalse(pool.try_execute([] { }));

	// Start the pool to drain the queue
	pool.start();
	std::this_thread::sleep_for(std::chrono::milliseconds(500));

	// After draining, try_execute should succeed again
	assertTrue(pool.try_execute([] { }));

	pool.stop();
}

TEST_CASE("ThreadPoolBackpressureTest/queued_reports_pending_tasks", "[backpressure]")
{
	turbo::thread_pool pool(1, 1);
	// Don't start — tasks accumulate

	// Queue some blocking tasks
	for (int i = 0; i < 10; ++i) {
		pool.try_execute([] {
			std::this_thread::sleep_for(std::chrono::seconds(60));
		});
	}

	// queued() should report approximate pending count
	assertTrue(pool.queued() > 0);
}

// Verify that the dropped/enqueued counters start at zero
// and that the backpressure path increments dropped.
// This test exercises the thread_pool-level mechanism that
// MultiThreadedDecoder::add() relies on.
TEST_CASE("ThreadPoolBackpressureTest/drop_counting_semantics", "[backpressure]")
{
	turbo::thread_pool pool(1, 1);
	// Don't start — all tasks queue up

	unsigned enqueued = 0;
	unsigned dropped = 0;

	// Simulate the same pattern as MultiThreadedDecoder::add()
	for (int i = 0; i < 500; ++i) {
		bool ok = pool.try_execute([] {
			std::this_thread::sleep_for(std::chrono::seconds(60));
		});
		if (ok)
			++enqueued;
		else
			++dropped;
	}

	// Some should have been enqueued, some dropped
	assertTrue(enqueued > 0);
	assertTrue(dropped > 0);
	// Total should equal attempts
	assertEquals(500u, enqueued + dropped);
}
