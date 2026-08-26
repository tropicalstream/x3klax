package com.x3.klax.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Neon line renderer. Every segment is extruded into a quad on the game
 * plane (glLineWidth is unreliable on mobile GPUs) and drawn twice:
 * a wide, faint glow pass and a narrow, hot core pass. Additive blending;
 * on the X3 waveguides black is transparent, so glow = light.
 *
 * The game feeds plane-local 2D coordinates; the plane basis scales them
 * into world units.
 *
 * ZERO allocations in the per-frame path — everything is scalar math into
 * a preallocated vertex array. The old FloatArray-per-vertex version
 * caused GC hitches (visible stutter) at a few thousand segments/frame.
 */
class NeonBatch {

    // Plane basis in world space. With the isometric tilt the game sets
    // `up` leaning away from the camera and `normal` pointing back toward
    // it, so lifted elements genuinely pop out in stereo.
    var origin = floatArrayOf(0f, 0f, 0f)
    var right = floatArrayOf(1f, 0f, 0f)
    var up = floatArrayOf(0f, 1f, 0f)
    var normal = floatArrayOf(0f, 0f, 1f)

    /** Height above the plane (plane units) applied to everything drawn
     *  until changed — set per element group, reset to 0 after. */
    var lift = 0f

    fun setBasis(
        ox: Float, oy: Float, oz: Float,
        rx: Float, ry: Float, rz: Float,
        ux: Float, uy: Float, uz: Float,
        nx: Float, ny: Float, nz: Float
    ) {
        origin[0] = ox; origin[1] = oy; origin[2] = oz
        right[0] = rx; right[1] = ry; right[2] = rz
        up[0] = ux; up[1] = uy; up[2] = uz
        normal[0] = nx; normal[1] = ny; normal[2] = nz
    }

    private var verts = FloatArray(60_000 * 7) // xyz rgba
    private var count = 0
    private var buffer: FloatBuffer =
        ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0; private var aColor = 0; private var uMvp = 0

    fun init() {
        val vs = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vColor = aColor;
            }
        """
        val fs = """
            precision mediump float;
            varying vec4 vColor;
            void main() { gl_FragColor = vec4(vColor.rgb * vColor.a, vColor.a); }
        """
        program = link(vs, fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    fun begin() { count = 0 }

    /** Plane-local (u,v) -> world, written straight into the vertex array. */
    private fun push(u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) =
        pushW(u, v, lift, r, g, b, a)

    /** Same, with an explicit height above the plane. */
    private fun pushW(u: Float, v: Float, h: Float,
                      r: Float, g: Float, b: Float, a: Float) {
        if ((count + 1) * 7 > verts.size) return // saturate, don't crash
        val i = count * 7
        verts[i] = origin[0] + right[0] * u + up[0] * v + normal[0] * h
        verts[i + 1] = origin[1] + right[1] * u + up[1] * v + normal[1] * h
        verts[i + 2] = origin[2] + right[2] * u + up[2] * v + normal[2] * h
        verts[i + 3] = r; verts[i + 4] = g; verts[i + 5] = b; verts[i + 6] = a
        count++
    }

    /** Vertical wireframe post from height h0 to h1 at plane point (u,v) —
     *  the corner struts of extruded bricks and the paddle. No glow pass. */
    fun post(u: Float, v: Float, h0: Float, h1: Float, width: Float,
             r: Float, g: Float, b: Float, a: Float) {
        val hw = width * 0.5f
        pushW(u - hw, v, h0, r, g, b, a); pushW(u + hw, v, h0, r, g, b, a)
        pushW(u + hw, v, h1, r, g, b, a)
        pushW(u - hw, v, h0, r, g, b, a); pushW(u + hw, v, h1, r, g, b, a)
        pushW(u - hw, v, h1, r, g, b, a)
    }

    /** Two triangles from 4 plane-local corners, CCW. */
    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float,
                     x2: Float, y2: Float, x3: Float, y3: Float,
                     r: Float, g: Float, b: Float, a: Float) {
        push(x0, y0, r, g, b, a); push(x1, y1, r, g, b, a); push(x2, y2, r, g, b, a)
        push(x0, y0, r, g, b, a); push(x2, y2, r, g, b, a); push(x3, y3, r, g, b, a)
    }

    /** Neon segment in plane-local units: glow pass + core pass. */
    fun line(u1: Float, v1: Float, u2: Float, v2: Float, width: Float,
             r: Float, g: Float, b: Float, a: Float = 1f, glow: Boolean = true) {
        var dx = u2 - u1; var dy = v2 - v1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return
        dx /= len; dy /= len
        val nx = -dy; val ny = dx
        // Vectrex discipline: a tight halo, not a bloom
        if (glow) linePass(u1, v1, u2, v2, dx, dy, nx, ny, width * 2.5f, r, g, b, a * 0.10f)
        linePass(u1, v1, u2, v2, dx, dy, nx, ny, width, r, g, b, a)
    }

    private fun linePass(u1: Float, v1: Float, u2: Float, v2: Float,
                         dx: Float, dy: Float, nx: Float, ny: Float, w: Float,
                         r: Float, g: Float, b: Float, a: Float) {
        val hw = w * 0.5f
        // extend caps by half width so joints look welded
        val ax = u1 - dx * hw; val ay = v1 - dy * hw
        val bx = u2 + dx * hw; val by = v2 + dy * hw
        quad(
            ax + nx * hw, ay + ny * hw,
            bx + nx * hw, by + ny * hw,
            bx - nx * hw, by - ny * hw,
            ax - nx * hw, ay - ny * hw,
            r, g, b, a
        )
    }

    fun circle(u: Float, v: Float, radius: Float, width: Float,
               r: Float, g: Float, b: Float, a: Float = 1f, segs: Int = 20) {
        var px = u + radius; var py = v
        for (i in 1..segs) {
            val t = i.toFloat() / segs * (Math.PI * 2).toFloat()
            val x = u + radius * cos(t); val y = v + radius * sin(t)
            line(px, py, x, y, width, r, g, b, a)
            px = x; py = y
        }
    }

    /** Filled solid quad (particles, token cores). */
    fun fill(u: Float, v: Float, halfW: Float, halfH: Float, angle: Float,
             r: Float, g: Float, b: Float, a: Float) {
        val c = cos(angle); val s = sin(angle)
        quad(
            u + (-halfW) * c - (-halfH) * s, v + (-halfW) * s + (-halfH) * c,
            u + halfW * c - (-halfH) * s, v + halfW * s + (-halfH) * c,
            u + halfW * c - halfH * s, v + halfW * s + halfH * c,
            u + (-halfW) * c - halfH * s, v + (-halfW) * s + halfH * c,
            r, g, b, a
        )
    }

    fun draw(mvp: FloatArray) {
        if (count == 0) return
        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) // additive neon
        buffer.clear()
        buffer.put(verts, 0, count * 7)
        buffer.position(0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(aPos)
        buffer.position(3)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 28, buffer)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aColor)
    }
}
