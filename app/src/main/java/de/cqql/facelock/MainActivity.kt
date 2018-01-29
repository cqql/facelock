package de.cqql.facelock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.face.FaceDetector
import kotlinx.android.synthetic.main.activity_main.*
import org.tensorflow.contrib.android.TensorFlowInferenceInterface
import java.lang.IllegalArgumentException
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    var bgThread: HandlerThread? = null
    var handler: Handler? = null
    var reader: ImageReader? = null
    lateinit var detector: FaceDetector
    var inference: TensorFlowInferenceInterface? = null

    val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            camera.createCaptureSession(listOf(reader?.surface), stateCallback, handler)
        }

        override fun onDisconnected(camera: CameraDevice) {
            feedback("Did you just rip out your front camera?")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            feedback("Could not access the front camera (Error $error)")
        }
    }

    val stateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            feedback("Could not configure the camera")
        }

        override fun onConfigured(session: CameraCaptureSession) {
            val camera = session.device

            try {
                // The return value is actually @NonNull
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader?.surface) }!!.build()

                session.capture(req, captureCallback, handler)
            } catch (e: IllegalArgumentException) {
                // TODO: Template type is not supported by camera (maybe check camera capabilities)
                // See CameraDevice#createCaptureRequest
            } catch (e: CameraAccessException) {
                feedback("Could not access the front camera", e)
            }
        }
    }

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            feedback("Took the picture")
        }
    }

    val imageListener = ImageReader.OnImageAvailableListener {
        // Decode the JPEG into a bitmap
        val image = it.acquireLatestImage()
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Detect faces
        val frame = Frame.Builder().setBitmap(bm).build()
        val sparseFaces = detector.detect(frame)
        val nfaces = sparseFaces.size()
        val faces = Array(nfaces) { sparseFaces.valueAt(it) }

        // Select largest face as most likely phone owner
        val face = faces.maxBy { it.height * it.width }
        if (face == null) {
            feedback("Did not detect a face")
            return@OnImageAvailableListener
        }
        val faceCrop = Bitmap.createBitmap(bm,
                face.position.x.toInt(), face.position.y.toInt(),
                face.width.toInt(), face.height.toInt())

        identifyFace(faceCrop)

        val h = Handler(mainLooper)
        h.post {
            imageView.setImageBitmap(faceCrop)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // When the app is first started, the receiver has not been registered at boot time
        ScreenOnReceiver.register(this)

        auth.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                authenticateUser()
            }
        })

        detector = FaceDetector.Builder(this).build()

        if (!detector.isOperational) {
            feedback("Could not initialize face detection")
        }

        try {
            inference = TensorFlowInferenceInterface(this.assets, "facenet.pb")
        } catch (e: RuntimeException) {
            feedback("Could not load face recognition engine", e)
        }
    }

    override fun onResume() {
        super.onResume()

        bgThread = HandlerThread("Camera").also { it.start() }
        handler = Handler(bgThread?.looper)
    }

    override fun onPause() {
        bgThread?.quitSafely()

        try {
            bgThread?.join()
            bgThread = null
            handler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }

        super.onPause()
    }

    override fun onDestroy() {
        inference?.close()

        ScreenOnReceiver.unregister(this)

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Try again with permissions granted
                    authenticateUser()
                }
            }
        }
    }

    fun authenticateUser() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                Toast.makeText(this, "FaceLock needs to see your face", Toast.LENGTH_SHORT).show()
            }

            ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE)
        if (manager == null || manager !is CameraManager) {
            feedback("Could not connect to the camera")
            return
        }

        // Get front camera
        val frontId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }

        if (frontId == null) {
            feedback("Your device does not have a front camera")
            return
        }

        val characteristics = manager.getCameraCharacteristics(frontId)
        val resolutions = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val sizes = resolutions.getOutputSizes(ImageFormat.JPEG)
        val maxSize = sizes.maxBy { it.height * it.width } ?: return
        reader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener(imageListener, handler)
        }

        manager.openCamera(frontId, cameraCallback, handler)
    }

    fun identifyFace(face: Bitmap): Boolean {
        val engine = inference
        if (engine == null) {
            feedback("There is no recognition engine loaded")
            return false
        }

        // Convert bitmap into a (160, 160, 3) float tensor
        val resized = Bitmap.createScaledBitmap(face, 160, 160, true)
        val ints = IntArray(160 * 160)
        resized.getPixels(ints, 0, resized.width, 0, 0, resized.width, resized.height)
        val floats = FloatArray(160 * 160 * 3)
        for (i in 0 until ints.size) {
            val j = 3 * i
            val k = ints[i]
            floats[j + 0] = ((k shr 0xF0) and 0xFF).toFloat()
            floats[j + 1] = ((k shr 0x08) and 0xFF).toFloat()
            floats[j + 2] = ((k shr 0x00) and 0xFF).toFloat()
        }

        // Normalize the image
        val mu = floats.reduce { a, b -> a + b } / floats.size
        val std = max(
                sqrt(floats.map { it - mu }.map { it * it }.reduce { a, b -> a + b } / floats.size),
                1.0f / sqrt(floats.size.toFloat()))
        val input = floats.map { (it - mu) / std }.toFloatArray()

        // Compute the embedding
        // TODO: Set phase_train to false
        //engine.feed("phase_train", arrayOf(false), 1)
        engine.feed("input", input, 1, 160, 160, 3)
        engine.run(arrayOf("embeddings"))
        val embedding = FloatArray(128)
        engine.fetch("embeddings", embedding)

        Log.d(TAG, embedding.joinToString())

        return false
    }

    fun feedback(msg: String, e: Exception? = null) {
        Log.e(TAG, msg)
        e?.apply { Log.e(TAG, toString()) }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        val CAMERA_REQUEST = 0
        val TAG = "MainActivity"
    }
}
