package app.zarpa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val GOLD = 0xFFD4AF37.toInt()
private const val GOLD2 = 0xFFFFE9A0.toInt()
private const val GOLDD = 0xFFB8860B.toInt()

/**
 * A animação do cartão "Empurrar o celular": um celular dá um empurrão pra frente
 * e a foto sai voando com rastro dourado. Fica em loop pra mostrar o gesto sem precisar ler.
 */
class FlickDemo(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val born = SystemClock.uptimeMillis()
    var on = true
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = GOLD; strokeCap = Paint.Cap.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA39B86.toInt(); textAlign = Paint.Align.CENTER; textSize = 12 * dp }
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), (150 * dp).toInt()) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = ((SystemClock.uptimeMillis() - born) % 2400) / 2400f
        val alpha = if (on) 255 else 90
        // fases: 0-0.30 prepara (puxa pra trás), 0.30-0.42 empurrão, 0.42-1 a foto voa
        val back = if (t < 0.30f) ease(t / 0.30f) else if (t < 0.42f) 1 - ease((t - 0.30f) / 0.12f) * 1.6f else -0.6f + ease(min(1f, (t - 0.42f) / 0.3f)) * 0.6f
        val pw = 46 * dp; val ph = 82 * dp
        val px = w * 0.30f; val py = h * 0.62f + back * 10 * dp
        val tilt = -12f + back * 6f
        // linhas de velocidade no empurrão
        if (t in 0.30f..0.55f) {
            val k = 1 - (t - 0.30f) / 0.25f
            pLine.alpha = (alpha * k).toInt(); pLine.strokeWidth = 2 * dp
            for (i in 0..2) { val yy = py - ph * 0.2f + i * 14 * dp; c.drawLine(px - pw * 0.9f - i * 6 * dp, yy + 18 * dp, px - pw * 0.9f - 22 * dp - i * 6 * dp, yy + 30 * dp, pLine) }
        }
        // celular
        c.save(); c.rotate(tilt, px, py)
        r.set(px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2)
        pFill.shader = null; pFill.color = 0xFF17171C.toInt(); pFill.alpha = alpha; c.drawRoundRect(r, 9 * dp, 9 * dp, pFill)
        pLine.alpha = alpha; pLine.strokeWidth = 2.5f * dp; c.drawRoundRect(r, 9 * dp, 9 * dp, pLine)
        // a foto ainda no celular (antes de sair)
        if (t < 0.42f) photo(c, px, py, pw * 0.62f, alpha, 0f)
        c.restore()
        // a foto voando pro canto de cima (o PC)
        if (t >= 0.42f) {
            val k = ease(min(1f, (t - 0.42f) / 0.45f))
            val fx = px + (w * 0.74f - px) * k; val fy = py - (py - h * 0.24f) * k - sin(k * PI).toFloat() * 26 * dp
            for (i in 1..6) {   // rastro
                val kk = max(0f, k - i * 0.05f)
                val tx = px + (w * 0.74f - px) * kk; val ty = py - (py - h * 0.24f) * kk - sin(kk * PI).toFloat() * 26 * dp
                pFill.shader = null; pFill.color = GOLD2; pFill.alpha = (alpha * (1 - i / 7f) * 0.6f).toInt(); c.drawCircle(tx, ty, (5 - i * 0.6f) * dp, pFill)
            }
            val fade = if (t > 0.9f) 1 - (t - 0.9f) / 0.1f else 1f
            photo(c, fx, fy, pw * (0.62f - 0.12f * k), (alpha * fade).toInt(), 360f * k * 0.15f)
        }
        // o "PC" lá no alto à direita
        val mx = w * 0.74f; val my = h * 0.24f
        r.set(mx - 40 * dp, my - 24 * dp, mx + 40 * dp, my + 24 * dp)
        pLine.alpha = (alpha * 0.8f).toInt(); pLine.strokeWidth = 2 * dp; c.drawRoundRect(r, 5 * dp, 5 * dp, pLine)
        c.drawLine(mx, my + 24 * dp, mx, my + 32 * dp, pLine); c.drawLine(mx - 14 * dp, my + 32 * dp, mx + 14 * dp, my + 32 * dp, pLine)
        pText.alpha = alpha; c.drawText("PC", mx, my + 50 * dp, pText)
        if (!on) { pText.alpha = 255; pText.color = Color.WHITE; pText.textSize = 13 * dp; c.drawText("desligado", w * 0.5f, h - 6 * dp, pText); pText.color = 0xFFA39B86.toInt(); pText.textSize = 12 * dp }
        postInvalidateOnAnimation()
    }

    private fun photo(c: Canvas, x: Float, y: Float, s: Float, alpha: Int, rot: Float) {
        c.save(); c.rotate(rot, x, y)
        r.set(x - s / 2, y - s * 0.4f, x + s / 2, y + s * 0.4f)
        pFill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, GOLDD, GOLD2, Shader.TileMode.CLAMP); pFill.alpha = alpha
        c.drawRoundRect(r, 4 * dp, 4 * dp, pFill); pFill.shader = null
        // montanhazinha + sol, pra ler como "foto"
        pFill.color = 0xFF1A1405.toInt(); pFill.alpha = (alpha * 0.55f).toInt()
        val path = android.graphics.Path().apply { moveTo(r.left + s * 0.1f, r.bottom - s * 0.08f); lineTo(r.left + s * 0.4f, r.top + s * 0.32f); lineTo(r.left + s * 0.62f, r.bottom - s * 0.2f); lineTo(r.left + s * 0.75f, r.top + s * 0.45f); lineTo(r.right - s * 0.08f, r.bottom - s * 0.08f); close() }
        c.drawPath(path, pFill); c.drawCircle(r.right - s * 0.22f, r.top + s * 0.2f, s * 0.08f, pFill)
        c.restore()
    }

    private fun ease(k: Float) = (1 - (1 - k) * (1 - k))
}

