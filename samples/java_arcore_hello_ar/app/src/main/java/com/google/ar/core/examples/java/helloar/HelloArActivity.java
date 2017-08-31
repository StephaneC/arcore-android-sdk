/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.helloar;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Frame.TrackingState;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.PlaneHitResult;
import com.google.ar.core.Session;
import com.google.ar.core.examples.java.helloar.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.helloar.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.helloar.rendering.PlaneAttachment;
import com.google.ar.core.examples.java.helloar.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.helloar.rendering.PointCloudRenderer;

import android.app.DialogFragment;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using
 * the ARCore API. The application will display any detected planes and will allow the user to
 * tap on a plane to place a 3d model of the Android robot.
 */
public class HelloArActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = HelloArActivity.class.getSimpleName();

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView mSurfaceView;

    private Config mDefaultConfig;
    private Session mSession;
    private BackgroundRenderer mBackgroundRenderer = new BackgroundRenderer();
    private GestureDetector mGestureDetector;
    private Snackbar mLoadingMessageSnackbar = null;

    private ObjectRenderer[] mVirtualObjects;
    //private ObjectRenderer[] mVirtualObjectShadow = new ObjectRenderer[]();
    private PlaneRenderer mPlaneRenderer = new PlaneRenderer();
    private PointCloudRenderer mPointCloud = new PointCloudRenderer();


    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private final float[] mAnchorMatrix = new float[16];

    // Tap handling and UI.
    private ArrayBlockingQueue<MotionEvent> mQueuedSingleTaps = new ArrayBlockingQueue<>(16);
    private Map<PlaneAttachment, Integer> mTouches = new HashMap<>();

    //to save img
    Button switchBtn;
    private String objName = "andy";//default value
    String[] objects;

    private boolean saveFrame = false;

    private int whichObj;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSurfaceView = (GLSurfaceView) findViewById(R.id.surfaceview);

        mSession = new Session(/*context=*/this);

        objects = this.getResources().getStringArray(R.array.objects_array);
        mVirtualObjects = new ObjectRenderer[objects.length];

        // Create default config, check is supported, create session from that config.
        mDefaultConfig = Config.createDefaultConfig();
        if (!mSession.isSupported(mDefaultConfig)) {
            Toast.makeText(this, "This device does not support AR", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Set up tap listener.
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                onSingleTap(e);
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        mSurfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mGestureDetector.onTouchEvent(event);
            }
        });

        // Set up renderer.
        mSurfaceView.setPreserveEGLContextOnPause(true);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        mSurfaceView.setRenderer(this);
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        findViewById(R.id.photo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (WriteImgPermissionHelper.hasWritePermission(HelloArActivity.this)) {
                    //take a picture
                    saveFrame = true;
                    mSurfaceView.requestRender();
                    //have to wait to draw and doing img save in onDraw
                } else {
                    WriteImgPermissionHelper.requestWriteStoragePermission(HelloArActivity.this);
                }
            }
        });

        switchBtn = (Button)findViewById(R.id.switch_obj);
        switchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                showDialog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (CameraPermissionHelper.hasCameraPermission(this)) {
            showLoadingMessage();
            // Note that order matters - see the note in onPause(), the reverse applies here.
            mSession.resume(mDefaultConfig);
            mSurfaceView.onResume();
        } else {
            CameraPermissionHelper.requestCameraPermission(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call mSession.update() and get a SessionPausedException.
        mSurfaceView.onPause();
        mSession.pause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == CameraPermissionHelper.CAMERA_PERMISSION_CODE
                    && !CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            finish();
        } else if(requestCode == CameraPermissionHelper.CAMERA_PERMISSION_CODE
          && !WriteImgPermissionHelper.hasWritePermission(this)){
            Toast.makeText(this,
                           "Write storage is needed to take pictures", Toast.LENGTH_LONG).show();

        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void onSingleTap(MotionEvent e) {
        // Queue tap if there is space. Tap is lost if queue is full.
        mQueuedSingleTaps.offer(e);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Create the texture and pass it to ARCore session to be filled during update().
        mBackgroundRenderer.createOnGlThread(/*context=*/this);
        mSession.setCameraTextureName(mBackgroundRenderer.getTextureId());

        // Prepare the other rendering objects.
        try {
            for(int i=0; i<objects.length; i++) {
                mVirtualObjects[i] = new ObjectRenderer();
                mVirtualObjects[i].createOnGlThread(/*context=*/this, objects[i] + ".obj", objects[i] + ".png");
                mVirtualObjects[i].setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);

                /** NO Shadow mVirtualObjectShadow.createOnGlThread(this,
                 "andy_shadow.obj", "andy_shadow.png");*/
                //mVirtualObjectShadow.setBlendMode(BlendMode.Shadow);
                //mVirtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read obj file");
        }
        try {
            mPlaneRenderer.createOnGlThread(/*context=*/this, "trigrid.png");
        } catch (IOException e) {
            Log.e(TAG, "Failed to read plane texture");
        }
        mPointCloud.createOnGlThread(/*context=*/this);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        mSession.setDisplayGeometry(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        try {
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = mSession.update();

            // Handle taps. Handling only one tap per frame, as taps are usually low frequency
            // compared to frame rate.
            MotionEvent tap = mQueuedSingleTaps.poll();
            if (tap != null && frame.getTrackingState() == TrackingState.TRACKING) {
                for (HitResult hit : frame.hitTest(tap)) {
                    // Check if any plane was hit, and if it was hit inside the plane polygon.
                    if (hit instanceof PlaneHitResult && ((PlaneHitResult) hit).isHitInPolygon()) {
                        // Cap the number of objects created. This avoids overloading both the
                        // rendering system and ARCore.
                        if (mTouches.size() >= 16) {
                            //FIXME mSession.removeAnchors(Arrays.asList(mTouches.get(0).getAnchor()));
                            //mTouches.remove(0);
                            Toast.makeText(this, this.getString(R.string.too_much_element), Toast.LENGTH_LONG).show();
                        } else {
                            // Adding an Anchor tells ARCore that it should track this position in
                            // space. This anchor will be used in PlaneAttachment to place the 3d model
                            // in the correct position relative both to the world and to the plane.
                            mTouches.put(new PlaneAttachment(((PlaneHitResult) hit).getPlane(), mSession
                              .addAnchor(hit.getHitPose())), whichObj);
                        }

                        // Hits are sorted by depth. Consider only closest hit on a plane.
                        break;
                    }
                }
            }

            // Draw background.
            mBackgroundRenderer.draw(frame);

            // If not tracking, don't draw 3d objects.
            if (frame.getTrackingState() == TrackingState.NOT_TRACKING) {
                return;
            }

            // Get projection matrix.
            float[] projmtx = new float[16];
            mSession.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            frame.getViewMatrix(viewmtx, 0);

            // Compute lighting from average intensity of the image.
            final float lightIntensity = frame.getLightEstimate().getPixelIntensity();

            // Visualize tracked points.
            mPointCloud.update(frame.getPointCloud());
            mPointCloud.draw(frame.getPointCloudPose(), viewmtx, projmtx);

            // Check if we detected at least one plane. If so, hide the loading message.
            if (mLoadingMessageSnackbar != null) {
                for (Plane plane : mSession.getAllPlanes()) {
                    if (plane.getType() == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            plane.getTrackingState() == Plane.TrackingState.TRACKING) {
                        hideLoadingMessage();
                        break;
                    }
                }
            }

            // Visualize planes.
            mPlaneRenderer.drawPlanes(mSession.getAllPlanes(), frame.getPose(), projmtx);

            // Visualize anchors created by touch.
            float scaleFactor = 1.0f;
            Iterator it = mTouches.entrySet().iterator();
            Map.Entry<PlaneAttachment, Integer> entry;
            while(it.hasNext()){
                entry = (Map.Entry<PlaneAttachment, Integer>) it.next();
                if (!entry.getKey().isTracking()) {
                    continue;
                }
                // Get the current combined pose of an Anchor and Plane in world space. The Anchor
                // and Plane poses are updated during calls to session.update() as ARCore refines
                // its estimate of the world.
                entry.getKey().getPose().toMatrix(mAnchorMatrix, 0);

                // Update and draw the model and its shadow.
                mVirtualObjects[entry.getValue()].updateModelMatrix(mAnchorMatrix, scaleFactor);
                //mVirtualObjectShadow.updateModelMatrix(mAnchorMatrix, scaleFactor);
                mVirtualObjects[entry.getValue()].draw(viewmtx, projmtx, lightIntensity);
                //mVirtualObjectShadow.draw(viewmtx, projmtx, lightIntensity);
            }
            /*for (PlaneAttachment planeAttachment : mTouches) {
                if (!planeAttachment.isTracking()) {
                    continue;
                }
                // Get the current combined pose of an Anchor and Plane in world space. The Anchor
                // and Plane poses are updated during calls to session.update() as ARCore refines
                // its estimate of the world.
                planeAttachment.getPose().toMatrix(mAnchorMatrix, 0);

                // Update and draw the model and its shadow.
                mVirtualObjects[whichObj].updateModelMatrix(mAnchorMatrix, scaleFactor);
                //mVirtualObjectShadow.updateModelMatrix(mAnchorMatrix, scaleFactor);
                mVirtualObjects[whichObj].draw(viewmtx, projmtx, lightIntensity);
                //mVirtualObjectShadow.draw(viewmtx, projmtx, lightIntensity);
            }*/

            if(saveFrame) {
                saveFrame = true;
                //new SaveImgTask().execute(gl);
                if(saveImage(takeScreenshot(gl))){
                    Toast.makeText(HelloArActivity.this, HelloArActivity.this.getString(R.string.photo_success), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(HelloArActivity.this, HelloArActivity.this.getString(R.string.photo_error), Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void showLoadingMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLoadingMessageSnackbar = Snackbar.make(
                    HelloArActivity.this.findViewById(android.R.id.content),
                    "Searching for surfaces...", Snackbar.LENGTH_INDEFINITE);
                mLoadingMessageSnackbar.getView().setBackgroundColor(0xbf323232);
                mLoadingMessageSnackbar.show();
            }
        });
    }

    private void hideLoadingMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLoadingMessageSnackbar.dismiss();
                mLoadingMessageSnackbar = null;
            }
        });
    }


    void showDialog() {
        DialogFragment newFragment = ImagesDialog.newInstance();
        newFragment.show(getFragmentManager(), "dialog");
    }


    public void selectedObject(final int which) {
        try {
            this.whichObj = which;
            objName = getResources().getStringArray(R.array.objects_array)[which];
            switchBtn.setText(objName);
        } catch (Exception e){
            Log.e(TAG, "WTF. Received a wrong whinch index", e);
        }
    }

    public Bitmap takeScreenshot(GL10 mGL) {
        final int mWidth = mSurfaceView.getWidth();
        final int mHeight = mSurfaceView.getHeight();
        IntBuffer ib = IntBuffer.allocate(mWidth * mHeight);
        IntBuffer ibt = IntBuffer.allocate(mWidth * mHeight);
        mGL.glReadPixels(0, 0, mWidth, mHeight, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, ib);

        // Convert upside down mirror-reversed image to right-side up normal image.
        for (int i = 0; i < mHeight; i++) {
            for (int j = 0; j < mWidth; j++) {
                ibt.put((mHeight - i - 1) * mWidth + j, ib.get(i * mWidth + j));
            }
        }

        Bitmap mBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
        mBitmap.copyPixelsFromBuffer(ibt);
        return mBitmap;
    }

    protected boolean saveImage(Bitmap bmScreen2) {
        File saved_image_file = new File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            + "/captured_Bitmap.png");
        if (saved_image_file.exists())
            saved_image_file.delete();
        try {
            FileOutputStream out = new FileOutputStream(saved_image_file);
            bmScreen2.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            MediaStore.Images.Media.insertImage(getContentResolver(), bmScreen2, "Screenshot AR" , "Screenshot");
            //addPicToGallery(saved_image_file.getPath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception on the saving img", e);
            return false;
        }
    }

    class SaveImgTask extends AsyncTask<GL10, Void, Boolean> {

        @Override
        protected Boolean doInBackground(final GL10... params) {
            return saveImage(takeScreenshot(params[0]));
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if(result){
                Toast.makeText(HelloArActivity.this, HelloArActivity.this.getString(R.string.photo_success), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(HelloArActivity.this, HelloArActivity.this.getString(R.string.photo_error), Toast.LENGTH_LONG).show();
            }
        }

        public Bitmap takeScreenshot(GL10 mGL) {
            final int mWidth = mSurfaceView.getWidth();
            final int mHeight = mSurfaceView.getHeight();
            IntBuffer ib = IntBuffer.allocate(mWidth * mHeight);
            IntBuffer ibt = IntBuffer.allocate(mWidth * mHeight);
            mGL.glReadPixels(0, 0, mWidth, mHeight, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, ib);

            // Convert upside down mirror-reversed image to right-side up normal image.
            for (int i = 0; i < mHeight; i++) {
                for (int j = 0; j < mWidth; j++) {
                    ibt.put((mHeight - i - 1) * mWidth + j, ib.get(i * mWidth + j));
                }
            }

            Bitmap mBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            mBitmap.copyPixelsFromBuffer(ibt);
            return mBitmap;
        }

        protected boolean saveImage(Bitmap bmScreen2) {
            File saved_image_file = new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                + "/captured_Bitmap.png");
            if (saved_image_file.exists())
                saved_image_file.delete();
            try {
                FileOutputStream out = new FileOutputStream(saved_image_file);
                bmScreen2.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
                out.close();
                MediaStore.Images.Media.insertImage(getContentResolver(), bmScreen2, "Screenshot AR" , "Screenshot");
                //addPicToGallery(saved_image_file.getPath());
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Exception on the saving img", e);
                return false;
            }
        }



    }
}
