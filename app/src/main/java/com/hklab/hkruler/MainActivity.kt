package com.hklab.hkruler

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var focusIndicator: View

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var selectedResolution: Size? = null
    private var top5Resolutions: List<Size> = emptyList()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 항상 가로 고정
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)

        // ✅ 초점 표시용 원 생성
        focusIndicator = View(this).apply {
            layoutParams = ViewGroup.LayoutParams(100, 100)
            background = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
            alpha = 0f
        }
        (previewView.parent as ViewGroup).addView(focusIndicator)

        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Initialization failed")
        }

        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            needs += Manifest.permission.WRITE_EXTERNAL_STORAGE
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it[Manifest.permission.CAMERA] == true) startCamera()
            else toast("Camera permission denied")
        }.launch(needs.toTypedArray())

        top5Resolutions = queryTop5JpegSizesForCameraId0()

        val labels = top5Resolutions.map { "${it.width}x${it.height}" }
        resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                selectedResolution = top5Resolutions.getOrNull(pos)
                startCamera()
            }

            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        captureButton.setOnClickListener { takePhoto() }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val backCamera = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(Surface.ROTATION_90) // ✅ 가로로 촬영
            selectedResolution?.let { captureBuilder.setTargetResolution(it) }
            val imageCapture = captureBuilder.build()

            val previewBuilder = Preview.Builder()
            selectedResolution?.let { previewBuilder.setTargetResolution(it) }
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            provider.unbindAll()
            camera = provider.bindToLifecycle(this, backCamera, preview, imageCapture)
            this.imageCapture = imageCapture

            setupTouchToFocus()

        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupTouchToFocus() {
        val cameraControl = camera?.cameraControl ?: return

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                // 터치 지점 표시 🔵
                focusIndicator.x = event.x - focusIndicator.width / 2
                focusIndicator.y = event.y - focusIndicator.height / 2
                focusIndicator.alpha = 1f
                focusIndicator.animate().alpha(0f).setDuration(1000).start()

                // 실제 초점 이동
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                cameraControl.startFocusAndMetering(action)
                toast("🔍 Focus adjusted")
            }
            true
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(
            getExternalFilesDir(null),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )

        val out = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(out, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    toast("Capture failed: ${exc.message}")
                }

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    toast("Saved: ${photoFile.name}")

                    scope.launch(Dispatchers.IO) {
                        try {
                            val edgeProcessor = EdgeProcessor(this@MainActivity)
                            selectedResolution?.let {
                                edgeProcessor.process(photoFile.absolutePath, it)
                            } ?: edgeProcessor.process(photoFile.absolutePath)

                            saveToGallery(photoFile)
                            val edgeFile = File(photoFile.parent, photoFile.nameWithoutExtension + "_edge.jpg")
                            if (edgeFile.exists()) saveToGallery(edgeFile)

                            withContext(Dispatchers.Main) {
                                toast("Original + Edge saved to gallery ✅")
                            }
                        } catch (e: Exception) {
                            Log.e("PostProcess", "Error: ${e.message}", e)
                        }
                    }
                }
            })
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HkRuler")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { galleryUri ->
            contentResolver.openOutputStream(galleryUri)?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            }
        }
    }

    private fun queryTop5JpegSizesForCameraId0(): List<Size> {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val characteristics = cm.getCameraCharacteristics("0")
            val map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()

            sizes.filter { it.width >= 1280 && it.height >= 720 }
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .distinctBy { Pair(it.width, it.height) }
                .take(5)
        } catch (e: Exception) {
            Log.e("ResQuery", "Failed: ${e.message}")
            listOf(Size(16320,12256), Size(12288,9216), Size(8192,6144))
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
