#pragma once

#include <memory>
#include <mutex>

// DecoderSession owns the single decoder instance that camera frames share.
//
// The Android camera delivers frames on a worker thread, while the activity is
// paused/destroyed on the UI thread. Without coordination, a frame that arrives
// *after* teardown has begun would lazily recreate the decoder (and its thread
// pool), leaking it once the activity is gone. To prevent that, shutdown() latches
// the session closed: while latched, get_or_create() returns nullptr instead of
// constructing a new decoder, so late frames are dropped. startup() re-arms the
// session for the next foreground session. All operations are mutex-guarded and
// idempotent.
template <typename DecoderT>
class DecoderSession
{
public:
	// Return the live decoder, lazily constructing it via `create` when needed.
	// `reuse(existing)` decides whether the current decoder can serve this frame;
	// when it returns false the decoder is rebuilt (mirrors set_mode() semantics).
	// Returns nullptr while the session is shut down -- the caller should drop the frame.
	template <typename ReuseFn, typename CreateFn>
	std::shared_ptr<DecoderT> get_or_create(ReuseFn&& reuse, CreateFn&& create)
	{
		std::lock_guard<std::mutex> lock(_mutex);
		if (_shutdown)
			return nullptr;
		if (!_proc or !reuse(*_proc))
			_proc = create();
		return _proc;
	}

	// Stop and release the decoder, then latch the session closed so a frame still
	// in flight cannot resurrect it. Idempotent: `stop` runs at most once per decoder.
	template <typename StopFn>
	void shutdown(StopFn&& stop)
	{
		std::lock_guard<std::mutex> lock(_mutex);
		_shutdown = true;
		if (_proc)
			stop(*_proc);
		_proc = nullptr;
	}

	// Re-arm the session so the next frame can construct a fresh decoder. Idempotent.
	void startup()
	{
		std::lock_guard<std::mutex> lock(_mutex);
		_shutdown = false;
	}

	bool is_shutdown() const
	{
		std::lock_guard<std::mutex> lock(_mutex);
		return _shutdown;
	}

protected:
	mutable std::mutex _mutex;
	std::shared_ptr<DecoderT> _proc;
	bool _shutdown = false;
};
