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
 * Manages sensor input for parallax effect using gravity-direction tilt extraction.
 * 
 * Uses TYPE_ROTATION_VECTOR (preferred) or TYPE_GAME_ROTATION_VECTOR for orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * KEY FIXES (from ai.md analysis):
 * 1. Auto-recalibrate when display rotation changes
 * 2. Use per-axis deadzone instead of magnitude-based (avoids killing single-axis motion)
 * 3. Use soft clamp (tanh) instead of hard clamp to avoid "stuck at edge" feel
 * 4. Extract gravity direction from remapped rotation matrix (more stable than Euler angles)
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
    }
    
    private fun handleRotationVector(values: FloatArray, displayRotation: Int) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        
        // Extract gravity direction from remapped rotation matrix
        // Android rotation matrix is row-major 3x3. To get world-up (0,0,1) in device coords,
        // we need the third COLUMN of the matrix: indices [2, 5, 8]
        // Negating gives gravity direction (pointing down instead of up)
        val gx = -remappedMatrix[2]
        val gy = -remappedMatrix[5]
        val gz = -remappedMatrix[8]
        
        // Compute pitch/roll from gravity direction (more stable than Euler angles)
        val roll = atan2(gx, gz)
        val pitch = atan2(gy, gz)
        
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
        
        val roll = atan2(gx, gz)
        val pitch = atan2(gy, gz)
        
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
        
        val relativePitch = pitch - baselinePitch
        val relativeRoll = roll - baselineRoll
        
        // Use soft clamp (tanh) instead of hard coerceIn to avoid "stuck at edge" feel
        // tanh approaches ±1 asymptotically, giving smooth response at all tilts
        // With FULL_TILT_DEGREES=45 and no extra multiplier, we get a gentle curve
        val normalizedX = -relativeRoll / FULL_TILT_RADIANS
        val normalizedY = -relativePitch / FULL_TILT_RADIANS
        
        val targetX = tanh(normalizedX.toDouble()).toFloat()
        val targetY = tanh(normalizedY.toDouble()).toFloat()
        
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
