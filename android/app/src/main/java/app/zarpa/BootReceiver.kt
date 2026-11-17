package app.zarpa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Depois de reiniciar o celular, volta a escutar o PC (se estiver pareado e ligado). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) ListenService.start(ctx)
    }
}
