package com.kids.carousel

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Item(val symbol: String, val ar: String, val fr: String, val en: String, val color: String, val imagePath: String? = null, val spell: Boolean = true,
                val onlyAr: Boolean = false, val cat: String = "")

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

    private fun cat(label: String, color: String, rows: String) = rows.trim().lines().map {
        val p = it.trim().split("|")
        Item(p[0], p[1], p[2], p[3], color, cat = label)
    }

    private val numbers = listOf(
        Triple("واحد", "un", "one"), Triple("اثنان", "deux", "two"), Triple("ثلاثة", "trois", "three"),
        Triple("أربعة", "quatre", "four"), Triple("خمسة", "cinq", "five"), Triple("ستة", "six", "six"),
        Triple("سبعة", "sept", "seven"), Triple("ثمانية", "huit", "eight"), Triple("تسعة", "neuf", "nine"),
        Triple("عشرة", "dix", "ten"), Triple("أحد عشر", "onze", "eleven"), Triple("اثنا عشر", "douze", "twelve"),
        Triple("ثلاثة عشر", "treize", "thirteen"), Triple("أربعة عشر", "quatorze", "fourteen"),
        Triple("خمسة عشر", "quinze", "fifteen"), Triple("ستة عشر", "seize", "sixteen"),
        Triple("سبعة عشر", "dix-sept", "seventeen"), Triple("ثمانية عشر", "dix-huit", "eighteen"),
        Triple("تسعة عشر", "dix-neuf", "nineteen"), Triple("عشرون", "vingt", "twenty")
    ).mapIndexed { i, t -> Item("${i + 1}", t.first, t.second, t.third, "#FFE0B2", cat = "أرقام · Nombres") }

    // Lettres latines A-Z (nom arabe = prononciation arabe de la lettre latine)
    private val latin = ('A'..'Z').zip(listOf(
        "إيه", "بي", "سي", "دي", "إي", "إف", "جي", "إتش", "آي", "جاي", "كيه", "إل", "إم",
        "إن", "أو", "بي", "كيو", "آر", "إس", "تي", "يو", "في", "دبليو", "إكس", "واي", "زد"
    )).map { (c, ar) -> Item("$c${c.lowercaseChar()}", ar, "$c", "$c", "#E1D5F5", spell = false, cat = "حروف · Lettres") }

    // Alphabet arabe: seul le nom arabe est prononcé, la transcription s'affiche en fr/en
    private val arabic = cat("الحروف العربية · Lettres arabes", "#D7EFD0", """
أ|ألف|alif|alif
ب|باء|baa|baa
ت|تاء|taa|taa
ث|ثاء|thaa|thaa
ج|جيم|jeem|jeem
ح|حاء|Haa|Haa
خ|خاء|khaa|khaa
د|دال|dal|dal
ذ|ذال|dhal|dhal
ر|راء|raa|raa
ز|زاي|zay|zay
س|سين|seen|seen
ش|شين|sheen|sheen
ص|صاد|Saad|Saad
ض|ضاد|Daad|Daad
ط|طاء|Taa|Taa
ظ|ظاء|Zaa|Zaa
ع|عين|ayn|ayn
غ|غين|ghayn|ghayn
ف|فاء|faa|faa
ق|قاف|qaf|qaf
ك|كاف|kaf|kaf
ل|لام|lam|lam
م|ميم|meem|meem
ن|نون|noon|noon
ه|هاء|haa|haa
و|واو|waw|waw
ي|ياء|yaa|yaa
""").map { it.copy(spell = false, onlyAr = true) }

    private val animals = cat("حيوانات · Animaux", "#FFF2CC", """
🐶|كلب|chien|dog
🐱|قطة|chat|cat
🐴|حصان|cheval|horse
🐮|بقرة|vache|cow
🐑|خروف|mouton|sheep
🐐|ماعز|chèvre|goat
🐔|دجاجة|poule|chicken
🐰|أرنب|lapin|rabbit
🐭|فأر|souris|mouse
🐘|فيل|éléphant|elephant
🦁|أسد|lion|lion
🐯|نمر|tigre|tiger
🐻|دب|ours|bear
🐒|قرد|singe|monkey
🦒|زرافة|girafe|giraffe
🐟|سمكة|poisson|fish
🐢|سلحفاة|tortue|turtle
🐸|ضفدع|grenouille|frog
🦆|بطة|canard|duck
🦋|فراشة|papillon|butterfly
🐝|نحلة|abeille|bee
🐦|عصفور|oiseau|bird
🐍|ثعبان|serpent|snake
🐪|جمل|dromadaire|camel
🐬|دلفين|dauphin|dolphin
🦓|حمار وحشي|zèbre|zebra
""")

    private val fruits = cat("فواكه · Fruits", "#FFE0E0", """
🍎|تفاحة|pomme|apple
🍌|موزة|banane|banana
🍊|برتقالة|orange|orange
🍇|عنب|raisin|grapes
🍓|فراولة|fraise|strawberry
🍉|بطيخ|pastèque|watermelon
🍐|إجاصة|poire|pear
🍑|خوخة|pêche|peach
🍒|كرز|cerises|cherries
🍍|أناناس|ananas|pineapple
🍋|ليمونة|citron|lemon
🥝|كيوي|kiwi|kiwi
""")

    private val vegetables = cat("خضروات · Légumes", "#DCEDC8", """
🍅|طماطم|tomate|tomato
🥕|جزرة|carotte|carrot
🥔|بطاطا|pomme de terre|potato
🌽|ذرة|maïs|corn
🥒|خيار|concombre|cucumber
🍆|باذنجان|aubergine|eggplant
🥦|بروكلي|brocoli|broccoli
🍄|فطر|champignon|mushroom
""")

    private val food = cat("طعام · Nourriture", "#FFE8CC", """
🍞|خبز|pain|bread
🧀|جبن|fromage|cheese
🥚|بيضة|œuf|egg
🍕|بيتزا|pizza|pizza
🍪|بسكويت|biscuit|cookie
🍦|آيس كريم|glace|ice cream
🍰|كعكة|gâteau|cake
🍯|عسل|miel|honey
🥛|حليب|lait|milk
💧|ماء|eau|water
""")

    private val vehicles = cat("مركبات · Véhicules", "#DDEBFF", """
🚗|سيارة|voiture|car
🚌|حافلة|bus|bus
🚆|قطار|train|train
✈️|طائرة|avion|airplane
🚲|دراجة|vélo|bicycle
🚢|سفينة|bateau|ship
🚁|مروحية|hélicoptère|helicopter
🚑|سيارة إسعاف|ambulance|ambulance
🚒|شاحنة إطفاء|camion de pompiers|fire truck
🚜|جرار|tracteur|tractor
🏍️|دراجة نارية|moto|motorcycle
🚀|صاروخ|fusée|rocket
""")

    private val things = cat("أشياء · Objets", "#EDE7F6", """
🏠|منزل|maison|house
🛏️|سرير|lit|bed
📖|كتاب|livre|book
✏️|قلم رصاص|crayon|pencil
🎒|حقيبة مدرسية|cartable|school bag
⚽|كرة|ballon|ball
🎈|بالون|ballon de baudruche|balloon
🔑|مفتاح|clé|key
⏰|منبه|réveil|alarm clock
📱|هاتف|téléphone|phone
💡|مصباح|ampoule|light bulb
🧸|دبدوب|nounours|teddy bear
🎁|هدية|cadeau|gift
☂️|مظلة|parapluie|umbrella
👓|نظارة|lunettes|glasses
🥄|ملعقة|cuillère|spoon
🚪|باب|porte|door
🎹|بيانو|piano|piano
""")

    private val clothes = cat("ملابس · Vêtements", "#FCE4EC", """
👕|قميص|t-shirt|T-shirt
👖|سروال|pantalon|pants
👟|حذاء|chaussure|shoe
🧦|جوارب|chaussettes|socks
🧢|قبعة|casquette|cap
👗|فستان|robe|dress
🧤|قفازات|gants|gloves
🧣|وشاح|écharpe|scarf
""")

    private val body = cat("الجسم · Corps", "#F3E5F5", """
👁️|عين|œil|eye
👂|أذن|oreille|ear
👃|أنف|nez|nose
👄|فم|bouche|mouth
✋|يد|main|hand
🦶|قدم|pied|foot
🦷|سن|dent|tooth
""")

    private val nature = cat("الطبيعة · Nature", "#D8F3F7", """
🌞|شمس|soleil|sun
🌙|قمر|lune|moon
⭐|نجمة|étoile|star
☁️|سحابة|nuage|cloud
🌧️|مطر|pluie|rain
❄️|ثلج|neige|snow
🌈|قوس قزح|arc-en-ciel|rainbow
🌳|شجرة|arbre|tree
🌸|زهرة|fleur|flower
🔥|نار|feu|fire
🌊|موجة|vague|wave
🌍|الأرض|Terre|earth
⛰️|جبل|montagne|mountain
🌵|صبار|cactus|cactus
🌴|نخلة|palmier|palm tree
""")

    private val shapes = cat("أشكال · Formes", "#E8EAF6", """
❤️|قلب|cœur|heart
🔺|مثلث|triangle|triangle
⬛|مربع|carré|square
⚪|دائرة|cercle|circle
""")

    // Ordre du carrousel: chiffres, lettres latines, lettres arabes, puis objets par thème
    val builtIn: List<Item> = numbers + latin + arabic + animals + fruits + vegetables + food +
        vehicles + things + clothes + body + nature + shapes
}
