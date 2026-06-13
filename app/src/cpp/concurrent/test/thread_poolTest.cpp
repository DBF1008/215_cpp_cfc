/* This code is subject to the terms of the Mozilla Public License, v.2.0. http://mozilla.org/MPL/2.0/. */
#include "catch.hpp"

#include "thread_pool.h"

// These tests lock down the explicit-backpressure contract that the cfc decode
// pipeline relies on: every submission resolves to exactly one of
// accepted / over_limit / full, and an over-limit submission is dropped (never queued).
// The pool is intentionally left un-started so the queue never drains, which makes
// the backlog -- and therefore each outcome -- fully deterministic.

using status = turbo::thread_pool::enqueue_status;

TEST_CASE( "thread_poolTest/AcceptedWhenBelowLimit", "[unit]" )
{
	turbo::thread_pool pool(1, 1);

	status res = pool.try_execute_within([] () {}, 100);
	REQUIRE( res == status::accepted );
	REQUIRE( pool.queued() >= 1 );
}

TEST_CASE( "thread_poolTest/OverLimitDropsWithoutQueueing", "[unit]" )
{
	turbo::thread_pool pool(1, 1);

	// build a backlog well past the soft limit using execute() (which always enqueues)
	const size_t softLimit = 3;
	for (int i = 0; i < 10; ++i)
		pool.execute([] () {});
	REQUIRE( pool.queued() >= softLimit );

	status res = pool.try_execute_within([] () {}, softLimit);
	REQUIRE( res == status::over_limit );

	// an explicit drop must not consume a queue slot -- the backlog is unchanged
	size_t before = pool.queued();
	status again = pool.try_execute_within([] () {}, softLimit);
	REQUIRE( again == status::over_limit );
	REQUIRE( pool.queued() == before );
}

TEST_CASE( "thread_poolTest/FullWhenQueueRejects", "[unit]" )
{
	turbo::thread_pool pool(1, 1);

	// try_execute() respects the bounded queue, so it eventually rejects under saturation.
	// This is exactly the signal the decode pipeline previously ignored.
	bool sawFull = false;
	for (int i = 0; i < 100000; ++i)
		if (!pool.try_execute([] () {}))
		{
			sawFull = true;
			break;
		}
	REQUIRE( sawFull );

	// With a soft limit above the backlog, the verdict comes from the queue itself: full.
	status res = pool.try_execute_within([] () {}, static_cast<size_t>(1) << 30);
	REQUIRE( res == status::full );
}
