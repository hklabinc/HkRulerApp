package com.hklab.hkruler.processing.filmcalib

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

object TicksCfg {
    @JvmStatic var PROFILE_SMOOTH_SIGMA = 1.2
    @JvmStatic var PROFILE_SMOOTH_RADIUS = 4
    @JvmStatic var MIN_SPACING = 2
    @JvmStatic var MAX_SPACING = 40
    @JvmStatic var NMS_SEP_FACTOR = 0.8
    @JvmStatic var TICK_GAP_LO_FACTOR = 0.60
    @JvmStatic var TICK_GAP_HI_FACTOR = 1.60
    @JvmStatic var MAX_MISSING_PER_GAP = 5
}

enum class Orientation { HORIZONTAL, VERTICAL } // horizontal: 세로 tick, vertical: 가로 tick

object Ticks {

    private fun ensureGray(img: Mat): Mat = Edges.ensureGray(img)

    /** 1D ‘어두움 에너지’ 프로필 (행/열 합) + 가우시안 1D smoothing */
    private fun energyProfile(gray: Mat, orientation: Orientation): Pair<DoubleArray, DoubleArray> {
        val g = ensureGray(gray)                // ← 반드시 8UC1 보장
        val inv = Mat()
        Core.bitwise_not(g, inv)                // inv = 255 - gray

        val reduced = Mat()
        val dim = if (orientation == Orientation.HORIZONTAL) 0 else 1 // 0: rows→1xW, 1: cols→Hx1
        Core.reduce(inv, reduced, dim, Core.REDUCE_SUM, CvType.CV_64F)

        val vec: DoubleArray = if (orientation == Orientation.HORIZONTAL) {
            DoubleArray(reduced.cols()) { x -> reduced.get(0, x)[0] }
        } else {
            DoubleArray(reduced.rows()) { y -> reduced.get(y, 0)[0] }
        }
        inv.release(); reduced.release()

        // gaussian kernel
        val sigma = TicksCfg.PROFILE_SMOOTH_SIGMA
        val radius = TicksCfg.PROFILE_SMOOTH_RADIUS
        val xs = (-radius..radius).map { it.toDouble() }
        val kList = xs.map { exp(-(it * it) / (2.0 * sigma * sigma)) }
        val ksum = kList.sum().coerceAtLeast(1e-12)
        val k = DoubleArray(kList.size) { i -> kList[i] / ksum }

        // conv same
        fun convSame(sig: DoubleArray, ker: DoubleArray): DoubleArray {
            val n = sig.size; val m = ker.size; val r = m / 2
            val out = DoubleArray(n)
            for (i in 0 until n) {
                var s = 0.0
                var w = 0.0
                for (j in 0 until m) {
                    val idx = i + j - r
                    if (idx in 0 until n) { s += sig[idx] * ker[j]; w += ker[j] }
                }
                out[i] = if (w > 0) s / w else sig[i]
            }
            return out
        }
        val sm = convSame(vec, k)
        return vec to sm
    }

    private fun findLocalMaxima(signal: DoubleArray, mask: BooleanArray): IntArray {
        val w = signal.size
        val out = ArrayList<Int>(max(1, w / 4))
        if (w >= 2) {
            if (mask[0] && signal[0] >= signal[1]) out.add(0)
            for (x in 1 until w - 1) {
                if (mask[x] && signal[x] >= signal[x - 1] && signal[x] >= signal[x + 1]) out.add(x)
            }
            if (mask[w - 1] && signal[w - 1] >= signal[w - 2]) out.add(w - 1)
        } else if (w == 1 && mask[0]) out.add(0)
        return out.toIntArray()
    }

    private fun robustSpacingFromCandidates(cands: IntArray): Int {
        if (cands.size < 3) return 5
        // diffs
        var maxVal = 0
        for (i in 0 until cands.size - 1) {
            val d = cands[i + 1] - cands[i]
            if (d > maxVal) maxVal = d
        }
        val bins = IntArray(max(1, maxVal + 1))
        for (i in 0 until cands.size - 1) {
            val d = cands[i + 1] - cands[i]
            if (d in TicksCfg.MIN_SPACING..TicksCfg.MAX_SPACING && d >= 0 && d < bins.size) bins[d]++
        }
        var step = 5; var best = -1
        for (i in bins.indices) if (bins[i] > best) { best = bins[i]; step = i }
        return max(TicksCfg.MIN_SPACING, step)
    }

