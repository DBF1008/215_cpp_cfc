package org.cimbar.camerafilecopy;

import java.io.File;
import java.util.LinkedList;

/**
 * Thread-safe state machine that serializes the "decode complete -> user save" pipeline.
 *
 * <p>The camera thread calls {@link #onFrameResult} when a file transfer completes.
 * The coordinator ensures that at most one save dialog is outstanding at any time;
 * additional completions are queued and presented to the user in FIFO order after
 * the current save finishes or is cancelled.</p>
 *
 * <p>All state mutations are guarded by a private lock. The listener callback is
 * always invoked <em>outside</em> the lock to prevent deadlocks.</p>
 */
public class SaveCoordinator {

    /** Observable states of the save pipeline. */
    public enum State {
        /** No pending saves; scanning normally. */
        IDLE,
        /** A decoded file is waiting for the user to pick a save location. */
        AWAITING_USER,
        /** The user picked a destination; the file copy is in progress. */
        SAVING
    }

    /**
     * Callback interface for SaveCoordinator events.
     * Implementations handle Android-specific work (showing the save dialog).
     */
    public interface SaveCoordinatorListener {
        /**
         * Called when the coordinator is ready to show a save dialog for a file.
         * The implementation MUST eventually call {@link #onSaveLocationAvailable}
         * or {@link #onSaveCancelled}.
         *
         * @param filename the original filename, suitable for use as EXTRA_TITLE
         */
        void requestSaveLocation(String filename);
    }

    /** One completed decode waiting to be saved. */
    private static class PendingFile {
        final String fullPath;
        final String filename;

        PendingFile(String fullPath, String filename) {
            this.fullPath = fullPath;
            this.filename = filename;
        }
    }

    private final Object lock = new Object();
    private State state = State.IDLE;
    private PendingFile currentFile = null;
    private final LinkedList<PendingFile> queue = new LinkedList<>();
    private final SaveCoordinatorListener listener;

    public SaveCoordinator(SaveCoordinatorListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.listener = listener;
    }

    // ---- Public API (callable from any thread) ----

    /**
     * Called when a frame decode completes and produces a file.
     * Safe to call from any thread (camera thread, etc.).
     *
     * @param fullPath absolute path to the temp file
     * @param filename just the filename component (for the save dialog title)
     */
    public void onFrameResult(String fullPath, String filename) {
        String filenameToRequest = null;

        synchronized (lock) {
            if (state == State.IDLE) {
                currentFile = new PendingFile(fullPath, filename);
                state = State.AWAITING_USER;
                filenameToRequest = filename;
            } else {
                // AWAITING_USER or SAVING -- queue it
                queue.add(new PendingFile(fullPath, filename));
            }
        }

        // Fire listener OUTSIDE the lock
        if (filenameToRequest != null) {
            listener.requestSaveLocation(filenameToRequest);
        }
    }

    /**
     * Called when the user picks a save destination.
     *
     * @param saveAction a Runnable that performs the actual file copy.
     *                   The coordinator runs it while in SAVING state,
     *                   then handles cleanup and queue drain.
     */
    public void onSaveLocationAvailable(Runnable saveAction) {
        PendingFile fileToSave;

        synchronized (lock) {
            if (state != State.AWAITING_USER) {
                // Spurious callback -- ignore
                return;
            }
            state = State.SAVING;
            fileToSave = currentFile;
        }

        // Perform I/O outside the lock
        if (saveAction != null) {
            saveAction.run();
        }

        // Cleanup and drain
        String nextFilename = null;
        synchronized (lock) {
            // Delete the temp file
            try {
                new File(fileToSave.fullPath).delete();
            } catch (Exception ignored) {
            }
            currentFile = null;
            state = State.IDLE;

            // Drain: if there are queued files, pick the next one
            if (!queue.isEmpty()) {
                currentFile = queue.removeFirst();
                state = State.AWAITING_USER;
                nextFilename = currentFile.filename;
            }
        }

        // Fire listener OUTSIDE the lock
        if (nextFilename != null) {
            listener.requestSaveLocation(nextFilename);
        }
    }

    /**
     * Called when the user cancels the save dialog.
     * Discards the current file (deletes the temp file) and drains the queue.
     */
    public void onSaveCancelled() {
        PendingFile cancelledFile;
        String nextFilename = null;

        synchronized (lock) {
            if (state != State.AWAITING_USER) {
                return;
            }
            cancelledFile = currentFile;
            currentFile = null;
            state = State.IDLE;

            // Delete the orphaned temp file
            try {
                new File(cancelledFile.fullPath).delete();
            } catch (Exception ignored) {
            }

            // Drain queue
            if (!queue.isEmpty()) {
                currentFile = queue.removeFirst();
                state = State.AWAITING_USER;
                nextFilename = currentFile.filename;
            }
        }

        // Fire listener OUTSIDE the lock
        if (nextFilename != null) {
            listener.requestSaveLocation(nextFilename);
        }
    }

    // ---- Query methods (thread-safe, for testing and observation) ----

    /** Returns the current state. */
    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    /** Returns the number of files waiting in the queue (not counting the current file). */
    public int getQueueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Returns the full path of the file currently being saved/shown, or null if IDLE. */
    public String getCurrentFullPath() {
        synchronized (lock) {
            return (currentFile != null) ? currentFile.fullPath : null;
        }
    }

    /** Returns the filename of the file currently being saved/shown, or null if IDLE. */
    public String getCurrentFilename() {
        synchronized (lock) {
            return (currentFile != null) ? currentFile.filename : null;
        }
    }
}
