package org.cimbar.camerafilecopy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Regression tests for {@link FileSaveHelper}.
 *
 * <p>The original {@code MainActivity.onActivityResult} copied the received temp file to the
 * destination and then deleted the temp file in a {@code finally} block -- so any failure
 * during the copy destroyed the received file while never saving it. These tests pin down
 * the corrected contract: the temp file is deleted only on a fully successful save, and is
 * preserved on every failure path so the transfer can be retried.
 *
 * <p>This runs as a plain JVM unit test (no Android dependencies):
 * {@code ./gradlew :app:testDebugUnitTest}.
 */
public class FileSaveHelperTest {

    private File newTempWith(byte[] contents) throws IOException {
        File f = File.createTempFile("cfc-src", ".bin");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(contents);
        }
        return f;
    }

    @Test
    public void copiesContentAndDeletesSourceOnSuccess() throws IOException {
        byte[] payload = "hello cimbar".getBytes(StandardCharsets.UTF_8);
        File src = newTempWith(payload);
        ByteArrayOutputStream dest = new ByteArrayOutputStream();

        boolean ok = FileSaveHelper.saveAndCleanup(src.getAbsolutePath(), dest);

        assertTrue("save should succeed", ok);
        assertArrayEquals("destination must receive the exact bytes", payload, dest.toByteArray());
        assertFalse("temp file should be deleted after a successful save", src.exists());
    }

    @Test
    public void emptyFileIsSavedAndSourceDeleted() throws IOException {
        File src = newTempWith(new byte[0]);
        ByteArrayOutputStream dest = new ByteArrayOutputStream();

        boolean ok = FileSaveHelper.saveAndCleanup(src.getAbsolutePath(), dest);

        assertTrue("an empty file is still a valid save", ok);
        assertEquals("destination must be empty", 0, dest.toByteArray().length);
        assertFalse("temp file should be deleted after a successful save", src.exists());
    }

    @Test
    public void preservesSourceWhenWriteFails() throws IOException {
        File src = newTempWith("important data".getBytes(StandardCharsets.UTF_8));
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk full");
            }
        };

        boolean ok = FileSaveHelper.saveAndCleanup(src.getAbsolutePath(), failing);

        assertFalse("save must report failure when the write fails", ok);
        assertTrue("temp file MUST survive a failed write (the core regression)", src.exists());
    }

    @Test
    public void preservesSourceWhenCloseFails() throws IOException {
        File src = newTempWith("important data".getBytes(StandardCharsets.UTF_8));
        // A stream whose bytes are only committed on close(); a close failure means the
        // data was not actually persisted, so the save must be treated as failed.
        OutputStream failingOnClose = new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void write(byte[] b, int off, int len) {
            }

            @Override
            public void close() throws IOException {
                throw new IOException("flush-on-close failed");
            }
        };

        boolean ok = FileSaveHelper.saveAndCleanup(src.getAbsolutePath(), failingOnClose);

        assertFalse("a close failure means the data may not be persisted", ok);
        assertTrue("temp file MUST survive a failed close (regression)", src.exists());
    }

    @Test
    public void nullDestinationIsHandledAndSourcePreserved() throws IOException {
        File src = newTempWith("keep me".getBytes(StandardCharsets.UTF_8));

        boolean ok = FileSaveHelper.saveAndCleanup(src.getAbsolutePath(), null);

        assertFalse("a null destination is not a successful save", ok);
        assertTrue("a null destination must never delete the temp file", src.exists());
    }

    @Test
    public void nullSourceIsHandledGracefully() {
        boolean ok = FileSaveHelper.saveAndCleanup(null, new ByteArrayOutputStream());

        assertFalse("a null source cannot be saved", ok);
    }
}
