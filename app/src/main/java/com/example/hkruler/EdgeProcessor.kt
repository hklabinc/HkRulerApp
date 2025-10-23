package com.hklab.hkruler

import android.content.Context
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class EdgeProcessor(private val context: Context) {

    fun process(imagePath: String) {
        val img = Imgcodecs.imread(imagePath)
        if (img.empty()) return

        // ① 해상도 절반으로 축소
        val reduced = Mat()
        Imgproc.resize(img, reduced, Size(img.cols() / 2.0, img.rows() / 2.0))

        // ② 블러 + 샤프닝
        val blurred = Mat()
        Imgproc.GaussianBlur(reduced, blurred, Size(5.0, 5.0), 0.0)
        val sharpened = Mat()
        Core.addWeighted(reduced, 1.5, blurred, -0.5, 0.0, sharpened)

        // ③ 저장 (원본명 유지)
        val outDir = File(context.getExternalFilesDir(null), "Processed")
        outDir.mkdirs()
        val baseName = File(imagePath).nameWithoutExtension
        val sharpenPath = File(outDir, "${baseName}_sharp.jpg").absolutePath
        Imgcodecs.imwrite(sharpenPath, sharpened)

        // ④ Canny 엣지 검출
        val gray = Mat()
        Imgproc.cvtColor(sharpened, gray, Imgproc.COLOR_BGR2GRAY)
        val edges = Mat()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // ⑤ 엣지 결과를 저장
        val edgeVis = Mat()
        Imgproc.cvtColor(edges, edgeVis, Imgproc.COLOR_GRAY2BGR)
        val edgePath = File(outDir, "${baseName}_edge.jpg").absolutePath
        Imgcodecs.imwrite(edgePath, edgeVis)
    }
}
