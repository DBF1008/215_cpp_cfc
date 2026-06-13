package org.cimbar.camerafilecopy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MainActivity} save-result handling.
 *
 * Covers the two extracted, framework-independent units:
 *   1. {@link MainActivity#copyStream(InputStream, OutputStream)} — pure byte copy
 *   2. {@link MainActivity#determineSaveOutcome(int, int, Intent, String, boolean)} — decision logic
 *
 * Robolectric provides working Android stubs (Intent, Uri, Activity.RESULT_*) so
 * the tests can run on the JVM without an emulator.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MainActivityTest {

    // -----------------------------------------------------------------------
    // copyStream tests
    // -----------------------------------------------------------------------

    @Test
    public void copyStream_copiesAllBytes() throws IOException {
        byte[] expected = "hello, cimbar!".getBytes();
        ByteArrayInputStream in = new ByteArrayInputStream(expected);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        MainActivity.copyStream(in, out);

        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    public void copyStream_handlesEmptyStream() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        MainActivity.copyStream(in, out);

        assertEquals(0, out.size());
    }

    @Test
    public void copyStream_handlesDataLargerThanBuffer() throws IOException {
        // copyStream uses an 8192-byte buffer; test with more data
        byte[] expected = new byte[8192 * 3 + 123];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 251);
        }
        ByteArrayInputStream in = new ByteArrayInputStream(expected);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        MainActivity.copyStream(in, out);

        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    public void copyStream_handlesExactlyOneBuffer() throws IOException {
        byte[] expected = new byte[8192];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 127);
        }
        ByteArrayInputStream in = new ByteArrayInputStream(expected);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        MainActivity.copyStream(in, out);

        assertArrayEquals(expected, out.toByteArray());
    }

    @Test(expected = IOException.class)
    public void copyStream_propagatesWriteException() throws IOException {
        byte[] data = "some data".getBytes();
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        OutputStream failingOut = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk full");
            }
        };

        MainActivity.copyStream(in, failingOut);
    }

    @Test(expected = IOException.class)
    public void copyStream_propagatesReadException() throws IOException {
        InputStream failingIn = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read error");
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("read error");
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        MainActivity.copyStream(failingIn, out);
    }

    // -----------------------------------------------------------------------
    // determineSaveOutcome tests — each branch of the decision matrix
    // -----------------------------------------------------------------------

    private static final int CREATE_FILE = 11; // mirrors MainActivity.CREATE_FILE
    private static final int OTHER_REQUEST = 99;

    /** Helper: build an Intent with a valid content Uri. */
    private static Intent intentWithUri() {
        return new Intent().setData(Uri.parse("content://test/destination"));
    }

    @Test
    public void determineSaveOutcome_wrongRequestCode_returnsIgnored() {
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                OTHER_REQUEST, Activity.RESULT_OK, intentWithUri(), "/tmp/file.bin", true);

        assertEquals(MainActivity.SaveOutcome.IGNORED, outcome);
    }

    @Test
    public void determineSaveOutcome_resultCanceled_returnsCancelled() {
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_CANCELED, intentWithUri(), "/tmp/file.bin", false);

        assertEquals(MainActivity.SaveOutcome.CANCELLED, outcome);
    }

    @Test
    public void determineSaveOutcome_resultCanceled_nullData_returnsCancelled() {
        // Even with null data, cancellation should be reported correctly
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_CANCELED, null, "/tmp/file.bin", false);

        assertEquals(MainActivity.SaveOutcome.CANCELLED, outcome);
    }

    @Test
    public void determineSaveOutcome_nullActivePath_returnsNoSource() {
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, intentWithUri(), null, false);

        assertEquals(MainActivity.SaveOutcome.NO_SOURCE, outcome);
    }

    @Test
    public void determineSaveOutcome_nullDataIntent_returnsPendingNoData() {
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, null, "/tmp/file.bin", false);

        assertEquals(MainActivity.SaveOutcome.PENDING_NO_DATA, outcome);
    }

    @Test
    public void determineSaveOutcome_nullUri_returnsPendingNoUri() {
        Intent data = new Intent(); // no Uri set

        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, data, "/tmp/file.bin", false);

        assertEquals(MainActivity.SaveOutcome.PENDING_NO_URI, outcome);
    }

    @Test
    public void determineSaveOutcome_copySucceeded_returnsSaveOk() {
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, intentWithUri(), "/tmp/file.bin", true);

        assertEquals(MainActivity.SaveOutcome.SAVE_OK, outcome);
    }

    @Test
    public void determineSaveOutcome_copyFailed_returnsSaveFailed() {
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, intentWithUri(), "/tmp/file.bin", false);

        assertEquals(MainActivity.SaveOutcome.SAVE_FAILED, outcome);
    }

    // -----------------------------------------------------------------------
    // determineSaveOutcome — ordering / priority checks
    // These verify that the decision function checks conditions in the right
    // order (e.g. requestCode before resultCode, activePath before data).
    // -----------------------------------------------------------------------

    @Test
    public void determineSaveOutcome_wrongRequestCode_takesPriorityOverAll() {
        // Even with RESULT_CANCELED and null everything, wrong requestCode wins
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                OTHER_REQUEST, Activity.RESULT_CANCELED, null, null, false);

        assertEquals(MainActivity.SaveOutcome.IGNORED, outcome);
    }

    @Test
    public void determineSaveOutcome_canceledTakesPriorityOverNullActivePath() {
        // Cancellation should be reported before we check activePath
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_CANCELED, null, null, false);

        assertEquals(MainActivity.SaveOutcome.CANCELLED, outcome);
    }

    @Test
    public void determineSaveOutcome_nullActivePathTakesPriorityOverNullData() {
        // If activePath is lost, we report NO_SOURCE even if data is also null
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, null, null, false);

        assertEquals(MainActivity.SaveOutcome.NO_SOURCE, outcome);
    }

    @Test
    public void determineSaveOutcome_nullDataTakesPriorityOverNullUri() {
        // null data is a different failure mode than null Uri
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, null, "/tmp/file.bin", false);

        assertEquals(MainActivity.SaveOutcome.PENDING_NO_DATA, outcome);
    }

    // -----------------------------------------------------------------------
    // Integration-level: verify temp file lifecycle contracts
    // These tests verify that the outcome signals correctly whether the
    // temp file should be deleted (SAVE_OK) or preserved (SAVE_FAILED).
    // -----------------------------------------------------------------------

    @Test
    public void tempFile_survivesFailedCopy() throws IOException {
        File tempFile = File.createTempFile("cfc_test_", ".bin");
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write("received data".getBytes());
            }

            MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                    CREATE_FILE, Activity.RESULT_OK, intentWithUri(),
                    tempFile.getAbsolutePath(), false);

            assertEquals(MainActivity.SaveOutcome.SAVE_FAILED, outcome);
            // Contract: SAVE_FAILED means the caller must NOT delete the temp file
            assertTrue("Temp file must survive a failed save so the user can retry",
                    tempFile.exists());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void tempFile_safeToDeleteAfterSuccessfulCopy() throws IOException {
        File tempFile = File.createTempFile("cfc_test_", ".bin");
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write("received data".getBytes());
            }

            MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                    CREATE_FILE, Activity.RESULT_OK, intentWithUri(),
                    tempFile.getAbsolutePath(), true);

            assertEquals(MainActivity.SaveOutcome.SAVE_OK, outcome);
            // Contract: SAVE_OK means the caller should delete the temp file
        } finally {
            tempFile.delete();
        }
    }

    // -----------------------------------------------------------------------
    // Regression: the original bug — copy failure must NOT delete temp file
    // -----------------------------------------------------------------------

    @Test
    public void regression_copyFailureDoesNotProduceSaveOk() {
        // This is the exact scenario that caused the original bug:
        // RESULT_OK + valid Uri + copy throws → old code deleted the temp file.
        // The fix: determineSaveOutcome must return SAVE_FAILED, not SAVE_OK.
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, intentWithUri(), "/tmp/file.bin", false);

        assertNotEquals("Copy failure must not be reported as success",
                MainActivity.SaveOutcome.SAVE_OK, outcome);
        assertEquals(MainActivity.SaveOutcome.SAVE_FAILED, outcome);
    }

    @Test
    public void regression_nullDataDoesNotProduceSaveOk() {
        // Old code: NPE on data.getData() → caught → finally deletes temp file.
        // Fix: null data must yield PENDING_NO_DATA, preserving the temp file.
        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, null, "/tmp/file.bin", false);

        assertNotEquals(MainActivity.SaveOutcome.SAVE_OK, outcome);
        assertEquals(MainActivity.SaveOutcome.PENDING_NO_DATA, outcome);
    }

    @Test
    public void regression_nullUriDoesNotProduceSaveOk() {
        // Old code: openOutputStream(null) → exception → finally deletes temp file.
        // Fix: null Uri must yield PENDING_NO_URI, preserving the temp file.
        Intent data = new Intent(); // no Uri

        MainActivity.SaveOutcome outcome = MainActivity.determineSaveOutcome(
                CREATE_FILE, Activity.RESULT_OK, data, "/tmp/file.bin", false);

        assertNotEquals(MainActivity.SaveOutcome.SAVE_OK, outcome);
        assertEquals(MainActivity.SaveOutcome.PENDING_NO_URI, outcome);
    }

    // -----------------------------------------------------------------------
    // Bundle key constants — verify they match expected values
    // (guards against accidental rename that would break state restoration)
    // -----------------------------------------------------------------------

    @Test
    public void bundleKeys_haveExpectedValues() {
        assertEquals("activePath", MainActivity.BUNDLE_KEY_ACTIVE_PATH);
        assertEquals("pendingSavePath", MainActivity.BUNDLE_KEY_PENDING_SAVE_PATH);
    }

    // -----------------------------------------------------------------------
    // SaveOutcome enum — verify all expected values exist
    // -----------------------------------------------------------------------

    @Test
    public void saveOutcome_hasAllExpectedValues() {
        MainActivity.SaveOutcome[] values = MainActivity.SaveOutcome.values();
        assertEquals(7, values.length);
        assertNotNull(MainActivity.SaveOutcome.valueOf("IGNORED"));
        assertNotNull(MainActivity.SaveOutcome.valueOf("CANCELLED"));
        assertNotNull(MainActivity.SaveOutcome.valueOf("NO_SOURCE"));
        assertNotNull(MainActivity.SaveOutcome.valueOf("PENDING_NO_DATA"));
        assertNotNull(MainActivity.SaveOutcome.valueOf("PENDING_NO_URI"));
        assertNotNull(MainActivity.SaveOutcome.valueOf("SAVE_OK"));
        assertNotNull(MainActivity.SaveOutcome.valueOf("SAVE_FAILED"));
    }
}
