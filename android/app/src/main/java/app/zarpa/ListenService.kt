package app.zarpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Mantém uma conexão quieta com o PC pra avisar quando ele manda algo. Dá pra desligar no app. */
class ListenService : Service() {
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        channels()
        val n = NotificationCompat.Builder(this, CH_LISTEN).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Fly pronto pra receber do PC").setContentText("Toque pra abrir")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open(false)).build()
        try {
            ServiceCompat.startForeground(this, 1, n, if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!running) { running = true; Thread { loop() }.start() }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun loop() {
        worker = Thread.currentThread()
        val st = Store(this); Inbox.init(this)
        var host: String? = null; var fails = 0
        while (running) {
            if (!st.paired || !st.listen) { stopSelf(); return }
            try {
                if (host == null) host = Net.findHost(st)
                if (host == null) { connected = false; fails++; pause(fails); continue }
                val items = Net.inboxWait(st, host, Inbox.lastSeq)
                fails = 0; connected = true
                if (items.isNotEmpty()) {
                    Inbox.add(items).forEach { notifyItem(it) }
                    Inbox.lastSeq = items.maxOf { it.seq }
                }
            } catch (e: IllegalStateException) { connected = false; stopSelf(); return }          // não está mais pareado
            catch (e: Exception) { connected = false; host = null; fails++; pause(fails) }
        }
    }

    /**
     * Pausa entre tentativas quando o PC não responde: 2 s, 4 s… até no máximo 10 s (antes ia até 1 min,
     * e logo depois de religar o PC o celular demorava pra voltar). Abrir o app interrompe a pausa (kick).
     */
    private fun pause(fails: Int) {
        sleeping = true
        try { Thread.sleep(minOf(10000L, 2000L * fails)) } catch (_: InterruptedException) { /* kick: tenta já */ }
        finally { sleeping = false; Thread.interrupted() }
    }

    private fun notifyItem(i: InItem) {
        val what = when { i.update -> "Atualização do Fly pronta pra instalar"; i.kind == "link" -> "Link: ${i.name}"; i.kind == "text" -> "Texto: ${i.name}"; else -> i.name }
        val n = NotificationCompat.Builder(this, CH_IN).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (i.from != null) "${i.from} mandou algo pra você" else "O PC mandou algo pra você").setContentText(what)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open(true)).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(i.id.hashCode(), n) } catch (_: SecurityException) { }
    }

    private fun open(inbox: Boolean): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_inbox", inbox)
        return PendingIntent.getActivity(this, if (inbox) 2 else 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun channels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Fly conectado", NotificationManager.IMPORTANCE_MIN).apply { description = "Ícone discreto enquanto o Fly espera algo do PC" })
        nm.createNotificationChannel(NotificationChannel(CH_IN, "Enviado pelo PC", NotificationManager.IMPORTANCE_HIGH).apply { description = "Aviso quando o PC (ou outro aparelho, pelo PC) manda um arquivo, link ou texto" })
    }

    companion object {
        const val CH_LISTEN = "listen"; const val CH_IN = "incoming"
        @Volatile private var worker: Thread? = null
        @Volatile private var sleeping = false   // só a pausa é interrompida; uma conexão ativa nunca é derrubada
        /** true quando a conexão de fundo está de pé de verdade (a última espera pelo PC respondeu). */
        @Volatile var connected = false
        /** Acorda a conexão de fundo se ela estiver na pausa entre tentativas. */
        fun kick() { try { if (sleeping) worker?.takeIf { it.isAlive }?.interrupt() } catch (_: Exception) { } }
        fun start(ctx: Context) {
            if (!Store(ctx).let { it.paired && it.listen }) return
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ListenService::class.java)) } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ListenService::class.java)) }
    }
}
