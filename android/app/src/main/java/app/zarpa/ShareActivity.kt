package app.zarpa

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela "Voando…": recebe o Compartilhar (ou arquivos escolhidos no app) e arremessa pro PC.
 * Um link compartilhado também vai direto pro PC (aparece como cartão com botão "Abrir link").
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var bar: ProgressBar
    private lateinit var card: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val st = Store(this)
        if (!st.paired) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }

        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1C1A14.toInt(), 0xFF08080A.toInt()))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
        card = TextView(this).apply {
            text = "F"; textSize = 46f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1A1405.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFB8860B.toInt(), 0xFFFFE9A0.toInt())).apply { cornerRadius = dp(18).toFloat() }
            elevation = dp(10).toFloat(); rotation = -10f
        }
        col.addView(card, LinearLayout.LayoutParams(dp(150), dp(112)).apply { bottomMargin = dp(26) })
        title = TextView(this).apply { text = "Procurando o PC…"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        sub = TextView(this).apply { text = st.pcName; textSize = 13f; setTextColor(0xFFA39B86.toInt()); gravity = Gravity.CENTER }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; max = 100 }
        col.addView(title); col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        col.addView(bar, LinearLayout.LayoutParams(dp(220), dp(8)).apply { topMargin = dp(20) })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val uris = collectUris(intent)
        val link = if (uris.isEmpty()) findLink(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        if (uris.isEmpty() && link == null) { fail("Só consigo enviar arquivos e links."); return }

        go(st, uris, link)
    }

    private fun go(st: Store, uris: List<Uri>, link: String?) {
        title.text = "Procurando o PC…"
        Thread {
            try {
                val host = Net.findHost(st) ?: throw Exception("Não achei o PC. Ele está ligado, com o Fly aberto e no mesmo Wi-Fi (ou com o Tailscale ligado)?\n${Net.lastError}")
                if (link != null) {
                    runOnUiThread { title.text = "Voando…"; sub.text = link }
                    Net.sendLink(st, host, link)
                } else {
                    uris.forEachIndexed { i, uri ->
                        val (name, size) = Net.fileInfo(this, uri)
                        runOnUiThread { title.text = "Voando… ${i + 1} de ${uris.size}"; sub.text = name; bar.isIndeterminate = size <= 0; bar.progress = 0 }
                        Net.upload(this, st, host, uri, name, null, size) { done ->
                            if (size > 0) runOnUiThread { bar.progress = (done * 100 / size).toInt() }
                        }
                    }
                }
                runOnUiThread { fly("Pousou no PC ✓") }
            } catch (e: Exception) {
                runOnUiThread { fail(e.message ?: "Falhou") }
            }
        }.start()
    }

    /** O "arremesso": o cartão sobe girando e some. */
    private fun fly(msg: String) {
        title.text = msg; sub.text = ""; bar.progress = 100
        card.animate().translationY(-resources.displayMetrics.heightPixels.toFloat()).rotation(-18f).scaleX(.45f).scaleY(.45f)
            .alpha(0f).setDuration(650).setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction { card.postDelayed({ finish() }, 700) }.start()
    }

    private fun fail(msg: String) {
        title.text = "Não deu"; sub.text = msg; bar.isIndeterminate = false; bar.progress = 0; bar.visibility = View.VISIBLE
        card.postDelayed({ finish() }, 5000)
    }

    private fun collectUris(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val l = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (l != null) out.addAll(l)
        } else if (i.action == Intent.ACTION_SEND) {
            val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) out.add(u)
        }
        return out
    }

    private fun findLink(text: String?): String? {
        if (text == null) return null
        val m = Patterns.WEB_URL.matcher(text)
        if (!m.find()) return null
        val s = m.group()
        return if (s.startsWith("http", true)) s else "https://$s"
    }
}
