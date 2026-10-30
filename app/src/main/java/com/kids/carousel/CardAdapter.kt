package com.kids.carousel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class CardAdapter(private val items: List<Item>, private val onTap: (Item) -> Unit) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(val root: LinearLayout, val img: ImageView, val symbol: TextView,
             val ar: TextView, val fr: TextView, val en: TextView) : RecyclerView.ViewHolder(root)

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(10)

    private fun tv(parent: ViewGroup, size: Float, rtl: Boolean = false) = TextView(parent.context).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#222222"))
        if (rtl) textDirection = View.TEXT_DIRECTION_RTL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(32, 32, 32, 32)
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (300 * ctx.resources.displayMetrics.density).toInt())
        }
        val s = tv(parent, 110f); val a = tv(parent, 40f, true); val f = tv(parent, 34f); val e = tv(parent, 34f)
        listOf<View>(img, s, a, f, e).forEach { root.addView(it) }
        return VH(root, img, s, a, f, e)
    }

    private fun spell(w: String, ar: Boolean): String {
        val l = if (ar) ArabicSpell.letters(w).map { it.toString() } else w.filter { it.isLetter() }.map { it.uppercase() }
        return if (l.size < 2) "" else "\n" + l.joinToString(if (ar) " " else "-")
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val it = items[i]
        h.root.setBackgroundColor(Color.parseColor(it.color))
        h.ar.text = it.ar + spell(it.ar, true)
        h.fr.text = it.fr + spell(it.fr, false)
        h.en.text = it.en + spell(it.en, false)
        h.root.setOnClickListener { _ -> onTap(it) }

        val path = it.imagePath
        if (path == null) {
            h.img.visibility = View.GONE; h.symbol.visibility = View.VISIBLE; h.symbol.text = it.symbol
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
