package app.zarpa

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Algo que o PC mandou pro celular e que espera você tocar em "Permitir". */
data class InItem(val seq: Long, val id: String, val kind: String, val name: String, val size: Long, val text: String?, val url: String?, val update: Boolean = false)

/** Lista (guardada no celular) do que o PC mandou e ainda não foi aceito nem recusado. */
object Inbox {
    private var p: SharedPreferences? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (p == null) p = ctx.applicationContext.getSharedPreferences("zarpa_inbox", Context.MODE_PRIVATE)
        try { Net.appVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { }
    }

    var lastSeq: Long
        get() = p?.getLong("lastSeq", 0) ?: 0
        set(v) { p?.edit()?.putLong("lastSeq", v)?.apply() }

    @Synchronized fun pending(): List<InItem> {
        val arr = try { JSONArray(p?.getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o ->
            InItem(o.getLong("seq"), o.getString("id"), o.getString("kind"), o.getString("name"), o.optLong("size"),
                if (o.isNull("text")) null else o.getString("text"), if (o.isNull("url")) null else o.getString("url"), o.optBoolean("update", false))
        } }
    }

    @Synchronized private fun save(list: List<InItem>) {
        val arr = JSONArray()
        list.forEach { i -> arr.put(JSONObject().put("seq", i.seq).put("id", i.id).put("kind", i.kind).put("name", i.name).put("size", i.size).put("text", i.text ?: JSONObject.NULL).put("url", i.url ?: JSONObject.NULL).put("update", i.update)) }
        p?.edit()?.putString("items", arr.toString())?.apply()
        listeners.forEach { it() }
    }

    /** Guarda só o que ainda não conhecemos e devolve os novos (pra notificar). */
    @Synchronized fun add(items: List<InItem>): List<InItem> {
        val cur = pending(); val known = cur.map { it.id }.toSet()
        val fresh = items.filter { it.id !in known }
        if (fresh.isNotEmpty()) save(cur + fresh)
        return fresh
    }

    @Synchronized fun remove(id: String) { save(pending().filter { it.id != id }) }
}
