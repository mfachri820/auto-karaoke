package com.autolyrics.lyrics

/**
 * Turns Korean and Japanese lyrics into Latin letters so they can be sung along to.
 *
 * Korean (Hangul) follows the Revised Romanization of Korean, including the common
 * sound changes between syllables (liaison, nasalization, ㄹ assimilation, aspiration),
 * e.g. 합니다 → hamnida, 한국어 → hangugeo.
 *
 * Japanese kana follow Hepburn. Kanji have no reliable reading without a dictionary,
 * so they are left as written.
 */
object Romanizer {

    /** Returns one romanized string per line ("" when a line needs none), or null if no line does. */
    fun romanizeLines(lines: List<String>): List<String>? {
        var any = false
        val out = lines.map { text ->
            val r = romanize(text)
            if (r != null) any = true
            r ?: ""
        }
        return if (any) out else null
    }

    /** Romanized text, or null when the text has no Hangul or kana. */
    fun romanize(text: String): String? {
        val hasHangul = text.any { isHangulSyllable(it) }
        val hasKana = text.any { isKana(it) }
        if (!hasHangul && !hasKana) return null
        var s = text
        if (hasHangul) s = romanizeKorean(s)
        if (hasKana) s = romanizeJapanese(s)
        s = s.map { FULL_WIDTH_PUNCT[it] ?: it.toString() }.joinToString("")
        return s.replace(MULTI_SPACE, " ").trim()
    }

    // ---------------------------------------------------------------- Korean

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3

