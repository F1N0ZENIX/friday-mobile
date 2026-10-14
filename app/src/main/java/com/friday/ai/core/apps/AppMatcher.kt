package com.friday.ai.core.apps

import java.text.Normalizer
import java.util.Locale

/** An installed app that has a launcher entry. */
data class LaunchableApp(val packageName: String, val label: String)

/** Which installed app a spoken name means — or why none is picked. */
sealed interface AppMatch {
    data class Found(val app: LaunchableApp, val how: How) : AppMatch

    /** Two or more apps fit about equally: name them and let the owner say which. */
    data class Ambiguous(val labels: List<String>) : AppMatch

    data object NotFound : AppMatch

    enum class How { EXACT, ALIAS, PREFIX, FUZZY }
}

/**
 * Picks the installed app a name refers to, deterministically.
 *
 * The launcher used to take the first app whose label *contained* the words,
 * in whatever order PackageManager listed them: "го" could open any of
 * Google, Google Maps or Gmail. Now, in this order:
 *
 *  1. the label said exactly (case, `ё`, punctuation, spacing and script —
 *     "телеграм" is "Telegram" — do not matter);
 *  2. a known brand alias ("хром" → Chrome), if that app is installed;
 *  3. every word said is the start of a word of the label ("whats" →
 *     WhatsApp), with a clear lead over the next candidate;
 *  4. a near spelling ("телеграмм", "камеру" for "Камера"): similarity at
 *     least [FUZZY_MIN], again with a clear lead.
 *
 * Anything closer than the margins is [AppMatch.Ambiguous], not a guess.
 */
object AppMatcher {

    /**
     * Share of the label the words must cover more than the runner-up does.
     * 0.3: "яндекс" covers 75 % of "Яндекс Go" and 50 % of "Яндекс Музыка" —
     * both start with the word said, so that is a question, not a pick.
     */
    const val PREFIX_MARGIN = 0.3

    /** 1 − edit distance / length; 0.8 lets one letter in five differ. */
    const val FUZZY_MIN = 0.8
    const val FUZZY_MARGIN = 0.1

    /** Below this a fuzzy match is noise ("ок" is close to everything short). */
    private const val FUZZY_MIN_LENGTH = 4
    private const val MAX_NAMED = 3

    /**
     * Brand names said differently from their label. Roles — "браузер",
     * "галерея", "сообщения" — are not here: they open the user's default
     * app for the role, whoever made it.
     */
    val aliases: Map<String, String> = mapOf(
        "ютуб" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "телеграм" to "org.telegram.messenger",
        "telegram" to "org.telegram.messenger",
        "хром" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "whatsapp" to "com.whatsapp",
        "вотсап" to "com.whatsapp",
        "ватсап" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "инстаграм" to "com.instagram.android",
        "spotify" to "com.spotify.music",
        "спотифай" to "com.spotify.music"
    )

    fun aliasFor(name: String): String? = aliases[normalize(name)]

    fun match(name: String, installed: List<LaunchableApp>, hint: String? = null): AppMatch {
        val said = key(name)
        if (said.isBlank()) return AppMatch.NotFound
        // One entry per package: an app with two launcher activities is still one app.
        val apps = installed.distinctBy { it.packageName }
        val aliased = (hint ?: aliasFor(name))?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
        return exact(said, apps, aliased)
            ?: aliased?.let { AppMatch.Found(it, AppMatch.How.ALIAS) }
            ?: prefix(said, apps)
            ?: fuzzy(said, apps)
            ?: AppMatch.NotFound
    }

    private fun exact(said: String, apps: List<LaunchableApp>, aliased: LaunchableApp?): AppMatch? {
        val hits = apps.filter { key(it.label) == said }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> AppMatch.Found(hits.single(), AppMatch.How.EXACT)
            // Two apps with the same label: the alias, if it is one of them, settles it.
            aliased != null && aliased in hits -> AppMatch.Found(aliased, AppMatch.How.EXACT)
            else -> AppMatch.Ambiguous(named(hits))
        }
    }

    private fun prefix(said: String, apps: List<LaunchableApp>): AppMatch? {
        val words = said.split(' ')
        val scored = apps.mapNotNull { app ->
            val label = key(app.label)
            val joined = label.replace(" ", "")
            val covers = startsEveryWord(words, label.split(' ')) || joined.startsWith(said.replace(" ", ""))
            if (covers) app to said.replace(" ", "").length.toDouble() / joined.length else null
        }
        return pick(scored, PREFIX_MARGIN, AppMatch.How.PREFIX)
    }

    private fun fuzzy(said: String, apps: List<LaunchableApp>): AppMatch? {
        if (said.length < FUZZY_MIN_LENGTH) return null
        val scored = apps.mapNotNull { app ->
            val label = key(app.label)
            // The whole label, or one of its words: "телеграмм" against "Telegram X" too.
            val best = (label.split(' ') + label).maxOf { Spelling.similarity(said, it) }
            if (best >= FUZZY_MIN) app to best else null
        }
        return pick(scored, FUZZY_MARGIN, AppMatch.How.FUZZY)
    }

    /** The best of [scored] if it leads the next by [margin]; otherwise the close ones, named. */
    private fun pick(scored: List<Pair<LaunchableApp, Double>>, margin: Double, how: AppMatch.How): AppMatch? {
        if (scored.isEmpty()) return null
        val ranked = scored.sortedWith(
            compareByDescending<Pair<LaunchableApp, Double>> { it.second }.thenBy { it.first.label }
        )
        val (best, score) = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.second
        return if (runnerUp == null || score - runnerUp >= margin) {
            AppMatch.Found(best, how)
        } else {
            AppMatch.Ambiguous(named(ranked.filter { score - it.second < margin }.map { it.first }))
        }
    }

    private fun startsEveryWord(said: List<String>, label: List<String>): Boolean {
        val free = label.toMutableList()
        return said.all { word ->
            val i = free.indexOfFirst { it.startsWith(word) }
            if (i >= 0) free.removeAt(i)
            i >= 0
        }
    }

    private fun named(apps: List<LaunchableApp>): List<String> =
        apps.map { it.label }.distinct().take(MAX_NAMED)

    /** Case, `ё`, Unicode forms, punctuation and spacing folded the same way for names and labels. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** [normalize]d and spelled in Latin letters, so "телеграм" and "Telegram" meet. */
    fun key(text: String): String = buildString {
        normalize(text).forEach { c -> append(LATIN[c] ?: c.toString()) }
    }

    private val LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )
}

/** How alike two spellings are: 1 − edit distance / the longer length. */
internal object Spelling {

    fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) 1.0 else 1.0 - distance(a, b).toDouble() / longest
    }

    /** Levenshtein distance. */
    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
