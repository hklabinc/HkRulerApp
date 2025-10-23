package com.hklab.hkruler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var resolutionSpinner: Spinner

    private var imageCapture: ImageCapture? = null
    private var selectedResolution: Size? = null
    private var top5Resolutions: List<Size> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val ok = granted[Manifest.permission.CAMERA] == true
            if (ok) startCamera() else toast("Camera permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "init failed")
        }

        // 권한
        val needs = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            needs += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(needs.toTypedArray())

        // 1) Camera2로 Camera ID=0 의 JPEG 지원 사이즈 조회 → 상위 5개
        top5Resolutions = queryTop5JpegSizesForCameraId0()

        // 2) Spinner 세팅
        val labels = top5Resolutions.map { "${it.width}x${it.height}" }
        resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                selectedResolution = top5Resolutions.getOrNull(pos)
                // 해상도 바뀌면 프리뷰/캡처 재바인딩
                startCamera()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        captureButton.setOnClickListener { takePhoto() }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder()
                // 프리뷰는 과도하게 크지 않게 1080p 권장
                .setTargetResolution(Size(1920, 1080))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

            selectedResolution?.let { captureBuilder.setTargetResolution(it) }

            imageCapture = captureBuilder.build()

            val backCamera = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(this, backCamera, preview, imageCapture)

        }, ContextCompat.getMainExecutor(this))
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
                    // OpenCV 후처리: 절반 축소 + 샤프닝 + Canny 엣지 저장
                    EdgeProcessor(this@MainActivity).process(photoFile.absolutePath)
                }
            })
    }

    /** Camera2로 Camera ID "0"의 JPEG 출력 가능한 사이즈를 조회해 상위 5개(면적 기준) 반환 */
    private fun queryTop5JpegSizesForCameraId0(): List<Size> {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cm.getCameraCharacteristics("0")
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()

            // 너무 작은 사이즈/비정상 사이즈 필터링 (안정성 위해 1280x720 이상만)
            val filtered = sizes.filter { it.width >= 1280 && it.height >= 720 }

            filtered.sortedByDescending { it.width.toLong() * it.height.toLong() }
                .distinctBy { Pair(it.width, it.height) }
                .take(5)
        } catch (e: Exception) {
            Log.e("ResQuery", "Failed: ${e.message}")
            // 폴백: 흔한 큰 해상도들
            listOf(Size(16320,12256), Size(12288,9216), Size(8192,6144), Size(8000,6000), Size(6016,4016))
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
