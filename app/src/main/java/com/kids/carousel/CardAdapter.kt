package com.kids.carousel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Carte: fond dégradé de la couleur du thème, carte blanche arrondie avec ombre, pastille de catégorie,
 *  compteur, grande image/emoji dans un cadre coloré, puis les 3 langues avec leur épellation. */
class CardAdapter(private val items: List<Item>, private val onTap: (Item) -> Unit) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    class Row(val tag: TextView, val name: TextView, val spell: TextView, val group: LinearLayout)
    class VH(val page: FrameLayout, val chip: TextView, val counter: TextView, val frame: FrameLayout,
             val symbol: TextView, val img: ImageView, val rows: List<Row>) : RecyclerView.ViewHolder(page)

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(10)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun accentOf(base: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(base, hsv)
        if (hsv[1] < 0.05f) return Color.parseColor("#5C6BC0")
        hsv[1] = 0.62f; hsv[2] = 0.78f
        return Color.HSVToColor(hsv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val page = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setPadding(dp(16), dp(76), dp(16), dp(24))   // place en haut pour le cadenas 🔒
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            elevation = dp(10).toFloat()
            background = rounded(Color.WHITE, dp(32).toFloat())
        }
        page.addView(card, FrameLayout.LayoutParams(MATCH, MATCH))

        val chip = TextView(ctx).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        val counter = TextView(ctx).apply { textSize = 13f; setTextColor(Color.parseColor("#90A4AE")) }
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(chip, lp(WRAP, WRAP)); addView(View(ctx), lp(0, 1, 1f)); addView(counter, lp(WRAP, WRAP))
        }, lp(MATCH, WRAP))

        val symbol = TextView(ctx).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val frame = FrameLayout(ctx).apply {
            clipToOutline = true
            addView(symbol, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        card.addView(frame, lp(MATCH, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(8) })

        fun row(tagText: String, rtl: Boolean, maxSp: Int): Row {
            val tag = TextView(ctx).apply {
                text = tagText; setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; minWidth = dp(40); setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            val name = TextView(ctx).apply {
                gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
                setTextColor(Color.parseColor("#263238"))
                if (rtl) textDirection = View.TEXT_DIRECTION_RTL
                setAutoSizeTextTypeUniformWithConfiguration(18, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val spell = TextView(ctx).apply {
                gravity = Gravity.CENTER; textSize = 15f; letterSpacing = 0.12f; maxLines = 2
                setTextColor(Color.parseColor("#78909C"))
                if (rtl) textDirection = View.TEXT_DIRECTION_RTL
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(tag, lp(WRAP, WRAP)); addView(name, lp(0, WRAP, 1f)); addView(View(ctx), lp(dp(40), 1))
            }
            val group = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(top, lp(MATCH, WRAP)); addView(spell, lp(MATCH, WRAP))
            }
            card.addView(group, lp(MATCH, WRAP).apply { topMargin = dp(6) })
            return Row(tag, name, spell, group)
        }
        val rows = listOf(row("AR", true, 40), row("FR", false, 34), row("EN", false, 34))
        return VH(page, chip, counter, frame, symbol, img, rows)
    }

    private fun spell(w: String, ar: Boolean): String {
        val l = if (ar) ArabicSpell.letters(w).map { it.toString() } else w.filter { it.isLetter() }.map { it.uppercase() }
        return if (l.size < 2) "" else l.joinToString(if (ar) " " else "-")
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val item = items[i]
        val d = h.page.resources.displayMetrics.density
        val base = runCatching { Color.parseColor(item.color) }.getOrDefault(Color.parseColor("#EEEEEE"))
        val accent = accentOf(base)

        h.page.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            ColorUtils.blendARGB(accent, Color.WHITE, 0.45f), ColorUtils.blendARGB(accent, Color.WHITE, 0.75f)))
        h.frame.background = rounded(base, 28 * d)
        h.chip.visibility = if (item.cat.isBlank()) View.INVISIBLE else View.VISIBLE
        h.chip.text = item.cat
        h.chip.setTextColor(ColorUtils.blendARGB(accent, Color.BLACK, 0.3f))
        h.chip.background = rounded(base, 20 * d)
        h.counter.text = "${i + 1} / $itemCount"

        val texts = listOf(item.ar, item.fr, item.en)
        for (r in 0..2) {
            val row = h.rows[r]
            row.name.text = texts[r]
            row.tag.background = rounded(accent, 10 * d)
            val sp = if (item.spell) spell(texts[r], r == 0) else ""
            row.spell.text = sp
            row.spell.visibility = if (sp.isEmpty()) View.GONE else View.VISIBLE
        }
        h.page.setOnClickListener { onTap(item) }

        val path = item.imagePath
        if (path == null) {
            h.img.visibility = View.GONE; h.symbol.visibility = View.VISIBLE
            h.symbol.text = item.symbol
            h.symbol.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.symbol.length <= 4) 104f else 60f)
            h.symbol.setTextColor(accent)
        } else {
            h.symbol.visibility = View.GONE; h.img.visibility = View.VISIBLE
            h.img.tag = path
            val cached = cache.get(path)
            if (cached != null) h.img.setImageBitmap(cached)
            else {
                h.img.setImageDrawable(null)
                io.execute {
                    val bmp = BitmapFactory.decodeFile(path) ?: return@execute
                    main.post { cache.put(path, bmp); if (h.img.tag == path) h.img.setImageBitmap(bmp) }
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
