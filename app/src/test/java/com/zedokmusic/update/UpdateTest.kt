package com.zedokmusic.update

import org.junit.Assert.*
import org.junit.Test

class UpdateTest {
    @Test fun parsesTagsAndVersionNames() {
        assertEquals("0.14.3-alpha", Version.parse("v0.14.3-alpha").toString())
        assertEquals("1.2.0", Version.parse("1.2").toString())
        assertNull(Version.parse("latest")); assertNull(Version.parse(null))
    }

    @Test fun ordersByNumbersNotText() {
        val v = { s: String -> Version.parse(s)!! }
        assertTrue(v("0.14.10-alpha") > v("0.14.9-alpha"))
        assertTrue(v("0.15.0-alpha") > v("0.14.99-alpha"))
        assertTrue(v("1.0.0") > v("0.99.99-alpha"))
        assertFalse(v("0.14.3-alpha") > v("0.14.3-alpha"))
    }

    private fun release(tag: String, pre: Boolean = true, draft: Boolean = false, apk: Boolean = true, digest: String? = null) = """
        {"tag_name":"$tag","name":"$tag","body":"Notes $tag","prerelease":$pre,"draft":$draft,"html_url":"https://x/$tag","published_at":"2026-01-01T00:00:00Z",
         "assets":[${if (apk) """{"name":"ZedokMusic-$tag.apk","size":123,"browser_download_url":"https://x/$tag.apk"${if (digest != null) ""","digest":"sha256:$digest"""" else ""}},""" else ""}
                   {"name":"SHA256SUMS","size":10,"browser_download_url":"https://x/sums"}]}"""

    @Test fun picksTheHighestUsableRelease() {
        val json = "[" + listOf(release("v0.9.0-alpha"), release("v0.10.0-alpha"), release("v0.11.0-alpha", draft = true), release("v0.12.0-alpha", apk = false)).joinToString(",") + "]"
        assertEquals("0.10.0-alpha", ReleaseParser.pick(json, includePre = true)!!.version.toString())
    }

    @Test fun preReleasesAreSkippedWhenNotWanted() {
        val json = "[" + listOf(release("v2.0.0", pre = true), release("v1.0.0", pre = false)).joinToString(",") + "]"
        assertEquals("1.0.0", ReleaseParser.pick(json, includePre = false)!!.version.toString())
        assertNull(ReleaseParser.pick("[" + release("v2.0.0", pre = true) + "]", includePre = false))
    }

    @Test fun checksumComesFromTheDigestOrFromSha256sums() {
        val h = "a".repeat(64); val h2 = "b".repeat(64)
        assertEquals(h, ReleaseParser.pick("[" + release("v1.0.0", digest = h) + "]", true)!!.sha256)
        val sums = "$h2  ZedokMusic-v1.0.0.apk\n${"c".repeat(64)}  other.apk\n"
        assertEquals(h2, ReleaseParser.pick("[" + release("v1.0.0") + "]", true) { sums }!!.sha256)
        assertNull(ReleaseParser.pick("[" + release("v1.0.0") + "]", true)!!.sha256)
    }
}