/** Medidor do teste: a barra enche com a força do último empurrão; a linha marca o limite escolhido. */
class ForceMeter(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private var peak = 0f; private var shown = 0f; private var thr = 12f; private var hitAt = 0L
    private val max = 25f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp; color = 0xFFA39B86.toInt() }
    private val r = RectF()

    fun set(peak: Float, thr: Float) { this.peak = peak; this.thr = thr; this.shown = 0f; if (peak >= thr) hitAt = SystemClock.uptimeMillis(); invalidate() }
    fun limit(thr: Float) { this.thr = thr; invalidate() }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), (58 * dp).toInt()) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val bh = 18 * dp; val top = 6 * dp
        shown += (peak - shown) * 0.25f
        r.set(0f, top, w, top + bh); p.shader = null; p.color = 0x22FFFFFF; c.drawRoundRect(r, bh / 2, bh / 2, p)
        val fw = (w * (shown / max)).coerceIn(0f, w)
        if (fw > 1) {
            r.set(0f, top, max(bh, fw), top + bh)
            val ok = peak >= thr
            p.shader = LinearGradient(0f, 0f, w, 0f, if (ok) GOLDD else 0xFF5A5546.toInt(), if (ok) GOLD2 else 0xFF8A8577.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, bh / 2, bh / 2, p); p.shader = null
        }
        // a linha do limite
        val lx = w * (thr / max)
        val flash = (SystemClock.uptimeMillis() - hitAt) < 500
        p.color = if (flash) Color.WHITE else GOLD; c.drawRect(lx - 1.5f * dp, top - 4 * dp, lx + 1.5f * dp, top + bh + 4 * dp, p)
        pt.textAlign = Paint.Align.CENTER; c.drawText("limite", lx.coerceIn(20 * dp, w - 20 * dp), top + bh + 20 * dp, pt)
        pt.textAlign = Paint.Align.LEFT; c.drawText("fraco", 0f, top + bh + 20 * dp, pt)
        pt.textAlign = Paint.Align.RIGHT; c.drawText("forte", w, top + bh + 20 * dp, pt)
        if (kotlin.math.abs(peak - shown) > 0.05f || flash) postInvalidateOnAnimation()
    }
}
