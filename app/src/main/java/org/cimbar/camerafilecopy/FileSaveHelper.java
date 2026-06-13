package org.cimbar.camerafilecopy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Safely persists a received temp file to a user-chosen destination.
 *
 * <p>This logic lives in its own dependency-free class (no Android imports) so it can
 * be unit tested on a plain JVM. The invariant it guarantees is the whole point of the
 * class: the source temp file is deleted <em>only</em> when the copy fully succeeds,
 * including the final close. If anything goes wrong the temp file is left untouched so
 * the received transfer is not lost and can be retried.
 */
final class FileSaveHelper {

    private static final int BUFFER_SIZE = 8192;

    private FileSaveHelper() {
    }

    /**
     * Copy the file at {@code sourcePath} into {@code dest}, taking ownership of closing
     * {@code dest}. The source file is deleted only if every step succeeds.
     *
     * @param sourcePath path to the temp file holding the received data; may be {@code null}
     * @param dest       destination stream (e.g. one opened from a content {@code Uri});
     *                   may be {@code null}
     * @return {@code true} only if the destination received the full file and was closed
     *         cleanly. When this returns {@code false} the source file is preserved.
     */
    static boolean saveAndCleanup(String sourcePath, OutputStream dest) {
        if (sourcePath == null || dest == null) {
            // Without both a source and a destination there is nothing to copy, and we
            // must never delete the source when the destination is missing.
            closeQuietly(dest);
            return false;
        }

        boolean success = false;
        try {
            try (InputStream in = new FileInputStream(sourcePath)) {
                copy(in, dest);
                dest.flush();
            }
            // Closing may flush buffered bytes to the underlying sink, so a failure here
            // means the data might not be fully persisted: treat it as a failed save.
            dest.close();
            success = true;
        } catch (IOException | RuntimeException e) {
            closeQuietly(dest);
        }

        if (success) {
            deleteQuietly(sourcePath);
        }
        return success;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int length;
        while ((length = in.read(buf)) > 0) {
            out.write(buf, 0, length);
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: the stream is being abandoned anyway.
        }
    }

    private static void deleteQuietly(String path) {
        try {
            new File(path).delete();
        } catch (RuntimeException ignored) {
            // A leftover temp file is preferable to crashing; nothing else to do.
        }
    }
}
