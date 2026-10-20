package com.steve.mcpodface.ui.components.glass

// Pure Kotlin so it can be unit-tested on the JVM.

/**
 * Blurs ARGB [pixels] ([width] × [height]) with [passes] separable box blurs of [radius], which
 * together approximate a Gaussian. Edges clamp. Used on the tiny artwork behind [ArtworkAmbience]:
 * stretched to full screen, an unblurred 48px image shows its pixels; blurred first, it reads as a
 * smooth wash.
 */
internal fun boxBlurArgb(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3): IntArray {
    require(pixels.size == width * height) { "pixels must be width * height" }
    if (radius <= 0 || width == 0 || height == 0) return pixels.copyOf()
    var src = pixels.copyOf()
    var dst = IntArray(src.size)
    repeat(passes) {
        blurLine(src, dst, width, height, radius, horizontal = true)
        blurLine(dst, src, width, height, radius, horizontal = false)
    }
    return src
}

/** One box-blur pass along rows ([horizontal]) or columns, reading [src] and writing [dst]. */
private fun blurLine(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val window = 2 * radius + 1
    for (line in 0 until lines) {
        fun index(i: Int): Int {
            val clamped = i.coerceIn(0, length - 1)
            return if (horizontal) line * width + clamped else clamped * width + line
        }
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[index(i)]
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            dst[index(i)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            // Slide the window: drop the leftmost pixel, add the next one.
            val out = src[index(i - radius)]
            val inc = src[index(i + radius + 1)]
            a += (inc ushr 24) - (out ushr 24)
            r += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inc and 0xFF) - (out and 0xFF)
        }
    }
}
