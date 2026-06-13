#include "unittest.h"

#include "DecoderSession.h"

#include <memory>

namespace {
	// Minimal stand-in for MultiThreadedDecoder: no OpenCV / thread pool needed.
	struct FakeDecoder
	{
		explicit FakeDecoder(int id) : id(id) {}
		int id;
	};
}

TEST_CASE( "DecoderSessionTest/testCreatesOnceAndReuses", "[unit]" )
{
	DecoderSession<FakeDecoder> session;
	int creates = 0;
	auto create = [&]() { ++creates; return std::make_shared<FakeDecoder>(7); };
	auto reuse = [](FakeDecoder&) { return true; };

	std::shared_ptr<FakeDecoder> first = session.get_or_create(reuse, create);
	assertTrue(first != nullptr);
	assertEquals(1, creates);

	std::shared_ptr<FakeDecoder> second = session.get_or_create(reuse, create);
	assertEquals(1, creates);                  // not recreated
	assertTrue(first.get() == second.get());   // same instance
}

TEST_CASE( "DecoderSessionTest/testRecreatesWhenReuseFails", "[unit]" )
{
	DecoderSession<FakeDecoder> session;
	int creates = 0;
	auto create = [&]() { ++creates; return std::make_shared<FakeDecoder>(creates); };

	std::shared_ptr<FakeDecoder> first = session.get_or_create([](FakeDecoder&) { return true; }, create);
	assertEquals(1, creates);

	std::shared_ptr<FakeDecoder> second = session.get_or_create([](FakeDecoder&) { return false; }, create);
	assertEquals(2, creates);
	assertTrue(first.get() != second.get());
}

TEST_CASE( "DecoderSessionTest/testShutdownDropsLateFrames", "[unit]" )
{
	// The core regression: after shutdown, a late camera frame must NOT resurrect
	// the decoder (which would leak a running thread pool).
	DecoderSession<FakeDecoder> session;
	int creates = 0;
	int stops = 0;
	auto create = [&]() { ++creates; return std::make_shared<FakeDecoder>(1); };
	auto reuse = [](FakeDecoder&) { return true; };

	session.get_or_create(reuse, create);
	assertEquals(1, creates);

	session.shutdown([&](FakeDecoder&) { ++stops; });
	assertEquals(1, stops);
	assertTrue(session.is_shutdown());

	std::shared_ptr<FakeDecoder> late = session.get_or_create(reuse, create);
	assertTrue(late == nullptr);               // frame dropped
	assertEquals(1, creates);                  // no resurrection
}

TEST_CASE( "DecoderSessionTest/testShutdownIsIdempotent", "[unit]" )
{
	DecoderSession<FakeDecoder> session;
	int stops = 0;
	session.get_or_create([](FakeDecoder&) { return true; },
	                      [&]() { return std::make_shared<FakeDecoder>(1); });

	session.shutdown([&](FakeDecoder&) { ++stops; });
	session.shutdown([&](FakeDecoder&) { ++stops; });
	assertEquals(1, stops);                    // second shutdown is a no-op
	assertTrue(session.is_shutdown());
}

TEST_CASE( "DecoderSessionTest/testShutdownWithNoDecoderIsSafe", "[unit]" )
{
	DecoderSession<FakeDecoder> session;
	int stops = 0;
	session.shutdown([&](FakeDecoder&) { ++stops; });
	assertEquals(0, stops);                     // nothing to stop
	assertTrue(session.is_shutdown());

	std::shared_ptr<FakeDecoder> dropped = session.get_or_create(
	    [](FakeDecoder&) { return true; },
	    []() { return std::make_shared<FakeDecoder>(1); });
	assertTrue(dropped == nullptr);
}

TEST_CASE( "DecoderSessionTest/testStartupReArmsSession", "[unit]" )
{
	DecoderSession<FakeDecoder> session;
	int creates = 0;
	auto create = [&]() { ++creates; return std::make_shared<FakeDecoder>(1); };
	auto reuse = [](FakeDecoder&) { return true; };

	session.get_or_create(reuse, create);
	session.shutdown([](FakeDecoder&) {});
	assertTrue(session.get_or_create(reuse, create) == nullptr);

	session.startup();
	assertFalse(session.is_shutdown());

	std::shared_ptr<FakeDecoder> revived = session.get_or_create(reuse, create);
	assertTrue(revived != nullptr);
	assertEquals(2, creates);                   // a fresh decoder after re-arm
}
