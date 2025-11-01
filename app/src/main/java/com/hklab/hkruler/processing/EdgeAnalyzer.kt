package com.hklab.hkruler.processing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class EdgeAnalyzer(
    private val overlay: LineOverlayView,
    private val throttleMs: Long = 500L      // 낮은 FPS로 분석: 전력 절감
) : ImageAnalysis.Analyzer {

    private var lastTs = 0L

    override fun analyze(image: ImageProxy) {
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            image.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTs < throttleMs) {
            image.close()
            return
        }
        lastTs = now

        val yPlane = image.planes[0]
        val w = image.width
        val h = image.height
        val yRowStride = yPlane.rowStride
        val yBuf = yPlane.buffer

        // Y(그레이스케일) 복사 (rowStride 보정)
        val gray = Mat(h, w, CvType.CV_8UC1)
        val tmp = ByteArray(yBuf.remaining())
        yBuf.get(tmp)
        var offset = 0
        for (r in 0 until h) {
            val rowEnd = offset + w
            gray.put(r, 0, tmp, offset, w)
            offset += yRowStride
            if (rowEnd > tmp.size) break
        }

        // 회전 보정
        val rot = image.imageInfo.rotationDegrees
        val rotated = Mat()
        when (rot) {
            90 -> Core.rotate(gray, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(gray, rotated, Core.ROTATE_180)
            270 -> Core.rotate(gray, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> gray.copyTo(rotated)
        }
        gray.release()

        // 다운스케일: 640 기준(속도/전력 절감)
        val baseW = 640
        val scale = baseW.toDouble() / rotated.width().coerceAtLeast(1)
        val resized = Mat()
        Imgproc.resize(rotated, resized, Size(), scale, scale, Imgproc.INTER_AREA)
        rotated.release()

        // Canny -> HoughP
        val edges = Mat()
        Imgproc.GaussianBlur(resized, resized, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(resized, edges, 50.0, 150.0)

        val lines = Mat()
        Imgproc.HoughLinesP(
            edges, lines, 1.0, Math.PI / 180.0, 60, 80.0, 10.0
        )

        val out = mutableListOf<LineN>()
        if (lines.rows() > 0) {
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0)
                val x1 = v[0].toFloat(); val y1 = v[1].toFloat()
                val x2 = v[2].toFloat(); val y2 = v[3].toFloat()
                val dx = x2 - x1; val dy = y2 - y1
                val angle = (Math.toDegrees(atan2(dy, dx).toDouble()) + 180) % 180
                val len = sqrt(dx * dx + dy * dy)
                out += LineN(x1, y1, x2, y2, angle.toFloat(), len)
            }
            // 수평/수직 우선순위로 2~3개씩 뽑기
            val horiz = out.filter { it.angleDeg < 30f || it.angleDeg > 150f }.sortedByDescending { it.score }.take(3)
            val vert = out.filter { abs(it.angleDeg - 90f) < 30f }.sortedByDescending { it.score }.take(3)
            out.clear(); out.addAll(horiz + vert)
        }

        overlay.setSourceSize(resized.width(), resized.height())
        overlay.update(out)

        lines.release()
        edges.release()
        resized.release()
        image.close()
    }
}
