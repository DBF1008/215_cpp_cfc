package org.cimbar.camerafilecopy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Regression tests for {@link SaveCoordinator}.
 *
 * These tests verify the thread-safe state machine that serializes
 * "decode complete -> user save" in the CFC scanner activity.
 */
public class SaveCoordinatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /** Records listener invocations for assertion. */
    private static class FakeListener implements SaveCoordinator.SaveCoordinatorListener {
        final List<String> requestedSaves = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void requestSaveLocation(String filename) {
            requestedSaves.add(filename);
        }
    }

    private FakeListener listener;
    private SaveCoordinator coordinator;

    @Before
    public void setUp() {
        listener = new FakeListener();
        coordinator = new SaveCoordinator(listener);
    }

    // ---- Basic state machine tests ----

    @Test
    public void testInitialState() {
        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
        assertEquals(0, coordinator.getQueueSize());
        assertNull(coordinator.getCurrentFullPath());
        assertNull(coordinator.getCurrentFilename());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullListenerRejected() {
        new SaveCoordinator(null);
    }

    @Test
    public void testSingleFileTransitionToAwaitingUser() {
        coordinator.onFrameResult("/tmp/file.bin", "file.bin");

        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals(0, coordinator.getQueueSize());
        assertEquals("/tmp/file.bin", coordinator.getCurrentFullPath());
        assertEquals("file.bin", coordinator.getCurrentFilename());
        assertEquals(1, listener.requestedSaves.size());
        assertEquals("file.bin", listener.requestedSaves.get(0));
    }

    @Test
    public void testFullSingleFileFlow() throws IOException {
        File tmpFile = tempFolder.newFile("transfer.bin");
        assertTrue(tmpFile.exists());

        coordinator.onFrameResult(tmpFile.getAbsolutePath(), "transfer.bin");
        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());

        // Simulate user picking a save location
        final AtomicBoolean copyRan = new AtomicBoolean(false);
        coordinator.onSaveLocationAvailable(new Runnable() {
            @Override
            public void run() {
                copyRan.set(true);
            }
        });

        assertTrue("Copy runnable should have executed", copyRan.get());
        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
        assertNull(coordinator.getCurrentFullPath());
        assertFalse("Temp file should be deleted after save", tmpFile.exists());
        assertEquals(1, listener.requestedSaves.size());
    }

    // ---- Queue accumulation tests ----

    @Test
    public void testQueueAccumulatesDuringAwaitingUser() {
        coordinator.onFrameResult("/tmp/a.bin", "a.bin");
        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals(1, listener.requestedSaves.size());

        // Two more files arrive while dialog is open
        coordinator.onFrameResult("/tmp/b.bin", "b.bin");
        coordinator.onFrameResult("/tmp/c.bin", "c.bin");

        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals(2, coordinator.getQueueSize());
        // Listener should NOT have been called again
        assertEquals(1, listener.requestedSaves.size());
        // Current file is still the first one
        assertEquals("a.bin", coordinator.getCurrentFilename());
    }

    @Test
    public void testQueueAccumulatesDuringSaving() {
        coordinator.onFrameResult("/tmp/a.bin", "a.bin");

        // Start saving (but don't finish yet — we're outside the lock during the Runnable)
        final CountDownLatch saveStarted = new CountDownLatch(1);
        final CountDownLatch saveCanFinish = new CountDownLatch(1);

        new Thread(new Runnable() {
            @Override
            public void run() {
                coordinator.onSaveLocationAvailable(new Runnable() {
                    @Override
                    public void run() {
                        saveStarted.countDown();
                        try {
                            saveCanFinish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }).start();

        try {
            assertTrue("Save should start", saveStarted.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("Interrupted waiting for save to start");
        }

        // At this point, state is SAVING
        assertEquals(SaveCoordinator.State.SAVING, coordinator.getState());

        // New file arrives during SAVING
        coordinator.onFrameResult("/tmp/b.bin", "b.bin");
        assertEquals(1, coordinator.getQueueSize());

        // Let the save finish
        saveCanFinish.countDown();
    }

    // ---- Queue drain tests ----

    @Test
    public void testQueueDrainsAfterSave() throws IOException {
        File fileA = tempFolder.newFile("a.bin");
        File fileB = tempFolder.newFile("b.bin");
        File fileC = tempFolder.newFile("c.bin");

        coordinator.onFrameResult(fileA.getAbsolutePath(), "a.bin");
        coordinator.onFrameResult(fileB.getAbsolutePath(), "b.bin");
        coordinator.onFrameResult(fileC.getAbsolutePath(), "c.bin");

        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals(2, coordinator.getQueueSize());
        assertEquals(1, listener.requestedSaves.size());

        // Save file A
        coordinator.onSaveLocationAvailable(new Runnable() {
            @Override
            public void run() {
                // no-op in test
            }
        });

        // After saving A, coordinator should auto-drain to B
        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals(fileB.getAbsolutePath(), coordinator.getCurrentFullPath());
        assertEquals("b.bin", coordinator.getCurrentFilename());
        assertEquals(1, coordinator.getQueueSize()); // C still queued
        assertEquals(2, listener.requestedSaves.size());
        assertEquals("b.bin", listener.requestedSaves.get(1));
        assertFalse("Temp file A should be deleted", fileA.exists());
    }

    @Test
    public void testFullQueueDrain() throws IOException {
        File fileA = tempFolder.newFile("a.bin");
        File fileB = tempFolder.newFile("b.bin");
        File fileC = tempFolder.newFile("c.bin");

        coordinator.onFrameResult(fileA.getAbsolutePath(), "a.bin");
        coordinator.onFrameResult(fileB.getAbsolutePath(), "b.bin");
        coordinator.onFrameResult(fileC.getAbsolutePath(), "c.bin");

        Runnable noopCopy = new Runnable() {
            @Override
            public void run() {
                // no-op
            }
        };

        // Save A -> auto-drain to B
        coordinator.onSaveLocationAvailable(noopCopy);
        assertEquals("b.bin", coordinator.getCurrentFilename());
        assertEquals(1, coordinator.getQueueSize());

        // Save B -> auto-drain to C
        coordinator.onSaveLocationAvailable(noopCopy);
        assertEquals("c.bin", coordinator.getCurrentFilename());
        assertEquals(0, coordinator.getQueueSize());

        // Save C -> IDLE (queue empty)
        coordinator.onSaveLocationAvailable(noopCopy);
        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
        assertNull(coordinator.getCurrentFullPath());

        // Listener called exactly 3 times, in order
        assertEquals(3, listener.requestedSaves.size());
        assertEquals("a.bin", listener.requestedSaves.get(0));
        assertEquals("b.bin", listener.requestedSaves.get(1));
        assertEquals("c.bin", listener.requestedSaves.get(2));

        // All temp files deleted
        assertFalse(fileA.exists());
        assertFalse(fileB.exists());
        assertFalse(fileC.exists());
    }

    // ---- Cancel tests ----

    @Test
    public void testCancelDiscardsCurrentAndDeletesTemp() throws IOException {
        File tmpFile = tempFolder.newFile("discard.bin");

        coordinator.onFrameResult(tmpFile.getAbsolutePath(), "discard.bin");
        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());

        coordinator.onSaveCancelled();

        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
        assertNull(coordinator.getCurrentFullPath());
        assertFalse("Temp file should be deleted on cancel", tmpFile.exists());
    }

    @Test
    public void testCancelDrainsQueue() throws IOException {
        File fileA = tempFolder.newFile("a.bin");
        File fileB = tempFolder.newFile("b.bin");

        coordinator.onFrameResult(fileA.getAbsolutePath(), "a.bin");
        coordinator.onFrameResult(fileB.getAbsolutePath(), "b.bin");

        // Cancel file A -> should drain to file B
        coordinator.onSaveCancelled();

        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals("b.bin", coordinator.getCurrentFilename());
        assertEquals(0, coordinator.getQueueSize());
        assertFalse("Cancelled file A should be deleted", fileA.exists());

        // Listener was called for A initially, then for B after cancel drain
        assertEquals(2, listener.requestedSaves.size());
        assertEquals("a.bin", listener.requestedSaves.get(0));
        assertEquals("b.bin", listener.requestedSaves.get(1));
    }

    // ---- No-op guard tests ----

    @Test
    public void testCancelWhileIdleIsNoop() {
        coordinator.onSaveCancelled();
        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
        assertEquals(0, listener.requestedSaves.size());
    }

    @Test
    public void testSaveLocationAvailableWhileIdleIsNoop() {
        final AtomicBoolean copyRan = new AtomicBoolean(false);
        coordinator.onSaveLocationAvailable(new Runnable() {
            @Override
            public void run() {
                copyRan.set(true);
            }
        });

        assertFalse("Copy runnable should NOT execute when state is IDLE", copyRan.get());
        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
    }

    @Test
    public void testSaveLocationAvailableWhileSavingIsNoop() {
        coordinator.onFrameResult("/tmp/a.bin", "a.bin");

        // Start saving in a way that blocks
        final CountDownLatch saveStarted = new CountDownLatch(1);
        final CountDownLatch saveCanFinish = new CountDownLatch(1);

        new Thread(new Runnable() {
            @Override
            public void run() {
                coordinator.onSaveLocationAvailable(new Runnable() {
                    @Override
                    public void run() {
                        saveStarted.countDown();
                        try {
                            saveCanFinish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }).start();

        try {
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("Interrupted");
        }

        // Try calling onSaveLocationAvailable again while SAVING — should be ignored
        final AtomicBoolean spuriousRan = new AtomicBoolean(false);
        coordinator.onSaveLocationAvailable(new Runnable() {
            @Override
            public void run() {
                spuriousRan.set(true);
            }
        });
        assertFalse("Spurious save callback should be ignored during SAVING", spuriousRan.get());

        // Clean up: let the original save finish
        saveCanFinish.countDown();
    }

    // ---- Temp file deletion tests ----

    @Test
    public void testTempFileDeletedOnSave() throws IOException {
        File tmpFile = tempFolder.newFile("writeme.bin");
        FileWriter writer = new FileWriter(tmpFile);
        writer.write("test data");
        writer.close();
        assertTrue(tmpFile.exists());
        assertTrue(tmpFile.length() > 0);

        coordinator.onFrameResult(tmpFile.getAbsolutePath(), "writeme.bin");
        coordinator.onSaveLocationAvailable(new Runnable() {
            @Override
            public void run() {
                // simulate copy
            }
        });

        assertFalse("Temp file must be deleted after save", tmpFile.exists());
    }

    @Test
    public void testTempFileDeletedOnCancel() throws IOException {
        File tmpFile = tempFolder.newFile("cancelme.bin");
        FileWriter writer = new FileWriter(tmpFile);
        writer.write("unwanted data");
        writer.close();
        assertTrue(tmpFile.exists());

        coordinator.onFrameResult(tmpFile.getAbsolutePath(), "cancelme.bin");
        coordinator.onSaveCancelled();

        assertFalse("Temp file must be deleted on cancel", tmpFile.exists());
    }

    // ---- Concurrency tests ----

    @Test
    public void testConcurrentSubmissions() throws Exception {
        final int THREADS = 10;
        final int FILES_PER_THREAD = 100;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startLatch.await();
                        for (int i = 0; i < FILES_PER_THREAD; i++) {
                            coordinator.onFrameResult(
                                "/tmp/fake_" + threadId + "_" + i,
                                "fake_" + threadId + "_" + i
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }
            }).start();
        }

        startLatch.countDown(); // fire all threads simultaneously
        assertTrue("Threads did not finish in time", doneLatch.await(10, TimeUnit.SECONDS));

        int total = THREADS * FILES_PER_THREAD;
        int accounted = coordinator.getQueueSize()
                      + (coordinator.getState() == SaveCoordinator.State.AWAITING_USER ? 1 : 0);
        assertEquals("All files must be accounted for (queued or current)", total, accounted);
        assertEquals("Only one dialog should have been shown", 1, listener.requestedSaves.size());
    }

    @Test
    public void testConcurrentSubmitAndSave() throws Exception {
        final int TOTAL_FILES = 200;
        final AtomicInteger filesProcessed = new AtomicInteger(0);
        final CountDownLatch allDone = new CountDownLatch(1);

        // Producer: submit files from multiple threads
        ExecutorService producers = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            final int threadId = t;
            producers.submit(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < TOTAL_FILES / 4; i++) {
                        coordinator.onFrameResult(
                            "/tmp/concurrent_" + threadId + "_" + i,
                            "concurrent_" + threadId + "_" + i
                        );
                    }
                }
            });
        }

        // Consumer: process saves in a loop
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                Runnable copyAction = new Runnable() {
                    @Override
                    public void run() {
                        filesProcessed.incrementAndGet();
                    }
                };
                while (filesProcessed.get() < TOTAL_FILES) {
                    if (coordinator.getState() == SaveCoordinator.State.AWAITING_USER) {
                        coordinator.onSaveLocationAvailable(copyAction);
                    } else {
                        Thread.yield();
                    }
                }
                allDone.countDown();
            }
        });
        consumer.start();

        assertTrue("All files should be processed within timeout",
                   allDone.await(30, TimeUnit.SECONDS));
        assertEquals(TOTAL_FILES, filesProcessed.get());
        assertEquals(SaveCoordinator.State.IDLE, coordinator.getState());
        assertEquals(0, coordinator.getQueueSize());

        producers.shutdown();
        assertTrue("Producers should finish", producers.awaitTermination(10, TimeUnit.SECONDS));
    }

    // ---- Listener called outside lock test ----

    @Test
    public void testListenerCalledOutsideLock() {
        // This test verifies the listener can call back into the coordinator
        // without deadlocking. If the lock were held during the callback,
        // the nested call would block forever.
        SaveCoordinator.SaveCoordinatorListener recursiveListener =
            new SaveCoordinator.SaveCoordinatorListener() {
                @Override
                public void requestSaveLocation(String filename) {
                    // Try to read state from within the callback.
                    // If the lock were held, getState() would deadlock.
                    SaveCoordinator.State s = coordinator.getState();
                    // State should be AWAITING_USER (set before callback fires)
                    assertEquals(SaveCoordinator.State.AWAITING_USER, s);
                }
            };

        SaveCoordinator testCoordinator = new SaveCoordinator(recursiveListener);
        testCoordinator.onFrameResult("/tmp/test.bin", "test.bin");
        // If we get here without hanging, the test passes
        assertEquals(SaveCoordinator.State.AWAITING_USER, testCoordinator.getState());
    }

    // ---- Edge case: frame result while save is completing ----

    @Test
    public void testFrameResultDuringSaveGetsQueued() throws IOException {
        File fileA = tempFolder.newFile("a.bin");

        coordinator.onFrameResult(fileA.getAbsolutePath(), "a.bin");

        // Start save in background
        final CountDownLatch saveStarted = new CountDownLatch(1);
        final CountDownLatch saveCanFinish = new CountDownLatch(1);

        new Thread(new Runnable() {
            @Override
            public void run() {
                coordinator.onSaveLocationAvailable(new Runnable() {
                    @Override
                    public void run() {
                        saveStarted.countDown();
                        try {
                            saveCanFinish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }).start();

        try {
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("Interrupted");
        }

        // New file arrives while SAVING
        coordinator.onFrameResult("/tmp/b.bin", "b.bin");
        assertEquals(SaveCoordinator.State.SAVING, coordinator.getState());
        assertEquals(1, coordinator.getQueueSize());

        // Let save finish -> should drain to b.bin
        saveCanFinish.countDown();

        // Give a moment for the background thread to finish cleanup
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertEquals(SaveCoordinator.State.AWAITING_USER, coordinator.getState());
        assertEquals("b.bin", coordinator.getCurrentFilename());
        assertEquals(2, listener.requestedSaves.size());
    }

    // ---- Regression: the original bug scenario ----

    @Test
    public void testRegressionRapidDoubleCompletion() {
        // Simulates the original bug: two files complete rapidly before user responds.
        // Before the fix: activePath would be overwritten, first file lost.
        // After the fix: first file is presented, second is queued.

        coordinator.onFrameResult("/data/files/transfer_001.bin", "transfer_001.bin");
        coordinator.onFrameResult("/data/files/transfer_002.bin", "transfer_002.bin");

        // Only the first file should trigger a save dialog
        assertEquals(1, listener.requestedSaves.size());
        assertEquals("transfer_001.bin", listener.requestedSaves.get(0));

        // The second file should be queued, not lost
        assertEquals(1, coordinator.getQueueSize());
        assertEquals("transfer_001.bin", coordinator.getCurrentFilename());
        assertEquals("/data/files/transfer_001.bin", coordinator.getCurrentFullPath());
    }
}
