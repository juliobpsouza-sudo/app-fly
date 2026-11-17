package app.zarpa

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detecta o "empurrão": um movimento rápido do celular pra frente (ou pra trás), como quem joga algo.
 * Usa a aceleração linear (sem a gravidade). Só conta empurrão no sentido da tela/topo do aparelho,
 * então balançar de lado ou andar não dispara.
 *
 * cb(pico, disparou): chamado quando um movimento termina. `pico` em m/s².
 */
class FlickDetector(ctx: Context, @Volatile var threshold: Float, private val cb: (Float, Boolean) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var peak = 0f
    private var peakAt = 0L
    private var calmSince = 0L
    private var lastFire = 0L

    val available get() = sensor != null
    fun start(): Boolean = sensor != null && sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    fun stop() { sm.unregisterListener(this); peak = 0f; calmSince = 0 }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val m = sqrt(x * x + y * y + z * z)
        val forward = maxOf(abs(y), abs(z))                 // frente/trás do aparelho (de pé: Z; deitado: Y)
        val now = e.timestamp / 1_000_000                    // ms
        if (m > MOVING) {
            if (m > peak && forward >= 0.7f * m) { peak = m; peakAt = now }   // ignora balanço pro lado
            calmSince = 0
        } else if (peak > 0f) {
            if (calmSince == 0L) calmSince = now
            if (now - calmSince > 100) {                      // o movimento acabou
                val fired = peak >= threshold && now - lastFire > 1200
                if (fired) lastFire = now
                cb(peak, fired); peak = 0f; calmSince = 0
            }
        }
        if (peak > 0f && now - peakAt > 700) { cb(peak, false); peak = 0f; calmSince = 0 }   // movimento longo = caminhada/balanço
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    companion object { private const val MOVING = 4f }
}
