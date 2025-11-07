package com.hklab.hkruler.processing.filmcalib

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

object RansacCfg {
    @JvmStatic var RANSAC_ITERS = 600
    @JvmStatic var EPS_PX = 2.0
    @JvmStatic var THETA0_DEG = 12.0
    @JvmStatic var MIN_EDGE_POINTS = 20
}

data class LineDraw(val p1: Point, val p2: Point, val color: Scalar, val thick: Int)
data class RansacResult(
    val lineH: LineDraw?, val lineV: LineDraw?,
    val intersect: Point?, val logs: List<String>
)

private data class ABC(val a: Double, val b: Double, val c: Double)

object RansacLines {

    private fun lineFromPoints(p1: DoubleArray, p2: DoubleArray): ABC? {
        val vx = p2[0] - p1[0]; val vy = p2[1] - p1[1]
        val n = doubleArrayOf(-vy, vx)
        val nn = hypot(n[0], n[1])
        if (nn < 1e-9) return null
        val a = n[0] / nn; val b = n[1] / nn
        val c = -(a * p1[0] + b * p1[1])
        return ABC(a, b, c)
    }

    /** TLS (SVD)로 inliers에 대한 직선 모델 */
    private fun tlsFromInliers(points: Array<DoubleArray>): ABC {
        // 중심 제거
        val cx = points.map { it[0] }.average()
        val cy = points.map { it[1] }.average()
        val X = Mat(points.size, 2, CvType.CV_64F)
        for ((i, p) in points.withIndex()) {
            X.put(i, 0, p[0] - cx)
            X.put(i, 1, p[1] - cy)
        }
        val w = Mat(); val u = Mat(); val vt = Mat()
        Core.SVDecomp(X, w, u, vt)
        val d = doubleArrayOf(vt.get(0, 0)[0], vt.get(0, 1)[0])  // 주성분 방향
        // 법선 = [-d_y, d_x]
        val na = -d[1]; val nb = d[0]
        val norm = hypot(na, nb).coerceAtLeast(1e-12)
        val a = na / norm; val b = nb / norm
        val c = -(a * cx + b * cy)
        X.release(); w.release(); u.release(); vt.release()
        return ABC(a, b, c)
    }

    private fun angleDeg(p1: DoubleArray, p2: DoubleArray): Double {
        val ang = (Math.toDegrees(atan2(p2[1] - p1[1], p2[0] - p1[0])) + 180.0) % 180.0
        return ang
    }

    private fun ransacFitLine(pts: Array<DoubleArray>, target: String): Pair<ABC?, BooleanArray?> {
        val N = pts.size
        if (N < 2) return null to null
        val rng = java.util.Random()
        var bestMask: BooleanArray? = null
        var bestCnt = -1

        repeat(RansacCfg.RANSAC_ITERS) {
            var i = 0; var j = 0
            do {
                i = rng.nextInt(N); j = rng.nextInt(N)
            } while (i == j)
            val p1 = pts[i]; val p2 = pts[j]
            val ang = angleDeg(p1, p2)

            if (target == "horizontal") {
                if (!(ang < RansacCfg.THETA0_DEG || ang > 180.0 - RansacCfg.THETA0_DEG)) return@repeat
            } else { // vertical
                if (abs(ang - 90.0) > RansacCfg.THETA0_DEG) return@repeat
            }

            val model = lineFromPoints(p1, p2) ?: return@repeat
            val a = model.a; val b = model.b; val c = model.c
            val mask = BooleanArray(N)
            var cnt = 0
            for (k in 0 until N) {
                val d = abs(a * pts[k][0] + b * pts[k][1] + c)
                if (d < RansacCfg.EPS_PX) { mask[k] = true; cnt++ }
            }
            if (cnt > bestCnt) { bestCnt = cnt; bestMask = mask }
        }

        if (bestMask == null || bestCnt < 2) return null to null
        val inliers = ArrayList<DoubleArray>()
        for (i in 0 until N) if (bestMask!![i]) inliers += pts[i]
        val abc = tlsFromInliers(inliers.toTypedArray())
        return abc to bestMask
    }

    private fun intersect(L1: ABC, L2: ABC): Point? {
        val det = L1.a * L2.b - L2.a * L1.b
        if (abs(det) < 1e-9) return null
        val x = (L1.b * L2.c - L2.b * L1.c) / det
        val y = (L2.a * L1.c - L1.a * L2.c) / det
        return Point(x, y)
    }

