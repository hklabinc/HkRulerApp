package com.hklab.hkruler.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import com.hklab.hkruler.processing.filmcalib.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

data class FilmEdgeParams(
    val PIXELS_PER_MM: Int = 16,
    val WH_M2379_MM: Pair<Int, Int> = 323 to 75, // (width_mm, height_mm)

    val ROI_OFFSET_X_PX: Int = 5,
    val ROI_OFFSET_Y_BASE_PX: Int = 5,
    val ROI_OFFSET_Y_MM: Int = 45,
    val ROI_WIDTH_MM: Int = 8,
    val ROI_HEIGHT_MM: Int = 30,
    val SHIFT_DISTANCE_MM: Int = 312,

    // colors (BGR)
    val CLR_RECT_H: Scalar = Scalar(0.0, 0.0, 255.0),
    val CLR_RECT_V: Scalar = Scalar(0.0, 255.0, 0.0),
    val CLR_TICK: Scalar    = Scalar(0.0, 255.0, 255.0),
    val CLR_INTER: Scalar   = Scalar(0.0, 255.0, 128.0),
    val T_THICK: Int = 1,
    val RECT_THICK: Int = 1,
    val ROI_RECT_THICK: Int = 2
)

data class FilmEdgeResult(
    val overlayFile: File,
    val edgeFile: File,
    val logs: List<String> = emptyList(),
    val w: Int,
    val h: Int
)

object FilmEdgeProcessor {

