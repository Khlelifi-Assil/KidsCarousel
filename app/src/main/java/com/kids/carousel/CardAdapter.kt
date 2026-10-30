package com.kids.carousel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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

/** Carte: fond dégradé du thème, carte blanche arrondie, pastille de catégorie, compteur,
 *  grande image (PNG transparent) ou emoji, puis les langues de la carte avec leur épellation.
 *  highlight() colore la lettre en cours d'épellation. */
class CardAdapter(private val items: List<Item>, private val onTap: (Item) -> Unit) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    class Row(val tag: TextView, val name: TextView, val spell: TextView, val group: LinearLayout) {
        var plain: String = ""
    }

    class VH(val page: FrameLayout, val chip: TextView, val counter: TextView, val frame: FrameLayout,
             val symbol: TextView, val img: ImageView, val rows: List<Row>) : RecyclerView.ViewHolder(page) {
        var accent: Int = Color.GRAY
    }

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(12)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val TEXT = Color.parseColor("#263238")

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
            setPadding(dp(16), dp(76), dp(16), dp(24))   // place en haut pour 🏠 et 🔒
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
        val img = ImageView(ctx)
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
                setTextColor(TEXT)
                if (rtl) textDirection = View.TEXT_DIRECTION_RTL
                setAutoSizeTextTypeUniformWithConfiguration(18, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val spell = TextView(ctx).apply {
                gravity = Gravity.CENTER; textSize = 16f; letterSpacing = 0.12f; maxLines = 2
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

    private val codes = listOf("ar", "fr", "en")

    /** row: 0=AR 1=FR 2=EN, -1 = rien. idx: -2 = mot en cours (nom coloré), >=0 = lettre en cours, -1 = rien. */
    fun highlight(h: VH, row: Int, idx: Int) {
        val dark = ColorUtils.blendARGB(h.accent, Color.BLACK, 0.25f)
        for (r in 0..2) {
            val rw = h.rows[r]
            rw.name.setTextColor(if (r == row && idx == -2) dark else TEXT)
            if (rw.plain.isEmpty()) continue
            val s = idx * 2   // lettres séparées par un caractère ("P-O-M" ou "ت ف ا")
            if (r == row && idx >= 0 && s < rw.plain.length) {
                val sp = SpannableString(rw.plain)
                val fl = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                sp.setSpan(BackgroundColorSpan(h.accent), s, s + 1, fl)
                sp.setSpan(ForegroundColorSpan(Color.WHITE), s, s + 1, fl)
                sp.setSpan(StyleSpan(Typeface.BOLD), s, s + 1, fl)
                sp.setSpan(RelativeSizeSpan(1.4f), s, s + 1, fl)
                rw.spell.text = sp
            } else rw.spell.text = rw.plain
        }
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val item = items[i]
        val d = h.page.resources.displayMetrics.density
        val base = runCatching { Color.parseColor(item.color) }.getOrDefault(Color.parseColor("#EEEEEE"))
        val accent = accentOf(base)
        h.accent = accent

        h.page.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            ColorUtils.blendARGB(accent, Color.WHITE, 0.45f), ColorUtils.blendARGB(accent, Color.WHITE, 0.75f)))
        h.frame.background = rounded(base, 28 * d)
        val label = Content.label(item.cat)
        h.chip.visibility = if (label.isBlank()) View.INVISIBLE else View.VISIBLE
        h.chip.text = label
        h.chip.setTextColor(ColorUtils.blendARGB(accent, Color.BLACK, 0.3f))
        h.chip.background = rounded(base, 20 * d)
        h.counter.text = "${i + 1} / $itemCount"

        val texts = listOf(item.ar, item.fr, item.en)
        for (r in 0..2) {
            val row = h.rows[r]
            val shown = codes[r] in item.langs && texts[r].isNotBlank()
            row.group.visibility = if (shown) View.VISIBLE else View.GONE
            row.name.text = texts[r]
            row.tag.background = rounded(accent, 10 * d)
            row.plain = if (shown && item.spell) spell(texts[r], r == 0) else ""
            row.spell.text = row.plain
            row.spell.visibility = if (row.plain.isEmpty()) View.GONE else View.VISIBLE
        }
        highlight(h, -1, -1)
        h.page.setOnClickListener { onTap(item) }

        val path = item.imagePath
        if (path == null) {
            h.img.visibility = View.GONE; h.symbol.visibility = View.VISIBLE
            h.symbol.text = item.symbol
            h.symbol.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.symbol.length <= 4) 104f else 60f)
            h.symbol.setTextColor(accent)
        } else {
            h.symbol.visibility = View.GONE; h.img.visibility = View.VISIBLE
            val png = path.endsWith(".png")      // PNG détouré: on montre l'objet entier, sans le rogner
            h.img.scaleType = if (png) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            val pad = if (png) (14 * d).toInt() else 0
            h.img.setPadding(pad, pad, pad, pad)
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