    private fun clipLineToRoi(abc: ABC, w: Int, h: Int): Pair<Point, Point> {
        val a = abc.a; val b = abc.b; val c = abc.c
        val pts = ArrayList<Point>()
        val eps = 1e-9

        if (abs(b) > eps) {
            val y0 = -(a * 0 + c) / b
            val y1 = -(a * (w - 1) + c) / b
            if (y0 in 0.0..(h - 1.0)) pts += Point(0.0, y0)
            if (y1 in 0.0..(h - 1.0)) pts += Point((w - 1.0), y1)
        }
        if (abs(a) > eps) {
            val x0 = -(b * 0 + c) / a
            val x1 = -(b * (h - 1) + c) / a
            if (x0 in 0.0..(w - 1.0)) pts += Point(x0, 0.0)
            if (x1 in 0.0..(w - 1.0)) pts += Point(x1, (h - 1.0))
        }
        // 부족 시 ROI 중심 투영 + 긴 세그먼트
        if (pts.size < 2) {
            val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
            val t = (a * cx + b * cy + c)
            val px = cx - a * t; val py = cy - b * t
            val dx = b; val dy = -a
            val dn = hypot(dx, dy).coerceAtMost(1e-12)
            val ux = dx / dn; val uy = dy / dn
            val p1 = Point((px - ux * w).coerceIn(0.0, w - 1.0), (py - uy * h).coerceIn(0.0, h - 1.0))
            val p2 = Point((px + ux * w).coerceIn(0.0, w - 1.0), (py + uy * h).coerceIn(0.0, h - 1.0))
            pts.clear(); pts += p1; pts += p2
        }
        // 가장 먼 2점
        var dmax = -1.0; var pair = Pair(pts[0], pts[1])
        for (i in 0 until pts.size) for (j in i + 1 until pts.size) {
            val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
            if (d > dmax) { dmax = d; pair = Pair(pts[i], pts[j]) }
        }
        return pair
    }

    /** 파이썬 process_roi_ransac() 대응 */
    fun processRoiRansac(edges: Mat, roi: Rect, label: String = "ROI"): RansacResult {
        val logs = mutableListOf<String>()
        val sub = edges.submat(roi)
        val ysxs = Mat()
        Core.findNonZero(sub, ysxs) // (x,y)
        if (ysxs.empty() || ysxs.rows() < RansacCfg.MIN_EDGE_POINTS) {
            logs += "[경고] $label: ROI 내 엣지 픽셀이 부족해 RANSAC 생략."
            sub.release(); ysxs.release()
            return RansacResult(null, null, null, logs)
        }
        val pts = Array(ysxs.rows()) { i ->
            val p = ysxs.get(i, 0)
            doubleArrayOf(p[0], p[1])
        }
        sub.release(); ysxs.release()

        val (Lh, _) = ransacFitLine(pts, "horizontal")
        val (Lv, _) = ransacFitLine(pts, "vertical")
        if (Lh == null || Lv == null) {
            logs += "[경고] $label: RANSAC 대표선 추정 실패(픽셀/방향 부족)."
            return RansacResult(null, null, null, logs)
        }
        val (h1, h2) = clipLineToRoi(Lh, roi.width, roi.height)
        val (v1, v2) = clipLineToRoi(Lv, roi.width, roi.height)

        val lineH = LineDraw(
            Point(roi.x + h1.x, roi.y + h1.y),
            Point(roi.x + h2.x, roi.y + h2.y),
            Scalar(255.0, 255.0, 0.0), 1 // cyan
        )
        val lineV = LineDraw(
            Point(roi.x + v1.x, roi.y + v1.y),
            Point(roi.x + v2.x, roi.y + v2.y),
            Scalar(255.0, 0.0, 255.0), 1 // magenta
        )

        val interLocal = intersect(Lh, Lv)
        val inter = interLocal?.let { Point(roi.x + it.x, roi.y + it.y) }
        if (inter != null) logs += "[$label-RANSAC] 교점(Global) X=${inter.x.toInt()}, Y=${inter.y.toInt()}"
        else logs += "[경고] $label: 두 직선이 거의 평행하여 교점 계산 불가."

        return RansacResult(lineH, lineV, inter, logs)
    }
}
