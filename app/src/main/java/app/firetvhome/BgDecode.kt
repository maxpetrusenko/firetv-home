package app.firetvhome

/** Bitmap decode sizing: largest power-of-two inSampleSize that keeps the result at or above half the screen. */
object BgDecode {
    fun sampleSize(srcW: Int, srcH: Int, screenW: Int, screenH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= screenW / 2 && srcH / (s * 2) >= screenH / 2) s *= 2
        return s
    }
    fun rgb565Bytes(srcW: Int, srcH: Int, sample: Int): Long = (srcW / sample).toLong() * (srcH / sample) * 2
}
