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
import kotlin.math.sqrt

/**
 * Manages sensor input for parallax effect using proper pitch/roll extraction.
 * 
 * Uses TYPE_ROTATION_VECTOR (preferred) or TYPE_GAME_ROTATION_VECTOR for orientation.
 * Falls back to TYPE_GRAVITY if rotation vectors unavailable.
 * 
 * KEY FIX (from ai.md analysis):
 * Using gravity vector components directly saturates near ±1 when phone is upright,
 * making up/down parallax appear "locked". The solution is to:
 * 1. Convert rotation vector to rotation matrix
 * 2. Extract pitch/roll angles using getOrientation()
 * 3. Use angles with baseline calibration for responsive parallax at all viewing angles
 */
class ParallaxSensorManager(context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "ParallaxSensorManager"
        private const val FILTER_ALPHA = 0.4f
        private const val FULL_TILT_DEGREES = 30f
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
    private val orientationAngles = FloatArray(3)

    private var filteredX = 0f
    private var filteredY = 0f
    
    private var baselinePitch = 0f
    private var baselineRoll = 0f
    private var calibrationCount = 0
    private var isCalibrated = false
    private var calibrationPitchSum = 0f
    private var calibrationRollSum = 0f
    
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
    }
    
    fun isRunning(): Boolean = isStarted

    override fun onSensorChanged(event: SensorEvent) {
        @Suppress("DEPRECATION")
        val displayRotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

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
        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        
        val pitch = orientationAngles[1]
        val roll = orientationAngles[2]
        
        if (!isCalibrated) {
            calibrationPitchSum += pitch
            calibrationRollSum += roll
            calibrationCount++
            if (calibrationCount >= CALIBRATION_SAMPLES) {
                baselinePitch = calibrationPitchSum / calibrationCount
                baselineRoll = calibrationRollSum / calibrationCount
                isCalibrated = true
                Log.d(TAG, "Calibrated: pitch=${Math.toDegrees(baselinePitch.toDouble())}deg")
            }
            return
        }
        
        val relativePitch = pitch - baselinePitch
        val relativeRoll = roll - baselineRoll
        
        val targetX = (-relativeRoll / FULL_TILT_RADIANS).coerceIn(-1f, 1f)
        val targetY = (-relativePitch / FULL_TILT_RADIANS).coerceIn(-1f, 1f)
        
        processTiltValues(targetX, targetY)
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
        
        val roll = kotlin.math.atan2(gx, gz)
        val pitch = kotlin.math.atan2(gy, gz)
        
        if (!isCalibrated) {
            calibrationPitchSum += pitch
            calibrationRollSum += roll
            calibrationCount++
            if (calibrationCount >= CALIBRATION_SAMPLES) {
                baselinePitch = calibrationPitchSum / calibrationCount
                baselineRoll = calibrationRollSum / calibrationCount
                isCalibrated = true
            }
            return
        }
        
        val relativePitch = pitch - baselinePitch
        val relativeRoll = roll - baselineRoll
        
        val targetX = (-relativeRoll / FULL_TILT_RADIANS).coerceIn(-1f, 1f)
        val targetY = (-relativePitch / FULL_TILT_RADIANS).coerceIn(-1f, 1f)
        
        processTiltValues(targetX, targetY)
    }
    
    private fun processTiltValues(targetX: Float, targetY: Float) {
        val tiltMag = sqrt(targetX * targetX + targetY * targetY)
        val scaledX = if (tiltMag > DEADZONE) targetX else 0f
        val scaledY = if (tiltMag > DEADZONE) targetY else 0f
        
        filteredX += FILTER_ALPHA * (scaledX - filteredX)
        filteredY += FILTER_ALPHA * (scaledY - filteredY)
        
        listener?.onTiltChanged(filteredX, filteredY)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
