package com.kids.carousel

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Item(val symbol: String, val ar: String, val fr: String, val en: String, val color: String, val imagePath: String? = null)

object Content {
    var items: List<Item> = emptyList()

    private fun readDir(c: Context, name: String): List<Item> = runCatching {
        val dir = File(c.filesDir, name)
        val arr = JSONObject(File(dir, "items.json").readText().trimStart('\uFEFF')).getJSONArray("items")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val img = o.optString("img")
            val f = File(dir, img)
            if (img.isBlank() || !f.isFile) null
            else Item("", o.optString("ar"), o.optString("fr"), o.optString("en"),
                o.optString("color").ifBlank { "#EEEEEE" }, f.path)
        }
    }.getOrDefault(emptyList())

    /** Contenu intégré + pack Internet + objets ajoutés par le parent. */
    fun load(c: Context) { items = builtIn + readDir(c, "pack") + readDir(c, "custom") }

    val builtIn = listOf(
        Item("🍎", "تفاحة", "pomme", "apple", "#FFE0E0"),
        Item("🐱", "قطة", "chat", "cat", "#FFF2CC"),
        Item("🚗", "سيارة", "voiture", "car", "#DDEBFF"),
        Item("⚽", "كرة", "ballon", "ball", "#E2F5E2"),
        Item("🌞", "شمس", "soleil", "sun", "#FFF9C4"),
        Item("🐟", "سمكة", "poisson", "fish", "#D8F3F7"),
        Item("أ A", "ألف", "A", "A", "#EADCF8"),
        Item("ب B", "باء", "B", "B", "#EADCF8"),
        Item("ت T", "تاء", "T", "T", "#EADCF8"),
        Item("1", "واحد", "un", "one", "#FFE8CC"),
        Item("2", "اثنان", "deux", "two", "#FFE8CC"),
        Item("3", "ثلاثة", "trois", "three", "#FFE8CC"),
        Item("4", "أربعة", "quatre", "four", "#FFE8CC"),
        Item("5", "خمسة", "cinq", "five", "#FFE8CC"),
    )
}
