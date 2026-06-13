package org.cimbar.camerafilecopy;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serializes the "a file finished transferring -> ask the user where to save it -> resume
 * receiving the next file" hand-off between the camera thread and the UI thread.
 *
 * <p>Why this exists:
 * <ul>
 *   <li><b>No dropped files.</b> The native decoder surfaces each completed file to Java exactly
 *       once (it remembers what it has already reported). If a second file finishes while the user
 *       is still picking a destination for the first, we cannot simply ignore it or it would be lost
 *       forever. Completions are therefore queued, never discarded.</li>
 *   <li><b>One dialog at a time.</b> Only a single save chooser is launched at once. Further
 *       completions wait their turn instead of stacking duplicate system dialogs or clobbering the
 *       path of the file currently being saved.</li>
 *   <li><b>Thread-safe & observable.</b> The camera thread (producer) and the UI thread (consumer)
 *       touch the shared state only under this object's monitor, and the current state is observable
 *       via {@link #isSaving()}, {@link #pendingCount()} and {@link #activePath()} so the flow can be
 *       reasoned about and unit-tested deterministically.</li>
 * </ul>
 *
 * <p>The {@link SaveLauncher} is always invoked <em>outside</em> the lock so that launching the save
 * UI never runs while the monitor is held.
 */
public class PendingSaveQueue {

    /** Callback used to actually present the save destination chooser for a queued temp file. */
    public interface SaveLauncher {
        /**
         * Show the save chooser for {@code tempFilePath}. Exactly one call to
         * {@link PendingSaveQueue#onSaveResolved()} is expected once the chooser resolves.
         */
        void launchSave(String tempFilePath);
    }

    private final SaveLauncher launcher;
    private final Deque<String> pending = new ArrayDeque<>();
    private boolean saving = false;
    private String activePath = null;

    public PendingSaveQueue(SaveLauncher launcher) {
        if (launcher == null)
            throw new IllegalArgumentException("launcher must not be null");
        this.launcher = launcher;
    }

    /**
     * Enqueue a fully-received file (identified by its temp file path) for saving. Safe to call from
     * any thread, typically the camera/decoder thread. If no save is currently in flight this kicks
     * off the chooser for this file; otherwise it waits behind the in-flight save.
     */
    public void offer(String tempFilePath) {
        if (tempFilePath == null)
            return;
        String toLaunch;
        synchronized (this) {
            pending.addLast(tempFilePath);
            toLaunch = pollNextLocked();
        }
        if (toLaunch != null)
            launcher.launchSave(toLaunch);
    }

    /**
     * Signal that the in-flight save chooser has resolved (saved, cancelled, or failed). Releases the
     * gate and immediately advances to the next queued file, if any. Typically called from the UI
     * thread once the chooser returns.
     */
    public void onSaveResolved() {
        String toLaunch;
        synchronized (this) {
            saving = false;
            activePath = null;
            toLaunch = pollNextLocked();
        }
        if (toLaunch != null)
            launcher.launchSave(toLaunch);
    }

    /** The temp file path of the save currently being shown to the user, or {@code null} if none. */
    public synchronized String activePath() {
        return activePath;
    }

    /** Whether a save chooser is currently in flight. */
    public synchronized boolean isSaving() {
        return saving;
    }

    /** Number of completed files waiting behind the in-flight save. */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /**
     * If nothing is in flight, claim the gate for the next queued file and return it (to be launched
     * by the caller outside the lock). Returns {@code null} if a save is already in flight or the
     * queue is empty. Must be called while holding this object's monitor.
     */
    private String pollNextLocked() {
        if (saving)
            return null;
        String next = pending.pollFirst();
        if (next == null)
            return null;
        saving = true;
        activePath = next;
        return next;
    }
}
