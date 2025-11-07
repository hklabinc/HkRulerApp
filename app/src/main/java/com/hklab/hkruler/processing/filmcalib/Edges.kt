package com.hklab.hkruler.processing.filmcalib

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** edges.py 에 해당하는 상수/함수들 */
object EdgesCfg {
    @JvmStatic var GAUSS_BLUR_KSIZE: Int = 3
    @JvmStatic var CANNY_LO: Int = 30
    @JvmStatic var CANNY_HI: Int = 90
    @JvmStatic var USE_L2: Boolean = true
    @JvmStatic var WINDOW_H: Int = 80
    @JvmStatic var WINDOW_W: Int = 80
}

object Edges {

    /** BGR 또는 Gray Mat 를 Gray 로 보장 */
    fun ensureGray(src: Mat): Mat {
        return if (src.type() == CvType.CV_8UC1) src.clone().also { /* gray already */ }
        else Mat().also { Imgproc.cvtColor(src, it, Imgproc.COLOR_BGR2GRAY) }
    }

    /** 파이썬과 동일: L2gradient 사용 시 임계 0.7 스케일 */
    private fun cannyThresholds(lo: Int, hi: Int, useL2: Boolean): Pair<Double, Double> {
        val scale = if (useL2) 0.7 else 1.0
        return lo * scale to hi * scale
    }

    /** Gray → GaussianBlur → Canny */
    fun computeEdges(
        imgBgrOrGray: Mat,
        gaussK: Int = EdgesCfg.GAUSS_BLUR_KSIZE,
        cannyLo: Int = EdgesCfg.CANNY_LO,
        cannyHi: Int = EdgesCfg.CANNY_HI,
        useL2: Boolean = EdgesCfg.USE_L2
    ): Mat {
        val gray = ensureGray(imgBgrOrGray)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(gaussK.toDouble(), gaussK.toDouble()), 0.0)
        val (t1, t2) = cannyThresholds(cannyLo, cannyHi, useL2)
        val edges = Mat()
        Imgproc.Canny(blur, edges, t1, t2, 3, useL2)
        gray.release(); blur.release()
        return edges
    }

    /** 파이썬과 동일: 엣지 픽셀 카운트 슬라이딩 합으로 가장 밀집 y/x 구간 */
    fun findDenseWindows(
        edges: Mat,
        windowH: Int = EdgesCfg.WINDOW_H,
        windowW: Int = EdgesCfg.WINDOW_W
    ): IntArray {
        val h = edges.rows()
        val w = edges.cols()
        val winH = windowH.coerceAtMost(max(1, h))
        val winW = windowW.coerceAtMost(max(1, w))

        // 행별 non-zero 카운트
        val ySum = IntArray(h)
        for (y in 0 until h) {
            val row = edges.row(y)
            ySum[y] = Core.countNonZero(row)
            row.release()
        }
        // 열별 카운트: 전치 후 행 기준으로 재사용
        val t = Mat()
        Core.transpose(edges, t)
        val xSum = IntArray(w)
        for (x in 0 until w) {
            val row = t.row(x)
            xSum[x] = Core.countNonZero(row)
            row.release()
        }
        t.release()

        fun bestWindow(sum: IntArray, win: Int): Int {
            if (sum.isEmpty()) return 0
            val actual = win.coerceAtMost(sum.size)
            var cur = 0
            for (i in 0 until actual) cur += sum[i]
            var best = cur
            var bestIdx = 0
            for (i in actual until sum.size) {
                cur += sum[i] - sum[i - actual]
                if (cur > best) { best = cur; bestIdx = i - actual + 1 }
            }
            return bestIdx
        }

        val yStart = bestWindow(ySum, winH)
        val yEnd   = min(yStart + winH, h - 1)
        val xStart = bestWindow(xSum, winW)
        val xEnd   = min(xStart + winW, w - 1)
        return intArrayOf(yStart, yEnd, xStart, xEnd)
    }
}
