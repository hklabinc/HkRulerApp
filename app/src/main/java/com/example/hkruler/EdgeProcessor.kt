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

        // ① 해상도 절반 축소
        val reduced = Mat()
        Imgproc.resize(img, reduced, Size(img.cols() / 2.0, img.rows() / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)

        // ② 블러 + 샤프닝 (원문 전략)
        val blurred = Mat()
        Imgproc.GaussianBlur(reduced, blurred, Size(5.0, 5.0), 0.0)
        val sharpened = Mat()
        Core.addWeighted(reduced, 1.5, blurred, -0.5, 0.0, sharpened)

        // 출력 폴더
        val outDir = File(context.getExternalFilesDir(null), "Processed").apply { mkdirs() }
        val base = File(imagePath).nameWithoutExtension

        // ③ 샤프닝 결과 저장
        val sharpPath = File(outDir, "${base}_sharp.jpg").absolutePath
        Imgcodecs.imwrite(sharpPath, sharpened)

        // ④ Canny 엣지 (Python 파라미터와 동일)
        val gray = Mat()
        Imgproc.cvtColor(sharpened, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // ⑤ 엣지 결과 저장 (가시화를 위해 BGR로)
        val edgeVis = Mat()
        Imgproc.cvtColor(edges, edgeVis, Imgproc.COLOR_GRAY2BGR)
        val edgePath = File(outDir, "${base}_edge.jpg").absolutePath
        Imgcodecs.imwrite(edgePath, edgeVis)
    }
}
