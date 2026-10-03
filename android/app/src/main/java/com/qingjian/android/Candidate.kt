package com.qingjian.android

import android.util.Log
import org.json.JSONArray

/**
 * 候选条目（schema 见 DATA_MANIFEST.md「候选 JSON Schema」）。
 *
 * @property word 候选词（中文或英文）
 * @property score 相对置信度启发值（非校准概率，仅用于排序高亮）
 * @property gloss 第一条释义文本（英文，可空串）
 * @property pos 词性缩写（如 int./n./v.，可空串）
 * @property syllables 拼音音节数组
 * @property kind 候选来源（chinese/english/code/cloud/shortcut/custom/emoji/sentence）
 */
data class Candidate(
    val word: String,
    val score: Double,
    val gloss: String,
    val pos: String,
    val syllables: List<String>,
    val kind: String
) {
    /** 候选栏注释文本：pos + gloss 拼接；两者皆空则为空串（候选栏据此隐藏注释） */
    val annotation: String
        get() = listOf(pos, gloss).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        /**
         * 解析 nativePinyinInput 返回的 JSON 数组；任何解析失败都退化为空表，绝不让 IME 崩溃。
         */
        fun parseArray(json: String?): List<Candidate> {
            if (json.isNullOrBlank()) {
                Log.w(TAG, "parseArray: null/blank JSON -> 0 candidates")
                return emptyList()
            }
            return try {
                val arr = JSONArray(json)
                val parsed = (0 until arr.length())
                    .map { i ->
                        val o = arr.getJSONObject(i)
                        Candidate(
                            word = o.optString("word"),
                            score = o.optDouble("score", 0.0),
                            gloss = o.optString("gloss"),
                            pos = o.optString("pos"),
                            syllables = o.optJSONArray("syllables")?.let { s ->
                                (0 until s.length()).map { j -> s.optString(j) }
                            } ?: emptyList(),
                            kind = o.optString("kind")
                        )
                    }
                    .filter { it.word.isNotEmpty() }
                Log.i(TAG, "parseArray: ok, parsed ${parsed.size} candidates (raw array len=${arr.length()})")
                parsed
            } catch (t: Throwable) {
                Log.w(TAG, "parseArray: candidate JSON parse failed", t)
                emptyList()
            }
        }
    }
}
