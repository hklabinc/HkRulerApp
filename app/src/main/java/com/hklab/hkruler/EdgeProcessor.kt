package com.hklab.hkruler

import android.content.Context
import android.util.Size
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class EdgeProcessor(private val context: Context) {

    /**
     * 선택한 해상도 유지하며 엣지 검출
     * @param imagePath 원본 이미지 경로
     * @param targetSize 선택한 해상도 (없으면 원본 크기 유지)
     */
    fun process(imagePath: String, targetSize: Size? = null) {
        val img = Imgcodecs.imread(imagePath)
        if (img.empty()) return

        // ✅ 선택된 해상도로 리사이즈 (선택되지 않으면 원본 크기 유지)
        val resized = Mat()
        if (targetSize != null) {
            Imgproc.resize(img, resized, Size(targetSize.width.toDouble(), targetSize.height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            resized.assignTo(img)
        }

        // ✅ 블러 + 샤프닝 (고해상도 그대로)
        val blurred = Mat()
        Imgproc.GaussianBlur(resized, blurred, Size(5.0, 5.0), 0.0)
        val sharpened = Mat()
        Core.addWeighted(resized, 1.5, blurred, -0.5, 0.0, sharpened)

        // ✅ 결과 저장 폴더 (원본과 동일)
        val outDir = File(imagePath).parentFile!!
        val base = File(imagePath).nameWithoutExtension

        // ✅ 샤프닝 결과 저장 (선택사항)
        val sharpPath = File(outDir, "${base}_sharp.jpg").absolutePath
        Imgcodecs.imwrite(sharpPath, sharpened)

        // ✅ Canny 엣지
        val gray = Mat()
        Imgproc.cvtColor(sharpened, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // ✅ 엣지 결과 (가시화용 BGR)
        val edgeVis = Mat()
        Imgproc.cvtColor(edges, edgeVis, Imgproc.COLOR_GRAY2BGR)
        val edgePath = File(outDir, "${base}_edge.jpg").absolutePath
        Imgcodecs.imwrite(edgePath, edgeVis)

        // ✅ 자원 해제
        img.release()
        resized.release()
        blurred.release()
        sharpened.release()
        gray.release()
        edges.release()
        edgeVis.release()
    }
}
