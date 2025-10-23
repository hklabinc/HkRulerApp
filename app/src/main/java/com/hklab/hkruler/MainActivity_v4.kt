//package com.example.hkruler
//
//import android.Manifest
//import android.content.ContentValues
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.graphics.Bitmap
//import android.graphics.BitmapFactory
//import android.graphics.Matrix
//import android.net.Uri
//import android.os.Build
//import android.os.Bundle
//import android.provider.MediaStore
//import android.widget.Button
//import android.widget.ImageView
//import android.widget.Toast
//import androidx.activity.ComponentActivity
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.core.content.ContextCompat
//import androidx.exifinterface.media.ExifInterface
//import java.io.InputStream
//
//class MainActivity_v4 : ComponentActivity() {
//
//    private var photoUri: Uri? = null
//    private lateinit var btnOpenCamera: Button
//    private lateinit var imageView: ImageView
//
//    // 권한 요청 런처
//    private val requestPermissionLauncher =
//        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
//            if (granted) {
//                openSystemCamera()
//            } else {
//                Toast.makeText(this, "카메라 권한 필요", Toast.LENGTH_SHORT).show()
//            }
//        }
//
//    // 카메라 실행 결과 받기
//    private val cameraLauncher =
//        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//            if (result.resultCode == RESULT_OK) {
//                photoUri?.let { uri ->
//                    // 큰 사진도 안전하게 불러오기 (EXIF 방향 보정 포함)
//                    val bitmap = loadBitmapWithRotation(uri)
//                    if (bitmap != null) {
//                        imageView.setImageBitmap(bitmap)
//                        Toast.makeText(this, "사진 저장됨: $uri", Toast.LENGTH_SHORT).show()
//                    } else {
//                        Toast.makeText(this, "이미지 로드 실패", Toast.LENGTH_SHORT).show()
//                    }
//                }
//            } else {
//                Toast.makeText(this, "촬영 취소됨", Toast.LENGTH_SHORT).show()
//            }
//        }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        btnOpenCamera = findViewById(R.id.btnOpenCamera)
//        imageView = findViewById(R.id.imageView)
//
//        btnOpenCamera.setOnClickListener {
//            if (ContextCompat.checkSelfPermission(
//                    this,
//                    Manifest.permission.CAMERA
//                ) == PackageManager.PERMISSION_GRANTED
//            ) {
//                openSystemCamera()
//            } else {
//                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
//            }
//        }
//    }
//
//    private fun openSystemCamera() {
//        val contentValues = ContentValues().apply {
//            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
//            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HkRuler")
//            }
//        }
//
//        photoUri = contentResolver.insert(
//            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//            contentValues
//        )
//
//        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
//            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
//        }
//
//        cameraLauncher.launch(intent)
//    }
//
//    /**
//     * EXIF 방향 보정 + Bitmap 축소 로드
//     */
//    private fun loadBitmapWithRotation(uri: Uri): Bitmap? {
//        return try {
//            val inputStream: InputStream? = contentResolver.openInputStream(uri)
//
//            // 1단계: 크기만 먼저 확인
//            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
//            BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, options)
//
//            // 너무 크면 축소 비율 계산
//            val maxSize = 2048 // 화면에 미리보기할 최대 해상도
//            var scale = 1
//            while (options.outWidth / scale > maxSize || options.outHeight / scale > maxSize) {
//                scale *= 2
//            }
//
//            // 2단계: 실제 비트맵 로드
//            val options2 = BitmapFactory.Options().apply { inSampleSize = scale }
//            val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, options2)
//
//            // 3단계: EXIF 회전 읽어서 보정
//            val exif = inputStream?.let { ExifInterface(it) }
//            val orientation = exif?.getAttributeInt(
//                ExifInterface.TAG_ORIENTATION,
//                ExifInterface.ORIENTATION_NORMAL
//            )
//            val matrix = Matrix()
//            when (orientation) {
//                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
//                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
//                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
//            }
//
//            bitmap?.let {
//                Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }
//}
