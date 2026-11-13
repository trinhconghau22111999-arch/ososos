package com.dk.home

import android.content.Context
import org.json.JSONObject

/** Danh sách kênh/đài (assets/channels.json) để AI nhận ra tên kênh nói thẳng, không cần nói "TV". */
object TvChannels {
    private var names: List<String>? = null

    private fun norm(t: String): String {
        val skip = setOf("mo", "kenh", "dai", "truyen", "hinh", "xem", "cho", "toi", "tv", "tivi", "len", "bat", "di", "nhe", "giup")
        return aiNorm(t).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in skip }.joinToString("")
    }

    private fun load(ctx: Context): List<String> = names ?: try {
        val txt = ctx.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(txt).getJSONArray("groups")
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            out.add(g.getString("name"))
            val chs = g.getJSONArray("channels")
            for (j in 0 until chs.length()) out.add(chs.getJSONObject(j).getString("name"))
        }
        out.toList()
    } catch (_: Exception) { emptyList() }.also { names = it }

    /** Tên kênh/đài khớp nhất với câu nói, hoặc null nếu không khớp rõ ràng. */
    fun find(ctx: Context, text: String): String? {
        val q = norm(text)
        if (q.length < 3) return null
        var best: String? = null; var bestScore = 0; var bestDiff = Int.MAX_VALUE
        for (name in load(ctx)) {
            val n = norm(name)
            if (n.length < 2) continue
            val score = if (n == q) 3 else if (n.contains(q) || q.contains(n)) 2 else 0
            val diff = kotlin.math.abs(n.length - q.length)
            if (score > bestScore || (score == bestScore && score > 0 && diff < bestDiff)) { best = name; bestScore = score; bestDiff = diff }
        }
        return if (bestScore >= 2) best else null
    }
}
