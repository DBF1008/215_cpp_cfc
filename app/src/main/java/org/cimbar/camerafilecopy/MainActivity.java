package org.cimbar.camerafilecopy;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;

import android.annotation.SuppressLint;
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

import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "cfc::MainActivity";
    private static final int CREATE_FILE = 11;
    private static final String STATE_ACTIVE_PATH = "cfc.activePath";

    private GestureDetectorCompat mDetector;
    private Toast introToast;

    private CameraBridgeViewBase mOpenCvCameraView;
    private ModeSelToggle mModeSwitch;
    private int modeVal = 0;
    private int detectedMode = 68;
    private String dataPath;
    private String activePath;

    public MainActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        // Restore the pending save target so a transfer survives Activity recreation
        // (e.g. if the process is rebuilt while the system file picker is in front).
        if (savedInstanceState != null) {
            this.activePath = savedInstanceState.getString(STATE_ACTIVE_PATH);
        }

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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Persist the pending save target so it survives Activity recreation while the
        // system file picker is in the foreground.
        outState.putString(STATE_ACTIVE_PATH, this.activePath);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_FILE)
            return;

        final String pendingPath = this.activePath;

        // Anything other than RESULT_OK (cancelled, dismissed, picker failure) must leave
        // the received file in place so the user can try saving it again.
        if (resultCode != RESULT_OK)
            return;

        if (pendingPath == null) {
            // No record of what to save -- e.g. the process was killed and the path could
            // not be restored. There is nothing we can safely write, but nothing is lost.
            Log.w(TAG, "CREATE_FILE result with no pending file to save");
            return;
        }

        // Don't assume RESULT_OK guarantees a usable destination: data, its Uri, and the
        // opened stream can each be null. On any of these, keep the temp file for a retry.
        Uri target = (data == null) ? null : data.getData();
        if (target == null) {
            Log.e(TAG, "CREATE_FILE returned OK without a destination; keeping " + pendingPath);
            notifySaveFailed();
            return;
        }

        OutputStream ostream = null;
        try {
            ostream = getContentResolver().openOutputStream(target);
        } catch (Exception e) {
            Log.e(TAG, "failed to open destination: " + e);
        }
        if (ostream == null) {
            Log.e(TAG, "destination stream was null; keeping " + pendingPath);
            notifySaveFailed();
            return;
        }

        // FileSaveHelper deletes the temp file only if the copy fully succeeds, so a
        // failure here never destroys the received data.
        boolean saved = FileSaveHelper.saveAndCleanup(pendingPath, ostream);
        if (saved) {
            this.activePath = null;
            Toast.makeText(this, R.string.save_succeeded, Toast.LENGTH_SHORT).show();
        } else {
            Log.e(TAG, "failed to save received file; keeping " + pendingPath);
            notifySaveFailed();
            // Leave activePath set so the temp file survives and can be retried.
        }
    }

    private void notifySaveFailed() {
        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
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
