package com.kids.carousel

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object PackImporter {
    /** Accepte une adresse GitHub « normale » (blob, tree, dépôt) et la convertit en adresse raw. */
    fun normalize(raw: String): String {
        var u = raw.trim().replaceFirst("http://", "https://")
        if (u.isNotEmpty() && !u.startsWith("https://")) u = "https://$u"
        if (Regex("""^https://(?:www\.)?github\.com/""").containsMatchIn(u)) u = u.substringBefore('?').substringBefore('#')

        Regex("""^https://(?:www\.)?github\.com/([^/]+)/([^/]+)/(?:blob|raw)/(.+)$""").find(u)?.let {
            return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}"
        }
        Regex("""^https://(?:www\.)?github\.com/([^/]+)/([^/]+)/tree/([^/]+)(?:/(.*))?$""").find(u)?.let {
            val path = it.groupValues[4].trim('/').ifBlank { "pack" }
            val file = if (path.endsWith(".json")) path else "$path/items.json"
            return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/${it.groupValues[3]}/$file"
        }
        Regex("""^https://(?:www\.)?github\.com/([^/]+)/([^/#?]+?)(?:\.git)?/?$""").find(u)?.let {
            return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/main/pack/items.json"
        }
        if (u.endsWith("/")) u += "items.json"
        return u
    }

    private fun getBytes(url: String): ByteArray {
        require(url.startsWith("https://")) { "L'adresse doit commencer par https://" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "KidsCarousel")
        val code = c.responseCode
        if (code == 404) error("Fichier introuvable (404). Vérifie l'adresse, la branche (main ou master) et que le dépôt est PUBLIC.")
        if (code != 200) error("Le serveur a répondu HTTP $code")
        return c.inputStream.use { it.readBytes() }
    }

    /** Télécharge items.json; si la branche « main » n'existe pas, essaie « master ». */
    private fun getJson(url: String): Pair<String, String> {
        val urls = listOf(url) + if ("/main/" in url) listOf(url.replace("/main/", "/master/")) else emptyList()
        var first: Exception? = null
        for (u in urls) {
            try { return String(getBytes(u), Charsets.UTF_8) to u } catch (e: Exception) { if (first == null) first = e }
        }
        throw first ?: IllegalStateException("adresse vide")
    }

    /** Résultat: message à afficher (nombre d'éléments importés). */
    fun import(c: Context, rawUrl: String, done: (Result<String>) -> Unit) {
        Thread {
            done(runCatching {
                val (text, url) = getJson(normalize(rawUrl))
                val t = text.trim().trimStart('\uFEFF')                      // BOM éventuel
                if (t.startsWith("<")) error("L'adresse renvoie une page web, pas le fichier JSON. Utilise le bouton « Raw » sur GitHub, ou colle l'adresse du dépôt.")
                val arr: JSONArray = try {
                    if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("items")
                } catch (e: JSONException) {
                    error("JSON invalide ou sans liste « items » (${e.message})")
                }
                val base = url.substringBeforeLast('/') + "/"
                val dir = File(c.filesDir, "pack").apply { File(this, "images").mkdirs() }
                var ok = 0; var missing = 0
                for (i in 0 until arr.length()) {
                    val img = arr.optJSONObject(i)?.optString("img").orEmpty()
                    if (img.isBlank() || img.contains("..") || !img.startsWith("images/")) { missing++; continue }
                    val dest = File(dir, img)
                    if (dest.isFile) { ok++; continue }
                    val got = runCatching {
                        val tmp = File(dest.path + ".tmp")
                        tmp.writeBytes(getBytes(base + img)); tmp.renameTo(dest)
                    }.isSuccess
                    if (got) ok++ else missing++
                }
                if (ok == 0) error("Aucune image n'a pu être téléchargée (${arr.length()} éléments dans le fichier).")
                File(dir, "items.json").writeText(JSONObject().put("items", arr).toString())
                Content.load(c)
                "$ok éléments importés" + if (missing > 0) " ($missing ignorés : image introuvable)" else ""
            })
        }.start()
    }
}
