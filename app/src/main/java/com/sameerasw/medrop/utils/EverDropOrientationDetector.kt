package com.sameerasw.medrop.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeviceFacing {
    FACING_UP,    // Screen is facing on top (receive only when orientation share is enabled)
    FACING_DOWN,  // Screen is facing down (share only when orientation share is enabled)
    ANYHOW        // Screen is tilted or upright (both share and receive)
}

/**
 * Detects whether the device screen is facing up, down, or anyhow using hardware sensors.
 * Implements hysteresis thresholds to prevent rapid state toggling / sensor noise.
 */
class EverDropOrientationDetector(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _deviceFacing = MutableStateFlow(DeviceFacing.ANYHOW)
    val deviceFacing: StateFlow<DeviceFacing> = _deviceFacing.asStateFlow()

    private var isListening = false
    private var filteredZ = 0f
    private var isFilterInitialized = false

    private var candidateFacing: DeviceFacing = DeviceFacing.ANYHOW
    private var candidateStartTime: Long = 0L

    companion object {
        private const val FILTER_ALPHA = 0.82f
        private const val DEBOUNCE_DURATION_MS = 350L

        // Calibration thresholds (m/s²)
        private const val THRESHOLD_FACING_UP_ENTER = 7.8f
        private const val THRESHOLD_FACING_UP_EXIT = 6.2f
        private const val THRESHOLD_FACING_DOWN_ENTER = -7.0f
        private const val THRESHOLD_FACING_DOWN_EXIT = -5.5f
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            val rawZ = event.values[2]

            // Apply exponential low-pass filter to dampen mechanical tap vibrations
            if (!isFilterInitialized) {
                filteredZ = rawZ
                isFilterInitialized = true
            } else {
                filteredZ = (FILTER_ALPHA * filteredZ) + ((1f - FILTER_ALPHA) * rawZ)
            }

            val current = _deviceFacing.value
            val instantaneousTarget = when (current) {
                DeviceFacing.FACING_UP -> {
                    if (filteredZ < THRESHOLD_FACING_UP_EXIT) {
                        if (filteredZ < THRESHOLD_FACING_DOWN_ENTER) DeviceFacing.FACING_DOWN else DeviceFacing.ANYHOW
                    } else {
                        DeviceFacing.FACING_UP
                    }
                }
                DeviceFacing.FACING_DOWN -> {
                    if (filteredZ > THRESHOLD_FACING_DOWN_EXIT) {
                        if (filteredZ > THRESHOLD_FACING_UP_ENTER) DeviceFacing.FACING_UP else DeviceFacing.ANYHOW
                    } else {
                        DeviceFacing.FACING_DOWN
                    }
                }
                DeviceFacing.ANYHOW -> {
                    if (filteredZ > THRESHOLD_FACING_UP_ENTER) {
                        DeviceFacing.FACING_UP
                    } else if (filteredZ < THRESHOLD_FACING_DOWN_ENTER) {
                        DeviceFacing.FACING_DOWN
                    } else {
                        DeviceFacing.ANYHOW
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (instantaneousTarget != current) {
                if (instantaneousTarget != candidateFacing) {
                    candidateFacing = instantaneousTarget
                    candidateStartTime = now
                } else if (now - candidateStartTime >= DEBOUNCE_DURATION_MS) {
                    _deviceFacing.value = instantaneousTarget
                }
            } else {
                candidateFacing = current
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (isListening || sensor == null || sensorManager == null) return
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        isListening = true
    }

    fun stop() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(listener)
        isListening = false
    }
}
