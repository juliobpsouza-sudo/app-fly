package app.zarpa

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

/** Imagem sempre quadrada (miniatura da galeria). */
class SquareImage(c: android.content.Context) : ImageView(c) {
    override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) }
}

class MainActivity : AppCompatActivity() {
    private lateinit var st: Store

    // galeria
    private var media: List<Uri> = emptyList()
    private val sent = HashSet<Uri>()
    private var selected: Uri? = null
    private var pending = 0
    private var cachedHost: String? = null
    private val sender = Executors.newSingleThreadExecutor()
    private val previewer = Executors.newFixedThreadPool(2)
    private val thumbs = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<Uri, Bitmap>(80) {}
    private var adapter: BaseAdapter? = null
    private var bigView: ImageView? = null
    private var hintView: TextView? = null
    private var statusView: TextView? = null

    private val scan = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        if (Net.applyQr(st, text)) { toast("Pareado com ${st.pcName} ✓"); render() }
        else toast("Esse QR não é do Fly")
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) sendViaShare(uris)
    }
    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- do PC pro celular ----------
    private var inboxOpen = false
    private var settingsOpen = false
    private var detector: FlickDetector? = null
    private var throwCard: View? = null
    private var testView: TextView? = null
    private var inboxChip: TextView? = null
    private var askedNotif = false
    private val recent = ArrayList<Pair<String, Uri?>>()   // o que já foi aceito nesta sessão
    private val inboxListener: () -> Unit = { runOnUiThread { if (inboxOpen) render() else updateInboxChip() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        st = Store(this)
        Inbox.init(this)
        inboxOpen = intent?.getBooleanExtra("open_inbox", false) == true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (settingsOpen) { settingsOpen = false; render() } else if (inboxOpen) { inboxOpen = false; render() } else finish() }
        })
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_inbox", false)) { inboxOpen = true; render() }
    }

    override fun onResume() {
        super.onResume()
        Inbox.listeners.add(inboxListener)
        ListenService.start(this)
        if (inboxOpen || ::st.isInitialized) { updateInboxChip() }
        startDetector()
    }

    override fun onPause() { Inbox.listeners.remove(inboxListener); detector?.stop(); detector = null; super.onPause() }

    // ---------- empurrão (flick com o próprio celular) ----------
    private fun startDetector() {
        detector?.stop(); detector = null
        if (!st.paired || !st.flick) return
        val d = FlickDetector(this, st.flickThr) { peak, fired -> runOnUiThread { onFlick(peak, fired) } }
        if (d.start()) detector = d
    }

    private fun onFlick(peak: Float, fired: Boolean) {
        if (settingsOpen) {   // tela de teste: mostra a força de cada empurrão
            testView?.text = String.format("Último empurrão: %.1f m/s²\n%s", peak,
                if (peak >= st.flickThr) "✓ passou do limite (${st.flickThr.toInt()})" else "abaixo do limite (${st.flickThr.toInt()}). Mais força!")
            if (fired) vibrate()
            return
        }
        if (!fired || inboxOpen) return
        val c = throwCard
        if (selected != null && c != null) { vibrate(); throwIt(c) }
        else toast("Escolha uma foto antes de empurrar o celular")
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun settingsRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg() }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(34), dp(16), dp(6)) }
        bar.addView(chip("‹ Voltar") { settingsOpen = false; render() })
        bar.addView(text("Configurações", 20f, Color.WHITE, true, Gravity.START), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }

        col.addView(text("EMPURRAR O CELULAR PRA ARREMESSAR", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(14))
        col.addView(text("Escolha uma foto e dê um empurrão rápido no celular pra frente, como quem joga. Ela é arremessada igual a arrastar pra cima. Balançar de lado ou andar não dispara.", 13f, 0xFFA39B86.toInt(), false, Gravity.START), mt(6))
        if (detector == null && !FlickDetector(this, 0f) { _, _ -> }.available) col.addView(text("Este aparelho não tem o sensor necessário.", 13f, 0xFFFBBF24.toInt(), false, Gravity.START), mt(6))
        col.addView(button(if (st.flick) "Ligado (toque pra desligar)" else "Desligado (toque pra ligar)", st.flick) { st.flick = !st.flick; startDetector(); render() }, mt(10))

        col.addView(text("FORÇA NECESSÁRIA", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(22))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, v) in listOf("Sensível" to 8f, "Normal" to 12f, "Firme" to 17f)) {
            row.addView(button(label, st.flickThr == v) { st.flickThr = v; detector?.threshold = v; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }
        col.addView(row, mt(8))

        col.addView(text("TESTE", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(24))
        col.addView(text("Segure o celular e empurre rápido pra frente. A força do empurrão aparece aqui e o celular vibra se passar do limite. Se não passar, use Sensível; se disparar sem querer, use Firme.", 13f, 0xFFA39B86.toInt(), false, Gravity.START), mt(6))
        testView = text("Último empurrão: —", 18f, Color.WHITE, true)
        testView!!.setPadding(dp(12), dp(22), dp(12), dp(22))
        testView!!.background = GradientDrawable().apply { setColor(0x1AFFFFFF); setStroke(dp(1), 0x33FFFFFF); cornerRadius = dp(16).toFloat() }
        col.addView(testView, mt(10))

        col.addView(text("ENVIAR ARQUIVOS PRO PC", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(28))
        col.addView(text("Escolha arquivos do celular (PDF, documentos, vídeos…) pra mandar pro PC.", 13f, 0xFFA39B86.toInt(), false, Gravity.START), mt(6))
        col.addView(button("Escolher arquivos…", false) { pickFiles.launch(arrayOf("*/*")) }, mt(10))

        col.addView(text("CONEXÃO COM O PC", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(28))
        col.addView(text("Conectado a ${st.pcName}. Sair desfaz o pareamento: pra voltar a usar, é preciso escanear o QR do PC de novo.", 13f, 0xFFA39B86.toInt(), false, Gravity.START), mt(6))
        col.addView(button("Sair", false) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sair e desparear?")
                .setMessage("O celular deixa de falar com ${st.pcName}. Pra voltar, escaneie o QR do PC de novo.")
                .setPositiveButton("Sair") { _, _ -> ListenService.stop(this); st.clear(); settingsOpen = false; render() }
                .setNegativeButton("Cancelar", null)
                .show()
        }, mt(10))

        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun inboxLabel(): String { val n = Inbox.pending().size; return if (n > 0) "Do PC ($n)" else "Do PC" }
    private fun updateInboxChip() { inboxChip?.text = inboxLabel() }

    private fun askNotifications() {
        if (askedNotif || Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) return
        askedNotif = true
        permReq.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun inboxRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg() }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(34), dp(16), dp(6)) }
        bar.addView(chip("‹ Voltar") { inboxOpen = false; render() })
        bar.addView(text("Do PC", 20f, Color.WHITE, true, Gravity.START), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        val items = Inbox.pending()
        if (items.isEmpty() && recent.isEmpty())
            col.addView(text("Nada esperando agora.\nQuando você soltar algo no Fly do PC, aparece aqui e o celular avisa.", 14f, 0xFFA39B86.toInt(), false), mt(40))
        items.forEach { col.addView(inboxCard(it), mt(10)) }
        if (recent.isNotEmpty()) {
            col.addView(text("RECEBIDO AGORA", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(24))
            recent.takeLast(10).reversed().forEach { (msg, uri) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)) }
                row.addView(text(msg, 13f, 0xFF34D399.toInt(), false, Gravity.START), LinearLayout.LayoutParams(0, -2, 1f))
                if (uri != null) row.addView(chip("Abrir") { openSaved(uri) })
                col.addView(row, mt(2))
            }
        }

        col.addView(text("RECEBER EM SEGUNDO PLANO", 12f, 0xFFA39B86.toInt(), true, Gravity.START), mt(30))
        col.addView(text("Mantém um ícone discreto e uma conexão com o PC pra o celular te avisar na hora. Gasta um pouco de bateria. Só funciona com o PC alcançável (mesmo Wi-Fi ou Tailscale ligado).", 13f, 0xFFA39B86.toInt(), false, Gravity.START), mt(6))
        col.addView(button(if (st.listen) "Desligar o recebimento" else "Ligar o recebimento", false) {
            st.listen = !st.listen
            if (st.listen) ListenService.start(this) else ListenService.stop(this)
            render()
        }, mt(10))
        col.addView(button("Evitar que o Android pause o Fly", false) {
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { toast("Abra Configurações > Apps > Fly > Bateria e escolha 'Sem restrição'") }
        }, mt(8))
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun inboxCard(it: InItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(0x1AFFFFFF); setStroke(dp(1), 0x33FFFFFF); cornerRadius = dp(16).toFloat() }
        }
        val kind = when { it.update -> "⬆ Atualização do Fly"; it.kind == "link" -> "🔗 Link"; it.kind == "text" -> "📝 Texto"; else -> "📄 Arquivo" }
        card.addView(text(kind, 12f, 0xFFFFE9A0.toInt(), true, Gravity.START))
        val title = when (it.kind) { "link" -> it.url ?: it.name; "text" -> (it.text ?: "").take(400); else -> it.name }
        card.addView(text(title, 15f, Color.WHITE, it.kind == "file", Gravity.START), mt(4))
        if (it.kind == "file") card.addView(text(fmt(it.size) + " · do seu PC", 12f, 0xFFA39B86.toInt(), false, Gravity.START), mt(2))
        val status = text("", 13f, 0xFFFBBF24.toInt(), false, Gravity.START)
        card.addView(status, mt(6))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val allow = button("Permitir", true) { accept(it, status, btns) }
        val no = button("Recusar", false) { decline(it) }
        btns.addView(allow, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        btns.addView(no, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(btns, mt(10))
        return card
    }

    private fun fmt(n: Long) = if (n > 1048576) String.format("%.1f MB", n / 1048576.0) else "${maxOf(1, n / 1024)} KB"

    private fun accept(it: InItem, status: TextView, btns: View) {
        btns.visibility = View.GONE; status.text = "Conectando ao PC…"
        Thread {
            try {
                val host = cachedHost ?: Net.findHost(st)?.also { h -> cachedHost = h } ?: throw Exception("Não achei o PC. Ligue o Tailscale ou use o mesmo Wi-Fi.")
                when (it.kind) {
                    "file" -> {
                        val uri = Net.download(this, st, host, it) { p -> runOnUiThread { status.text = "Baixando $p%…" } }
                        Net.ack(st, host, it.id, true)
                        done(it, if (it.update) "✓ Atualização baixada: ${it.name}" else "✓ Salvo em Downloads/Fly: ${it.name}", uri)
                        if (it.update || it.name.endsWith(".apk", true)) runOnUiThread { installApk(uri) }   // já abre a tela de instalar
                    }
                    "link" -> {
                        Net.ack(st, host, it.id, true)
                        runOnUiThread { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(it.url))) } catch (_: Exception) { toast("Nenhum app abriu o link") } }
                        done(it, "✓ Link aberto: ${it.name}", null)
                    }
                    else -> {
                        runOnUiThread { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Fly", it.text ?: "")) }
                        Net.ack(st, host, it.id, true)
                        done(it, "✓ Texto copiado pra área de transferência", null)
                    }
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Não deu: ${e.message}"; btns.visibility = View.VISIBLE } }
        }.start()
    }

    private fun decline(it: InItem) {
        Thread { val h = cachedHost ?: Net.findHost(st); if (h != null) Net.ack(st, h, it.id, false); Inbox.remove(it.id) }.start()
    }

    private fun done(it: InItem, msg: String, uri: Uri?) { recent.add(msg to uri); Inbox.remove(it.id) }

    /** Abre o instalador do Android. Na primeira vez, o Android exige liberar "instalar apps" pro Fly. */
    private fun installApk(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Libere 'Permitir deste app' pro Fly instalar atualizações, depois volte e toque em Abrir.")
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            return
        }
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast("Não consegui abrir o instalador. O arquivo está em Downloads/Fly.") }
    }

    private fun openSaved(uri: Uri) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { toast("Nenhum app consegue abrir esse arquivo. Ele está em Downloads/Fly.") }
    }

    // ---------- permissões ----------
    private fun mediaPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, "android.permission.ACCESS_LOCAL_NETWORK")
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, "android.permission.ACCESS_LOCAL_NETWORK")
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasMedia() = when {
        Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun render() {
        when {
            !st.paired -> setContentView(pairingRoot())
            settingsOpen -> setContentView(settingsRoot())
            inboxOpen -> setContentView(inboxRoot())
            else -> setContentView(pairedRoot())
        }
    }

    // ---------- pareamento ----------
    private fun pairingRoot(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(40), dp(24), dp(24)) }
        col.addView(logo(), LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        col.addView(text("Fly", 26f, Color.WHITE, true))
        col.addView(text("Conecte ao seu PC", 16f, 0xFFD9CFAE.toInt(), false), mt(4))
        col.addView(text("1.  No PC, abra o Fly (ícone na bandeja ao lado do relógio).\n2.  Clique nele para abrir “Parear celular”.\n3.  Toque em “Escanear QR” aqui e aponte pra tela do PC.\n\nNa primeira vez, PC e celular precisam estar na mesma rede (ou com o Tailscale ligado nos dois).", 14f, 0xFFA39B86.toInt(), false, Gravity.START), mt(18))
        col.addView(button("Escanear QR do PC", true) {
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Aponte para o QR do Fly no PC").setBeepEnabled(false).setOrientationLocked(false))
        }, mt(22))
        col.addView(text("Sem câmera? Digite:", 13f, 0xFFA39B86.toInt(), false), mt(26))
        val ip = field("IP do PC (ex.: 192.168.0.5 ou 100.x.x.x)", InputType.TYPE_CLASS_PHONE)
        val code = field("Código de 6 dígitos", InputType.TYPE_CLASS_NUMBER)
        col.addView(ip, mt(8)); col.addView(code, mt(8))
        col.addView(button("Parear com código", false) {
            val h = ip.text.toString().trim().substringBefore(":"); val c = code.text.toString().trim()
            if (h.isEmpty() || c.length != 6) { toast("Preencha o IP e o código de 6 dígitos"); return@button }
            Thread {
                try {
                    val j = Net.pair(h, st.port, c)
                    st.hosts = listOf(h); st.token = j.getString("token"); st.pcName = j.optString("name", "PC")
                    runOnUiThread { toast("Pareado ✓"); render() }
                } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Falhou") } }
            }.start()
        }, mt(10))
        return ScrollView(this).apply { background = bg(); addView(col) }
    }

    // ---------- galeria + arremesso ----------
    private fun pairedRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(); clipChildren = false; clipToPadding = false }

        // barra do topo
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(34), dp(8), dp(4)) }
        statusView = text("● ${st.pcName}", 13f, 0xFFA39B86.toInt(), false, Gravity.START)
        bar.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(chip("↻") { refreshGallery() })
        inboxChip = chip(inboxLabel()) { inboxOpen = true; render() }
        bar.addView(inboxChip)
        bar.addView(chip("Link") { sendClipboardLink() })
        bar.addView(chip("⚙") { settingsOpen = true; render() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        checkConnection()
        askNotifications()

        if (!hasMedia()) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
            c.addView(logo(), LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(16) })
            c.addView(text("Mostrar suas fotos aqui", 20f, Color.WHITE, true))
            c.addView(text("O Fly precisa de acesso à galeria para você escolher uma foto e arremessar pro PC. As fotos só saem do celular quando você arremessa.", 14f, 0xFFA39B86.toInt(), false), mt(8))
            c.addView(button("Permitir acesso às fotos", true) { permReq.launch(mediaPerms()) }, mt(20))
            root.addView(c, LinearLayout.LayoutParams(-1, 0, 1f))
            return root
        }

        media = loadMedia()
        val wide = resources.configuration.screenWidthDp >= 600
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }

        // zona de arremesso
        val zone = FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0x22FFFFFF); setStroke(dp(2), 0x55FFFFFF); cornerRadius = dp(16).toFloat() }
            elevation = dp(8).toFloat()
        }
        bigView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        card.addView(bigView, FrameLayout.LayoutParams(-1, -1))
        hintView = text("Toque numa foto pra pegar", 14f, 0xFFD9CFAE.toInt(), false)
        zone.addView(card, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(30) })
        zone.addView(hintView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        throwCard = card
        attachThrow(card, zone)
        val zoneLp = if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f)
        body.addView(zone, zoneLp)

        // galeria
        val cols = if (wide) 4 else 4
        val grid = GridView(this).apply {
            numColumns = cols; horizontalSpacing = dp(3); verticalSpacing = dp(3); setPadding(dp(3), dp(3), dp(3), dp(3)); clipToPadding = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            overScrollMode = View.OVER_SCROLL_NEVER   // o efeito de esticar do Android 12+ vazava por cima do cartão
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = media.size
            override fun getItem(p: Int) = media[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val cell = (cv as? FrameLayout) ?: FrameLayout(this@MainActivity).also {
                    it.addView(SquareImage(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22FFFFFF) })
                    it.addView(TextView(this@MainActivity).apply { text = "✓"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setBackgroundColor(0x8834D399.toInt()); visibility = View.GONE }, FrameLayout.LayoutParams(-1, -1))
                }
                val iv = cell.getChildAt(0) as ImageView
                val ck = cell.getChildAt(1)
                val u = media[p]
                ck.visibility = if (sent.contains(u)) View.VISIBLE else View.GONE
                cell.animate().cancel(); cell.scaleX = 1f; cell.scaleY = 1f
                cell.alpha = if (u == selected) 0.45f else 1f
                iv.tag = u
                val hit = cache.get(u)
                if (hit != null) iv.setImageBitmap(hit) else {
                    iv.setImageDrawable(null)
                    thumbs.execute {
                        val bm = try { contentResolver.loadThumbnail(u, Size(260, 260), null) } catch (_: Exception) { null }
                        if (bm != null) { cache.put(u, bm); iv.post { if (iv.tag == u) iv.setImageBitmap(bm) } }
                    }
                }
                return cell
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, v, p, _ ->
            // a miniatura afunda e volta com uma mola, e a foto entra no cartão
            v.animate().cancel()
            v.animate().scaleX(.88f).scaleY(.88f).setDuration(80).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.2f)).start()
            }.start()
            select(media[p])
        }
        val gridLp = if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1.15f)
        // O body não recorta (o cartão precisa voar pra fora no arremesso). Então a grade vai num container
        // que recorta, senão as fileiras que saem pela borda de cima desenham por cima do cartão.
        val gridBox = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        gridBox.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(gridBox, gridLp)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        if (media.isEmpty()) hintView?.text = "Nenhuma foto encontrada na galeria"
        return root
    }

    private fun select(u: Uri) {
        selected = u
        hintView?.text = "⌃  Arraste a foto pra cima pra arremessar"
        bigView?.setImageDrawable(null)
        thumbs.execute {
            val bm = try { contentResolver.loadThumbnail(u, Size(1200, 1200), null) } catch (_: Exception) { cache.get(u) }
            runOnUiThread {
                if (selected == u) bigView?.let { iv ->
                    iv.setImageBitmap(bm)
                    iv.animate().cancel()
                    iv.alpha = 0f; iv.scaleX = .9f; iv.scaleY = .9f
                    iv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
                }
            }
        }
        adapter?.notifyDataSetChanged()
    }

    /** Arrastar o cartão pra cima (ou dar um "flick") arremessa a foto selecionada. */
    private fun attachThrow(card: View, zone: ViewGroup) {
        var sx = 0f; var sy = 0f; var vt: VelocityTracker? = null; var armed = false
        card.setOnTouchListener { v, e ->
            if (selected == null) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; armed = false
                    v.animate().cancel()
                    vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    v.translationY = dy.coerceAtMost(dp(30).toFloat()); v.translationX = dx * 0.5f; v.rotation = dx * 0.03f
                    // quanto mais sobe, mais o cartão "levanta": encolhe um pouco e fica mais leve
                    val lift = (-dy / dp(130)).coerceIn(0f, 1f)
                    v.scaleX = 1f - .07f * lift; v.scaleY = v.scaleX; v.alpha = 1f - .15f * lift
                    // vibra uma vez ao passar do ponto em que o arremesso dispara
                    if (lift >= 1f && !armed) { armed = true; vibrate() } else if (lift < .9f) armed = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f; val dy = e.rawY - sy
                    vt?.recycle(); vt = null
                    if (e.actionMasked == MotionEvent.ACTION_UP && (vy < -1300f || dy < -dp(130))) throwIt(v)
                    else v.animate().translationX(0f).translationY(0f).rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()
                }
            }
            true
        }
    }

    private fun throwIt(v: View) {
        val u = selected ?: return
        selected = null
        v.animate().translationY(-(v.bottom + dp(260)).toFloat()).scaleX(.45f).scaleY(.45f).rotation(-14f).alpha(0f)
            .setDuration(380).setInterpolator(AccelerateInterpolator(1.3f)).withEndAction {
                // o cartão vazio volta com fade e um pequeno "pop", em vez de aparecer de uma vez
                v.translationX = 0f; v.translationY = 0f; v.rotation = 0f; v.scaleX = .92f; v.scaleY = .92f; v.alpha = 0f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.3f)).start()
                bigView?.setImageDrawable(null)
                hintView?.text = "Pronto! Toque em outra foto pra arremessar"
                adapter?.notifyDataSetChanged()
            }.start()
        enqueue(u)
    }

    private fun enqueue(uri: Uri) {
        pending++; updateStatus()
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val (name, size) = Net.fileInfo(this, uri)

        // 1) Prévia leve, em paralelo: faz a foto "pousar" no PC na hora.
        previewer.execute {
            try {
                val host = cachedHost ?: Net.findHost(st)?.also { cachedHost = it } ?: return@execute
                val bm = contentResolver.loadThumbnail(uri, Size(1280, 1280), null)
                val bos = java.io.ByteArrayOutputStream()
                bm.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                Net.sendPreview(st, host, id, name, bos.toByteArray())
            } catch (_: Exception) { /* sem prévia, o original ainda chega */ }
        }

        // 2) Original, em segundo plano. Sem "ping" antes: só procura de novo se falhar.
        sender.execute {
            try {
                var host = cachedHost ?: Net.findHost(st).also { cachedHost = it }
                    ?: throw Exception("Não achei o PC. Ligue o Tailscale ou use o mesmo Wi-Fi.\n${Net.lastError}")
                // Na rede móvel a conexão pode cair no meio (troca de rede, túnel renegociando): tenta de novo até 3 vezes.
                var attempt = 0
                while (true) {
                    try { Net.upload(this, st, host, uri, name, id, size) { }; break }
                    catch (e: java.io.IOException) {
                        if (++attempt >= 3) throw e
                        Thread.sleep(1500L * attempt)
                        host = Net.findHost(st).also { cachedHost = it } ?: throw Exception("Perdi o PC no meio do envio.\n${Net.lastError}")
                    }
                }
                runOnUiThread { sendErr = null; sent.add(uri); adapter?.notifyDataSetChanged() }
            } catch (e: Exception) {
                runOnUiThread { sendErr = "${e.javaClass.simpleName}: ${e.message}"; toast(e.message ?: "Falhou") }
            } finally {
                runOnUiThread { pending--; updateStatus() }
            }
        }
    }

    private var sendErr: String? = null
    private fun updateStatus() {
        val err = sendErr
        statusView?.text = when {
            pending > 0 -> "🚀 Enviando $pending…"
            err != null -> "⚠ Último envio falhou\n$err"
            else -> "● ${st.pcName}"
        }
        if (pending == 0) statusView?.setTextColor(if (err != null) 0xFFFBBF24.toInt() else 0xFF34D399.toInt())
    }

    private fun checkConnection() {
        statusView?.text = "● ${st.pcName} · procurando…"
        Thread {
            val h = Net.findHost(st); cachedHost = h
            if (h != null) Net.hello(st, h)
            runOnUiThread {
                statusView?.text = if (h != null) "● ${st.pcName} · conectado" else "● ${st.pcName} · fora de alcance\n${Net.lastError}"
                statusView?.setTextColor(if (h != null) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())
            }
        }.start()
    }

    /** Lê a galeria de novo sem reiniciar o app: mantém a foto selecionada, a conexão e as miniaturas já carregadas. */
    private fun refreshGallery() {
        if (!hasMedia()) { toast("O Fly ainda não tem acesso às fotos"); return }
        Thread {
            val novo = loadMedia()
            runOnUiThread {
                media = novo
                adapter?.notifyDataSetChanged()
                if (selected == null) hintView?.text = if (novo.isEmpty()) "Nenhuma foto encontrada na galeria" else "Toque numa foto pra pegar"
                toast("Galeria atualizada: ${novo.size} ${if (novo.size == 1) "item" else "itens"}")
            }
        }.start()
    }

    private fun loadMedia(): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val f = MediaStore.Files.FileColumns.MEDIA_TYPE
            val proj = arrayOf(MediaStore.Files.FileColumns._ID, f)
            val sel = "$f=? OR $f=?"
            val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            contentResolver.query(MediaStore.Files.getContentUri("external"), proj, sel, args, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 500) {
                    val id = c.getLong(0)
                    val base = if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out.add(ContentUris.withAppendedId(base, id))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun sendClipboardLink() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (t.isNullOrEmpty() || !t.startsWith("http", true)) { toast("Copie um link (http…) primeiro"); return }
        startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t))
    }

    private fun sendViaShare(uris: List<Uri>) {
        val clip = ClipData.newRawUri("arquivos", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        val i = Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = clip
        startActivity(i)
    }

    // ---------- helpers de UI ----------
    private fun bg() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1C1A14.toInt(), 0xFF08080A.toInt(), 0xFF08080A.toInt()))
    private fun logo(): View = ImageView(this).apply { setImageResource(R.drawable.ic_launcher); scaleType = ImageView.ScaleType.FIT_CENTER }
    private fun mt(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun text(s: String, size: Float, color: Int, bold: Boolean, g: Int = Gravity.CENTER) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = g; if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; setTextColor(Color.WHITE); setHintTextColor(0xFF8A826F.toInt()); textSize = 15f
        background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(12).toFloat() }; setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }
    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) 0xFF1A1405.toInt() else Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        background = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFB8860B.toInt(), 0xFFD4AF37.toInt())).apply { cornerRadius = dp(14).toFloat() }
        else GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(14).toFloat() }
        setOnClickListener { onClick() }
    }
}
