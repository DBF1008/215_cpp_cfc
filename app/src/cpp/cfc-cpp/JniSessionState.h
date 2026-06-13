/* This code is subject to the terms of the Mozilla Public License, v.2.0. http://mozilla.org/MPL/2.0/. */
#pragma once

#include <set>
#include <string>
#include <ctime>

// Encapsulates the process-scoped session state that the JNI layer uses to
// track decode progress, completed files, and per-frame snapshots.
//
// Extracted from jni.cpp so that resetSessionState() can be regression-tested
// independently of the Android / JNI runtime.
struct JniSessionState
{
	std::set<std::string> completed;
	unsigned calls = 0;
	int transferStatus = 0;
	clock_t frameDecodeSnapshot = 0;
	clock_t frameSuccessSnapshot = 0;

	void reset()
	{
		completed.clear();
		calls = 0;
		transferStatus = 0;
		frameDecodeSnapshot = 0;
		frameSuccessSnapshot = 0;
	}

	bool isClean() const
	{
		return completed.empty()
			and calls == 0
			and transferStatus == 0
			and frameDecodeSnapshot == 0
			and frameSuccessSnapshot == 0;
	}
};
