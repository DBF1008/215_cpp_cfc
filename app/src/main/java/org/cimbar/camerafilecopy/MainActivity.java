package org.cimbar.camerafilecopy;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.view.GestureDetectorCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "cfc::MainActivity";
    private static final int CREATE_FILE = 11;
    static final String BUNDLE_KEY_ACTIVE_PATH = "activePath";
    static final String BUNDLE_KEY_PENDING_SAVE_PATH = "pendingSavePath";

    private GestureDetectorCompat mDetector;
    private Toast introToast;

    private CameraBridgeViewBase mOpenCvCameraView;
    private ModeSelToggle mModeSwitch;
    private int modeVal = 0;
    private int detectedMode = 68;
    private String dataPath;
    private String activePath;
    private String pendingSavePath;

    public MainActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        //! [ocv_loader_init]
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
            System.loadLibrary("cfc-cpp");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }
        //! [ocv_loader_init]

        //! [keep_screen]
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //! [keep_screen]

        this.dataPath = this.getFilesDir().getPath();
        //this.dataPath = this.getExternalFilesDir(null).getPath(); // for manual testing

        // Restore state across Activity recreations (e.g. process death during file picker)
        if (savedInstanceState != null) {
            this.activePath = savedInstanceState.getString(BUNDLE_KEY_ACTIVE_PATH, null);
            this.pendingSavePath = savedInstanceState.getString(BUNDLE_KEY_PENDING_SAVE_PATH, null);
        }

        setContentView(R.layout.activity_main);

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.main_surface);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        // set up mode toggle
        mModeSwitch = (ModeSelToggle) findViewById(R.id.mode_switch);
        mModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    modeVal = detectedMode;
                } else {
                    modeVal = 0;
                }
            }
        });

        // Set up the swipe gestures
        mDetector = new GestureDetectorCompat(this, new FlingGestureListener());

        // and the hint toast
        introToast = Toast.makeText(this, "↕ Swipe to encode data! Or use cimbar.org :)",  Toast.LENGTH_LONG);
    }

    @Override
    public void onStart() {
        super.onStart();
        introToast.show();
        // reset autodetect
        mModeSwitch.setChecked(false);
        modeVal = 0;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (this.activePath != null)
            outState.putString(BUNDLE_KEY_ACTIVE_PATH, this.activePath);
        if (this.pendingSavePath != null)
            outState.putString(BUNDLE_KEY_PENDING_SAVE_PATH, this.pendingSavePath);
    }

    @Override
    public void onPause()
    {
        shutdownJNI();
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.enableView();

        // If a previous save attempt failed (e.g. process death during file picker),
        // offer to retry now that the Activity is back.
        if (this.pendingSavePath != null) {
            final String path = this.pendingSavePath;
            new AlertDialog.Builder(this)
                .setTitle("Save failed")
                .setMessage("The previous file transfer could not be saved. Try again?")
                .setPositiveButton("Retry", (dialog, which) -> retrySave(path))
                .setNegativeButton("Discard", (dialog, which) -> {
                    try { new File(path).delete(); } catch (Exception e) {}
                    this.pendingSavePath = null;
                })
                .setCancelable(false)
                .show();
        }
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    public void onDestroy() {
        shutdownJNI();
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame frame) {
        // get current camera frame as OpenCV Mat object
        Mat mat = frame.rgba();

        // native call to process current camera frame
        String res = processImageJNI(mat.getNativeObjAddr(), this.dataPath, this.modeVal);

        // res will contain a file path if we completed a transfer. Ask the user where to save it
        if (res.startsWith("/")) {
            if (res.length() == 2 && res.charAt(1) == '4') {
                detectedMode = 4;
            }
            else if (res.length() == 3 && res.charAt(1) == '6' && res.charAt(2) == '6') {
                detectedMode = 66;
            }
            else if (res.length() == 3 && res.charAt(1) == '6' && res.charAt(2) == '7') {
                detectedMode = 67;
            }
            else {
                detectedMode = 68;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mModeSwitch.setChecked(true);
                    mModeSwitch.setModeVal(detectedMode);
                }
            });

        }
        else if (!res.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, res);
            // can't get putExtra to work for extra values, so we'll save it in the class
            this.activePath = this.dataPath + "/" + res;
            startActivityForResult(intent, CREATE_FILE);
        }

        // return processed frame for live preview
        return mat;
    }

    /** Outcome of a CREATE_FILE activity result, used to drive state transitions. */
    enum SaveOutcome {
        IGNORED,            // not our request code
        CANCELLED,          // user cancelled the picker
        NO_SOURCE,          // activePath was lost (recreation race)
        PENDING_NO_DATA,    // OK but data Intent is null — preserve for retry
        PENDING_NO_URI,     // OK but Uri is null — preserve for retry
        SAVE_OK,            // copy succeeded
        SAVE_FAILED         // copy threw — preserve for retry
    }

    /**
     * Pure decision function: given the activity-result inputs and whether the copy
     * succeeded (ignored when not applicable), determine what happened.
     * This method has no side effects and is unit-tested directly.
     */
    static SaveOutcome determineSaveOutcome(int requestCode, int resultCode,
                                            @Nullable Intent data,
                                            @Nullable String activePath,
                                            boolean copySucceeded) {
        if (requestCode != CREATE_FILE)
            return SaveOutcome.IGNORED;

        if (resultCode != RESULT_OK)
            return SaveOutcome.CANCELLED;

        if (activePath == null)
            return SaveOutcome.NO_SOURCE;

        if (data == null)
            return SaveOutcome.PENDING_NO_DATA;

        if (data.getData() == null)
            return SaveOutcome.PENDING_NO_URI;

        return copySucceeded ? SaveOutcome.SAVE_OK : SaveOutcome.SAVE_FAILED;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        final String srcPath = this.activePath;

        // Determine the copy result upfront if we'll need it for the decision
        boolean copySucceeded = false;
        if (requestCode == CREATE_FILE && resultCode == RESULT_OK
                && srcPath != null && data != null && data.getData() != null) {
            copySucceeded = copyFileToUri(srcPath, data.getData());
        }

        SaveOutcome outcome = determineSaveOutcome(requestCode, resultCode, data, srcPath, copySucceeded);

        switch (outcome) {
            case IGNORED:
                return;

            case CANCELLED:
                if (srcPath != null) {
                    try { new File(srcPath).delete(); } catch (Exception e) {}
                    this.activePath = null;
                }
                return;

            case NO_SOURCE:
                Log.w(TAG, "onActivityResult: activePath lost after recreation");
                return;

            case PENDING_NO_DATA:
                Log.w(TAG, "onActivityResult: data Intent is null; preserving temp file for retry");
                this.pendingSavePath = srcPath;
                this.activePath = null;
                showToast("Save failed: no result data. You will be prompted to retry.");
                return;

            case PENDING_NO_URI:
                Log.w(TAG, "onActivityResult: result Uri is null; preserving temp file for retry");
                this.pendingSavePath = srcPath;
                this.activePath = null;
                showToast("Save failed: invalid destination. You will be prompted to retry.");
                return;

            case SAVE_OK:
                try { new File(srcPath).delete(); } catch (Exception e) {}
                this.activePath = null;
                this.pendingSavePath = null;
                showToast("File saved successfully");
                return;

            case SAVE_FAILED:
                this.pendingSavePath = srcPath;
                this.activePath = null;
                showToast("Save failed. You will be prompted to retry.");
                return;
        }
    }

    /**
     * Copy the temp file at {@code srcPath} to the given content {@code destUri}.
     * Returns true only when every byte was written and the stream was flushed.
     */
    boolean copyFileToUri(String srcPath, Uri destUri) {
        InputStream istream = null;
        OutputStream ostream = null;
        try {
            istream = new FileInputStream(srcPath);
            ostream = getContentResolver().openOutputStream(destUri);
            if (ostream == null) {
                Log.e(TAG, "openOutputStream returned null for " + destUri);
                return false;
            }
            copyStream(istream, ostream);
            ostream.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "failed to write file: " + e.toString());
            return false;
        } finally {
            try { if (istream != null) istream.close(); } catch (IOException e) {}
            try { if (ostream != null) ostream.close(); } catch (IOException e) {}
        }
    }

    /** Pure byte-copy between two streams. Package-visible for unit testing. */
    static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int length;
        while ((length = in.read(buf)) > 0) {
            out.write(buf, 0, length);
        }
    }

    /** Re-launch the file picker for a previously-failed save attempt. */
    private void retrySave(String srcPath) {
        this.pendingSavePath = null;
        File f = new File(srcPath);
        if (!f.exists()) {
            showToast("Temp file no longer exists, cannot retry.");
            return;
        }
        this.activePath = srcPath;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, f.getName());
        startActivityForResult(intent, CREATE_FILE);
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private native String processImageJNI(long mat, String path, int modeInt);
    private native void shutdownJNI();

    @Override
    public boolean onTouchEvent(MotionEvent event){
        if (this.mDetector.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }
    @SuppressLint("ClickableViewAccessibility")
    class FlingGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final String TAG = "Gestures";

        // We only want fling gestures to trigger the view transitions, not scrolling.
        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                               float velocityX, float velocityY) {
            final int THRESHOLD = 100;
            final int VEL_THRESHOLD = 100;

            if (Math.abs(velocityY) < VEL_THRESHOLD)
                return false;
            if (Math.abs(event1.getY() - event2.getY()) < THRESHOLD)
                return false;
            if (mOpenCvCameraView != null)
                mOpenCvCameraView.disableView();
            if (introToast != null)
                introToast.cancel();

            Intent intent = new Intent(MainActivity.this, WebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        }
    }

}
