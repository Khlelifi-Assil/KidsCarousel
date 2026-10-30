package com.kids.carousel

import android.Manifest
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.util.Locale

class MainActivity : FragmentActivity() {
    private lateinit var pager: ViewPager2
    private lateinit var adapter: CardAdapter
    private lateinit var homeView: ScrollView
    private lateinit var homeBtn: TextView
    private var current: List<Item> = emptyList()
    private var currentCat: String? = null
    private var inHome = true

    private var tts: TextToSpeech? = null
    private var gen = 0
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var ttsReady = false
    private var speaking = false
    private var spokenPos = -1

    private val auto = object : Runnable {
        override fun run() {
            if (inHome || current.isEmpty()) return
            if (speaking) { handler.postDelayed(this, 1000); return }
            pager.currentItem = (pager.currentItem + 1) % current.size
            handler.postDelayed(this, 8000) // filet de sécurité si aucune voix n'est disponible
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ================= écrans =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        Content.load(this)
        pager = ViewPager2(this).apply { visibility = View.GONE }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {      // la page change: on coupe tout de suite l'ancienne voix
                gen++; speaking = false; spokenPos = -1; tts?.stop(); clearHighlights()
            }
            override fun onPageScrollStateChanged(state: Int) { // page stabilisée: on lit la bonne image
                if (state == ViewPager2.SCROLL_STATE_IDLE) speakCurrent()
            }
        })
        homeView = ScrollView(this)
        homeBtn = TextView(this).apply {
            text = "🏠"; textSize = 28f; setPadding(40, 60, 40, 40); visibility = View.GONE
            setOnClickListener { showHome() }
        }
        val lock = TextView(this).apply {
            text = "🔒"; textSize = 28f; alpha = 0.35f; setPadding(40, 60, 40, 40)
            setOnLongClickListener { askExit(); true }   // appui long = sortie parent
        }
        setContentView(FrameLayout(this).apply {
            addView(homeView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(pager, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(homeBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(lock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!inHome) showHome() } // Retour: au menu des catégories seulement
        })

        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) runOnUiThread { ttsReady = true; speakCurrent() } }
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        AlarmHelper.schedule(this, Config.HOUR, Config.MIN)

