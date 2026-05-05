package com.ninjha.still

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.ninjha.still.core.MotionState
import kotlin.math.sqrt

class SensorFusion(
    context: Context,
    private val onSample: (SensorSnapshot) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var lastLightLux: Float? = null

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, ACCELEROMETER_DELAY_US)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, LIGHT_DELAY_US)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lastLightLux = event.values.firstOrNull()
            Sensor.TYPE_ACCELEROMETER -> onSample(event.toSnapshot(lastLightLux))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun SensorEvent.toSnapshot(lightLux: Float?): SensorSnapshot {
        val x = values.getOrElse(0) { 0f }
        val y = values.getOrElse(1) { 0f }
        val z = values.getOrElse(2) { 0f }
        val magnitude = sqrt(x * x + y * y + z * z)
        val deltaFromGravity = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)
        val motion = when {
            deltaFromGravity < 0.35f -> MotionState.Still
            deltaFromGravity < 1.4f -> MotionState.MicroVibration
            else -> MotionState.HighMotion
        }

        return SensorSnapshot(
            accelerationMagnitude = magnitude,
            motion = motion,
            lightLux = lightLux,
        )
    }
}

data class SensorSnapshot(
    val accelerationMagnitude: Float,
    val motion: MotionState,
    val lightLux: Float?,
)

private const val ACCELEROMETER_DELAY_US = 1_000_000
private const val LIGHT_DELAY_US = 2_000_000
