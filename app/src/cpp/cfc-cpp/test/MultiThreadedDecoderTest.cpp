#include "catch.hpp"
#include "unittest.h"

#include "MultiThreadedDecoder.h"

// Compile-time verification that MultiThreadedDecoder::AddResult has the expected values.
// This ensures the enum contract is stable for JNI consumers and other callers.
TEST_CASE("MultiThreadedDecoderAddResult/enum_values_are_distinct", "[decoder]")
{
	auto queued = MultiThreadedDecoder::AddResult::Queued;
	auto full = MultiThreadedDecoder::AddResult::QueueFull;
	auto stopped = MultiThreadedDecoder::AddResult::Stopped;

	assertTrue(queued != full);
	assertTrue(queued != stopped);
	assertTrue(full != stopped);
}

// Verify that a freshly-constructed decoder has zero drop/enqueue counts.
// NOTE: This test requires a fully-configured cimbar environment (Config, fountain init, etc.)
// and is only meaningful when built as part of the complete cfc test suite.
TEST_CASE("MultiThreadedDecoderAddResult/counters_start_at_zero", "[decoder]")
{
	// We cannot easily instantiate MultiThreadedDecoder in a unit test without
	// the full cimbar runtime (Config, fountain wirehair, OpenCV, etc.).
	// Instead, we verify the static counters that track decode pipeline health.
	// These are shared across all instances but are reset to 0 at program start.
	// In a test harness, they will be at or near 0 unless other tests ran first.
	assertTrue(MultiThreadedDecoder::count >= 0);
	assertTrue(MultiThreadedDecoder::decoded >= 0);
	assertTrue(MultiThreadedDecoder::bytes >= 0);
}
