package com.steve.mcpodface.ui.components.glass

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmbienceBlurTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun uniformImageIsUnchanged() {
        val c = argb(255, 10, 120, 200)
        val pixels = IntArray(8 * 6) { c }
        assertThat(boxBlurArgb(pixels, 8, 6, radius = 2).toList()).containsExactlyElementsIn(pixels.toList())
    }

    @Test fun hardEdgeBecomesAGradient() {
        // Left half black, right half white, 16 × 4.
        val black = argb(255, 0, 0, 0)
        val white = argb(255, 255, 255, 255)
        val pixels = IntArray(16 * 4) { if (it % 16 < 8) black else white }
        val out = boxBlurArgb(pixels, 16, 4, radius = 2)
        val row = (0 until 16).map { (out[it] shr 16) and 0xFF }
        // Far edges stay put, the middle is in between, and red never decreases left to right.
        assertThat(row.first()).isEqualTo(0)
        assertThat(row.last()).isEqualTo(255)
        assertThat(row[7]).isIn(1..254)
        assertThat(row[8]).isIn(1..254)
        assertThat(row).isInOrder()
        // Alpha is untouched for an opaque image.
        assertThat(out.all { it ushr 24 == 255 }).isTrue()
    }

    @Test fun zeroRadiusCopies() {
        val pixels = intArrayOf(1, 2, 3, 4)
        val out = boxBlurArgb(pixels, 2, 2, radius = 0)
        assertThat(out.toList()).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(out).isNotSameInstanceAs(pixels)
    }
}
