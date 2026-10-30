package com.kids.carousel

import android.Manifest
import android.app.AlertDialog
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.viewpager2.widget.ViewPager2
import java.util.Locale

class MainActivity : FragmentActivity() {
    private lateinit var pager: ViewPager2
    private var tts: TextToSpeech? = null
    private var gen = 0
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var ttsReady = false
    private var speaking = false
    private var spokenPos = -1
    private val auto = object : Runnable {
        override fun run() {
            if (speaking) { handler.postDelayed(this, 1000); return }
            pager.currentItem = (pager.currentItem + 1) % Content.items.size
            handler.postDelayed(this, 8000) // filet de sécurité si aucune voix n'est disponible
        }
    }

    /** Lit l'élément affiché, une seule fois par page. */
    private fun speakCurrent() {
        val p = pager.currentItem
        if (!resumed || !ttsReady || spokenPos == p) return
        spokenPos = p
        Content.items.getOrNull(p)?.let { speak(it) }
    }

    private fun finished(my: Int) {
        if (my != gen) return
        speaking = false
        if (resumed) { handler.removeCallbacks(auto); handler.postDelayed(auto, 1500) }
    }

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
        pager = ViewPager2(this).apply { adapter = newAdapter() }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {      // la page change: on coupe tout de suite l'ancienne voix
                gen++; speaking = false; spokenPos = -1; tts?.stop()
            }
            override fun onPageScrollStateChanged(state: Int) { // page stabilisée: on lit la bonne image
                if (state == ViewPager2.SCROLL_STATE_IDLE) speakCurrent()
            }
        })
        val lock = TextView(this).apply {
            text = "🔒"; textSize = 28f; alpha = 0.35f; setPadding(40, 60, 40, 40)
            setOnLongClickListener { askExit(); true }   // appui long = sortie parent
        }
        setContentView(FrameLayout(this).apply {
            addView(pager, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(lock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {} // Retour désactivé
        })

        tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) runOnUiThread { ttsReady = true; speakCurrent() } }
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        AlarmHelper.schedule(this, Config.HOUR, Config.MIN)

        if (!PinStore.hasPin(this)) askNewPin() else startLock()
    }

    override fun onResume() { super.onResume(); resumed = true; speakCurrent() }
    override fun onPause() {
        super.onPause(); resumed = false; gen++; speaking = false; spokenPos = -1
        tts?.stop(); handler.removeCallbacks(auto)
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private fun startLock() { try { startLockTask() } catch (_: Exception) {} }

    // ---- Voix féminine / petite fille: mot puis épellation, en ar, fr, en ----
    private data class Step(val loc: Locale, val text: String, val rate: Float)

    private fun speak(item: Item) {
        val steps = mutableListOf<Step>()
        listOf(Locale("ar") to item.ar, Locale.FRENCH to item.fr, Locale.ENGLISH to item.en).forEach { (l, w) ->
            steps += Step(l, w, 0.9f)
            if (l.language == "ar") {
                if (ArabicSpell.letters(w).size > 1) steps += Step(l, ArabicSpell.spokenNames(w), 0.75f)
            } else {
                val ls = w.filter { it.isLetter() }
                if (ls.length > 1) steps += Step(l, ls.uppercase().toList().joinToString(", "), 0.6f)
            }
        }
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
                val sample = mapOf("ar" to "تفاحة", "fr" to "pomme, P, O, M, M, E", "en" to "apple, A, P, P, L, E")[lang]!!
                val my = ++gen
                say(listOf(Step(Locale(lang), sample, 0.8f)), 0, my)
            }.setNegativeButton("Terminé", null).show()
    }

    // ---- Sortie parent: empreinte ou PIN ----
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

    // ---- Espace parent: import Internet ----
    private fun newAdapter() = CardAdapter(Content.items) { speak(it) }
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
        val rbEn = android.widget.RadioButton(this).apply { text = "English"; id = View.generateViewId(); isChecked = true }
        val rbFr = android.widget.RadioButton(this).apply { text = "Français"; id = View.generateViewId() }
        val rg = android.widget.RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL; addView(rbEn); addView(rbFr) }
        val note = TextView(this).apply {
            text = "Une ligne = un objet.\n• Mot seul : traduit automatiquement (langue des mots seuls ci-dessous ; l'arabe est reconnu tout seul).\n" +
                "• Ou : français | english | عربي (un champ peut rester vide).\n" +
                "• Plusieurs lignes : la première image trouvée est ajoutée automatiquement."
            setPadding(0, 0, 0, 16)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            listOf(note, rg, input).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Nouveaux objets").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Générer") { _, _ ->
                val list = parseLines(input.text.toString(), rbEn.isChecked)
                when {
                    list.isEmpty() -> toast("Saisis au moins un mot")
                    list.size == 1 -> generateObject(list[0].ar, list[0].fr, list[0].en) // aperçu + choix de l'image
                    else -> bulkAdd(list)
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun bulkAdd(entries: List<Entry>) {
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
                    var bmp: android.graphics.Bitmap? = null
                    var credit = ""
                    for (c in ObjectGenerator.search(w.third).take(5)) {
                        bmp = ObjectGenerator.download(c.url)
                        if (bmp != null) { credit = c.credit; break }
                    }
                    ObjectGenerator.add(this, w.first, w.second, w.third, bmp ?: error("aucune image"), credit)
                }
                if (r.isSuccess) ok++ else failed += "$label (${r.exceptionOrNull()?.message})"
                Thread.sleep(800)
            }
            runOnUiThread {
                dlg.dismiss()
                Content.load(this); pager.adapter = newAdapter()
                if (ok > 0) pager.setCurrentItem(Content.items.size - ok, false)
                AlertDialog.Builder(this).setTitle("$ok objet(s) ajouté(s)")
                    .setMessage(if (failed.isEmpty()) "Tout est passé. Vérifie les images en les faisant défiler ; supprime les mauvaises dans « Mes objets »."
                                else "Échecs :\n" + failed.joinToString("\n"))
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun generateObject(ar: String, fr: String, en: String) {
        toast("Traduction et recherche d'image…")
        Thread {
            val r = runCatching {
                val w = ObjectGenerator.complete(ar, fr, en)
                w to ObjectGenerator.search(w.third)
            }
            runOnUiThread {
                r.onSuccess { (w, cands) ->
                    if (cands.isEmpty()) toast("Aucune image trouvée pour « ${w.third} ». Essaie un autre mot anglais.")
                    else previewObject(w.first, w.second, w.third, cands, 0)
                }.onFailure { toast("Échec : ${it.message}") }
            }
        }.start()
    }

    private fun previewObject(ar: String, fr: String, en: String, cands: List<ObjectGenerator.Candidate>, idx: Int) {
        if (idx >= cands.size) { toast("Plus d'autres images pour « $en »"); return }
        Thread {
            val bmp = ObjectGenerator.download(cands[idx].url)
            runOnUiThread {
                if (bmp == null) { previewObject(ar, fr, en, cands, idx + 1); return@runOnUiThread }
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, (280 * resources.displayMetrics.density).toInt())
                }
                AlertDialog.Builder(this).setTitle("$ar · $fr · $en").setView(iv)
                    .setPositiveButton("Ajouter") { _, _ ->
                        ObjectGenerator.add(this, ar, fr, en, bmp, cands[idx].credit)
                        Content.load(this); pager.adapter = newAdapter()
                        pager.setCurrentItem(Content.items.size - 1, false)
                        toast("Objet ajouté")
                    }
                    .setNeutralButton("Autre image") { _, _ -> previewObject(ar, fr, en, cands, idx + 1) }
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
                        Content.load(this); pager.adapter = newAdapter(); toast("Supprimé")
                    }.setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Fermer", null).show()
    }

    private fun runImport() {
        val url = prefs().getString("url", "") ?: ""
        if (url.isBlank()) { askUrl(); return }
        toast("Téléchargement en cours…")
        PackImporter.import(this, url) { r ->
            runOnUiThread {
                r.onSuccess { n -> pager.adapter = newAdapter(); toast("$n images importées") }
                    .onFailure { toast("Échec : ${it.message}") }
            }
        }
    }

    private fun askUrl() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs().getString("url", "https://raw.githubusercontent.com/USER/REPO/main/pack/items.json"))
        }
        AlertDialog.Builder(this).setTitle("Adresse de items.json").setView(et)
            .setPositiveButton("Enregistrer et importer") { _, _ ->
                prefs().edit().putString("url", et.text.toString().trim()).apply(); runImport()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun exitApp() {
        try { stopLockTask() } catch (_: Exception) {}
        finishAndRemoveTask()
    }
}
