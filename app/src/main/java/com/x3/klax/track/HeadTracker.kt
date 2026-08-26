package com.x3.klax.track

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.x3.klax.MathX
import com.x3.klax.OneEuro
import dalvik.system.DexClassLoader
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Head tracking source ladder (field guide §5). Try each, keep the first that
 * works. Everything is normalized to OpenXR convention at this boundary:
 * right-handed, +Y up, -Z forward, quaternions xyzw.
 *
 *  1. RayNeo SLAM runtime via reflection (6DoF)
 *  2. TYPE_POSE_6DOF sensor (ENU -> XR remap)
 *  3. TYPE_GAME_ROTATION_VECTOR (3DoF, with the X3 yaw<->pitch mount remap)
 *
 * De-jank: 200 Hz gyro integration between samples, short-slerp easing,
 * One-Euro on position, ~32 ms prediction, relocalization snap at 0.5 rad.
 */
class HeadTracker(private val ctx: Context) : SensorEventListener {

    enum class Source { NONE, RUNTIME, POSE6DOF, ROTVEC }

    @Volatile var source = Source.NONE; private set

    private fun setSource(next: Source) {
        if (source != next) {
            Log.i(TAG, "pose source $source -> $next")
            source = next
        }
    }

    // Reference pose from the active source, gyro-integrated forward.
    private var qRef = MathX.quatIdentity()
    private var qCur = MathX.quatIdentity()
    private val pRaw = FloatArray(3)
    private val pFilt = FloatArray(3)
    private val pVel = FloatArray(3)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastGyroTs = 0L
    private var lastPosTs = 0L
    private val euroX = OneEuro(); private val euroY = OneEuro(); private val euroZ = OneEuro()

    // Recenter = yaw-only zero (never touch gravity) + position origin.
    private var yawZero = 0f
    private val pZero = FloatArray(3)

    // RayNeo runtime reflection handles
    private var fxrApi: Any? = null
    private var mGetPose: java.lang.reflect.Method? = null
    private var mGetQuat: java.lang.reflect.Method? = null
    private var fPos: java.lang.reflect.Field? = null

    private var sensorManager: SensorManager? = null

    fun start() {
        tryRuntime()
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sm = sensorManager!!
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        if (source != Source.RUNTIME) {
            val p6 = sm.getDefaultSensor(Sensor.TYPE_POSE_6DOF)
            if (p6 != null) {
                sm.registerListener(this, p6, SensorManager.SENSOR_DELAY_FASTEST)
                setSource(Source.POSE6DOF)
            } else {
                sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                    setSource(Source.ROTVEC)
                }
            }
        }
        Log.i(TAG, "head source=$source")
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    /** Field guide §5.1 — load FxrApi out of the firmware calibration APK. */
    private fun tryRuntime() {
        try {
            val sdkInfo = ctx.packageManager.getApplicationInfo("com.ffalcon.calibration", 0)
            val loader = DexClassLoader(
                sdkInfo.sourceDir, ctx.codeCacheDir.path,
                ctx.applicationInfo.nativeLibraryDir + File.pathSeparator + sdkInfo.nativeLibraryDir,
                ctx.classLoader
            )
            val fxr = loader.loadClass("com.rayneo.xr.sdk.FxrApi")
            val api = fxr.getMethod("Get").invoke(null) ?: return
            val ok1 = fxr.getMethod("initAndroid", Context::class.java).invoke(api, ctx) as? Boolean ?: false
            if (!ok1) { Log.w(TAG, "FxrApi initAndroid=false"); return }
            val ok2 = api.javaClass.getMethod("bindService").invoke(api) as? Boolean ?: false
            if (!ok2) { Log.w(TAG, "FxrApi bindService=false"); return }
            fxr.getMethod("StartXR").invoke(api)
            mGetPose = fxr.getMethod("GetHeadTrackerPose", Long::class.javaPrimitiveType)
            fxrApi = api
            setSource(Source.RUNTIME)
            Log.i(TAG, "RayNeo SLAM runtime bound")
        } catch (t: Throwable) {
            Log.w(TAG, "runtime unavailable: $t")
        }
    }

