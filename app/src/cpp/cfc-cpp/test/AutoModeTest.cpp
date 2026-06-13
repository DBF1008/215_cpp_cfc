/* This code is subject to the terms of the Mozilla Public License, v.2.0. http://mozilla.org/MPL/2.0/. */
#include "unittest.h"

#include "AutoMode.h"

#include <vector>

TEST_CASE( "AutoModeTest/testExplicitModePassesThrough", "[unit]" )
{
	// An explicit (non-zero) mode is always used verbatim, regardless of frame index.
	assertEquals( 4, cfc::select_mode(4, 1) );
	assertEquals( 4, cfc::select_mode(4, 2) );
	assertEquals( 66, cfc::select_mode(66, 3) );
	assertEquals( 67, cfc::select_mode(67, 4) );
	assertEquals( 68, cfc::select_mode(68, 100) );
}

TEST_CASE( "AutoModeTest/testAutodetectCyclesFourModes", "[unit]" )
{
	// Autodetect (mode 0) deterministically cycles 4 / 66 / 67 / 68 by 1-based frame index.
	assertEquals( 4,  cfc::select_mode(0, 1) );
	assertEquals( 66, cfc::select_mode(0, 2) );
	assertEquals( 67, cfc::select_mode(0, 3) );
	assertEquals( 68, cfc::select_mode(0, 4) );
	assertEquals( 4,  cfc::select_mode(0, 5) );
	assertEquals( 66, cfc::select_mode(0, 6) );
	assertEquals( 67, cfc::select_mode(0, 7) );
	assertEquals( 68, cfc::select_mode(0, 8) );
}

TEST_CASE( "AutoModeTest/testCyclerStartsAtModeFour", "[unit]" )
{
	// A fresh cycler counts from zero and its very first autodetect frame is mode 4.
	cfc::AutoModeCycler cycler;
	assertEquals( 0, (int)cycler.frames() );
	assertEquals( 4, cycler.next(0) );
	assertEquals( 1, (int)cycler.frames() );
}

TEST_CASE( "AutoModeTest/testCyclerAdvancesEvenForExplicitMode", "[unit]" )
{
	// The frame counter advances on every frame, even when an explicit mode is used,
	// so the autodetect rotation tracks the absolute frame index within the session.
	cfc::AutoModeCycler cycler;
	assertEquals( 4, cycler.next(4) );   // frame 1, explicit
	assertEquals( 4, cycler.next(4) );   // frame 2, explicit
	assertEquals( 67, cycler.next(0) );  // frame 3, autodetect -> 3 % 4 == 3 -> 67
}

TEST_CASE( "AutoModeTest/testNewSessionRestartsRotation", "[unit]" )
{
	// Regression: each session owns its own cycler, so a brand new session restarts
	// the rotation from frame 1 (mode 4) no matter how far a previous session ran.
	// Before the fix the frame counter was process-global (inline static), so a new
	// session would resume the rotation from a stale remainder.
	cfc::AutoModeCycler previous;
	for (int i = 0; i < 7; ++i) // advance the previous session by an arbitrary amount
		previous.next(0);
	assertEquals( 7, (int)previous.frames() );

	cfc::AutoModeCycler fresh; // new session
	std::vector<int> got;
	for (int i = 0; i < 5; ++i)
		got.push_back( fresh.next(0) );

	std::vector<int> expected({4, 66, 67, 68, 4});
	assertEquals( expected, got );
}