        showHome()
        if (!PinStore.hasPin(this)) askNewPin() else startLock()
    }

    private fun tile(symbol: String, ar: String, fr: String, count: Int, color: String, onClick: () -> Unit): View {
        val base = runCatching { Color.parseColor(color) }.getOrDefault(Color.LTGRAY)
        val dark = Color.HSVToColor(FloatArray(3).also { Color.colorToHSV(base, it); it[1] = 0.6f; it[2] = 0.45f })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(14)); elevation = dp(4).toFloat()
            background = GradientDrawable().apply { setColor(base); cornerRadius = dp(26).toFloat() }
            addView(TextView(context).apply { text = symbol; textSize = 42f; gravity = Gravity.CENTER; setTextColor(dark) })
            addView(TextView(context).apply {
                text = ar; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#263238")); textDirection = View.TEXT_DIRECTION_RTL
            })
            addView(TextView(context).apply {
                text = fr; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#455A64"))
            })
            addView(TextView(context).apply {
                text = "$count"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(dark)
            })
            setOnClickListener { onClick() }
        }
    }

    /** Menu d'accueil: une grande tuile par catégorie. */
    private fun buildHome() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(72), dp(16), dp(28))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(TextView(context).apply {
                    text = "Kids Carousel"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#37474F"))
                })
                addView(TextView(context).apply {
                    text = "تعلّم · Apprends · Learn"; textSize = 15f; setTextColor(Color.parseColor("#78909C"))
                })
            })
        })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))

        val tiles = mutableListOf<View>()
        tiles += tile("🌟", "الكل", "Tout", Content.items.size, "#FFF59D") { showCategory(null) }
        Content.categories.forEach { c ->
            val n = Content.itemsOf(c.id).size
            if (n > 0) tiles += tile(c.symbol, c.ar, c.fr, n, c.color) { showCategory(c.id) }
        }
        tiles.chunked(2).forEach { pair ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEach { t -> addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }) }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            })
        }
        homeView.removeAllViews(); homeView.addView(col)
    }

    private fun showHome() {
        inHome = true
        gen++; speaking = false; spokenPos = -1; tts?.stop(); handler.removeCallbacks(auto)
        buildHome()
        pager.visibility = View.GONE; homeView.visibility = View.VISIBLE; homeBtn.visibility = View.GONE
        homeView.scrollTo(0, 0)
    }

    private fun showCategory(catId: String?) {
        val list = Content.itemsOf(catId)
        if (list.isEmpty()) { toast("Cette catégorie est vide"); return }
        inHome = false; currentCat = catId; current = list
        adapter = CardAdapter(current) { speak(it) }
        pager.adapter = adapter
        pager.setCurrentItem(0, false)
        homeView.visibility = View.GONE; pager.visibility = View.VISIBLE; homeBtn.visibility = View.VISIBLE
        gen++; speaking = false; spokenPos = -1; tts?.stop()
        pager.post { speakCurrent() }
        handler.removeCallbacks(auto); handler.postDelayed(auto, 8000)
    }

    private fun refreshAfterChange(openCat: String? = null, goLast: Boolean = false) {
        Content.load(this)
        if (openCat != null) {
            showCategory(openCat)
            if (goLast) pager.post { pager.setCurrentItem(current.size - 1, false) }
        } else if (inHome) showHome() else showCategory(currentCat)
    }

    // ================= lecture =================
    private fun currentVH(): CardAdapter.VH? {
        if (!::adapter.isInitialized || inHome) return null
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(pager.currentItem) as? CardAdapter.VH
    }

    private fun clearHighlights() {
        if (!::adapter.isInitialized) return
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (k in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(k)) as? CardAdapter.VH)?.let { adapter.highlight(it, -1, -1) }
    }

    /** Lit l'élément affiché, une seule fois par page. */
    private fun speakCurrent() {
        if (inHome || !resumed || !ttsReady) return
        val p = pager.currentItem
        if (spokenPos == p) return
        spokenPos = p
        current.getOrNull(p)?.let { speak(it) }
    }

    private fun finished(my: Int) {
        if (my != gen) return
        speaking = false; clearHighlights()
        if (resumed && !inHome) { handler.removeCallbacks(auto); handler.postDelayed(auto, 1500) }
    }

    override fun onResume() { super.onResume(); resumed = true; speakCurrent() }
    override fun onPause() {
        super.onPause(); resumed = false; gen++; speaking = false; spokenPos = -1
        tts?.stop(); handler.removeCallbacks(auto)
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private fun startLock() { try { startLockTask() } catch (_: Exception) {} }

    // ---- Voix: mot puis épellation lettre par lettre (la lettre dite est colorée) ----
    private data class Step(val loc: Locale, val text: String, val rate: Float, val row: Int = -1, val idx: Int = -1)

    private fun speak(item: Item) {
        val steps = mutableListOf<Step>()
        listOf(Locale("ar") to item.ar, Locale.FRENCH to item.fr, Locale.ENGLISH to item.en).forEachIndexed { r, (l, w) ->
            val code = l.language
            if (code !in item.langs || w.isBlank()) return@forEachIndexed
            steps += Step(l, w, 0.9f, r, -2)
            if (!item.spell) return@forEachIndexed
            if (code == "ar") {
                val ls = ArabicSpell.letters(w)
                if (ls.size > 1) ls.forEachIndexed { k, ch -> steps += Step(l, ArabicSpell.name(ch), 0.75f, r, k) }
            } else {
                val ls = w.filter { it.isLetter() }
                if (ls.length > 1) ls.forEachIndexed { k, ch -> steps += Step(l, ch.uppercase(), 0.7f, r, k) }
            }
        }
        if (steps.isEmpty()) return
        val my = ++gen
        speaking = true; handler.removeCallbacks(auto)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { runOnUiThread { finished(my) } }
            override fun onDone(id: String?) {
                val next = (id?.removePrefix("u")?.toIntOrNull() ?: return) + 1
                if (my != gen) return
                if (next < steps.size) runOnUiThread { say(steps, next, my) } else runOnUiThread { finished(my) }
            }
        })
        say(steps, 0, my)
    }

    private fun voiceFor(loc: Locale): Voice? {
        val all = tts?.voices?.filter { it.locale.language == loc.language } ?: return null
        val saved = prefs().getString("voice_" + loc.language, null)
        return all.firstOrNull { it.name == saved }
            ?: all.firstOrNull { it.name.contains("female", true) && !it.isNetworkConnectionRequired }
            ?: all.firstOrNull { !it.isNetworkConnectionRequired }
            ?: all.firstOrNull()
    }

    private fun say(seq: List<Step>, i: Int, my: Int) {
        if (my != gen) return
        val st = seq[i]
        currentVH()?.let { adapter.highlight(it, st.row, st.idx) }
        val v = voiceFor(st.loc)
        if (v != null) tts?.voice = v else tts?.language = st.loc
        tts?.setPitch(if (prefs().getBoolean("girl", true)) 1.5f else 1.15f) // 1.5 = voix de petite fille
        tts?.setSpeechRate(st.rate)
        tts?.speak(st.text, TextToSpeech.QUEUE_FLUSH, null, "u$i")
    }

    private fun pickVoice() {
        val langs = listOf("العربية" to "ar", "Français" to "fr", "English" to "en")
        AlertDialog.Builder(this).setTitle("Langue")
            .setItems(langs.map { it.first }.toTypedArray()) { _, i -> pickVoiceFor(langs[i].second) }.show()
    }

    private fun pickVoiceFor(lang: String) {
        val vs = tts?.voices?.filter { it.locale.language == lang }?.sortedBy { it.name } ?: emptyList()
        if (vs.isEmpty()) { toast("Aucune voix pour cette langue (Paramètres > Synthèse vocale)"); return }
        val labels = vs.mapIndexed { i, v ->
            "Voix ${i + 1} · ${v.locale}" + if (v.isNetworkConnectionRequired) " (en ligne)" else ""
        }
        AlertDialog.Builder(this).setTitle("Touche une voix pour l'écouter")
            .setItems(labels.toTypedArray()) { _, i ->
                prefs().edit().putString("voice_$lang", vs[i].name).apply()
                val sample = mapOf("ar" to "تفاحة", "fr" to "pomme", "en" to "apple")[lang]!!
                val my = ++gen
                say(listOf(Step(Locale(lang), sample, 0.8f)), 0, my)
            }.setNegativeButton("Terminé", null).show()
    }

    // ================= sortie parent: empreinte ou PIN =================
    private fun askExit() {
        val ok = BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        if (!ok) { askPin(); return }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Réservé aux parents")
            .setNegativeButtonText("Code PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = parentMenu()
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) askPin()
            }
        }).authenticate(info)
    }

    private fun pinInput() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        gravity = Gravity.CENTER
    }

    private fun askPin() {
        val et = pinInput()
        AlertDialog.Builder(this).setTitle("Code PIN parent").setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (PinStore.check(this, et.text.toString())) parentMenu()
                else Toast.makeText(this, "PIN incorrect", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun askNewPin() {
        val et = pinInput()
        AlertDialog.Builder(this).setTitle("Créer le code PIN parent (4 chiffres min.)").setView(et)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val p = et.text.toString()
                if (p.length >= 4) { PinStore.setPin(this, p); startLock() } else askNewPin()
            }.show()
    }

    // ================= espace parent =================
    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun parentMenu() {
        val labels = arrayOf(
            "➕ Ajouter des objets (une ligne = un objet)", "🗑 Mes objets ajoutés", "Mettre à jour le contenu (Internet)",
            "Adresse du pack", "Choisir la voix",
            "Voix petite fille : " + if (prefs().getBoolean("girl", true)) "OUI → désactiver" else "NON → activer",
            "Quitter l'application")
        AlertDialog.Builder(this).setTitle("Espace parent").setItems(labels) { _, w ->
            when (w) {
                0 -> askObject(); 1 -> manageObjects(); 2 -> runImport(); 3 -> askUrl(); 4 -> pickVoice()
                5 -> { prefs().edit().putBoolean("girl", !prefs().getBoolean("girl", true)).apply(); parentMenu() }
                6 -> exitApp()
            }
        }.show()
    }

    // ---- Saisir et générer des objets (une ligne = un objet) ----
    private data class Entry(val ar: String, val fr: String, val en: String)

    private fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }

    private fun parseLines(text: String, singleIsEnglish: Boolean): List<Entry> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val p = line.split("|").map { it.trim() }
            if (p.size == 1) when {
                hasArabic(p[0]) -> Entry(p[0], "", "")
                singleIsEnglish -> Entry("", "", p[0])
                else -> Entry("", p[0], "")
            } else Entry(ar = p.getOrElse(2) { "" }, fr = p[0], en = p.getOrElse(1) { "" })
        }.filter { it.ar.isNotEmpty() || it.fr.isNotEmpty() || it.en.isNotEmpty() }

    private fun askObject() {
        val input = EditText(this).apply {
            hint = "apple\nchat | cat\nvoiture | car | سيارة\nقطة"
            minLines = 7; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val rbEn = RadioButton(this).apply { text = "English"; id = View.generateViewId(); isChecked = true }
        val rbFr = RadioButton(this).apply { text = "Français"; id = View.generateViewId() }
        val rg = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL; addView(rbEn); addView(rbFr) }
        val cats = Content.categories.filter { it.id !in setOf("numbers", "latin", "arabic") }
        val spinner = Spinner(this).apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                cats.map { "${it.symbol}  ${it.fr} · ${it.ar}" }))
            setSelection(cats.indexOfFirst { it.id == (currentCat ?: "mine") }.coerceAtLeast(0))
        }
        val note = TextView(this).apply {
            text = "Une ligne = un objet.\n• Mot seul : traduit automatiquement (langue des mots seuls ci-dessous ; l'arabe est reconnu tout seul).\n" +
                "• Ou : français | english | عربي (un champ peut rester vide).\n" +
                "• Plusieurs lignes : la première image trouvée est ajoutée automatiquement (PNG à fond transparent en priorité)."
            setPadding(0, 0, 0, 16)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(TextView(context).apply { text = "Catégorie :"; setPadding(0, 0, 0, 4) })
            addView(spinner); addView(View(context), LinearLayout.LayoutParams(1, 16))
            listOf(note, rg, input).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Nouveaux objets").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Générer") { _, _ ->
                val list = parseLines(input.text.toString(), rbEn.isChecked)
                val catId = cats.getOrNull(spinner.selectedItemPosition)?.id ?: "mine"
                when {
                    list.isEmpty() -> toast("Saisis au moins un mot")
                    list.size == 1 -> generateObject(list[0].ar, list[0].fr, list[0].en, catId) // aperçu + choix de l'image
                    else -> bulkAdd(list, catId)
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun bulkAdd(entries: List<Entry>, catId: String) {
        val msg = TextView(this).apply { setPadding(48, 32, 48, 0); textSize = 18f }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this).setTitle("Génération en cours…").setView(msg).setCancelable(false)
            .setNegativeButton("Arrêter") { _, _ -> cancel.set(true) }.create()
        dlg.show()
        Thread {
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, e) in entries.withIndex()) {
                if (cancel.get()) break
                val label = e.en.ifBlank { e.fr }.ifBlank { e.ar }
                runOnUiThread { msg.text = "${i + 1} / ${entries.size}\n$label" }
                val r = runCatching {
                    val w = ObjectGenerator.complete(e.ar, e.fr, e.en)
                    var best: Bitmap? = null; var bestCredit = ""
                    var fallback: Bitmap? = null; var fallbackCredit = ""
                    for (c in ObjectGenerator.search(w.third).take(8)) {
                        val b = ObjectGenerator.download(c.url) ?: continue
                        if (ObjectGenerator.isTransparent(b)) { best = b; bestCredit = c.credit; break }
                        if (fallback == null) { fallback = b; fallbackCredit = c.credit }
                    }
                    val bmp = best ?: fallback ?: error("aucune image")
                    ObjectGenerator.add(this, w.first, w.second, w.third, bmp, if (best != null) bestCredit else fallbackCredit, catId)
                }
                if (r.isSuccess) ok++ else failed += "$label (${r.exceptionOrNull()?.message})"
                Thread.sleep(800)
            }
            runOnUiThread {
                dlg.dismiss()
                refreshAfterChange(if (ok > 0) catId else null)
                AlertDialog.Builder(this).setTitle("$ok objet(s) ajouté(s)")
                    .setMessage(if (failed.isEmpty()) "Tout est passé. Vérifie les images en les faisant défiler ; supprime les mauvaises dans « Mes objets »."
                                else "Échecs :\n" + failed.joinToString("\n"))
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun generateObject(ar: String, fr: String, en: String, catId: String) {
        toast("Traduction et recherche d'image…")
        Thread {
            val r = runCatching {
                val w = ObjectGenerator.complete(ar, fr, en)
                w to ObjectGenerator.search(w.third)
            }
            runOnUiThread {
                r.onSuccess { (w, cands) ->
                    if (cands.isEmpty()) toast("Aucune image trouvée pour « ${w.third} ». Essaie un autre mot anglais.")
                    else previewObject(w.first, w.second, w.third, cands, 0, catId)
                }.onFailure { toast("Échec : ${it.message}") }
            }
        }.start()
    }

    private fun previewObject(ar: String, fr: String, en: String, cands: List<ObjectGenerator.Candidate>, idx: Int, catId: String) {
        if (idx >= cands.size) { toast("Plus d'autres images pour « $en »"); return }
        Thread {
            val bmp = ObjectGenerator.download(cands[idx].url)
            runOnUiThread {
                if (bmp == null) { previewObject(ar, fr, en, cands, idx + 1, catId); return@runOnUiThread }
                val transparent = ObjectGenerator.isTransparent(bmp)
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#ECEFF1"))
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, dp(280))
                }
                AlertDialog.Builder(this).setTitle("$ar · $fr · $en" + if (transparent) "  ✓ PNG transparent" else "")
                    .setView(iv)
                    .setPositiveButton("Ajouter") { _, _ ->
                        ObjectGenerator.add(this, ar, fr, en, bmp, cands[idx].credit, catId)
                        refreshAfterChange(catId, goLast = true)
                        toast("Objet ajouté")
                    }
                    .setNeutralButton("Autre image") { _, _ -> previewObject(ar, fr, en, cands, idx + 1, catId) }
                    .setNegativeButton("Annuler", null).show()
            }
        }.start()
    }

    private fun manageObjects() {
        val mine = Content.items.filter { it.imagePath?.contains("/custom/") == true }
        if (mine.isEmpty()) { toast("Aucun objet ajouté pour l'instant"); return }
        AlertDialog.Builder(this).setTitle("Touche un objet pour le supprimer")
            .setItems(mine.map { "${it.fr} · ${it.en} · ${it.ar}" }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setTitle("Supprimer « ${mine[i].fr} » ?")
                    .setPositiveButton("Supprimer") { _, _ ->
                        ObjectGenerator.remove(this, File(mine[i].imagePath!!).nameWithoutExtension)
                        refreshAfterChange(); toast("Supprimé")
                    }.setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Fermer", null).show()
    }

    private fun runImport() {
        val url = prefs().getString("url", "") ?: ""
        if (url.isBlank()) { askUrl(); return }
        toast("Téléchargement en cours…")
        PackImporter.import(this, url) { r ->
            runOnUiThread {
                r.onSuccess { msg -> refreshAfterChange(); toast(msg) }
                    .onFailure { toast("Échec : ${it.message}") }
            }
        }
    }

    private fun askUrl() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs().getString("url", "https://raw.githubusercontent.com/Khlelifi-Assil/KidsCarousel/main/pack/items.json"))
        }
        AlertDialog.Builder(this).setTitle("Adresse du pack").setView(et)
            .setMessage("Colle l'adresse du dépôt GitHub (https://github.com/USER/REPO) ou celle du fichier pack/items.json. Le dépôt doit être public.")
            .setPositiveButton("Enregistrer et importer") { _, _ ->
                prefs().edit().putString("url", et.text.toString().trim()).apply(); runImport()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun exitApp() {
        try { stopLockTask() } catch (_: Exception) {}
        finishAndRemoveTask()
    }
}
