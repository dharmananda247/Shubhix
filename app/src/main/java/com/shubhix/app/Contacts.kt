package com.shubhix.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

data class ContactHit(val name: String, val number: String)

/** Finds contacts by name, nickname, spelling mistakes and even Hindi spellings. */
object ContactBook {

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context): List<ContactHit> {
        if (!hasPermission(ctx)) return emptyList()
        val out = LinkedHashMap<String, ContactHit>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 5000) {
                    val name = c.getString(0)
                    val number = c.getString(1)
                    if (name != null && number != null) {
                        val key = name.lowercase()
                        if (!out.containsKey(key)) out[key] = ContactHit(name, number)
                    }
                }
            }
        } catch (e: Exception) {
            // unreadable contacts: return what we have
        }
        return out.values.toList()
    }

    /** Best matches first. An empty list means nothing close was found. */
    fun search(ctx: Context, query: String, nicknames: Map<String, String>): List<ContactHit> {
        val people = all(ctx)
        if (people.isEmpty()) return emptyList()
        val raw = norm(query)
        val q0 = norm(roman(query))
        val q = norm(nicknames[raw] ?: nicknames[q0] ?: q0)
        if (q.isEmpty()) return emptyList()
        val rows = people.map { Triple(it, norm(it.name), norm(roman(it.name))) }

        val exact = rows.filter { it.second == q || it.third == q }.map { it.first }
        if (exact.isNotEmpty()) return exact.take(5)

        val starts = rows.filter {
            it.second.startsWith(q) || it.third.startsWith(q) ||
                it.second.split(" ").contains(q) || it.third.split(" ").contains(q)
        }.map { it.first }
        if (starts.isNotEmpty()) return starts.take(5)

        val contains = rows.filter { it.second.contains(q) || it.third.contains(q) }.map { it.first }
        if (contains.isNotEmpty()) return contains.take(5)

        if (q.length >= 4) {
            val scored = rows.map { Pair(it.first, maxOf(similarity(it.second, q), similarity(it.third, q))) }
                .filter { it.second >= 0.72 }
                .sortedByDescending { it.second }
            if (scored.isNotEmpty()) return scored.take(3).map { it.first }
        }

        if (query.any { it.code in 0x0900..0x097F }) {
            val sk = skeleton(query)
            if (sk.length >= 2) {
                val hits = rows.filter { skeleton(it.first.name) == sk }.map { it.first }
                if (hits.isNotEmpty()) return hits.take(3)
            }
        }
        return emptyList()
    }

    // ---------- text helpers ----------

    fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun skeleton(s: String): String =
        norm(roman(s)).replace(Regex("[aeiouyhw ]"), "").replace(Regex("(.)\\1+"), "$1")

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return 1.0 - dp[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    // A simple Hindi (Devanagari) to English-letters converter, enough to match names.
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d",
        'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y",
        'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e", 'ै' to "ai",
        'ो' to "o", 'ौ' to "au", 'ृ' to "ri"
    )
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ए' to "e",
        'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri"
    )

    fun roman(s: String): String {
        if (s.none { it.code in 0x0900..0x097F }) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val c = consonants[ch]
            if (c != null) {
                sb.append(c)
                var j = i + 1
                if (j < s.length && s[j] == '़') j++          // skip the nukta dot
                val next = if (j < s.length) s[j] else ' '
                if (next == '्') {
                    j++                                          // virama: no vowel after this consonant
                } else if (matras.containsKey(next)) {
                    sb.append(matras[next] ?: "")
                    j++
                } else if (consonants.containsKey(next)) {
                    sb.append("a")                               // inherent vowel before another consonant
                }
                i = j
            } else if (vowels.containsKey(ch)) {
                sb.append(vowels[ch] ?: "")
                i++
            } else if (ch == 'ं' || ch == 'ँ') {
                sb.append("n")
                i++
            } else if (ch == 'ः') {
                sb.append("h")
                i++
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
