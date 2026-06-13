#pragma once

namespace cfc {

// Picks the fountain mode to decode a single frame with.
//
// When `mode` is non-zero the caller has chosen an explicit mode and it is used
// verbatim. When `mode` is 0 (autodetect) the four supported modes are cycled
// deterministically based on a 1-based frame index, so that consecutive frames
// attempt 4 / 66 / 67 / 68 in turn.
inline int select_mode(int mode, unsigned long frameIndex)
{
	if (mode != 0)
		return mode;

	switch (frameIndex % 4)
	{
		case 1:  return 4;
		case 2:  return 66;
		case 3:  return 67;
		default: return 68;
	}
}

// Session-scoped driver for autodetect mode rotation.
//
// The frame counter that selects the autodetect mode lives here -- *not* in
// process-global state. Because each decoding session owns its own cycler, a
// brand new session always begins its rotation from frame 1 (mode 4), instead
// of inheriting whatever remainder the previous session happened to leave
// behind.
class AutoModeCycler
{
public:
	// Advances the session frame counter and returns the mode for this frame.
	// The counter advances on every frame (even for an explicit mode) so the
	// autodetect rotation tracks the absolute frame index within the session.
	int next(int mode)
	{
		++_frames;
		return select_mode(mode, _frames);
	}

	unsigned long frames() const { return _frames; }

private:
	unsigned long _frames = 0;
};

}
