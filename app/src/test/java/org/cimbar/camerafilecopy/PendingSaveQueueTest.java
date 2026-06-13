package org.cimbar.camerafilecopy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression tests for {@link PendingSaveQueue}, the serialization layer behind cfc's
 * "file received -> ask user where to save -> resume" flow.
 *
 * <p>These pin the behaviour the defect fix introduced:
 * <ul>
 *   <li>only one save chooser is launched at a time (no stacked/duplicate dialogs),</li>
 *   <li>completions that arrive mid-save are queued, never dropped (the native side reports each
 *       file only once, so dropping would lose data),</li>
 *   <li>the queue drains in FIFO order once each save resolves, and</li>
 *   <li>all of the above hold under concurrent producers.</li>
 * </ul>
 */
public class PendingSaveQueueTest {

    /** Records the paths handed to launchSave, in order. */
    private static final class RecordingLauncher implements PendingSaveQueue.SaveLauncher {
        final List<String> launched = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void launchSave(String tempFilePath) {
            launched.add(tempFilePath);
        }
    }

    @Test
    public void firstCompletionLaunchesImmediately() {
        RecordingLauncher launcher = new RecordingLauncher();
        PendingSaveQueue queue = new PendingSaveQueue(launcher);

        queue.offer("/data/a");

        assertEquals(1, launcher.launched.size());
        assertEquals("/data/a", launcher.launched.get(0));
        assertTrue(queue.isSaving());
        assertEquals("/data/a", queue.activePath());
        assertEquals(0, queue.pendingCount());
    }

    @Test
    public void completionsDuringActiveSaveAreQueuedNotLaunched() {
        RecordingLauncher launcher = new RecordingLauncher();
        PendingSaveQueue queue = new PendingSaveQueue(launcher);

        queue.offer("/data/a"); // launches immediately
        queue.offer("/data/b"); // arrives while 'a' is still being saved
        queue.offer("/data/c");

        // Only one dialog so far; the others wait in line and 'a' is still the active file.
        assertEquals(1, launcher.launched.size());
        assertEquals("/data/a", queue.activePath());
        assertEquals(2, queue.pendingCount());
    }

    @Test
    public void queueDrainsInFifoOrderAsSavesResolve() {
        RecordingLauncher launcher = new RecordingLauncher();
        PendingSaveQueue queue = new PendingSaveQueue(launcher);

        queue.offer("/data/a");
        queue.offer("/data/b");
        queue.offer("/data/c");

        queue.onSaveResolved(); // 'a' done -> 'b' shown
        assertEquals("/data/b", queue.activePath());
        assertEquals(1, queue.pendingCount());

        queue.onSaveResolved(); // 'b' done -> 'c' shown
        assertEquals("/data/c", queue.activePath());
        assertEquals(0, queue.pendingCount());

        queue.onSaveResolved(); // 'c' done -> idle
        assertFalse(queue.isSaving());
        assertNull(queue.activePath());

        assertEquals(java.util.Arrays.asList("/data/a", "/data/b", "/data/c"), launcher.launched);
    }

    @Test
    public void resolveWhenIdleIsNoOp() {
        RecordingLauncher launcher = new RecordingLauncher();
        PendingSaveQueue queue = new PendingSaveQueue(launcher);

        queue.onSaveResolved(); // nothing in flight

        assertFalse(queue.isSaving());
        assertNull(queue.activePath());
        assertEquals(0, launcher.launched.size());
    }

    @Test
    public void nullOfferIsIgnored() {
        RecordingLauncher launcher = new RecordingLauncher();
        PendingSaveQueue queue = new PendingSaveQueue(launcher);

        queue.offer(null);

        assertFalse(queue.isSaving());
        assertEquals(0, launcher.launched.size());
        assertEquals(0, queue.pendingCount());
    }

    /**
     * Hammer the queue from many producer threads while a single consumer drains it, mirroring the
     * camera thread (producer) / UI thread (consumer) split. Verifies the gate is never violated
     * (no two saves active at once) and that no completion is ever dropped or duplicated.
     */
    @Test
    public void concurrentProducersNeverDropOrDoubleLaunch() throws Exception {
        final int producers = 8;
        final int perProducer = 500;
        final int total = producers * perProducer;

        final LinkedBlockingQueue<String> launchedQ = new LinkedBlockingQueue<>();
        final AtomicInteger concurrentActive = new AtomicInteger(0);
        final AtomicBoolean gateViolated = new AtomicBoolean(false);

        PendingSaveQueue queue = new PendingSaveQueue(new PendingSaveQueue.SaveLauncher() {
            @Override
            public void launchSave(String tempFilePath) {
                // launchSave must only ever be called when no other save is active.
                if (concurrentActive.incrementAndGet() != 1)
                    gateViolated.set(true);
                launchedQ.add(tempFilePath);
            }
        });

        // Consumer: behaves like the UI handling each chooser result, one at a time.
        final Set<String> consumed = new HashSet<>();
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < total; i++) {
                        String path = launchedQ.poll(10, TimeUnit.SECONDS);
                        if (path == null)
                            return; // timed out -> test will fail on the size assertion below
                        consumed.add(path);
                        concurrentActive.decrementAndGet();
                        queue.onSaveResolved();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "save-consumer");
        consumer.start();

        // Producers: each offers a disjoint, unique set of paths.
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> producerThreads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perProducer; i++)
                        queue.offer("/data/file-" + (base + i));
                }
            }, "save-producer-" + p);
            producerThreads.add(t);
            t.start();
        }

        start.countDown();
        for (Thread t : producerThreads)
            t.join(30_000);
        consumer.join(30_000);

        assertFalse("two saves were active at once", gateViolated.get());
        assertEquals("a completed file was dropped or duplicated", total, consumed.size());

        // Build the full expected set and confirm exact coverage (no drops, no extras).
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < total; i++)
            expected.add("/data/file-" + i);
        assertEquals(expected, consumed);

        // Everything drained: idle and empty.
        assertFalse(queue.isSaving());
        assertNull(queue.activePath());
        assertEquals(0, queue.pendingCount());
        assertEquals(0, concurrentActive.get());
    }
}
