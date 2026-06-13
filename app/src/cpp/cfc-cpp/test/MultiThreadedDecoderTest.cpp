#include "unittest.h"
#include "MultiThreadedDecoder.h"

#include <filesystem>
#include <memory>
#include <string>

namespace {
	std::string makeTempDir(const std::string& suffix)
	{
		std::string path = std::filesystem::temp_directory_path().string() + "/cfc_test_" + suffix;
		std::filesystem::create_directories(path);
		return path;
	}
}

TEST_CASE( "MultiThreadedDecoder/newInstanceHasZeroCounters", "[unit]" )
{
	std::string tmpDir = makeTempDir("zero");
	MultiThreadedDecoder dec(tmpDir, 4);

	assertEquals( 0, dec.count );
	assertEquals( 0, dec.bytes );
	assertEquals( 0, dec.perfect );
	assertEquals( 0, dec.decoded );
	assertEquals( 0, dec.decodeTicks );
	assertEquals( 0, dec.scanned );
	assertEquals( 0, dec.scanTicks );
	assertEquals( 0, dec.extractTicks );

	dec.stop();
	std::filesystem::remove_all(tmpDir);
}

TEST_CASE( "MultiThreadedDecoder/countersAreInstanceLevel", "[unit]" )
{
	std::string tmpDir1 = makeTempDir("inst1");
	std::string tmpDir2 = makeTempDir("inst2");

	auto dec1 = std::make_unique<MultiThreadedDecoder>(tmpDir1, 4);

	// manually bump dec1's counters to simulate a previous session
	dec1->count = 100;
	dec1->bytes = 5000;
	dec1->perfect = 42;
	dec1->decoded = 80;
	dec1->decodeTicks = 999;
	dec1->scanned = 120;
	dec1->scanTicks = 777;
	dec1->extractTicks = 333;

	// create a second instance — it must start from zero, not inherit dec1's values
	MultiThreadedDecoder dec2(tmpDir2, 4);

	assertEquals( 0, dec2.count );
	assertEquals( 0, dec2.bytes );
	assertEquals( 0, dec2.perfect );
	assertEquals( 0, dec2.decoded );
	assertEquals( 0, dec2.decodeTicks );
	assertEquals( 0, dec2.scanned );
	assertEquals( 0, dec2.scanTicks );
	assertEquals( 0, dec2.extractTicks );

	// dec1's counters must be unchanged by dec2's creation
	assertEquals( 100, dec1->count );
	assertEquals( 5000, dec1->bytes );
	assertEquals( 42, dec1->perfect );
	assertEquals( 80, dec1->decoded );

	dec1->stop();
	dec2.stop();
	std::filesystem::remove_all(tmpDir1);
	std::filesystem::remove_all(tmpDir2);
}

TEST_CASE( "MultiThreadedDecoder/resetStatsClearsCounters", "[unit]" )
{
	std::string tmpDir = makeTempDir("reset");
	MultiThreadedDecoder dec(tmpDir, 4);

	// bump counters
	dec.count = 50;
	dec.bytes = 2000;
	dec.perfect = 10;
	dec.decoded = 30;
	dec.decodeTicks = 500;
	dec.scanned = 60;
	dec.scanTicks = 400;
	dec.extractTicks = 200;

	dec.reset_stats();

	assertEquals( 0, dec.count );
	assertEquals( 0, dec.bytes );
	assertEquals( 0, dec.perfect );
	assertEquals( 0, dec.decoded );
	assertEquals( 0, dec.decodeTicks );
	assertEquals( 0, dec.scanned );
	assertEquals( 0, dec.scanTicks );
	assertEquals( 0, dec.extractTicks );

	dec.stop();
	std::filesystem::remove_all(tmpDir);
}
