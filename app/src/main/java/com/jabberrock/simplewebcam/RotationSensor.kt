package com.jabberrock.simplewebcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Game rotation vector (no magnetometer). [deviceToArbitraryZVertical] matches
 * [SensorManager.getQuaternionFromVector]: a unit quaternion **q** such that (in the same sense as
 * `getRotationMatrix`) **v_world = rotate(q, v_phone)** — i.e. **device → game/world** frame.
 * “Up” follows the game frame’s Z axis; yaw is arbitrary without a magnetometer.
 */
class RotationSensor(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameRotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val _deviceToArbitraryZVertical =
        MutableStateFlow(Quaternion(w = 1.0, x = 0.0, y = 0.0, z = 0.0))

    val deviceToArbitraryZVertical: StateFlow<Quaternion> =
        _deviceToArbitraryZVertical.asStateFlow()

    private var listening = false

    private val listener =
        object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    return
                }
                if (event.values.size < 4) {
                    return
                }
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                val w = q[0].toDouble()
                val x = q[1].toDouble()
                val y = q[2].toDouble()
                val z = q[3].toDouble()
                _deviceToArbitraryZVertical.value =
                    Quaternion(w = w, x = x, y = y, z = z)
            }
        }

    fun start() {
        if (listening || gameRotationSensor == null) {
            return
        }
        listening =
            sensorManager.registerListener(
                listener,
                gameRotationSensor,
                SensorManager.SENSOR_DELAY_GAME,
            )
    }

    fun stop() {
        if (!listening) {
            return
        }
        sensorManager.unregisterListener(listener)
        listening = false
    }
}