    private fun nms1d(cands: IntArray, amp: DoubleArray, minSep: Int): IntArray {
        if (cands.isEmpty()) return cands
        val order = cands.indices.sortedByDescending { amp[it] }
        val taken = BooleanArray(cands.size)
        val keep = ArrayList<Int>()
        for (oi in order) {
            if (taken[oi]) continue
            val cx = cands[oi]
            keep.add(oi)

            // 인접 억제 구간 계산
            val leftBound = cx - minSep
            val rightBound = cx + minSep

            // IntArray.binarySearch는 정확히 일치해야 하므로 경계 인덱스는 수동 스캔
            var leftIdx = oi
            while (leftIdx - 1 >= 0 && cands[leftIdx - 1] >= leftBound) leftIdx--
            var rightIdx = oi
            while (rightIdx + 1 < cands.size && cands[rightIdx + 1] <= rightBound) rightIdx++

            for (i in leftIdx..rightIdx) taken[i] = true
            taken[oi] = true
        }
        return keep.map { cands[it] }.sorted().toIntArray()
    }

    private fun refineCentersByCom(gray: Mat, centers: IntArray, win: Int, orientation: Orientation): IntArray {
        val inv = Mat()
        Core.bitwise_not(gray, inv)

        val reduced = Mat()
        val dim = if (orientation == Orientation.HORIZONTAL) 0 else 1
        Core.reduce(inv, reduced, dim, Core.REDUCE_SUM, CvType.CV_64F)
        val weights = if (orientation == Orientation.HORIZONTAL) {
            DoubleArray(reduced.cols()) { x -> reduced.get(0, x)[0] }
        } else {
            DoubleArray(reduced.rows()) { y -> reduced.get(y, 0)[0] }
        }
        inv.release(); reduced.release()

        val n = weights.size
        val out = ArrayList<Int>()
        for (c in centers) {
            val s = max(0, c - win)
            val e = min(n, c + win + 1)
            var wsum = 0.0; var xsum = 0.0
            for (x in s until e) { val wv = weights[x]; wsum += wv; xsum += wv * x }
            val rc = if (wsum <= 1e-6) c else round(xsum / wsum).toInt()
            out.add(rc)
        }
        return out.distinct().sorted().toIntArray()
    }

