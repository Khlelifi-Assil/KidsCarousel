package com.kids.carousel

import android.content.Context
import org.json.JSONObject
import java.io.File

/** langs = langues affichées ET prononcées pour cette carte (ex: lettres latines -> fr, en seulement). */
data class Item(
    val symbol: String, val ar: String, val fr: String, val en: String, val color: String,
    val imagePath: String? = null, val spell: Boolean = true,
    val langs: List<String> = listOf("ar", "fr", "en"), val cat: String = ""
)

data class Category(val id: String, val symbol: String, val ar: String, val fr: String, val en: String, val color: String) {
    val label: String get() = "$ar · $fr"
}

object Content {
    var items: List<Item> = emptyList()
    var categories: List<Category> = emptyList()

    fun itemsOf(catId: String?): List<Item> = if (catId == null) items else items.filter { it.cat == catId }
    fun label(id: String): String = categories.firstOrNull { it.id == id }?.label ?: ""

    private fun readDir(c: Context, name: String): Pair<List<Category>, List<Item>> = runCatching {
        val dir = File(c.filesDir, name)
        val root = JSONObject(File(dir, "items.json").readText().trimStart('\uFEFF'))
        val cats = mutableListOf<Category>()
        root.optJSONArray("categories")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank()) cats += Category(id, o.optString("emoji").ifBlank { "📁" },
                    o.optString("ar", id), o.optString("fr", id), o.optString("en", id),
                    o.optString("color").ifBlank { "#EEEEEE" })
            }
        }
        val arr = root.getJSONArray("items")
        val list = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val img = o.optString("img"); val f = File(dir, img)
            if (img.isBlank() || !f.isFile) continue
            list += Item("", o.optString("ar"), o.optString("fr"), o.optString("en"), o.optString("color"),
                f.path, true, listOf("ar", "fr", "en"), o.optString("category").ifBlank { "mine" })
        }
        Pair(cats.toList(), list.toList())
    }.getOrDefault(Pair(emptyList<Category>(), emptyList<Item>()))

    /** Contenu intégré (emoji) + pack Internet (images PNG qui remplacent les emoji) + objets du parent. */
    fun load(c: Context) {
        val (packCats, pack) = readDir(c, "pack")
        val (customCats, custom) = readDir(c, "custom")
        val key = { i: Item -> i.cat + "|" + i.en.lowercase() }
        val packByKey = pack.associateBy(key)
        val merged = builtIn.map { b -> packByKey[key(b)]?.let { b.copy(imagePath = it.imagePath) } ?: b }
        val builtKeys = builtIn.map(key).toSet()
        val all = merged + pack.filter { key(it) !in builtKeys } + custom
        val cats = (builtInCats + packCats + customCats).distinctBy { it.id }.toMutableList()
        all.map { it.cat }.distinct().filter { id -> cats.none { it.id == id } }
            .forEach { id -> cats += Category(id, "📁", id, id, id, "#EEEEEE") }
        categories = cats
        items = all.map { x ->
            if (x.color.isNotBlank()) x else x.copy(color = cats.firstOrNull { it.id == x.cat }?.color ?: "#EEEEEE")
        }
    }

    val builtInCats = listOf(
        Category("numbers", "123", "أرقام", "Nombres", "Numbers", "#FFE0B2"),
        Category("latin", "Aa", "الحروف اللاتينية", "Lettres latines", "Latin letters", "#E1D5F5"),
        Category("arabic", "أ", "الحروف العربية", "Lettres arabes", "Arabic letters", "#D7EFD0"),
        Category("animals", "🐘", "حيوانات", "Animaux", "Animals", "#FFF2CC"),
        Category("fruits", "🍎", "فواكه", "Fruits", "Fruits", "#FFE0E0"),
        Category("vegetables", "🥕", "خضروات", "Légumes", "Vegetables", "#DCEDC8"),
        Category("food", "🍞", "طعام", "Nourriture", "Food", "#FFE8CC"),
        Category("vehicles", "🚗", "مركبات", "Véhicules", "Vehicles", "#DDEBFF"),
        Category("things", "🧸", "أشياء", "Objets", "Objects", "#EDE7F6"),
        Category("clothes", "👕", "ملابس", "Vêtements", "Clothes", "#FCE4EC"),
        Category("body", "👁️", "الجسم", "Corps", "Body", "#F3E5F5"),
        Category("relations", "👨‍👩‍👧", "العلاقات الاجتماعية", "Relations sociales", "Social relations", "#FFE4E1"),
        Category("nature", "🌳", "الطبيعة", "Nature", "Nature", "#D8F3F7"),
        Category("shapes", "🔺", "أشكال", "Formes", "Shapes", "#E8EAF6"),
        Category("mine", "🧩", "إضافاتي", "Mes ajouts", "My additions", "#E0F2F1"),
    )

    private fun cat(id: String, rows: String): List<Item> {
        val color = builtInCats.first { it.id == id }.color
        return rows.trim().lines().map {
            val p = it.trim().split("|")
            Item(p[0], p[1], p[2], p[3], color, cat = id)
        }
    }

    // Chiffres 1 à 100 (l'épellation lettre par lettre seulement jusqu'à 10)
    private val numbers = cat("numbers", """
1|واحد|un|one
2|اثنان|deux|two
3|ثلاثة|trois|three
4|أربعة|quatre|four
5|خمسة|cinq|five
6|ستة|six|six
7|سبعة|sept|seven
8|ثمانية|huit|eight
9|تسعة|neuf|nine
10|عشرة|dix|ten
11|أحد عشر|onze|eleven
12|اثنا عشر|douze|twelve
13|ثلاثة عشر|treize|thirteen
14|أربعة عشر|quatorze|fourteen
15|خمسة عشر|quinze|fifteen
16|ستة عشر|seize|sixteen
17|سبعة عشر|dix-sept|seventeen
18|ثمانية عشر|dix-huit|eighteen
19|تسعة عشر|dix-neuf|nineteen
20|عشرون|vingt|twenty
21|واحد وعشرون|vingt et un|twenty-one
22|اثنان وعشرون|vingt-deux|twenty-two
23|ثلاثة وعشرون|vingt-trois|twenty-three
24|أربعة وعشرون|vingt-quatre|twenty-four
25|خمسة وعشرون|vingt-cinq|twenty-five
26|ستة وعشرون|vingt-six|twenty-six
27|سبعة وعشرون|vingt-sept|twenty-seven
28|ثمانية وعشرون|vingt-huit|twenty-eight
29|تسعة وعشرون|vingt-neuf|twenty-nine
30|ثلاثون|trente|thirty
31|واحد وثلاثون|trente et un|thirty-one
32|اثنان وثلاثون|trente-deux|thirty-two
33|ثلاثة وثلاثون|trente-trois|thirty-three
34|أربعة وثلاثون|trente-quatre|thirty-four
35|خمسة وثلاثون|trente-cinq|thirty-five
36|ستة وثلاثون|trente-six|thirty-six
37|سبعة وثلاثون|trente-sept|thirty-seven
38|ثمانية وثلاثون|trente-huit|thirty-eight
39|تسعة وثلاثون|trente-neuf|thirty-nine
40|أربعون|quarante|forty
41|واحد وأربعون|quarante et un|forty-one
42|اثنان وأربعون|quarante-deux|forty-two
43|ثلاثة وأربعون|quarante-trois|forty-three
44|أربعة وأربعون|quarante-quatre|forty-four
45|خمسة وأربعون|quarante-cinq|forty-five
46|ستة وأربعون|quarante-six|forty-six
47|سبعة وأربعون|quarante-sept|forty-seven
48|ثمانية وأربعون|quarante-huit|forty-eight
49|تسعة وأربعون|quarante-neuf|forty-nine
50|خمسون|cinquante|fifty
51|واحد وخمسون|cinquante et un|fifty-one
52|اثنان وخمسون|cinquante-deux|fifty-two
53|ثلاثة وخمسون|cinquante-trois|fifty-three
54|أربعة وخمسون|cinquante-quatre|fifty-four
55|خمسة وخمسون|cinquante-cinq|fifty-five
56|ستة وخمسون|cinquante-six|fifty-six
57|سبعة وخمسون|cinquante-sept|fifty-seven
58|ثمانية وخمسون|cinquante-huit|fifty-eight
59|تسعة وخمسون|cinquante-neuf|fifty-nine
60|ستون|soixante|sixty
61|واحد وستون|soixante et un|sixty-one
62|اثنان وستون|soixante-deux|sixty-two
63|ثلاثة وستون|soixante-trois|sixty-three
64|أربعة وستون|soixante-quatre|sixty-four
65|خمسة وستون|soixante-cinq|sixty-five
66|ستة وستون|soixante-six|sixty-six
67|سبعة وستون|soixante-sept|sixty-seven
68|ثمانية وستون|soixante-huit|sixty-eight
69|تسعة وستون|soixante-neuf|sixty-nine
70|سبعون|soixante-dix|seventy
71|واحد وسبعون|soixante et onze|seventy-one
72|اثنان وسبعون|soixante-douze|seventy-two
73|ثلاثة وسبعون|soixante-treize|seventy-three
74|أربعة وسبعون|soixante-quatorze|seventy-four
75|خمسة وسبعون|soixante-quinze|seventy-five
76|ستة وسبعون|soixante-seize|seventy-six
77|سبعة وسبعون|soixante-dix-sept|seventy-seven
78|ثمانية وسبعون|soixante-dix-huit|seventy-eight
79|تسعة وسبعون|soixante-dix-neuf|seventy-nine
80|ثمانون|quatre-vingts|eighty
81|واحد وثمانون|quatre-vingt-un|eighty-one
82|اثنان وثمانون|quatre-vingt-deux|eighty-two
83|ثلاثة وثمانون|quatre-vingt-trois|eighty-three
84|أربعة وثمانون|quatre-vingt-quatre|eighty-four
85|خمسة وثمانون|quatre-vingt-cinq|eighty-five
86|ستة وثمانون|quatre-vingt-six|eighty-six
87|سبعة وثمانون|quatre-vingt-sept|eighty-seven
88|ثمانية وثمانون|quatre-vingt-huit|eighty-eight
89|تسعة وثمانون|quatre-vingt-neuf|eighty-nine
90|تسعون|quatre-vingt-dix|ninety
91|واحد وتسعون|quatre-vingt-onze|ninety-one
92|اثنان وتسعون|quatre-vingt-douze|ninety-two
93|ثلاثة وتسعون|quatre-vingt-treize|ninety-three
94|أربعة وتسعون|quatre-vingt-quatorze|ninety-four
95|خمسة وتسعون|quatre-vingt-quinze|ninety-five
96|ستة وتسعون|quatre-vingt-seize|ninety-six
97|سبعة وتسعون|quatre-vingt-dix-sept|ninety-seven
98|ثمانية وتسعون|quatre-vingt-dix-huit|ninety-eight
99|تسعة وتسعون|quatre-vingt-dix-neuf|ninety-nine
100|مئة|cent|one hundred
""").map { if (it.symbol.toInt() > 10) it.copy(spell = false) else it }

    // Lettres latines A-Z: seulement français et anglais (pas de ligne arabe)
    private val latin = ('A'..'Z').map {
        Item("$it${it.lowercaseChar()}", "", "$it", "$it", "#E1D5F5", spell = false,
            langs = listOf("fr", "en"), cat = "latin")
    }

    // Alphabet arabe: seulement le nom arabe (pas de ligne latine)
    private val arabic = cat("arabic", """
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
""").map { it.copy(spell = false, langs = listOf("ar")) }

    private val animals = cat("animals", """
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
    private val fruits = cat("fruits", """
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
    private val vegetables = cat("vegetables", """
🍅|طماطم|tomate|tomato
🥕|جزرة|carotte|carrot
🥔|بطاطا|pomme de terre|potato
🌽|ذرة|maïs|corn
🥒|خيار|concombre|cucumber
🍆|باذنجان|aubergine|eggplant
🥦|بروكلي|brocoli|broccoli
🍄|فطر|champignon|mushroom
""")
    private val food = cat("food", """
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
    private val vehicles = cat("vehicles", """
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
    private val things = cat("things", """
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
    private val clothes = cat("clothes", """
👕|قميص|t-shirt|T-shirt
👖|سروال|pantalon|pants
👟|حذاء|chaussure|shoe
🧦|جوارب|chaussettes|socks
🧢|قبعة|casquette|cap
👗|فستان|robe|dress
🧤|قفازات|gants|gloves
🧣|وشاح|écharpe|scarf
""")
    private val body = cat("body", """
👁️|عين|œil|eye
👂|أذن|oreille|ear
👃|أنف|nez|nose
👄|فم|bouche|mouth
✋|يد|main|hand
🦶|قدم|pied|foot
🦷|سن|dent|tooth
""")
    private val relations = cat("relations", """
👨|أب|papa|father
👩|أم|maman|mother
👬|أخ|frère|brother
👭|أخت|sœur|sister
👦|ولد|garçon|boy
👧|بنت|fille|girl
👶|رضيع|bébé|baby
👴|جد|grand-père|grandfather
👵|جدة|grand-mère|grandmother
👨‍👩‍👧‍👦|عائلة|famille|family
👫|أصدقاء|amis|friends
🤝|مصافحة|poignée de main|handshake
🤗|عناق|câlin|hug
👋|تحية|salut|hello
🙏|شكرا|merci|thank you
👨‍🏫|معلم|maître|teacher
👩‍⚕️|طبيبة|médecin|doctor
👮|شرطي|policier|police officer
👨‍🍳|طباخ|cuisinier|cook
👨‍🚒|رجل إطفاء|pompier|firefighter
🏘️|جيران|voisins|neighbors
🎉|حفلة|fête|party
""")
    private val nature = cat("nature", """
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
    private val shapes = cat("shapes", """
❤️|قلب|cœur|heart
🔺|مثلث|triangle|triangle
⬛|مربع|carré|square
⚪|دائرة|cercle|circle
""")

    val builtIn: List<Item> = numbers + latin + arabic + animals + fruits + vegetables + food +
        vehicles + things + clothes + body + relations + nature + shapes
}
