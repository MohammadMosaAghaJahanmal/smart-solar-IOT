package com.virtualcamerapro.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.io.File
import java.util.concurrent.Executors

/** Independent APK: consumes only public Camera2/CameraX APIs, no broker access. */
class ClientActivity : ComponentActivity() {
    private val worker = HandlerThread("CameraConsumer").apply { start() }
    private val handler = Handler(worker.looper)
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var provider: ProcessCameraProvider? = null
    private var photo: ImageCapture? = null
    private var api = "camera2"
    private var generation = 0
    @Volatile var previewFrames = 0L; private set
    @Volatile var lastCapture: File? = null; private set
    @Volatile var failure: String? = null; private set
    private val cameraId get() = intent.getStringExtra("cameraId") ?: "0"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = intent.getStringExtra("api") ?: "camera2"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,28,20,20) }
        status = TextView(this).apply { text = "Independent camera client · Camera $cameraId" }
        layout.addView(status)
        val actions = LinearLayout(this)
        for (mode in listOf("camera2","camerax")) actions.addView(Button(this).apply { text = mode; setOnClickListener { api = mode; open() } })
        actions.addView(Button(this).apply { text = "Capture"; setOnClickListener { capture() } })
        layout.addView(actions)
        frame = FrameLayout(this); layout.addView(frame,LinearLayout.LayoutParams(-1,0,1f)); setContentView(layout)
    }
    override fun onResume() { super.onResume(); if(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open() else requestPermissions(arrayOf(Manifest.permission.CAMERA),7) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) open() }
    private fun report(e: Throwable) { failure = e.message ?: e.toString(); runOnUiThread { status.text = failure } }
    private fun closeCamera() {
        generation++; session?.close(); session = null; device?.close(); device = null; reader?.close(); reader = null
        surface?.release(); surface = null; provider?.unbindAll(); photo = null
    }
    private fun open() {
        closeCamera(); frame.removeAllViews(); previewFrames = 0; failure = null; lastCapture = null
        status.text = "$api · requested camera ID $cameraId"
        if(api == "camerax") openCameraX() else openCamera2()
    }
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun openCameraX() {
        val view = PreviewView(this); frame.addView(view)
        val current = generation
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (current != generation) return@addListener
            try {
                provider = future.get()
                val selector = CameraSelector.Builder().addCameraFilter { cameras -> cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }.build()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image -> previewFrames++; image.close() }
                provider!!.bindToLifecycle(this,selector,preview,requireNotNull(photo),analysis)
            } catch(e: Exception) { report(e) }
        },mainExecutor)
    }
    @Suppress("MissingPermission", "DEPRECATION")
    private fun openCamera2() {
        val view = TextureView(this); frame.addView(view)
        val current = generation
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                try {
                    val manager = getSystemService(CameraManager::class.java)
                    val sizes = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    val size = sizes.minBy { kotlin.math.abs(it.width*it.height-1280*720) }
                    reader = ImageReader.newInstance(size.width,size.height,android.graphics.ImageFormat.JPEG,2)
                    reader!!.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { image ->
                        val data = ByteArray(image.planes[0].buffer.remaining()); image.planes[0].buffer.get(data)
                        val file = File(getExternalFilesDir(null),"capture-${SystemClock.elapsedRealtime()}.jpg"); file.writeBytes(data); lastCapture = file
                        runOnUiThread { status.text = "Saved ${file.name} · $previewFrames preview frames" }
                    } },handler)
                    texture.setDefaultBufferSize(1280,720); surface = Surface(texture)
                    manager.openCamera(cameraId,object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if(current != generation) { camera.close(); return }
                            device = camera
                            camera.createCaptureSession(listOf(surface!!,reader!!.surface),object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if(current != generation) { s.close(); return }; session = s
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface!!) }.build()
                                    s.setRepeatingRequest(request,object : CameraCaptureSession.CaptureCallback() { override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { previewFrames++ } },handler)
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) { report(IllegalStateException("Camera2 stream configuration failed")) }
                            },handler)
                        }
                        override fun onDisconnected(camera: CameraDevice) { camera.close(); report(IllegalStateException("Camera disconnected; reopen the session")) }
                        override fun onError(camera: CameraDevice, error: Int) { camera.close(); report(IllegalStateException("Camera2 error $error")) }
                    },handler)
                } catch(e: Exception) { report(e) }
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture,w: Int,h: Int) {}
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
        }
    }
    fun capture() {
        lastCapture = null
        try {
            if(api == "camerax") {
                val file = File(getExternalFilesDir(null),"capture-${SystemClock.elapsedRealtime()}.jpg")
                requireNotNull(photo) { "CameraX not ready" }.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) { lastCapture = file }
                    override fun onError(error: ImageCaptureException) { report(error) }
                })
            } else {
                val request = requireNotNull(device) { "Camera2 not ready" }.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(requireNotNull(reader).surface); set(CaptureRequest.JPEG_ORIENTATION,0) }.build()
                requireNotNull(session) { "Camera2 session not ready" }.capture(request,null,handler)
            }
        } catch(e: Exception) { report(e) }
    }
    override fun onPause() { closeCamera(); super.onPause() }
    override fun onDestroy() { worker.quitSafely(); executor.shutdown(); super.onDestroy() }
}