    /** 파이썬 detect_tick_centers() 대응 */
    fun detectTickCenters(grayBgrOrGray: Mat, orientation: Orientation): IntArray {
        val gray = ensureGray(grayBgrOrGray)
        val (profile, profileSm) = energyProfile(gray, orientation)
        val minv = profileSm.minOrNull() ?: 0.0
        val maxv = profileSm.maxOrNull() ?: (minv + 1.0)
        val rng = max((maxv - minv), 1e-6)
        val scaled = ByteArray(profileSm.size) { i ->
            ((255.0 * (profileSm[i] - minv) / rng).roundToInt()).toByte()
        }

        // 1xN Mat 으로 Otsu
        val src = Mat(1, scaled.size, CvType.CV_8UC1)
        src.put(0, 0, scaled)
        val thr = Mat()
        Imgproc.threshold(src, thr, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val mask = BooleanArray(scaled.size) { i -> thr.get(0, i)[0] > 0.0 }
        src.release(); thr.release()

        val cands = findLocalMaxima(profileSm, mask)
        val amps = DoubleArray(cands.size) { i -> profileSm[cands[i]] }
        val step = robustSpacingFromCandidates(cands)
        val minSep = max(2, (TicksCfg.NMS_SEP_FACTOR * step).roundToInt())
        val peaks = nms1d(cands, amps, minSep)
        val win = max(1, step / 2)
        val centers = refineCentersByCom(gray, peaks, win, orientation)

        // 안전 장치: 너무 가까우면 더 어두운 쪽 유지
        val final = ArrayList<Int>()
        val raw = profile
        for (x in centers.sorted()) {
            if (final.isEmpty() || x - final.last() >= minSep) final.add(x)
            else {
                if (raw[x] > raw[final.last()]) final[final.lastIndex] = x
            }
        }
        gray.release()
        return final.toIntArray()
    }

    // ===== tick 보정 =====

    private fun modeInt(values: IntArray, fallback: Int = 5): Int {
        val vals = values.filter { it > 0 }
        if (vals.isEmpty()) return fallback
        val m = vals.maxOrNull()!!
        val bins = IntArray(m + 1)
        for (v in vals) bins[v]++
        return bins.indices.maxByOrNull { bins[it] } ?: fallback
    }

    data class RepairInfo(
        val logs: MutableList<String> = mutableListOf(),
        val errorSmallGap: Boolean = false,
        val baseSpacing: Int = 0,
        val meanGoodSpacing: Double = 0.0,
        val largeGapIndices: List<Int> = emptyList(),
        val smallGapIndices: List<Int> = emptyList(),
        val insertedTotal: Int = 0
    )

    /** 파이썬 repair_ticks_by_spacing() 대응 */
    fun repairTicksBySpacing(
        ticks: IntArray,
        loFactor: Double = TicksCfg.TICK_GAP_LO_FACTOR,
        hiFactor: Double = TicksCfg.TICK_GAP_HI_FACTOR,
        maxMissingPerGap: Int = TicksCfg.MAX_MISSING_PER_GAP
    ): Pair<IntArray, RepairInfo> {
        val logs = mutableListOf<String>()
        if (ticks.size < 2) return ticks to RepairInfo(logs = logs)

        val spacings = ticks.toList().zipWithNext { a: Int, b: Int -> b - a }.toIntArray()
        val base = modeInt(spacings, 5)

        val smallMask = BooleanArray(spacings.size) { i -> spacings[i] < (base * loFactor).roundToInt() }
        val largeMask = BooleanArray(spacings.size) { i -> spacings[i] > (base * hiFactor).roundToInt() }

        val smallIdx = ArrayList<Int>().apply {
            for (i in spacings.indices) if (smallMask[i]) add(i)
        }
        val largeIdx = ArrayList<Int>().apply {
            for (i in spacings.indices) if (largeMask[i]) add(i)
        }

        val good = ArrayList<Int>().apply {
            for (i in spacings.indices) if (!smallMask[i] && !largeMask[i]) add(spacings[i])
        }
        val meanGood = if (good.isNotEmpty()) good.average() else base.toDouble()

        logs += "[tick 보정] base_spacing(mode)=$base px, mean_good=${"%.2f".format(meanGood)} px"

        if (smallIdx.isNotEmpty()) {
            for (i in smallIdx) {
                logs += "[오류] 비정상적으로 작은 간격: ticks[$i]=${ticks[i]} → ticks[${i+1}]=${ticks[i+1]} (gap=${spacings[i]} px, 임계<${(base*loFactor).roundToInt()} px)"
            }
            return ticks to RepairInfo(
                logs = logs,
                errorSmallGap = true,
                baseSpacing = base,
                meanGoodSpacing = meanGood
            )
        }

        val repaired = ArrayList<Int>()
        var inserted = 0
        for (i in 0 until ticks.size - 1) {
            repaired += ticks[i]
            if (largeMask[i]) {
                val gap = spacings[i]
                var nMissing = max(1, (gap / meanGood).roundToInt() - 1)
                nMissing = min(nMissing, maxMissingPerGap)
                val trial = IntArray(nMissing) { k -> ticks[i] + ((k + 1) * meanGood).roundToInt() }
                val valid = trial.filter { it < ticks[i + 1] }.distinct().sorted()
                repaired.addAll(valid)
                inserted += valid.size
                logs += "[보간] 큰 간격 보정: i=$i, gap=$gap px → 보간 ${valid.size}개 삽입: $valid"
            }
        }
        repaired += ticks.last()
        val repairedSorted = repaired.distinct().sorted().toIntArray()

        return repairedSorted to RepairInfo(
            logs = logs,
            errorSmallGap = false,
            baseSpacing = base,
            meanGoodSpacing = meanGood,
            largeGapIndices = largeIdx,
            smallGapIndices = smallIdx,
            insertedTotal = inserted
        )
    }
}
