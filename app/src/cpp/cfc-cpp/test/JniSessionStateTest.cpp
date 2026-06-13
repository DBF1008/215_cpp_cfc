#include "catch.hpp"
#include "JniSessionState.h"
#include "unittest.h"

TEST_CASE( "JniSessionState/default_is_clean", "[unit]" )
{
	JniSessionState state;
	assertTrue(state.isClean());
	assertEquals(0u, state.calls);
	assertEquals(0, state.transferStatus);
	assertEquals((clock_t)0, state.frameDecodeSnapshot);
	assertEquals((clock_t)0, state.frameSuccessSnapshot);
	assertTrue(state.completed.empty());
}

TEST_CASE( "JniSessionState/reset_clears_completed_files", "[unit]" )
{
	JniSessionState state;
	state.completed.insert("file1.bin");
	state.completed.insert("file2.bin");
	assertEquals(2u, (unsigned)state.completed.size());

	state.reset();

	assertTrue(state.completed.empty());
}

TEST_CASE( "JniSessionState/reset_clears_calls_counter", "[unit]" )
{
	JniSessionState state;
	state.calls = 1024;

	state.reset();

	assertEquals(0u, state.calls);
}

TEST_CASE( "JniSessionState/reset_clears_transfer_status", "[unit]" )
{
	JniSessionState state;
	state.transferStatus = 2; // full decode indicator from prior session

	state.reset();

	assertEquals(0, state.transferStatus);
}

TEST_CASE( "JniSessionState/reset_clears_frame_snapshots", "[unit]" )
{
	JniSessionState state;
	state.frameDecodeSnapshot = 500;
	state.frameSuccessSnapshot = 300;

	state.reset();

	assertEquals((clock_t)0, state.frameDecodeSnapshot);
	assertEquals((clock_t)0, state.frameSuccessSnapshot);
}

TEST_CASE( "JniSessionState/reset_clears_all_fields_simultaneously", "[unit]" )
{
	JniSessionState state;
	state.completed.insert("decoded_file.bin");
	state.calls = 256;
	state.transferStatus = 1;
	state.frameDecodeSnapshot = 100;
	state.frameSuccessSnapshot = 50;

	assertFalse(state.isClean());

	state.reset();

	assertTrue(state.isClean());
}

// Regression: simulates the bug scenario where a second session inherits
// stale state from the first because shutdownJNI() did not reset it.
TEST_CASE( "JniSessionState/new_session_not_polluted_after_reset", "[unit]" )
{
	// --- session 1: accumulate state ---
	JniSessionState state;
	state.completed.insert("report.bin");
	state.calls = 128;
	state.transferStatus = 2;
	state.frameDecodeSnapshot = 400;
	state.frameSuccessSnapshot = 200;

	// --- shutdown: reset all session state ---
	state.reset();
	assertTrue(state.isClean());

	// --- session 2: first frame tick (calls == 1) ---
	// After reset, calls starts at 0 so the first processImage increments to 1.
	++state.calls;
	assertEquals(1u, state.calls);

	// (calls & 31) == 1 triggers the transfer-status check on the very first
	// frame of the new session.  With clean snapshots (both 0) the comparison
	// against the *new* decoder's counters (also starting at 0) correctly
	// yields transferStatus == 0, not a stale positive value.
	unsigned calls = state.calls;
	clock_t decodeSnapshot = 0;   // new decoder, nothing decoded yet
	clock_t perfectSnapshot = 0;  // new decoder, nothing perfect yet

	if ((calls & 31) == 1)
	{
		state.transferStatus = perfectSnapshot > state.frameSuccessSnapshot;
		state.transferStatus += (decodeSnapshot > state.frameDecodeSnapshot);
		state.frameDecodeSnapshot = decodeSnapshot;
		state.frameSuccessSnapshot = perfectSnapshot;
	}

	assertEquals(0, state.transferStatus);

	// A file that was completed in session 1 should NOT be silently skipped
	// in session 2 (the set was cleared, so it will be reported again).
	assertTrue(state.completed.find("report.bin") == state.completed.end());
}

// Regression: duplicate filename across sessions must be re-reported.
TEST_CASE( "JniSessionState/duplicate_filename_rereported_after_reset", "[unit]" )
{
	JniSessionState state;

	// session 1: file completed
	state.completed.insert("data.bin");

	// shutdown
	state.reset();

	// session 2: same filename appears again -- must not be filtered out
	std::string filename = "data.bin";
	bool isNew = (state.completed.find(filename) == state.completed.end());
	assertTrue(isNew);

	state.completed.insert(filename);
	assertEquals(1u, (unsigned)state.completed.size());
}
