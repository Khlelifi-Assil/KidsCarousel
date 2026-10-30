package com.kids.carousel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Génère un objet: traduction (MyMemory) + images libres (Openverse), stocké dans filesDir/custom. */
object ObjectGenerator {
    data class Candidate(val url: String, val credit: String)

    private val colors = listOf("#FFE0E0", "#FFF2CC", "#DDEBFF", "#E2F5E2", "#FFF9C4", "#D8F3F7", "#EADCF8", "#FFE8CC")

    private fun fetch(url: String): ByteArray {
        val u = url.replaceFirst("http://", "https://")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "KidsCarousel/1.0")
        return c.inputStream.use { it.readBytes() }
    }

    fun translate(text: String, from: String, to: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val j = JSONObject(String(fetch("https://api.mymemory.translated.net/get?q=$q&langpair=$from|$to")))
        check(j.optString("responseStatus") == "200") { "traduction indisponible" }
        val t = j.getJSONObject("responseData").getString("translatedText").trim()
        return if (to == "ar") t else t.lowercase()
    }

    /** Complète les champs vides à partir du premier rempli (priorité: anglais, français, arabe). */
    fun complete(ar: String, fr: String, en: String): Triple<String, String, String> {
        val src = when {
            en.isNotBlank() -> "en" to en
            fr.isNotBlank() -> "fr" to fr
            else -> "ar" to ar
        }
        fun f(cur: String, to: String) = if (cur.isNotBlank()) cur.trim() else translate(src.second.trim(), src.first, to)
        return Triple(f(ar, "ar"), f(fr, "fr"), f(en, "en"))
    }

    fun search(query: String): List<Candidate> {
        val q = URLEncoder.encode(query, "UTF-8")
        val arr = JSONObject(String(fetch(
            "https://api.openverse.org/v1/images/?q=$q&license_type=commercial&mature=false&extension=jpg&page_size=12"
        ))).getJSONArray("results")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optInt("width") >= 400 && it.optInt("height") >= 400 }
            .map {
                Candidate(it.getString("url"),
                    "${it.optString("title")} - ${it.optString("creator")} - " +
                        "${it.optString("license").uppercase()} ${it.optString("license_version")} - ${it.optString("foreign_landing_url")}")
            }
    }

    fun download(url: String): Bitmap? = runCatching {
        val b = fetch(url)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / s > 1024) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    private fun readRoot(f: File) =
        runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject().put("items", JSONArray()))

    fun add(c: Context, ar: String, fr: String, en: String, bmp: Bitmap, credit: String) {
        val dir = File(c.filesDir, "custom"); File(dir, "images").mkdirs()
        val id = "c${System.currentTimeMillis()}"
        val r = 512f / maxOf(bmp.width, bmp.height)
        val sb = if (r < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true) else bmp
        File(dir, "images/$id.jpg").outputStream().use { sb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val file = File(dir, "items.json")
        val root = readRoot(file)
        root.getJSONArray("items").put(JSONObject().put("id", id).put("img", "images/$id.jpg")
            .put("ar", ar).put("fr", fr).put("en", en).put("color", colors.random()).put("credit", credit))
        file.writeText(root.toString())
    }

    fun remove(c: Context, id: String) {
        val dir = File(c.filesDir, "custom"); val file = File(dir, "items.json")
        val root = readRoot(file); val old = root.getJSONArray("items"); val kept = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != id) kept.put(old.getJSONObject(i))
        file.writeText(JSONObject().put("items", kept).toString())
        File(dir, "images/$id.jpg").delete()
    }
}
