package com.x3.klax.render

/**
 * Angular stroke font in the spirit of neon vector arcade text.
 * Glyphs live on a 0..4 x 0..6 grid, drawn as neon segments.
 * Advance is 5.5 grid units; size parameter = height of one grid unit
 * in plane-local meters (so cap height = 6 * size).
 */
object VectorFont {

    // Each glyph: floatArray of segments (x1,y1,x2,y2)*
    private val G = HashMap<Char, FloatArray>().apply {
        put('A', f(0,0, 2,6, 2,6, 4,0, 1,2, 3,2))
        put('B', f(0,0, 0,6, 0,6, 3,6, 3,6, 4,5, 4,5, 3,3, 0,3, 3,3, 3,3, 4,1, 4,1, 3,0, 3,0, 0,0))
        put('C', f(4,1, 3,0, 3,0, 1,0, 1,0, 0,1, 0,1, 0,5, 0,5, 1,6, 1,6, 3,6, 3,6, 4,5))
        put('D', f(0,0, 0,6, 0,6, 3,6, 3,6, 4,4, 4,4, 4,2, 4,2, 3,0, 3,0, 0,0))
        put('E', f(4,0, 0,0, 0,0, 0,6, 0,6, 4,6, 0,3, 3,3))
        put('F', f(0,0, 0,6, 0,6, 4,6, 0,3, 3,3))
        put('G', f(4,5, 3,6, 3,6, 1,6, 1,6, 0,5, 0,5, 0,1, 0,1, 1,0, 1,0, 3,0, 3,0, 4,1, 4,1, 4,3, 4,3, 2,3))
        put('H', f(0,0, 0,6, 4,0, 4,6, 0,3, 4,3))
        put('I', f(1,0, 3,0, 2,0, 2,6, 1,6, 3,6))
        put('J', f(0,1, 1,0, 1,0, 3,0, 3,0, 4,1, 4,1, 4,6, 2,6, 4,6))
        put('K', f(0,0, 0,6, 4,6, 0,2, 0,2, 4,0))
        put('L', f(0,6, 0,0, 0,0, 4,0))
        put('M', f(0,0, 0,6, 0,6, 2,3, 2,3, 4,6, 4,6, 4,0))
        put('N', f(0,0, 0,6, 0,6, 4,0, 4,0, 4,6))
        put('O', f(1,0, 3,0, 3,0, 4,1, 4,1, 4,5, 4,5, 3,6, 3,6, 1,6, 1,6, 0,5, 0,5, 0,1, 0,1, 1,0))
        put('P', f(0,0, 0,6, 0,6, 3,6, 3,6, 4,5, 4,5, 4,4, 4,4, 3,3, 3,3, 0,3))
        put('Q', f(1,0, 3,0, 3,0, 4,1, 4,1, 4,5, 4,5, 3,6, 3,6, 1,6, 1,6, 0,5, 0,5, 0,1, 0,1, 1,0, 2,2, 4,0))
        put('R', f(0,0, 0,6, 0,6, 3,6, 3,6, 4,5, 4,5, 4,4, 4,4, 3,3, 3,3, 0,3, 1,3, 4,0))
        put('S', f(4,5, 3,6, 3,6, 1,6, 1,6, 0,5, 0,5, 1,3, 1,3, 3,3, 3,3, 4,1, 4,1, 3,0, 3,0, 1,0, 1,0, 0,1))
        put('T', f(0,6, 4,6, 2,6, 2,0))
        put('U', f(0,6, 0,1, 0,1, 1,0, 1,0, 3,0, 3,0, 4,1, 4,1, 4,6))
        put('V', f(0,6, 2,0, 2,0, 4,6))
        put('W', f(0,6, 1,0, 1,0, 2,3, 2,3, 3,0, 3,0, 4,6))
        put('X', f(0,0, 4,6, 0,6, 4,0))
        put('Y', f(0,6, 2,3, 4,6, 2,3, 2,3, 2,0))
        put('Z', f(0,6, 4,6, 4,6, 0,0, 0,0, 4,0))
        put('0', f(1,0, 3,0, 3,0, 4,1, 4,1, 4,5, 4,5, 3,6, 3,6, 1,6, 1,6, 0,5, 0,5, 0,1, 0,1, 1,0, 0,1, 4,5))
        put('1', f(1,5, 2,6, 2,6, 2,0, 1,0, 3,0))
        put('2', f(0,5, 1,6, 1,6, 3,6, 3,6, 4,5, 4,5, 4,4, 4,4, 0,0, 0,0, 4,0))
        put('3', f(0,6, 4,6, 4,6, 2,4, 2,4, 4,2, 4,2, 4,1, 4,1, 3,0, 3,0, 1,0, 1,0, 0,1))
        put('4', f(3,0, 3,6, 3,6, 0,2, 0,2, 4,2))
        put('5', f(4,6, 0,6, 0,6, 0,4, 0,4, 3,4, 3,4, 4,3, 4,3, 4,1, 4,1, 3,0, 3,0, 0,0))
        put('6', f(3,6, 1,6, 1,6, 0,5, 0,5, 0,1, 0,1, 1,0, 1,0, 3,0, 3,0, 4,1, 4,1, 4,2, 4,2, 3,3, 3,3, 0,3))
        put('7', f(0,6, 4,6, 4,6, 1,0))
        put('8', f(1,3, 0,4, 0,4, 0,5, 0,5, 1,6, 1,6, 3,6, 3,6, 4,5, 4,5, 4,4, 4,4, 3,3, 3,3, 1,3, 1,3, 0,2, 0,2, 0,1, 0,1, 1,0, 1,0, 3,0, 3,0, 4,1, 4,1, 4,2, 4,2, 3,3))
        put('9', f(1,0, 3,0, 3,0, 4,1, 4,1, 4,5, 4,5, 3,6, 3,6, 1,6, 1,6, 0,5, 0,5, 0,4, 0,4, 1,3, 1,3, 4,3))
        put('.', f(2,0, 2,1))
        put(':', f(2,1, 2,2, 2,4, 2,5))
        put('!', f(2,2, 2,6, 2,0, 2,1))
        put('-', f(1,3, 3,3))
        put('+', f(1,3, 3,3, 2,2, 2,4))
        put('>', f(1,5, 3,3, 3,3, 1,1))
        put('<', f(3,5, 1,3, 1,3, 3,1))
        put('/', f(0,0, 4,6))
        put('%', f(0,0, 4,6, 0,5, 1,6, 3,0, 4,1))
        put(',', f(2,2, 2,1, 2,1, 1,0))
        put('\'', f(2,5, 2,6))
        put('?', f(0,5, 1,6, 1,6, 3,6, 3,6, 4,5, 4,5, 4,4, 4,4, 2,3, 2,3, 2,2, 2,0, 2,1))
        put('x', f(1,0, 3,4, 1,4, 3,0)) // small x for "x3 lives"
        put(' ', FloatArray(0))
    }

    private fun f(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }

    const val ADVANCE = 5.5f

    fun width(text: String, size: Float) = text.length * ADVANCE * size

    /**
     * Draw text. (u,v) = position of the baseline-left corner unless
     * centered; size = grid unit in meters; width = stroke width.
     */
    fun draw(batch: NeonBatch, text: String, u: Float, v: Float, size: Float,
             r: Float, g: Float, b: Float, a: Float = 1f,
             centered: Boolean = false, strokeWidth: Float = -1f) {
        val sw = if (strokeWidth > 0f) strokeWidth else (size * 0.55f).coerceAtLeast(0.0012f)
        var x = if (centered) u - width(text, size) * 0.5f else u
        for (ch in text) {
            val gl = G[ch.uppercaseChar()] ?: G[' ']!!
            var i = 0
            while (i + 3 < gl.size) {
                batch.line(
                    x + gl[i] * size, v + gl[i + 1] * size,
                    x + gl[i + 2] * size, v + gl[i + 3] * size,
                    sw, r, g, b, a
                )
                i += 4
            }
            x += ADVANCE * size
        }
    }
}
