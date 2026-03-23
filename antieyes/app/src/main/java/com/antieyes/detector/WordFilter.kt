package com.antieyes.detector

import android.content.Context
import android.util.Log

private const val TAG = "WORD_FILTER"

/**
 * Result of filtering OCR text through the dictionary.
 *
 * @param acceptedWords  words that passed the filter
 * @param speakableText  the final string to speak (may be empty)
 */
data class WordFilterResult(
    val acceptedWords: List<String>,
    val speakableText: String
)

/**
 * Filters OCR output to keep only real, human-useful words.
 *
 * Rejects serial numbers, plate numbers, digit/letter mixtures,
 * long digit sequences, and symbol-heavy tokens.
 *
 * Uses a compact English word list loaded from assets/wordlist.txt
 * into a HashSet for O(1) lookup.
 *
 * @param enableFuzzy  if true, allow edit-distance-1 matches for words
 *                     of length >= 5. Defaults to false (too risky).
 */
class WordFilter(context: Context, private val enableFuzzy: Boolean = false) {

    /** Dictionary loaded once at startup */
    private val dictionary: HashSet<String>

    /** Minimum word length to bother checking */
    private val minWordLength = 2

    /** Digit sequences longer than this are rejected outright */
    private val maxDigitSequenceLength = 7

    init {
        val startMs = System.currentTimeMillis()
        dictionary = HashSet()
        try {
            context.assets.open("wordlist.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val word = line.trim().uppercase()
                    if (word.isNotEmpty()) {
                        dictionary.add(word)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wordlist.txt: ${e.message}", e)
        }
        val elapsed = System.currentTimeMillis() - startMs
        Log.i(TAG, "Dictionary loaded: ${dictionary.size} words in ${elapsed}ms")
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Filter OCR text down to real, useful words.
     *
     * @param ocrText  raw OCR output string
     * @return WordFilterResult with accepted words and a speakable string
     */
    fun filterToRealWords(ocrText: String): WordFilterResult {
        // Split on whitespace and newlines
        val tokens = ocrText.split(Regex("[\\s\\n\\r]+")).filter { it.isNotEmpty() }
        val accepted = mutableListOf<String>()

        for (raw in tokens) {
            val cleaned = normalize(raw)
            if (cleaned.isEmpty()) continue

            when {
                // Reject: digit-letter mixtures like A7B9, 12AB
                isDigitLetterMix(cleaned) -> {
                    Log.d(TAG, "Rejected (digit-letter mix): \"$raw\"")
                }
                // Reject: long digit-only sequences (serial numbers, codes)
                isLongDigitSequence(cleaned) -> {
                    Log.d(TAG, "Rejected (long digit seq): \"$raw\"")
                }
                // Reject: mostly symbols / punctuation
                isMostlySymbols(raw) -> {
                    Log.d(TAG, "Rejected (mostly symbols): \"$raw\"")
                }
                // Reject: too short after cleaning
                cleaned.length < minWordLength -> {
                    Log.d(TAG, "Rejected (too short): \"$raw\"")
                }
                // Accept: only if it's a real word in the dictionary
                else -> {
                    if (dictionary.contains(cleaned)) {
                        Log.d(TAG, "Accepted (in dictionary): \"$cleaned\"")
                        accepted.add(cleaned.lowercase().replaceFirstChar { it.uppercase() })
                    } else {
                        Log.d(TAG, "Rejected (not in dictionary): \"$cleaned\"")
                    }
                }
            }
        }

        val speakable = accepted.joinToString(" ")
        Log.d(TAG, "Filter result: ${tokens.size} tokens -> ${accepted.size} accepted: \"$speakable\"")

        return WordFilterResult(accepted, speakable)
    }

    // ── Normalization ────────────────────────────────────────────────

    /**
     * Normalize a token for dictionary lookup:
     * - Uppercase
     * - Strip leading/trailing punctuation (keep internal apostrophes)
     */
    private fun normalize(token: String): String {
        var s = token.uppercase()
        // Strip leading punctuation/symbols
        s = s.trimStart { !it.isLetterOrDigit() }
        // Strip trailing punctuation/symbols
        s = s.trimEnd { !it.isLetterOrDigit() }
        return s
    }

    // ── Rejection checks ─────────────────────────────────────────────

    /** True if the token mixes digits and letters (e.g. A7B9, 12AB, B4R) */
    private fun isDigitLetterMix(s: String): Boolean {
        val hasDigit = s.any { it.isDigit() }
        val hasLetter = s.any { it.isLetter() }
        return hasDigit && hasLetter
    }

    /** True if the token is all digits and too long (serial number / code) */
    private fun isLongDigitSequence(s: String): Boolean {
        return s.all { it.isDigit() } && s.length > maxDigitSequenceLength
    }

    /** True if most of the original token is symbols / non-alphanumeric */
    private fun isMostlySymbols(s: String): Boolean {
        if (s.isEmpty()) return true
        val alphaCount = s.count { it.isLetterOrDigit() }
        return alphaCount.toFloat() / s.length < 0.5f
    }

    // ── Fuzzy matching ───────────────────────────────────────────────

    /**
     * Check if any word in the dictionary is within edit distance 1 of [s].
     * Only used for words of length >= 5 when fuzzy mode is enabled.
     *
     * This is intentionally brute-force (fine for a small dictionary).
     */
    private fun hasFuzzyMatch(s: String): Boolean {
        for (word in dictionary) {
            // Only compare same-ish length words to save time
            if (kotlin.math.abs(word.length - s.length) > 1) continue
            if (editDistance(s, word) <= 1) return true
        }
        return false
    }

    /** Simple Levenshtein edit distance (bounded at 2 for early exit). */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return 2 // early exit

        val prev = IntArray(lb + 1) { it }
        val curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            var minVal = curr[0]
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < minVal) minVal = curr[j]
            }
            // Early termination: if every value in this row is >= 2, we can stop
            if (minVal >= 2) return 2
            System.arraycopy(curr, 0, prev, 0, lb + 1)
        }
        return prev[lb]
    }
}
