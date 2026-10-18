package com.wetzlar.camera.lpc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device attitude suitable for perspective assistance. Yaw is provided for UI use only. */
data class LpcOrientation(val azimuthRadians: Float, val pitchRadians: Float, val rollRadians: Float)

/**
 * Reads the fused rotation-vector sensor. Call [start] while a camera preview is active and [stop]
 * when it is not; sensor hardware may be unavailable, in which case start returns false.
 * The angles use Android's natural-device coordinate frame. A UI in a rotated display orientation
 * should remap axes before feeding pitch/roll to [LPCMatrixCalculator].
 */
class LpcOrientationTracker(context: Context) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _orientation = MutableStateFlow(LpcOrientation(0f, 0f, 0f))
    val orientation: StateFlow<LpcOrientation> = _orientation.asStateFlow()

    fun start(): Boolean {
        val sensor = rotationVector ?: return false
        return manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotation = FloatArray(9)
        val angles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, angles)
        _orientation.value = LpcOrientation(angles[0], angles[1], angles[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
