package info.olli.weatherlwp.parallax

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Manages sensor input for parallax effect.
 * 
 * Uses TYPE_ROTATION_VECTOR (preferred) or TYPE_GAME_ROTATION_VECTOR for orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * KEY IMPLEMENTATION:
 * 1. Remap coordinate system based on display rotation (portrait/landscape)
 * 2. Use SensorManager.getOrientation() after remapping for proper pitch/roll
 * 3. Auto-recalibrate when display rotation changes
 * 4. Use per-axis deadzone instead of magnitude-based (avoids killing single-axis motion)
 * 5. Use soft clamp (tanh) instead of hard clamp to avoid "stuck at edge" feel
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        private const val FILTER_ALPHA = 0.4f
        private const val FULL_TILT_DEGREES = 45f  // Increased from 30 for more range
        private const val FULL_TILT_RADIANS = (FULL_TILT_DEGREES * PI / 180f).toFloat()
        private const val DEADZONE = 0.03f
        private const val CALIBRATION_SAMPLES = 10
    }
    
    interface Listener {
        fun onTiltChanged(tiltX: Float, tiltY: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val activeSensor: Sensor? = rotationVector ?: gameRotationVector ?: gravitySensor ?: accelerometer
    
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)  // azimuth, pitch, roll

    private var filteredX = 0f
    private var filteredY = 0f
    
    private var baselinePitch = 0f
    private var baselineRoll = 0f
    private var calibrationCount = 0
    private var isCalibrated = false
    private var calibrationPitchSum = 0f
    private var calibrationRollSum = 0f
    
    // Track display rotation for auto-recalibration
    private var lastDisplayRotation = Surface.ROTATION_0
    
    private var listener: Listener? = null
    private var isStarted = false

    fun setListener(l: Listener) { listener = l }

    fun start() {
        if (activeSensor == null) {
            Log.w(TAG, "No suitable sensor available for parallax")
            return
        }
        if (!isStarted) {
            filteredX = 0f
            filteredY = 0f
            baselinePitch = 0f
            baselineRoll = 0f
            calibrationCount = 0
            calibrationPitchSum = 0f
            calibrationRollSum = 0f
            isCalibrated = false
            lastDisplayRotation = getCurrentDisplayRotation()
            
            sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME)
            isStarted = true
            Log.d(TAG, "Sensor started, calibrating...")
        }
    }

    fun stop() {
        if (isStarted) {
            sensorManager.unregisterListener(this)
            isStarted = false
            Log.d(TAG, "Sensor stopped")
        }
    }
    
    fun recalibrate() {
        calibrationCount = 0
        calibrationPitchSum = 0f
        calibrationRollSum = 0f
        isCalibrated = false
        filteredX = 0f
        filteredY = 0f
        Log.d(TAG, "Recalibration triggered")
    }
    
    fun isRunning(): Boolean = isStarted
    
    @Suppress("DEPRECATION")
    private fun getCurrentDisplayRotation(): Int {
        return windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            val displayRotation = getCurrentDisplayRotation()
            
            // Auto-recalibrate if display rotation changed
            if (displayRotation != lastDisplayRotation) {
                Log.d(TAG, "Display rotation changed: $lastDisplayRotation -> $displayRotation, recalibrating")
                lastDisplayRotation = displayRotation
                recalibrate()
                return  // Skip this sample, wait for fresh calibration
            }

            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    handleRotationVector(event.values, displayRotation)
                }
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    handleGravityVector(event.values, displayRotation)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sensor event: ${e.message}", e)
        }
    }
    
    private fun handleRotationVector(values: FloatArray, displayRotation: Int) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        
        // Remap coordinate system based on display rotation
        // This ensures sensor data matches what the user sees on screen
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        
        // Use Android's getOrientation() for proper pitch/roll extraction
        // This is the recommended approach after remapCoordinateSystem()
        // orientationAngles[0] = azimuth (rotation around Z axis)
        // orientationAngles[1] = pitch (rotation around X axis, -π to π)
        // orientationAngles[2] = roll (rotation around Y axis, -π/2 to π/2)
        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        
        val pitch = orientationAngles[1]  // Forward/backward tilt → Y parallax
        val roll = orientationAngles[2]   // Left/right tilt → X parallax
        
        processOrientationAngles(pitch, roll)
    }
    
    private fun handleGravityVector(values: FloatArray, displayRotation: Int) {
        var gx = values[0]
        var gy = values[1]
        val gz = values[2]
        
        when (displayRotation) {
            Surface.ROTATION_90 -> { val temp = gx; gx = gy; gy = -temp }
            Surface.ROTATION_180 -> { gx = -gx; gy = -gy }
            Surface.ROTATION_270 -> { val temp = gx; gx = -gy; gy = temp }
        }
        
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 0.1f) return
        
        // Use projection-based tilt instead of atan2(*, gz) to avoid singularity when gz → 0
        // This gives stable 2D tilt even when device is vertical
        // Use sqrt(gy*gy + gz*gz) and sqrt(gx*gx + gz*gz) as denominators
        val rollDenom = sqrt(gx * gx + gz * gz)
        val pitchDenom = sqrt(gy * gy + gz * gz)
        
        val roll = if (rollDenom > 0.01f) atan2(gx, rollDenom) * if (gz < 0) -1f else 1f 
                   else if (gx > 0) (PI / 2).toFloat() else (-PI / 2).toFloat()
        val pitch = if (pitchDenom > 0.01f) atan2(gy, pitchDenom) * if (gz < 0) -1f else 1f
                    else if (gy > 0) (PI / 2).toFloat() else (-PI / 2).toFloat()
        
        processOrientationAngles(pitch, roll)
    }
    
    private fun processOrientationAngles(pitch: Float, roll: Float) {
        if (!isCalibrated) {
            calibrationPitchSum += pitch
            calibrationRollSum += roll
            calibrationCount++
            if (calibrationCount >= CALIBRATION_SAMPLES) {
                baselinePitch = calibrationPitchSum / calibrationCount
                baselineRoll = calibrationRollSum / calibrationCount
                isCalibrated = true
                Log.d(TAG, "Calibrated: pitch=${Math.toDegrees(baselinePitch.toDouble())}deg, roll=${Math.toDegrees(baselineRoll.toDouble())}deg")
            }
            return
        }
        
        // Calculate relative angles from baseline
        var relativePitch = pitch - baselinePitch
        var relativeRoll = roll - baselineRoll
        
        // Clamp relative angles to valid range to prevent extreme values
        // when phone is tilted far from calibration position
        relativePitch = relativePitch.coerceIn(-FULL_TILT_RADIANS * 1.5f, FULL_TILT_RADIANS * 1.5f)
        relativeRoll = relativeRoll.coerceIn(-FULL_TILT_RADIANS * 1.5f, FULL_TILT_RADIANS * 1.5f)
        
        // Detect near-vertical orientation (phone tilted far from flat)
        // When absolute pitch is high, reduce parallax effect to avoid erratic behavior
        val absolutePitchFromFlat = abs(pitch)
        val verticalFactor = if (absolutePitchFromFlat > (PI / 3).toFloat()) {  // > 60 degrees
            // Smoothly reduce effect as phone approaches vertical
            val excess = (absolutePitchFromFlat - (PI / 3).toFloat()) / ((PI / 6).toFloat())  // 0-1 over 60-90 degrees
            (1f - excess.coerceIn(0f, 1f))
        } else {
            1f
        }
        
        // Use soft clamp (tanh) instead of hard coerceIn to avoid "stuck at edge" feel
        // tanh approaches ±1 asymptotically, giving smooth response at all tilts
        val normalizedX = -relativeRoll / FULL_TILT_RADIANS
        val normalizedY = -relativePitch / FULL_TILT_RADIANS
        
        val targetX = tanh(normalizedX.toDouble()).toFloat() * verticalFactor
        val targetY = tanh(normalizedY.toDouble()).toFloat() * verticalFactor
        
        processTiltValues(targetX, targetY)
    }
    
    private fun processTiltValues(targetX: Float, targetY: Float) {
        // Per-axis deadzone instead of magnitude-based
        // This prevents killing single-axis motion when combined magnitude is small
        val scaledX = if (abs(targetX) > DEADZONE) targetX else 0f
        val scaledY = if (abs(targetY) > DEADZONE) targetY else 0f
        
        filteredX += FILTER_ALPHA * (scaledX - filteredX)
        filteredY += FILTER_ALPHA * (scaledY - filteredY)
        
        listener?.onTiltChanged(filteredX, filteredY)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
