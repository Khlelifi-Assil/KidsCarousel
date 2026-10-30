package com.kids.carousel

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Item(val symbol: String, val ar: String, val fr: String, val en: String, val color: String, val imagePath: String? = null)

object Content {
    var items: List<Item> = emptyList()

    /** Contenu intégré + pack importé depuis Internet (si présent). */
    fun load(c: Context) {
        val imported = runCatching {
            val dir = File(c.filesDir, "pack")
            val arr = JSONObject(File(dir, "items.json").readText()).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
                val f = File(dir, o.getString("img"))
                if (!f.exists()) null
                else Item("", o.getString("ar"), o.getString("fr"), o.getString("en"),
                    o.optString("color", "#EEEEEE"), f.path)
            }
        }.getOrDefault(emptyList())
        items = builtIn + imported
    }

    // Pour utiliser de vraies images: remplace `symbol` par un id drawable et affiche-le dans un ImageView.
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
