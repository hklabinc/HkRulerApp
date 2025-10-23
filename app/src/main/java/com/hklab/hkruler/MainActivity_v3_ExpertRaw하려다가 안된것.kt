//package com.example.hkruler
//
//import android.content.Intent
//import android.graphics.Bitmap
//import android.graphics.BitmapFactory
//import android.net.Uri
//import android.os.Bundle
//import android.provider.MediaStore
//import android.util.Log
//import android.widget.Button
//import android.widget.ImageView
//import android.widget.Toast
//import androidx.activity.ComponentActivity
//
//class `MainActivity_v3_ExpertRaw하려다가 안된것` : ComponentActivity() {
//
//    private lateinit var btnExpertRaw: Button
//    private lateinit var imageView: ImageView
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        btnExpertRaw = findViewById(R.id.btnExpertRaw)
//        imageView = findViewById(R.id.imageView)
//
//        btnExpertRaw.setOnClickListener {
//            openExpertRaw()
//        }
//    }
//
//    private fun openExpertRaw() {
//        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
//        intent.setPackage("com.samsung.android.app.galaxyraw")
//        if (intent.resolveActivity(packageManager) != null) {
//            startActivity(intent)
//        } else {
//            Toast.makeText(this, "Expert RAW 실행 불가", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    override fun onResume() {
//        super.onResume()
//        // 앱으로 돌아올 때마다 최근 사진 자동 로드 (축소본으로)
//        val latest = getLatestImageUri()
//        if (latest != null) {
//            Log.d("ExpertRAW", "최근 사진 URI = $latest")
//            val bitmap = loadScaledBitmap(latest)
//            if (bitmap != null) {
//                imageView.setImageBitmap(bitmap)   // 축소본 표시
//            } else {
//                Toast.makeText(this, "이미지를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private fun getLatestImageUri(): Uri? {
//        val projection = arrayOf(
//            MediaStore.Images.Media._ID,
//            MediaStore.Images.Media.DATE_ADDED
//        )
//        val sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC"
//
//        contentResolver.query(
//            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//            projection,
//            null, null,
//            sortOrder
//        )?.use { cursor ->
//            if (cursor.moveToFirst()) {
//                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
//                return Uri.withAppendedPath(
//                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//                    id.toString()
//                )
//            }
//        }
//        return null
//    }
//
//    /**
//     * 원본 50MP 사진을 그대로 띄우면 OutOfMemory 발생하므로
//     * 축소해서 Bitmap을 생성한다.
//     */
//    private fun loadScaledBitmap(uri: Uri): Bitmap? {
//        return try {
//            // 먼저 이미지 크기만 읽기
//            val options = BitmapFactory.Options().apply {
//                inJustDecodeBounds = true
//            }
//            contentResolver.openInputStream(uri)?.use { stream ->
//                BitmapFactory.decodeStream(stream, null, options)
//            }
//
//            // 원하는 최대 크기 (예: 1080px 기준)
//            val maxSize = 1080
//            var scale = 1
//            while (options.outWidth / scale > maxSize || options.outHeight / scale > maxSize) {
//                scale *= 2
//            }
//
//            // 실제 디코딩 (축소 적용)
//            val decodeOptions = BitmapFactory.Options().apply {
//                inSampleSize = scale
//            }
//
//            contentResolver.openInputStream(uri)?.use { stream ->
//                BitmapFactory.decodeStream(stream, null, decodeOptions)
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            null
//        }
//    }
//}