    /** Poll SLAM runtime; call once per frame from the GL thread. */
    fun pollRuntime() {
        val api = fxrApi ?: return
        try {
            val pose = mGetPose?.invoke(api, System.nanoTime()) ?: return
            if (mGetQuat == null) {
                mGetQuat = pose.javaClass.getMethod("GetQuaternion")
                fPos = pose.javaClass.fields.firstOrNull { it.name.contains("position", true) }
            }
            @Suppress("UNCHECKED_CAST")
            val q = mGetQuat?.invoke(pose) as? FloatArray ?: return
            val p = fPos?.get(pose) as? FloatArray
            onSourceSample(MathX.quatNormalize(q), p)
        } catch (t: Throwable) {
            // Runtime hiccups (loadProfile NPE family) are guarded at the
            // process level in MainActivity; here just skip the frame.
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val now = e.timestamp
                if (lastGyroTs != 0L) {
                    val dt = (now - lastGyroTs) * 1e-9f
                    if (dt in 1e-5f..0.05f) {
                        // Integrate in body frame between source samples.
                        val dq = MathX.quatFromGyro(e.values[0], e.values[1], e.values[2], dt)
                        qRef = MathX.quatNormalize(MathX.quatMul(qRef, dq))
                    }
                }
                lastGyro = floatArrayOf(e.values[0], e.values[1], e.values[2])
                lastGyroTs = now
            }
            Sensor.TYPE_POSE_6DOF -> {
                if (source != Source.POSE6DOF) return
                // [qx qy qz qw tx ty tz] in Android ENU; ENU->XR: x'=x, y'=z, z'=-y
                val q = MathX.quatNormalize(floatArrayOf(e.values[0], e.values[2], -e.values[1], e.values[3]))
                val p = floatArrayOf(e.values[4], e.values[6], -e.values[5])
                onSourceSample(q, p)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (source != Source.ROTVEC) return
                val m = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(m, e.values)
                // Android's rotation-vector matrix is row-major. Use the device
                // -Z forward column, matching the proven X3/argrab path; using
                // the third row is the classic "look right pans up" regression.
                val fx = -m[2]; val fy = -m[5]; val fz = -m[8]
                var yaw = atan2(fx, -fz)
                var pitch = asin(fy.coerceIn(-1f, 1f))
                // THE X3 MOUNT REMAP (verified on device, non-obvious):
                val t = yaw; yaw = pitch; pitch = -t
                val q = MathX.quatMul(MathX.quatYaw(yaw), MathX.quatPitch(pitch))
                onSourceSample(MathX.quatNormalize(q), null)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onSourceSample(q: FloatArray, p: FloatArray?) {
        // Relocalization snap: big jump -> snap, don't glide.
        qRef = if (MathX.quatAngle(qRef, q) > 0.5f) q else MathX.slerp(qRef, q, 0.35f)
        if (p != null) {
            val now = System.nanoTime()
            val dt = if (lastPosTs == 0L) 0.033f else ((now - lastPosTs) * 1e-9f).coerceIn(1e-4f, 0.2f)
            lastPosTs = now
            val nx = euroX.filter(p[0], dt)
            val ny = euroY.filter(p[1], dt)
            val nz = euroZ.filter(p[2], dt)
            pVel[0] = (nx - pFilt[0]) / dt; pVel[1] = (ny - pFilt[1]) / dt; pVel[2] = (nz - pFilt[2]) / dt
            pFilt[0] = nx; pFilt[1] = ny; pFilt[2] = nz
            pRaw[0] = p[0]; pRaw[1] = p[1]; pRaw[2] = p[2]
        }
    }

    /** Recenter: yaw-only zero, position origin here. Field guide §5. */
    fun recenter() {
        yawZero = MathX.yawOf(qRef)
        pZero[0] = pFilt[0]; pZero[1] = pFilt[1]; pZero[2] = pFilt[2]
    }

    /**
     * Render pose with ~predictSec extrapolation (q x exp(0.5 w t), p + v t).
     * Returns [qx qy qz qw px py pz].
     */
    fun getPose(predictSec: Float): FloatArray {
        if (source == Source.RUNTIME) pollRuntime()
        // Ease rendered pose onto the gyro-fused reference (kills stair-step).
        qCur = MathX.slerp(qCur, qRef, 0.45f)
        var q = qCur
        if (predictSec > 0f) {
            val dq = MathX.quatFromGyro(lastGyro[0], lastGyro[1], lastGyro[2], predictSec)
            q = MathX.quatNormalize(MathX.quatMul(q, dq))
        }
        // Apply yaw-zero recenter.
        val qz = MathX.quatMul(MathX.quatYaw(-yawZero), q)
        val vMax = 3f
        val px = pFilt[0] - pZero[0] + pVel[0].coerceIn(-vMax, vMax) * predictSec
        val py = pFilt[1] - pZero[1] + pVel[1].coerceIn(-vMax, vMax) * predictSec
        val pz = pFilt[2] - pZero[2] + pVel[2].coerceIn(-vMax, vMax) * predictSec
        val pr = MathX.quatRotate(MathX.quatYaw(-yawZero), floatArrayOf(px, py, pz))
        return floatArrayOf(qz[0], qz[1], qz[2], qz[3], pr[0], pr[1], pr[2])
    }

    fun sourceLabel() = when (source) {
        Source.RUNTIME -> "RT"; Source.POSE6DOF -> "6DF"; Source.ROTVEC -> "3DF"; Source.NONE -> "--"
    }

    companion object { private const val TAG = "X3KlaxHead" }
}
