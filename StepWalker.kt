package com.novavr

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detecta seus passos pelo acelerometro (sem ARCore e sem permissao especial).
 * Cada passo move voce no mundo virtual por [stepLen] metros; as janelas ficam paradas no lugar.
 * Parado = nenhum passo = nada se mexe, entao nao ha "deriva".
 */
class StepWalker(private val sm: SensorManager) : SensorEventListener {

    @Volatile var enabled = true
    @Volatile var stepLen = 0.55f              // comprimento de cada passo (m), ajustavel
    @Volatile var rotRate = 0f                 // giro da cabeca (rad/s), informado pela tela: ignora passos girando rapido
    @Volatile var steps = 0                    // total de passos detectados (aparece no texto do topo)

    private var queued = 0
    private var base = 9.81f
    private var smooth = 0f
    private var peak = 0f
    private var armed = true
    private var lastStep = -1L

    fun start() {
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() { sm.unregisterListener(this) }

    @Synchronized fun takeSteps(): Int { val v = queued; queued = 0; return v }
    @Synchronized private fun push() { queued++; steps++ }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        base += (m - base) * 0.02f                       // gravidade (tendencia lenta)
        smooth += ((m - base) - smooth) * 0.35f          // balanço vertical suavizado
        peak = max(peak * 0.998f, abs(smooth))
        val th = max(0.8f, peak * 0.5f)                  // limite adaptativo
        val t = e.timestamp / 1_000_000L
        if (armed && smooth > th && (lastStep < 0 || t - lastStep > 300)) {
            armed = false
            lastStep = t
            if (enabled && rotRate < 1.5f) push()
        } else if (!armed && smooth < th * 0.3f) {
            armed = true
        }
    }
}
