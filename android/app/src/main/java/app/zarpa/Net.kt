package app.zarpa

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Guarda o PC pareado. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("zarpa", Context.MODE_PRIVATE)
    var hosts: List<String>
        get() = (p.getString("hosts", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) { p.edit().putString("hosts", v.joinToString(",")).apply() }
    var port: Int
        get() = p.getInt("port", 47800)
        set(v) { p.edit().putInt("port", v).apply() }
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v).apply() }
    var pcName: String
        get() = p.getString("name", "PC") ?: "PC"
        set(v) { p.edit().putString("name", v).apply() }
    var listen: Boolean   // receber do PC em segundo plano (liga por padrão)
        get() = p.getBoolean("listen", true)
        set(v) { p.edit().putBoolean("listen", v).apply() }
    var flick: Boolean       // arremessar empurrando o celular (liga por padrão)
        get() = p.getBoolean("flick", true)
        set(v) { p.edit().putBoolean("flick", v).apply() }
    var flickThr: Float      // força mínima do empurrão, em m/s²
        get() = p.getFloat("flickThr", 12f)
        set(v) { p.edit().putFloat("flickThr", v).apply() }
    var pairNonce: String   // código único do QR; enviado ao PC na 1ª conexão e depois apagado
        get() = p.getString("nonce", "") ?: ""
        set(v) { p.edit().putString("nonce", v).apply() }
    val paired: Boolean get() = token.isNotEmpty() && hosts.isNotEmpty()
    fun clear() { p.edit().clear().apply() }
}

object Net {
    /** Erros de todos os endereços tentados na última busca (um por linha). */
    @Volatile var appVersion: String = ""      // versão deste app; o PC mostra na aba Atualizações
    @Volatile var lastError: String = ""
    private fun addError(msg: String) { synchronized(this) { lastError = if (lastError.isEmpty()) msg else "$lastError\n$msg" } }

    // O primeiro contato pelo Tailscale/rede móvel pode levar vários segundos (o túnel "acorda"), então o prazo é folgado.
    fun ping(host: String, port: Int, timeoutMs: Int = 7000): Boolean = try {
        val c = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        val ok = c.responseCode == 200 && (c.inputStream.bufferedReader().readText().contains("zarpa"))
        if (!ok) addError("$host respondeu, mas não é o Fly")
        ok
    } catch (e: Exception) { addError("$host → ${e.javaClass.simpleName}"); false }

    /** Acha qual endereço do PC responde agora (o último que funcionou vai primeiro). */
    fun findHost(st: Store): String? {
        val list = st.hosts
        if (list.isEmpty()) return null
        lastError = ""
        // Testa todos os endereços ao mesmo tempo e fica com o primeiro que responder.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(list.size)
        var found: String? = null
        try {
            val ecs = java.util.concurrent.ExecutorCompletionService<String?>(pool)
            list.forEach { h -> ecs.submit(java.util.concurrent.Callable<String?> { if (ping(h, st.port)) h else null }) }
            repeat(list.size) { if (found == null) found = ecs.take().get() }
        } finally { pool.shutdownNow() }
        found?.let { good -> if (list.first() != good) st.hosts = listOf(good) + list.filter { it != good } }
        return found
    }

    /** Avisa o PC que este celular está conectado (fecha a janela do QR e abre a galeria no PC). */
    fun hello(st: Store, host: String) {
        try {
            val c = URL("http://$host:${st.port}/hello").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer ${st.token}")
            val k = st.pairNonce
            if (appVersion.isNotEmpty()) c.setRequestProperty("X-Zarpa-App", appVersion)
            if (k.isNotEmpty()) c.setRequestProperty("X-Zarpa-Pair", k)   // prova pro PC que fui eu que li o QR
            c.outputStream.use { it.write(ByteArray(0)) }
            if (c.responseCode == 200 && k.isNotEmpty()) st.pairNonce = ""
        } catch (_: Exception) { }
    }

    /** Miniatura que chega antes do original e faz o pouso imediato no PC. */
    fun sendPreview(st: Store, host: String, id: String, name: String, jpeg: ByteArray) {
        val c = URL("http://$host:${st.port}/preview?id=${URLEncoder.encode(id, "UTF-8")}&name=${URLEncoder.encode(name, "UTF-8")}").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer ${st.token}")
        c.setFixedLengthStreamingMode(jpeg.size)
        c.outputStream.use { it.write(jpeg) }
        check(c.responseCode)
    }

    /** Pareamento manual: IP + código de 6 dígitos mostrado no PC. */
    fun pair(host: String, port: Int, code: String): JSONObject {
        val c = URL("http://$host:$port/pair").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
        val ok = c.responseCode == 200
        val body = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        val j = JSONObject(body)
        if (!ok) throw Exception(j.optString("error", "Erro ${c.responseCode}"))
        return j
    }

