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

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "cfc::MainActivity";
    private static final int CREATE_FILE = 11;

    private GestureDetectorCompat mDetector;
    private Toast introToast;

    private CameraBridgeViewBase mOpenCvCameraView;
    private ModeSelToggle mModeSwitch;
    private int modeVal = 0;
    private int detectedMode = 68;
    private String dataPath;
    private SaveCoordinator saveCoordinator;

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

        // Initialize the save coordinator with a listener that marshals to the UI thread
        saveCoordinator = new SaveCoordinator(new SaveCoordinator.SaveCoordinatorListener() {
            @Override
            public void requestSaveLocation(final String filename) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        intent.putExtra(Intent.EXTRA_TITLE, filename);
                        startActivityForResult(intent, CREATE_FILE);
                    }
                });
            }
        });

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
        // Cancel any pending save dialog if the user navigates away
        saveCoordinator.onSaveCancelled();
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
            saveCoordinator.onFrameResult(this.dataPath + "/" + res, res);
        }

        // return processed frame for live preview
        return mat;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != CREATE_FILE)
            return;

        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri destUri = data.getData();
            saveCoordinator.onSaveLocationAvailable(new Runnable() {
                @Override
                public void run() {
                    String sourcePath = saveCoordinator.getCurrentFullPath();
                    if (sourcePath == null)
                        return;

                    // copy the temp file to the user-specified location
                    try (
                            InputStream istream = new FileInputStream(sourcePath);
                            OutputStream ostream = getContentResolver().openOutputStream(destUri)
                    ) {
                        byte[] buf = new byte[8192];
                        int length;
                        while ((length = istream.read(buf)) > 0) {
                            ostream.write(buf, 0, length);
                        }
                        ostream.flush();
                    } catch (Exception e) {
                        Log.e(TAG, "failed to write file " + e.toString());
                    }
                }
            });
        } else {
            saveCoordinator.onSaveCancelled();
        }
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