    /** 파일 경로 입력 → 파이프라인 실행 → 캐시 폴더에 overlay/edge PNG 생성 후 경로 반환 */
    fun processFromFile(
        context: Context,
        input: File,
        cacheDir: File,
        params: FilmEdgeParams = FilmEdgeParams(),
        onDebug: ((String) -> Unit)? = null
    ): FilmEdgeResult {
        require(input.exists()) { "입력 파일 없음: ${input.absolutePath}" }

        // 0) 이미지 로드 + EXIF 회전 반영 + 가로 강제
        val img = loadMatWithExifAndLandscape(input, onDebug)
        val h = img.rows(); val w = img.cols()
        onDebug?.invoke("loaded ${w}x${h} (landscape enforced)")

        // 1) 전처리 + Canny
        val edges = Edges.computeEdges(img, EdgesCfg.GAUSS_BLUR_KSIZE, EdgesCfg.CANNY_LO, EdgesCfg.CANNY_HI, EdgesCfg.USE_L2)
        onDebug?.invoke("edges ok")

        // 2) 엣지 밀집 윈도우
        val win = Edges.findDenseWindows(edges, EdgesCfg.WINDOW_H, EdgesCfg.WINDOW_W)
        var yStart = win[0]; var yEnd = win[1]; var xStart = win[2]; var xEnd = win[3]
        // sanity/fallback
        if (yStart < 0 || yEnd < 0 || xStart < 0 || xEnd < 0 || yStart >= h || xStart >= w || yEnd <= yStart || xEnd <= xStart) {
            // 가운데에 기본창(폭: w/3, 높이: h/3)
            val cw = max(2, w / 3)
            val ch = max(2, h / 3)
            xStart = max(0, (w - cw) / 2); xEnd = min(w - 1, xStart + cw)
            yStart = max(0, (h - ch) / 2); yEnd = min(h - 1, yStart + ch)
            onDebug?.invoke("dense window fallback: [$xStart..$xEnd]x[$yStart..$yEnd]")
        }

        data class DrawOp(
            val kind: String,
            val p1: Point? = null,
            val p2: Point? = null,
            val color: Scalar? = null,
            val thick: Int = 1,
            val center: Point? = null,
            val radius: Int = 0
        )
        val drawOps = mutableListOf<DrawOp>()
        val logs = mutableListOf<String>()

        // === 기준 캘리브레이션 박스 크기(px) ===
        val widthPx  = ceil(params.WH_M2379_MM.first  * params.PIXELS_PER_MM.toDouble()).toInt()
        val heightPx = ceil(params.WH_M2379_MM.second * params.PIXELS_PER_MM.toDouble()).toInt()

        // === 박스 (원본 동일 좌표) ===
        val rectHorizP1 = Point(xStart.toDouble(), yStart.toDouble())
        val rectHorizP2 = Point(min((xEnd + widthPx).toDouble(), (w - 1).toDouble()), yEnd.toDouble())
        val rectVertP1  = Point(xStart.toDouble(), yEnd.toDouble())
        val rectVertP2  = Point(xEnd.toDouble(), min((yEnd + heightPx).toDouble(), (h - 1).toDouble()))
        drawOps += DrawOp("rect", rectHorizP1, rectHorizP2, params.CLR_RECT_H, params.RECT_THICK)
        drawOps += DrawOp("rect", rectVertP1,  rectVertP2,  params.CLR_RECT_V, params.RECT_THICK)

        // --- (1) 가로 박스 내 '세로 tick' 검출
        val yEndHalf = yStart + max(2, (yEnd - yStart) / 2)
        var boxHoriz = Rect(xStart, yStart, (min(xEnd + widthPx, w - 1) - xStart).coerceAtLeast(2), (yEndHalf - yStart).coerceAtLeast(2))
        boxHoriz = clampRect(boxHoriz, w, h)
        val grayHoriz = Edges.ensureGray(img.submat(boxHoriz))
        val ticksHLocal = Ticks.detectTickCenters(grayHoriz, Orientation.HORIZONTAL)
        val ticksH = IntArray(ticksHLocal.size) { i -> ticksHLocal[i] + boxHoriz.x }
        val repH = Ticks.repairTicksBySpacing(ticksH)
        val ticksHRepaired = repH.first
        logs += repH.second.logs
        if (repH.second.errorSmallGap) logs += "[오류] 가로 자에서 비정상적으로 작은 간격 발견 → 시각화만 수행"
        var sumH = 0.0; var cntH = 0
        for (i in 0 until ticksHRepaired.size - 1) { sumH += (ticksHRepaired[i + 1] - ticksHRepaired[i]).toDouble(); cntH++ }
        val PIXELS_PER_MM_H = if (cntH > 0) sumH / cntH else params.PIXELS_PER_MM.toDouble()
        for (i in ticksHRepaired.indices) {
            val cx = ticksHRepaired[i]
            drawOps += DrawOp("line", Point(cx.toDouble(), boxHoriz.y.toDouble()), Point(cx.toDouble(), (boxHoriz.y + boxHoriz.height).toDouble()), params.CLR_TICK, params.T_THICK)
        }
        grayHoriz.release()

        // --- (2) 세로 박스 내 '가로 tick' 검출
        val xEndHalf = xStart + max(2, (xEnd - xStart) / 2)
        var boxVert = Rect(xStart, yEnd, (xEndHalf - xStart).coerceAtLeast(2), (min(yEnd + heightPx, h - 1) - yEnd).coerceAtLeast(2))
        boxVert = clampRect(boxVert, w, h)
        val grayVert = Edges.ensureGray(img.submat(boxVert))
        val ticksVLocal = Ticks.detectTickCenters(grayVert, Orientation.VERTICAL)
        val ticksV = IntArray(ticksVLocal.size) { i -> ticksVLocal[i] + boxVert.y }
        val repV = Ticks.repairTicksBySpacing(ticksV)
        val ticksVRepaired = repV.first
        logs += repV.second.logs
        if (repV.second.errorSmallGap) logs += "[오류] 세로 자에서 비정상적으로 작은 간격 발견 → 시각화만 수행"
        var sumV = 0.0; var cntV = 0
        for (i in 0 until ticksVRepaired.size - 1) { sumV += (ticksVRepaired[i + 1] - ticksVRepaired[i]).toDouble(); cntV++ }
        val PIXELS_PER_MM_V = if (cntV > 0) sumV / cntV else params.PIXELS_PER_MM.toDouble()
        for (i in ticksVRepaired.indices) {
            val cy = ticksVRepaired[i]
            drawOps += DrawOp("line", Point(boxVert.x.toDouble(), cy.toDouble()), Point((boxVert.x + boxVert.width).toDouble(), cy.toDouble()), params.CLR_TICK, params.T_THICK)
        }
        grayVert.release()

        // === ROI_POINT1 정의 (clamp 필수)
        val roiOffsetYPx = params.ROI_OFFSET_Y_BASE_PX + round(params.ROI_OFFSET_Y_MM * PIXELS_PER_MM_H).toInt()
        var ROI_POINT1 = Rect(
            min(xEnd + params.ROI_OFFSET_X_PX, w - 2),
            min(yEnd + roiOffsetYPx, h - 2),
            max(2, round(params.ROI_WIDTH_MM * PIXELS_PER_MM_H).toInt()),
            max(2, round(params.ROI_HEIGHT_MM * PIXELS_PER_MM_V).toInt())
        )
        ROI_POINT1 = clampRect(ROI_POINT1, w, h)
        drawOps += DrawOp("rect",
            Point(ROI_POINT1.x.toDouble(), ROI_POINT1.y.toDouble()),
            Point((ROI_POINT1.x + ROI_POINT1.width).toDouble(), (ROI_POINT1.y + ROI_POINT1.height).toDouble()),
            params.CLR_TICK, params.ROI_RECT_THICK
        )

        val r1 = RansacLines.processRoiRansac(edges, ROI_POINT1, "ROI_POINT1")
        logs += r1.logs
        val inter1 = r1.intersect

        // === ROI_POINT2: 수평 이동 + clamp
        val shiftPx = round(params.SHIFT_DISTANCE_MM * PIXELS_PER_MM_H).toInt()
        var ROI_POINT2 = Rect(ROI_POINT1.x + shiftPx, ROI_POINT1.y, ROI_POINT1.width, ROI_POINT1.height)
        ROI_POINT2 = clampRect(ROI_POINT2, w, h)
        drawOps += DrawOp("rect",
            Point(ROI_POINT2.x.toDouble(), ROI_POINT2.y.toDouble()),
            Point((ROI_POINT2.x + ROI_POINT2.width).toDouble(), (ROI_POINT2.y + ROI_POINT2.height).toDouble()),
            params.CLR_TICK, params.ROI_RECT_THICK
        )

        val r2 = RansacLines.processRoiRansac(edges, ROI_POINT2, "ROI_POINT2")
        logs += r2.logs
        val inter2 = r2.intersect

        listOf(r1.lineH, r1.lineV, r2.lineH, r2.lineV).forEach { line ->
            if (line != null) drawOps += DrawOp("line", line.p1, line.p2, line.color, line.thick)
        }
        listOf(inter1, inter2).forEach { p ->
            if (p != null) drawOps += DrawOp("circle", center = p, radius = 2, color = params.CLR_INTER, thick = -1)
        }

        // === 거리 로그
        fun computeAxisDistance(c1: Int, c2: Int, ticks: IntArray, pxPerMm: Double, axisName: String) {
            if (ticks.isEmpty()) return
            var i1 = 0; for (i in ticks.indices) if (ticks[i] < c1) i1 = i
            var i2 = 0; for (i in ticks.indices) if (ticks[i] < c2) i2 = i
            val diff1Px = (c1 - ticks[i1]); val diff2Px = (c2 - ticks[i2])
            val diff1mm = diff1Px / pxPerMm; val diff2mm = diff2Px / pxPerMm
            val indexDiff = (i2 - i1)
            logs += "[${axisName}] indexDiff=$indexDiff, diff1=${"%.2f".format(diff1mm)}mm, diff2=${"%.2f".format(diff2mm)}mm"
        }
        if (inter1 != null && inter2 != null) {
            computeAxisDistance(inter1.x.roundToInt(), inter2.x.roundToInt(), ticksHRepaired, PIXELS_PER_MM_H, "가로자")
            computeAxisDistance(inter1.y.roundToInt(), inter2.y.roundToInt(), ticksVRepaired, PIXELS_PER_MM_V, "세로자")
        } else {
            logs += "[오류] 두 ROI 중 하나 이상에서 교점 계산 실패."
        }

        // === (A) Edge 시각화 이미지 (BGR)
        val edgeVis = Mat()
        Imgproc.cvtColor(edges, edgeVis, Imgproc.COLOR_GRAY2BGR)
        fun applyDrawOps(dst: Mat) {
            for (op in drawOps) {
                when (op.kind) {
                    "rect" -> Imgproc.rectangle(dst, op.p1, op.p2, op.color, op.thick)
                    "line" -> Imgproc.line(dst, op.p1, op.p2, op.color, op.thick)
                    "circle" -> Imgproc.circle(dst, op.center, op.radius, op.color, op.thick)
                }
            }
        }
        applyDrawOps(edgeVis)

        // === (B) Overlay = 원본 위에 edgeVis의 유효 픽셀만 덮기
        val overlay = img.clone()
        applyDrawOps(overlay)   // 원본 위에 선/박스/교점 바로 그린다.

        // === 저장(캐시)
        fun matToPngFile(src: Mat, file: File) {
            val rgba = Mat()
            Imgproc.cvtColor(src, rgba, Imgproc.COLOR_BGR2RGBA)
            val b = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, b)
            FileOutputStream(file).use { out -> b.compress(Bitmap.CompressFormat.PNG, 100, out) }
            b.recycle()
            rgba.release()
        }
        val base = input.nameWithoutExtension
        val edgeFile = File(cacheDir, "${base}_edge.png")
        val overlayFile = File(cacheDir, "${base}_overlay.png")
        matToPngFile(edgeVis, edgeFile)
        matToPngFile(overlay, overlayFile)

