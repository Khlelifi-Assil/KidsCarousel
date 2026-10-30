package com.kids.carousel

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object PackImporter {
    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "L'adresse doit commencer par https://" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "KidsCarousel")
        }
    }

    /** Télécharge items.json + images dans filesDir/pack. Résultat: nombre d'éléments disponibles. */
    fun import(c: Context, url: String, done: (Result<Int>) -> Unit) {
        Thread {
            done(runCatching {
                val json = open(url).inputStream.bufferedReader().readText()
                val arr = JSONObject(json).getJSONArray("items")
                val base = url.substringBeforeLast('/') + "/"
                val dir = File(c.filesDir, "pack").apply { File(this, "images").mkdirs() }
                for (i in 0 until arr.length()) {
                    val img = arr.getJSONObject(i).getString("img")
                    require(!img.contains("..") && img.startsWith("images/")) { "chemin invalide" }
                    val dest = File(dir, img)
                    if (dest.exists()) continue
                    runCatching {
                        val tmp = File(dest.path + ".tmp")
                        open(base + img).inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        tmp.renameTo(dest)
                    }
                }
                File(dir, "items.json").writeText(json)
                Content.load(c)
                Content.items.count { it.imagePath != null }
            })
        }.start()
    }
}
