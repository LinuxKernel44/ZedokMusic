package com.zedokmusic.update

/** A release version such as "0.14.3-alpha" (tag "v0.14.3-alpha"). Ordered by the numbers; the suffix only breaks ties. */
data class Version(val major: Int, val minor: Int, val patch: Int, val tag: String) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        private val rx = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+.]?([0-9A-Za-z.-]+))?$""")
        fun parse(text: String?): Version? {
            val m = rx.find(text?.trim() ?: return null) ?: return null
            return Version(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt(), m.groupValues[4])
        }
    }
    override fun toString() = "$major.$minor.$patch" + if (tag.isNotEmpty()) "-$tag" else ""
}

/** One published release, reduced to what the updater needs. */
data class ReleaseInfo(
    val version: Version, val name: String, val notes: String, val prerelease: Boolean, val publishedAt: String,
    val apkName: String, val apkUrl: String, val apkSize: Long, val sha256: String?, val pageUrl: String,
)

object ReleaseParser {
    /**
     * Picks the newest usable release from the GitHub "list releases" JSON: not a draft, has an .apk, and is not a pre-release
     * unless [includePre]. "Newest" is the highest version (the list order is by date, which is usually the same).
     * [sums] is the text of the release's SHA256SUMS asset, used when the asset itself carries no digest.
     */
    fun pick(json: String, includePre: Boolean, sumsFor: (org.json.JSONObject) -> String? = { null }): ReleaseInfo? {
        val arr = org.json.JSONArray(json)
        var best: ReleaseInfo? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val pre = r.optBoolean("prerelease")
            if (pre && !includePre) continue
            val v = Version.parse(r.optString("tag_name")) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: org.json.JSONObject? = null
            for (k in 0 until assets.length()) assets.getJSONObject(k).let { if (it.optString("name").endsWith(".apk", true)) apk = it }
            val a = apk ?: continue
            val name = a.optString("name")
            var sha = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
            if (sha == null) sha = sumsFor(r)?.lines()?.firstNotNullOfOrNull { l ->
                val p = l.trim().split(Regex("\\s+")); if (p.size >= 2 && p[1].trimStart('*') == name && p[0].length == 64) p[0] else null
            }
            val info = ReleaseInfo(v, r.optString("name").ifBlank { v.toString() }, r.optString("body"), pre, r.optString("published_at"),
                name, a.optString("browser_download_url"), a.optLong("size"), sha?.lowercase(), r.optString("html_url"))
            if (best == null || info.version > best.version) best = info
        }
        return best
    }
}
