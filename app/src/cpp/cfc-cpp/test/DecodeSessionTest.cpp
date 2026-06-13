#include "unittest.h"

#include "DecodeSession.h"

// Regression coverage for the cfc JNI session-state cleanup. Each of these
// would fail if shutdownJNI() forgot to call DecodeSession::reset() (the bug
// these tests guard against): a re-opened scan page would inherit the previous
// session's completed-file set and transfer-status snapshots.

TEST_CASE( "DecodeSessionTest/completedSetClearedOnReset", "[unit]" )
{
	DecodeSession s;

	// first sighting of a name is reported, repeats within the session are not
	assertTrue( s.mark_completed("photo.jpg") );
	assertFalse( s.mark_completed("photo.jpg") );

	s.reset();

	// after a session ends, the very same name must be treated as brand new --
	// otherwise a coincidentally-duplicate file would be silently dropped
	assertTrue( s.mark_completed("photo.jpg") );
}

TEST_CASE( "DecodeSessionTest/resetClearsAllSessionState", "[unit]" )
{
	DecodeSession s;
	s.completed.insert("a");
	s.completed.insert("b");
	s.calls = 9999;
	s.transferStatus = 2;
	s.frameDecodeSnapshot = 1234;
	s.frameSuccessSnapshot = 567;

	s.reset();

	assertTrue( s.completed.empty() );
	assertEquals( 0u, s.calls );
	assertEquals( 0, s.transferStatus );
	assertEquals( std::clock_t(0), s.frameDecodeSnapshot );
	assertEquals( std::clock_t(0), s.frameSuccessSnapshot );
}

TEST_CASE( "DecodeSessionTest/transferStatusTracksCurrentSessionAfterReset", "[unit]" )
{
	DecodeSession s;

	// --- session A: decode some frames, ending in a "full decode" state ---
	assertEquals( 0, s.tick(0, 0) ); // first frame samples a (0,0) baseline
	for (int i = 0; i < 31; ++i)     // frames 2..32 do not hit the sample gate
		s.tick(50, 50);
	assertEquals( 2, s.tick(100, 100) ); // frame 33 samples: decoded & perfect grew
	assertEquals( 2, s.transferStatus );

	// --- scan page closes ---
	s.reset();

	// --- session B: with cumulative counters also reset to 0 (see jni.cpp),
	// the first frame must report idle, not session A's stale "full decode" ---
	int fresh = s.tick(0, 0);
	assertEquals( 0, fresh );
	assertEquals( 1u, s.calls ); // gate fired on frame 1, proving calls was reset
	assertEquals( std::clock_t(0), s.frameDecodeSnapshot );
	assertEquals( std::clock_t(0), s.frameSuccessSnapshot );
}
