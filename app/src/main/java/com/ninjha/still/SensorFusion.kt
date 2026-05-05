package com.ninjha.still

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.ninjha.still.core.DevicePlace
import com.ninjha.still.core.MotionState
import kotlin.math.abs
import kotlin.math.sqrt

class SensorFusion(
    context: Context,
    private val onSample: (SensorSnapshot) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var lastAcceleration = VectorSample.Zero
    private var lastGyroscope = VectorSample.Zero
    private var lastGravity = VectorSample.Zero
    private var lastLightLux: Float? = null
    private var initialStepCount: Float? = null
    private var sessionSteps = 0
    private var detectedSteps = 0

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, ACCELEROMETER_DELAY_US)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, GYROSCOPE_DELAY_US)
        }
        gravitySensor?.let {
            sensorManager.registerListener(this, it, GRAVITY_DELAY_US)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, LIGHT_DELAY_US)
        }
        stepCounter?.let {
            sensorManager.registerListener(this, it, STEP_DELAY_US)
        }
        stepDetector?.let {
            sensorManager.registerListener(this, it, STEP_DELAY_US)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> lastAcceleration = event.toVectorSample()
            Sensor.TYPE_GYROSCOPE -> lastGyroscope = event.toVectorSample()
            Sensor.TYPE_GRAVITY -> lastGravity = event.toVectorSample()
            Sensor.TYPE_LIGHT -> lastLightLux = event.values.firstOrNull()
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: 0f
                if (initialStepCount == null) initialStepCount = total
                sessionSteps = (total - (initialStepCount ?: total)).toInt().coerceAtLeast(0)
            }
            Sensor.TYPE_STEP_DETECTOR -> detectedSteps += event.values.firstOrNull()?.toInt() ?: 1
        }
        onSample(snapshot())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun SensorEvent.toVectorSample(): VectorSample {
        val x = values.getOrElse(0) { 0f }
        val y = values.getOrElse(1) { 0f }
        val z = values.getOrElse(2) { 0f }
        return VectorSample(x, y, z)
    }

    private fun snapshot(): SensorSnapshot {
        val accelerationMagnitude = lastAcceleration.magnitude
        val accelerationDeltaFromGravity = abs(accelerationMagnitude - SensorManager.GRAVITY_EARTH)
        val angularSpeedRadPerSec = lastGyroscope.magnitude
        val gravityMagnitude = lastGravity.magnitude
        val motion = when {
            accelerationDeltaFromGravity < 0.35f && angularSpeedRadPerSec < 0.15f -> MotionState.Still
            accelerationDeltaFromGravity < 1.4f || angularSpeedRadPerSec < 0.8f -> MotionState.MicroVibration
            else -> MotionState.HighMotion
        }
        val gravityOrientation = when {
            gravityMagnitude < 1f -> "unknown"
            lastGravity.z < -7f -> "screen down"
            lastGravity.z > 7f -> "screen up"
            abs(lastGravity.y) > 7f -> "upright"
            abs(lastGravity.x) > 7f -> "sideways"
            else -> "angled"
        }
        val sensorDevicePlace = when {
            lastGravity.z < -7f -> DevicePlace.FaceDown
            (lastLightLux ?: Float.MAX_VALUE) < 2f && accelerationDeltaFromGravity > 0.35f -> DevicePlace.Pocket
            abs(lastGravity.y) > 7f && accelerationDeltaFromGravity < 0.35f && angularSpeedRadPerSec < 0.15f -> DevicePlace.ChargingStand
            lastGravity.z > 5f -> DevicePlace.InHand
            angularSpeedRadPerSec > 0.25f || accelerationDeltaFromGravity > 0.35f -> DevicePlace.InHand
            else -> null
        }

        return SensorSnapshot(
            acceleration = lastAcceleration,
            accelerationMagnitude = accelerationMagnitude,
            accelerationDeltaFromGravity = accelerationDeltaFromGravity,
            gyroscope = lastGyroscope,
            angularSpeedRadPerSec = angularSpeedRadPerSec,
            gravity = lastGravity,
            gravityMagnitude = gravityMagnitude,
            gravityOrientation = gravityOrientation,
            motion = motion,
            lightLux = lastLightLux,
            sensorDevicePlace = sensorDevicePlace,
            hasAccelerometer = accelerometer != null,
            hasGyroscope = gyroscope != null,
            hasGravitySensor = gravitySensor != null,
            hasLightSensor = lightSensor != null,
            hasStepCounter = stepCounter != null,
            hasStepDetector = stepDetector != null,
            sessionSteps = maxOf(sessionSteps, detectedSteps),
        )
    }
}

data class VectorSample(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    val magnitude: Float = sqrt(x * x + y * y + z * z)

    companion object {
        val Zero = VectorSample(0f, 0f, 0f)
    }
}

data class SensorSnapshot(
    val acceleration: VectorSample,
    val accelerationMagnitude: Float,
    val accelerationDeltaFromGravity: Float,
    val gyroscope: VectorSample,
    val angularSpeedRadPerSec: Float,
    val gravity: VectorSample,
    val gravityMagnitude: Float,
    val gravityOrientation: String,
    val motion: MotionState,
    val lightLux: Float?,
    val sensorDevicePlace: DevicePlace?,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasGravitySensor: Boolean,
    val hasLightSensor: Boolean,
    val hasStepCounter: Boolean,
    val hasStepDetector: Boolean,
    val sessionSteps: Int,
) {
    companion object {
        val Empty = SensorSnapshot(
            acceleration = VectorSample.Zero,
            accelerationMagnitude = 0f,
            accelerationDeltaFromGravity = SensorManager.GRAVITY_EARTH,
            gyroscope = VectorSample.Zero,
            angularSpeedRadPerSec = 0f,
            gravity = VectorSample.Zero,
            gravityMagnitude = 0f,
            gravityOrientation = "unknown",
            motion = MotionState.Still,
            lightLux = null,
            sensorDevicePlace = null,
            hasAccelerometer = false,
            hasGyroscope = false,
            hasGravitySensor = false,
            hasLightSensor = false,
            hasStepCounter = false,
            hasStepDetector = false,
            sessionSteps = 0,
        )
    }
}

private const val ACCELEROMETER_DELAY_US = 1_000_000
private const val GYROSCOPE_DELAY_US = 1_000_000
private const val GRAVITY_DELAY_US = 1_000_000
private const val LIGHT_DELAY_US = 2_000_000
private const val STEP_DELAY_US = 2_000_000