    /** QR do PC: zarpa://pair?h=ip1,ip2&p=47800&t=TOKEN&n=NOME */
    fun applyQr(st: Store, text: String): Boolean {
        val u = Uri.parse(text)
        if (u.scheme != "zarpa" || u.host != "pair") return false
        val h = u.getQueryParameter("h")?.split(",")?.filter { it.isNotBlank() } ?: return false
        val t = u.getQueryParameter("t") ?: return false
        st.hosts = h; st.port = u.getQueryParameter("p")?.toIntOrNull() ?: 47800
        st.token = t; st.pcName = u.getQueryParameter("n") ?: "PC"
        st.pairNonce = u.getQueryParameter("k") ?: ""
        return true
    }

    fun fileInfo(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "arquivo"; var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        return name to size
    }

    fun upload(ctx: Context, st: Store, host: String, uri: Uri, name: String, id: String? = null, size: Long = -1, onBytes: (Long) -> Unit) {
        val idq = if (id != null) "&id=${URLEncoder.encode(id, "UTF-8")}" else ""
        val c = URL("http://$host:${st.port}/upload?kind=file&name=${URLEncoder.encode(name, "UTF-8")}$idq").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 180000
        c.setRequestProperty("Authorization", "Bearer ${st.token}")
        if (size > 0) c.setRequestProperty("X-Zarpa-Size", size.toString())
        c.setChunkedStreamingMode(256 * 1024)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw Exception("Não consegui abrir o arquivo")
        input.use { ins ->
            c.outputStream.use { out ->
                val buf = ByteArray(256 * 1024); var done = 0L
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; onBytes(done) }
            }
        }
        check(c.responseCode)
    }

    // ---------- PC → celular ----------
    /** Pergunta ao PC "tem algo pra mim?". O PC segura a resposta até ~25 s ou até chegar algo (long-poll). */
    fun inboxWait(st: Store, host: String, after: Long): List<InItem> {
        val c = URL("http://$host:${st.port}/inbox/wait?after=$after").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 45000
        c.setRequestProperty("Authorization", "Bearer ${st.token}")
        if (appVersion.isNotEmpty()) c.setRequestProperty("X-Zarpa-App", appVersion)
        if (c.responseCode == 401) throw IllegalStateException("Este celular não está mais pareado")
        check(c.responseCode)
        val arr = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o ->
            InItem(o.getLong("seq"), o.getString("id"), o.getString("kind"), o.optString("name", "arquivo"), o.optLong("size", 0),
                if (o.isNull("text")) null else o.getString("text"), if (o.isNull("url")) null else o.getString("url"), o.optBoolean("update", false))
        } }
    }

    fun ack(st: Store, host: String, id: String, received: Boolean) {
        try {
            val c = URL("http://$host:${st.port}/inbox/ack?id=${URLEncoder.encode(id, "UTF-8")}&status=${if (received) "received" else "declined"}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer ${st.token}")
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode
        } catch (_: Exception) { }
    }

    /** Baixa o arquivo do PC direto para Downloads/Fly (só depois de você tocar em Permitir). */
    fun download(ctx: Context, st: Store, host: String, item: InItem, onPct: (Int) -> Unit): Uri {
        val c = URL("http://$host:${st.port}/inbox/file?id=${URLEncoder.encode(item.id, "UTF-8")}").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer ${st.token}")
        if (c.responseCode == 404) throw Exception("O arquivo não está mais no PC")
        check(c.responseCode)
        val total = c.contentLengthLong.takeIf { it > 0 } ?: item.size
        val name = item.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "arquivo" }
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Fly")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Não consegui criar o arquivo no celular")
        try {
            c.inputStream.use { ins ->
                (r.openOutputStream(uri) ?: throw Exception("Não consegui gravar o arquivo")).use { out ->
                    val buf = ByteArray(128 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onPct(p) } }
                    }
                }
            }
            values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0); r.update(uri, values, null, null)
            return uri
        } catch (e: Exception) { try { r.delete(uri, null, null) } catch (_: Exception) {}; throw e }
    }

    fun sendLink(st: Store, host: String, link: String) {
        val c = URL("http://$host:${st.port}/upload?kind=link").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 10000
        c.setRequestProperty("Authorization", "Bearer ${st.token}")
        c.outputStream.use { it.write(link.toByteArray()) }
        check(c.responseCode)
    }

    private fun check(code: Int) {
        if (code != 200) throw Exception(when (code) { 401 -> "Este celular não está mais pareado"; else -> "O PC respondeu erro $code" })
    }
}