    private val INITIALS = arrayOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s",
        "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    )
    private val MEDIALS = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa",
        "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    )
    private val FINALS = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k",
        "m", "l", "l", "l", "p", "l", "m", "p", "p", "t",
        "t", "ng", "t", "t", "k", "t", "p", "t"
    )

    /** For a final consonant followed by a silent ㅇ: what stays, and what moves to the next syllable. */
    private val LIAISON: Map<Int, Pair<String, String>> = mapOf(
        1 to ("" to "g"), 2 to ("" to "kk"), 3 to ("k" to "s"), 4 to ("" to "n"),
        5 to ("n" to "j"), 6 to ("" to "n"), 7 to ("" to "d"), 8 to ("" to "r"),
        9 to ("l" to "g"), 10 to ("l" to "m"), 11 to ("l" to "b"), 12 to ("l" to "s"),
        13 to ("l" to "t"), 14 to ("l" to "p"), 15 to ("" to "r"), 16 to ("" to "m"),
        17 to ("" to "b"), 18 to ("p" to "s"), 19 to ("" to "s"), 20 to ("" to "ss"),
        22 to ("" to "j"), 23 to ("" to "ch"), 24 to ("" to "k"), 25 to ("" to "t"),
        26 to ("" to "p"), 27 to ("" to "")
    )

    private val K_GROUP = setOf(1, 2, 3, 9, 24)
    private val T_GROUP = setOf(7, 19, 20, 22, 23, 25, 27)
    private val P_GROUP = setOf(14, 17, 18, 26)

    // Initial indices
    private const val INI_G = 0
    private const val INI_N = 2
    private const val INI_D = 3
    private const val INI_R = 5
    private const val INI_M = 6
    private const val INI_SILENT = 11
    private const val INI_J = 12
    private const val INI_H = 18

    // Final indices
    private const val FIN_N = 4
    private const val FIN_NH = 6
    private const val FIN_L = 8
    private const val FIN_LH = 15
    private const val FIN_M = 16
    private const val FIN_NG = 21
    private const val FIN_H = 27

    private fun isHangulSyllable(c: Char) = c.code in HANGUL_BASE..HANGUL_LAST

    private fun romanizeKorean(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (!isHangulSyllable(text[i])) {
                sb.append(text[i])
                i++
                continue
            }
            var j = i
            while (j < text.length && isHangulSyllable(text[j])) j++
            // Keep a space between romanized Korean and neighbouring non-space text.
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(romanizeHangulRun(text.substring(i, j)))
            if (j < text.length && !text[j].isWhitespace() && !isPunctuation(text[j])) sb.append(' ')
            i = j
        }
        return sb.toString()
    }

    private fun romanizeHangulRun(run: String): String {
        val n = run.length
        val ini = IntArray(n)
        val med = IntArray(n)
        val fin = IntArray(n)
        for (k in 0 until n) {
            val code = run[k].code - HANGUL_BASE
            ini[k] = code / 588
            med[k] = (code % 588) / 28
            fin[k] = code % 28
        }

        val initOverride = arrayOfNulls<String>(n)
        val sb = StringBuilder()
        for (k in 0 until n) {
            val initial = initOverride[k] ?: INITIALS[ini[k]]
            val f = fin[k]
            var finalStr = FINALS[f]

            if (f != 0 && k + 1 < n) {
                val next = ini[k + 1]
                when {
                    // Final moves onto a following silent ㅇ: 한국어 → han-gu-geo
                    next == INI_SILENT && f != FIN_NG -> {
                        val (stay, move) = LIAISON[f] ?: ("" to "")
                        finalStr = stay
                        initOverride[k + 1] = move
                    }
                    // ㄹ meets ㄴ: 설날 → seollal
                    next == INI_N && (f == FIN_L || f == FIN_LH) -> {
                        finalStr = "l"
                        initOverride[k + 1] = "l"
                    }
                    // Nasalization before ㄴ/ㅁ: 합니다 → hamnida, 읽는 → ingneun
                    next == INI_N || next == INI_M -> {
                        finalStr = when (f) {
                            in K_GROUP -> "ng"
                            in T_GROUP -> "n"
                            in P_GROUP -> "m"
                            else -> finalStr
                        }
                    }
                    // ㄹ assimilation: 신라 → silla, 심리 → simni, 국립 → gungnip
                    next == INI_R -> when (f) {
                        FIN_N, FIN_L -> {
                            finalStr = "l"
                            initOverride[k + 1] = "l"
                        }
                        FIN_M, FIN_NG -> initOverride[k + 1] = "n"
                        in K_GROUP -> {
                            finalStr = "ng"
                            initOverride[k + 1] = "n"
                        }
                        in P_GROUP -> {
                            finalStr = "m"
                            initOverride[k + 1] = "n"
                        }
                    }
                    // Aspiration with a following ㅎ: 축하 → chuka, 좋다 → jota
                    next == INI_H && (f == 1 || f == 7 || f == 17 || f == 22) -> {
                        finalStr = ""
                        initOverride[k + 1] = when (f) {
                            1 -> "k"
                            7 -> "t"
                            17 -> "p"
                            else -> "ch"
                        }
                    }
                    (f == FIN_H || f == FIN_NH || f == FIN_LH) &&
                        (next == INI_G || next == INI_D || next == INI_J) -> {
                        finalStr = when (f) {
                            FIN_H -> ""
                            FIN_NH -> "n"
                            else -> "l"
                        }
                        initOverride[k + 1] = when (next) {
                            INI_G -> "k"
                            INI_D -> "t"
                            else -> "ch"
                        }
                    }
                }
            }

            sb.append(initial).append(MEDIALS[med[k]]).append(finalStr)
        }
        return sb.toString()
    }

    // -------------------------------------------------------------- Japanese

    private const val SMALL_TSU = 'っ'
    private const val LONG_MARK = 'ー'

    private fun isKana(c: Char): Boolean {
        val code = c.code
        return code in 0x3041..0x3096 || code in 0x30A1..0x30FA || code == 0x30FC
    }

    private fun toHiragana(c: Char): Char {
        val code = c.code
        return if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else c
    }

    private val KANA_DIGRAPHS = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho", "しぇ" to "she",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho", "ちぇ" to "che",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo", "じぇ" to "je",
        "ぢゃ" to "ja", "ぢゅ" to "ju", "ぢょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
        "てぃ" to "ti", "でぃ" to "di", "とぅ" to "tu", "どぅ" to "du",
        "うぃ" to "wi", "うぇ" to "we", "うぉ" to "wo",
        "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo",
        "つぁ" to "tsa", "つぃ" to "tsi", "つぇ" to "tse", "つぉ" to "tso"
    )

    private val KANA = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "wi", 'ゑ' to "we", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ゔ' to "vu",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
        'ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo", 'ゎ' to "wa",
        'ゕ' to "ka", 'ゖ' to "ke"
    )

    private fun romanizeJapanese(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isKana(c)) {
                sb.append(c)
                i++
                continue
            }
            var j = i
            while (j < text.length && isKana(text[j])) j++
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(romanizeKanaRun(text.substring(i, j)))
            if (j < text.length && !text[j].isWhitespace() && !isPunctuation(text[j])) sb.append(' ')
            i = j
        }
        return sb.toString()
    }

    private fun romanizeKanaRun(run: String): String {
        val kana = run.map { toHiragana(it) }.joinToString("")
        val sb = StringBuilder()
        var doubleNext = false
        var i = 0
        while (i < kana.length) {
            val c = kana[i]
            when (c) {
                SMALL_TSU -> {
                    doubleNext = true
                    i++
                    continue
                }
                LONG_MARK -> {
                    val lastVowel = sb.lastOrNull { it in "aeiou" }
                    if (lastVowel != null && sb.isNotEmpty() && sb.last() in "aeiou") sb.append(lastVowel)
                    i++
                    continue
                }
            }
            val pair = if (i + 1 < kana.length) kana.substring(i, i + 2) else null
            val roman: String
            if (pair != null && KANA_DIGRAPHS.containsKey(pair)) {
                roman = KANA_DIGRAPHS.getValue(pair)
                i += 2
            } else {
                roman = KANA[c] ?: c.toString()
                i++
            }
            if (doubleNext && roman.isNotEmpty() && roman[0] !in "aeiou") {
                sb.append(if (roman.startsWith("ch")) 't' else roman[0])
            }
            doubleNext = false
            sb.append(roman)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- Shared

    private val MULTI_SPACE = Regex(" {2,}")

    private val FULL_WIDTH_PUNCT = mapOf(
        '、' to ", ", '。' to ". ", '！' to "! ", '？' to "? ", '　' to " ",
        '「' to "\"", '」' to "\"", '『' to "\"", '』' to "\"",
        '（' to " (", '）' to ") ", '～' to "~", '…' to "…", '・' to " "
    )

    private fun isPunctuation(c: Char) =
        c in ",.!?;:'\")]}…~" || FULL_WIDTH_PUNCT.containsKey(c)
}
