package com.kids.carousel

/** Épellation arabe: on ignore les voyelles brèves (tashkeel) et on dit le NOM de chaque lettre. */
object ArabicSpell {
    private val names = mapOf(
        'ا' to "ألف", 'أ' to "ألف", 'إ' to "ألف", 'آ' to "ألف", 'ٱ' to "ألف",
        'ب' to "باء", 'ت' to "تاء", 'ث' to "ثاء", 'ج' to "جيم", 'ح' to "حاء", 'خ' to "خاء",
        'د' to "دال", 'ذ' to "ذال", 'ر' to "راء", 'ز' to "زاي", 'س' to "سين", 'ش' to "شين",
        'ص' to "صاد", 'ض' to "ضاد", 'ط' to "طاء", 'ظ' to "ظاء", 'ع' to "عين", 'غ' to "غين",
        'ف' to "فاء", 'ق' to "قاف", 'ك' to "كاف", 'ل' to "لام", 'م' to "ميم", 'ن' to "نون",
        'ه' to "هاء", 'و' to "واو", 'ي' to "ياء", 'ؤ' to "واو", 'ئ' to "ياء", 'ء' to "همزة",
        'ة' to "تاء مربوطة", 'ى' to "ألف مقصورة"
    )

    /** Lettres de base du mot (sans harakat, tatweel, espaces ni caractères latins). */
    fun letters(word: String): List<Char> = word.filter { it in names }.toList()

    /** Texte à faire lire par la synthèse vocale: « تاء، فاء، ألف، حاء، تاء مربوطة ». */
    fun spokenNames(word: String): String = letters(word).joinToString("، ") { names.getValue(it) }
}
