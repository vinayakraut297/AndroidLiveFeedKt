package com.example.livefeeddemo;

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.example.livefeeddemo.R
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Camera Connection Fragment that captures images from camera.
 *
 *
 * Instantiated by newInstance.
 */
@SuppressLint("ValidFragment")
class CameraConnectionFragment @SuppressLint("ValidFragment") private constructor(
    private val cameraConnectionCallback: ConnectionCallback,
    /** A [OnImageAvailableListener] to receive frames as they are available.  */
    private val imageListener: OnImageAvailableListener,
    /** The layout identifier to inflate for this Fragment.  */
    private val layout: Int,
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap).  */
    private val inputSize: Size
) : Fragment() {  
    companion object {
        /**
         * The camera preview size will be chosen to be the smallest frame by pixel size capable of
         * containing a DESIRED_SIZE x DESIRED_SIZE square.
         */
        private const val MINIMUM_PREVIEW_SIZE = 320

        /** Conversion from screen rotation to JPEG orientation.  */
        private val ORIENTATIONS = SparseIntArray()
        private const val FRAGMENT_DIALOG = "dialog"

        /**
         * Given `choices` of `Size`s supported by a camera, chooses the smallest one whose
         * width and height are at least as large as the minimum of both, or an exact match if possible.
         *
         * @param choices The list of sizes that the camera supports for the intended output class
         * @param width The minimum desired width
         * @param height The minimum desired height
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        protected fun chooseOptimalSize(
            choices: Array<Size>,
            width: Int,
            height: Int
        ): Size {
            val minSize = Math.max(
                Math.min(width, height),
                MINIMUM_PREVIEW_SIZE
            )
            val desiredSize = Size(width, height)

            // Collect the supported resolutions that are at least as big as the preview Surface
            var exactSizeFound = false
            val bigEnough: MutableList<Size> =
                ArrayList()
            val tooSmall: MutableList<Size> =
                ArrayList()
            for (option in choices) {
                if (option == desiredSize) {
                    // Set the size but don't return yet so that remaining sizes will still be logged.
                    exactSizeFound = true
                }
                if (option.height >= minSize && option.width >= minSize) {
                    bigEnough.add(option)
                } else {
                    tooSmall.add(option)
                }
            }
            if (exactSizeFound) {
                return desiredSize
            }

            // Pick the smallest of those, assuming we found any
            return if (bigEnough.size > 0) {
                // LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
                Collections.min(
                    bigEnough,
                    CompareSizesByArea()
                )
            } else {
                // LOGGER.e("Couldn't find any suitable preview size");
                choices[0]
            }
        }

        fun newInstance(
            callback: ConnectionCallback,
            imageListener: OnImageAvailableListener,
            layout: Int,
            inputSize: Size
        ): CameraConnectionFragment {
            return CameraConnectionFragment(callback, imageListener, layout, inputSize)
        }

        init {
            ORIENTATIONS.append(
                Surface.ROTATION_0,
                90
            )
            ORIENTATIONS.append(
                Surface.ROTATION_90,
                0
            )
            ORIENTATIONS.append(
                Surface.ROTATION_180,
                270
            )
            ORIENTATIONS.append(
                Surface.ROTATION_270,
                180
            )
        }
    }

    /** A [Semaphore] to prevent the app from exiting before closing the camera.  */
    private val cameraOpenCloseLock =
        Semaphore(1)

    private val captureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
        }
    }
    private var cameraId: String? = null
    private var textureView: AutoFitTextureView? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private var sensorOrientation: Int? = null
    private var previewSize: Size? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }
    private var previewReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release()
            cameraDevice = cd
            createCameraPreviewSession()
        }

        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
        }

        override fun onError(cd: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
            val activity = activity
            activity?.finish()
        }
    }

    /**
     * Shows a [Toast] on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        val activity = activity
        activity?.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {

        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        textureView = view.findViewById<View>(R.id.texture) as AutoFitTextureView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView!!.isAvailable) {
            openCamera(textureView!!.width, textureView!!.height)
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    fun setCamera(cameraId: String?) {
        this.cameraId = cameraId
    }

    /** Sets up member variables related to camera.  */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun setUpCameraOutputs() {
        val activity = activity
        val manager =
            activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(
                map!!.getOutputSizes(SurfaceTexture::class.java),
                inputSize.width,
                inputSize.height
            )

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
            } else {
                textureView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
            }
        } catch (e: CameraAccessException) {
            //  LOGGER.e(e, "Exception!");
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance("getString(R.string.tfe_ic_camera_error)")
                .show(childFragmentManager, FRAGMENT_DIALOG)
            throw IllegalStateException("getString(R.string.tfe_ic_camera_error)")
        }
        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation!!)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs()
        configureTransform(width, height)
        val activity = activity
        val manager =
            activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            if (ActivityCompat.checkSelfPermission(
                    getActivity(),
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            // LOGGER.e(e, "Exception!");
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /** Closes the current [CameraDevice].  */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != previewReader) {
                previewReader!!.close()
                previewReader = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /** Starts a background thread and its [Handler].  */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /** Stops the background thread and its [Handler].  */
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            //    LOGGER.e(e, "Exception!");
        }
    }

    /** Creates a new [CameraCaptureSession] for camera preview.  */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView!!.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            // LOGGER.i("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            // Create the reader for the preview frames.
            previewReader = ImageReader.newInstance(
                previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2
            )
            previewReader!!.setOnImageAvailableListener(imageListener, backgroundHandler)
            previewRequestBuilder!!.addTarget(previewReader!!.surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice!!.createCaptureSession(
                Arrays.asList(surface, previewReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder!!.build()
                            captureSession!!.setRepeatingRequest(
                                previewRequest!!, captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            //       LOGGER.e(e, "Exception!");
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        showToast("Failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            //        LOGGER.e(e, "Exception!");
        }
    }

    /**
     * Configures the necessary [Matrix] transformation to `mTextureView`. This method should be
     * called after the camera preview size is determined in setUpCameraOutputs and also the size of
     * `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity = activity
        if (null == textureView || null == previewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(
            0F, 0F,
            previewSize!!.height.toFloat(),
            previewSize!!.width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize!!.height,
                viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView!!.setTransform(matrix)
    }

    /**
     * Callback for Activities to use to initialize their data once the selected preview size is
     * known.
     */
    interface ConnectionCallback {
        fun onPreviewSizeChosen(size: Size?, cameraRotation: Int)
    }

    /** Compares two `Size`s based on their areas.  */
    internal class CompareSizesByArea : Comparator<Size?> {
        override fun compare(lhs: Size?, rhs: Size?): Int {
            return java.lang.Long.signum(
                lhs!!.width.toLong() * lhs.height - rhs!!.width.toLong() * rhs.height
            )
        }
    }

    /** Shows an error message dialog.  */
    class ErrorDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
            val activity = activity
            return AlertDialog.Builder(activity)
                .setMessage(arguments.getString(ARG_MESSAGE))
                .setPositiveButton(
                    android.R.string.ok
                ) { dialogInterface, i -> activity.finish() }
                .create()
        }

        companion object {
            private const val ARG_MESSAGE = "message"
            fun newInstance(message: String?): ErrorDialog {
                val dialog =
                    ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }
    }

}