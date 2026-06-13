#pragma once

#include <ctime>
#include <set>
#include <string>

// Per-scan-session state for the cfc JNI layer.
//
// The JNI bridge keeps a single instance of this for the lifetime of the
// loaded native library. Because the library outlives any individual scan
// page, this state MUST be cleared (see reset()) whenever a scanning session
// ends -- otherwise a freshly opened scan page inherits the previous session's
// already-completed file names and stale transfer-status snapshots. Symptoms of
// skipping the reset: a new file whose name happens to match an earlier one is
// silently dropped, and the guidance border/progress reflect the old session.
struct DecodeSession
{
	// File names already surfaced to the user this session (dedup set).
	std::set<std::string> completed;

	// Number of frames processed this session.
	unsigned calls = 0;

	// 0 == idle, 1 == partial decode, 2 == full decode (as of last sample).
	int transferStatus = 0;

	// Cumulative decode/perfect counts captured at the previous sample, used to
	// detect progress within the current session.
	std::clock_t frameDecodeSnapshot = 0;
	std::clock_t frameSuccessSnapshot = 0;

	// Clears every field so the next session starts from a clean slate.
	void reset()
	{
		completed.clear();
		calls = 0;
		transferStatus = 0;
		frameDecodeSnapshot = 0;
		frameSuccessSnapshot = 0;
	}

	// Records a finished file name. Returns true the first time a name is seen
	// this session (i.e. it should be reported to the user), false if it was
	// already reported.
	bool mark_completed(const std::string& name)
	{
		return completed.insert(name).second;
	}

	// Advances the frame counter and, once every 32 frames, refreshes
	// transferStatus from the cumulative decode/perfect counts. Returns the
	// current transferStatus.
	int tick(std::clock_t decoded, std::clock_t perfect)
	{
		++calls;
		if ((calls & 31) == 1)
		{
			transferStatus = (perfect > frameSuccessSnapshot? 1 : 0)
			               + (decoded > frameDecodeSnapshot? 1 : 0);
			frameDecodeSnapshot = decoded;
			frameSuccessSnapshot = perfect;
		}
		return transferStatus;
	}
};