        // 릴리즈
        edgeVis.release(); overlay.release(); edges.release(); img.release()

        return FilmEdgeResult(
            overlayFile = overlayFile,
            edgeFile = edgeFile,
            logs = logs,
            w = w, h = h
        )
    }

    /** EXIF 회전 반영 + 가로 강제(폭>높이) */
    private fun loadMatWithExifAndLandscape(file: File, onDebug: ((String) -> Unit)?): Mat {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: error("Bitmap decode 실패")
        var mat = Mat()
        Utils.bitmapToMat(bmp, mat)

        // EXIF
        try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> mat = rotate90(mat, cw = true)
                ExifInterface.ORIENTATION_ROTATE_180 -> mat = rotate180(mat)
                ExifInterface.ORIENTATION_ROTATE_270 -> mat = rotate90(mat, cw = false)
            }
        } catch (_: Throwable) {}

        // 가로 강제
        if (mat.rows() > mat.cols()) {
            mat = rotate90(mat, cw = false)
            onDebug?.invoke("rotated to landscape (90deg)")
        }

        // 채널 정규화: 항상 BGR 3채널로 만든다
        if (mat.channels() == 4) {
            val bgr = Mat()
            Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
            mat.release()
            mat = bgr
        }
        return mat
    }

    private fun rotate90(src: Mat, cw: Boolean): Mat {
        val dst = Mat()
        Core.transpose(src, dst)
        Core.flip(dst, dst, if (cw) 1 else 0) // 1: y축 기준(시계), 0: x축 기준(반시계)
        src.release()
        return dst
    }

    private fun rotate180(src: Mat): Mat {
        val dst = Mat()
        Core.flip(src, dst, -1)
        src.release()
        return dst
    }

    /** 이미지 경계로 clamp, 최소 2×2 보장 */
    private fun clampRect(r: Rect, w: Int, h: Int): Rect {
        var x0 = r.x.coerceIn(0, w - 2)
        var y0 = r.y.coerceIn(0, h - 2)
        var x1 = (r.x + r.width).coerceIn(1, w - 1)
        var y1 = (r.y + r.height).coerceIn(1, h - 1)
        if (x1 - x0 < 2) x1 = (x0 + 2).coerceAtMost(w - 1)
        if (y1 - y0 < 2) y1 = (y0 + 2).coerceAtMost(h - 1)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }
}
