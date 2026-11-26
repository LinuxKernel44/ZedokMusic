package com.zedokmusic.model

import kotlin.math.roundToInt

/**
 * Turns the audio files of a drum kit into a pad layout (16 pads) without touching Android: file names are classified (kick, snare, clap, hats, toms...),
 * velocity layers (_v1, _soft, _hard...) and round robins (_rr2, trailing numbers) are recognised, and each sound goes to the pad where a drum machine
 * would put it. Pure Kotlin so it is unit tested on the JVM.
 */
object KitMapper {
    /** One audio file of a kit; [path] is relative to the kit's root folder, with '/' separators. */
    data class Entry(val path: String) { val name get() = path.substringAfterLast('/') ; val stem get() = name.substringBeforeLast('.') }
    /** A sample on a pad: played for velocities [vlo]..[vhi]; zones that cover the same velocities take turns (round robin). */
    data class Zone(val entry: Entry, val vlo: Int = 1, val vhi: Int = 127)
    class Plan(val name: String, val pads: Array<List<Zone>>, val dropped: List<Entry>, val note: String = "") {
        val used get() = pads.sumOf { it.size }
        val padsUsed get() = pads.count { it.isNotEmpty() }
    }

    enum class Role(val pads: IntArray) {
        Kick(intArrayOf(0, 1)), Rim(intArrayOf(2)), Snare(intArrayOf(3)), Clap(intArrayOf(4)),
        TomLow(intArrayOf(5)), HatClosed(intArrayOf(6, 8)), TomMid(intArrayOf(7)), TomHigh(intArrayOf(9)), HatOpen(intArrayOf(10)),
        Perc(intArrayOf(11, 14)), Cymbal(intArrayOf(12)), Shaker(intArrayOf(13)), Ride(intArrayOf(13, 12)), Other(intArrayOf(15))
    }

    private fun tokens(s: String): List<String> =
        s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** What a sound is, from its file name first and its folders second. */
    fun roleOf(path: String): Role {
        val parts = path.split('/'); val file = parts.last().substringBeforeLast('.')
        for (src in listOf(file, parts.dropLast(1).joinToString(" "))) {
            val tk = tokens(src); val flat = tk.joinToString("")
            fun has(vararg k: String) = k.any { w -> tk.contains(w) }
            fun sub(vararg k: String) = k.any { flat.contains(it) }
            val open = sub("openhat", "hatopen", "hihatopen", "ohat", "hhopen", "openhh") || (has("open") && (sub("hat") || has("hh", "oh")))
            when {
                open || has("oh", "hho") -> return Role.HatOpen
                sub("closedhat", "hatclosed", "chat", "hihat", "hhc", "pedalhat") || has("ch", "hhc", "hh", "hat", "hats", "pedal") || (sub("hat")) -> return Role.HatClosed
                sub("kick", "bassdrum", "kik") || has("bd", "kck", "kicks") -> return Role.Kick
                sub("sidestick", "rimshot") && !sub("snare") || has("rim", "sidestick") -> return Role.Rim
                sub("snare", "snr") || has("sd", "snares") -> return Role.Snare
                sub("handclap", "clap") || has("cp", "claps") -> return Role.Clap
                sub("crash", "cymbal", "splash", "china") || has("cym", "crsh") -> return Role.Cymbal
                sub("ride") -> return Role.Ride
                sub("tom") || has("floor", "rack") -> return when { has("low", "lo", "floor", "l") || sub("low") -> Role.TomLow; has("high", "hi", "h") || sub("high") -> Role.TomHigh; else -> Role.TomMid }
                sub("shaker", "maracas", "egg", "tamb") -> return Role.Shaker
                sub("cowbell", "clave", "conga", "bongo", "perc", "snap", "wood", "block", "bell", "triangle", "guiro", "agogo") -> return Role.Perc
            }
        }
        return Role.Other
    }

    private val velWords = mapOf("pp" to 20, "soft" to 25, "quiet" to 25, "light" to 25, "p" to 40, "mp" to 55, "med" to 60, "medium" to 60, "mid" to 60, "mf" to 75, "f" to 95, "hard" to 100, "loud" to 100, "ff" to 120)

    private class Parsed(val base: String, val velLevel: Float?, val rr: Int?)

    /** Splits a file name into its base name and the velocity layer / round-robin markers it carries. */
    private fun parse(entry: Entry): Parsed {
        var stem = entry.stem.lowercase()
        var vel: Float? = null; var rr: Int? = null
        Regex("[ _.-]?rr[ _-]?(\\d+)").find(stem)?.let { rr = it.groupValues[1].toInt(); stem = stem.replace(it.value, " ") }
        Regex("[ _.-]?(?:vel|velocity|v)[ _-]?(\\d+)(?![a-z])").find(stem)?.let { m -> vel = m.groupValues[1].toFloat().let { n -> if (n > 8) n else n }; stem = stem.replace(m.value, " ") }
        val parts = stem.split(Regex("[ _.-]+")).filter { it.isNotEmpty() }.toMutableList()
        parts.removeAll { w -> velWords[w]?.let { vel = it.toFloat() + 1000f; true } == true }   // words rank above numbers only among themselves (offset keeps them sortable)
        var base = parts.joinToString(" ")
        Regex("[ ]*\\(?(\\d{1,3})\\)?$").find(base)?.let { m -> if (rr == null && vel == null) rr = m.groupValues[1].toInt(); base = base.removeRange(m.range) }
        return Parsed(base.trim(), vel, rr)
    }

    /** Lays a kit out on 16 pads. */
    fun plan(name: String, entries: List<Entry>): Plan {
        // 1. group files that are variants (velocity layers / round robins) of one sound
        class Group(val role: Role, val key: String, val files: MutableList<Pair<Entry, Parsed>> = mutableListOf())
        val groups = LinkedHashMap<String, Group>()
        for (e in entries.sortedBy { it.path.lowercase() }) {
            val role = roleOf(e.path); val p = parse(e)
            groups.getOrPut(role.name + "|" + p.base) { Group(role, p.base) }.files.add(e to p)
        }
        // 2. zones of a group: velocity levels split 1..127 into equal bands, files of the same level are round robins
        fun zonesOf(g: Group): List<Zone> {
            val levels = g.files.mapNotNull { it.second.velLevel }.distinct().sorted()
            if (levels.size <= 1) return g.files.sortedBy { it.second.rr ?: 0 }.map { Zone(it.first) }
            val band = 127f / levels.size
            return g.files.sortedWith(compareBy({ it.second.velLevel ?: 0f }, { it.second.rr ?: 0 })).map { (e, p) ->
                val i = levels.indexOf(p.velLevel).coerceAtLeast(0)
                Zone(e, (i * band).roundToInt() + 1, if (i == levels.size - 1) 127 else ((i + 1) * band).roundToInt())
            }
        }
        // 3. place the groups: each role fills its preferred pads, the rest goes to free pads
        val pads = Array(16) { emptyList<Zone>() }; val dropped = mutableListOf<Entry>()
        val pending = ArrayList<Group>()
        val byRole = groups.values.groupBy { it.role }
        for (role in Role.values()) for (g in byRole[role].orEmpty().sortedBy { it.key }) {
            val slot = role.pads.firstOrNull { pads[it].isEmpty() }
            if (slot != null) pads[slot] = zonesOf(g) else pending.add(g)
        }
        for (g in pending) { val slot = (0 until 16).firstOrNull { pads[it].isEmpty() }; if (slot != null) pads[slot] = zonesOf(g) else dropped.addAll(g.files.map { it.first }) }
        val note = if (dropped.isNotEmpty()) "${dropped.size} more file${if (dropped.size > 1) "s" else ""} did not fit on the 16 pads." else ""
        return Plan(name, pads, dropped, note)
    }

    /** A readable name for a pad: the file name without velocity-layer / round-robin markers ("Snare_v1" -> "Snare", "Kick 03" -> "Kick"). */
    fun label(e: Entry): String {
        val t = e.stem.replace(Regex("[ _.-]?(?:rr|vel|velocity|v)[ _-]?\\d+$", RegexOption.IGNORE_CASE), "").replace(Regex("[ _.-]?(?:soft|medium|hard|loud|quiet)$", RegexOption.IGNORE_CASE), "").replace(Regex("[ _.-]+\\(?\\d{1,3}\\)?$"), "").replace('_', ' ').trim()
        return t.ifEmpty { e.stem }
    }

    /** A plan from explicit zones for one pad (files picked by hand). */
    fun padPlan(entries: List<Entry>): List<Zone> {
        val groups = plan("", entries)
        return groups.pads.flatMap { it }.ifEmpty { entries.map { Zone(it) } }.let { z -> if (z.size == entries.size) z else entries.map { Zone(it) } }
    }

    // ---- state text understood by the Drums plugin: one zone per line "velLow,velHigh,roundRobin,path"
    fun zoneText(zones: List<Zone>, resolve: (String) -> String = { it }): String = zones.joinToString("\n") { "${it.vlo},${it.vhi},0,${resolve(it.entry.path)}" }
    fun parseZoneText(text: String): List<Zone> = text.lines().mapNotNull { l ->
        val p = l.split(',', limit = 4); if (p.size < 4) null else Zone(Entry(p[3]), p[0].toIntOrNull() ?: 1, p[1].toIntOrNull() ?: 127)
    }

    // ---------------------------------------------------------------- SFZ
    class SfzRegion(val sample: String, val lokey: Int, val hikey: Int, val lovel: Int, val hivel: Int, val seq: Int, val group: Int)
    private val noteNames = mapOf("c" to 0, "d" to 2, "e" to 4, "f" to 5, "g" to 7, "a" to 9, "b" to 11)
    private fun key(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val m = Regex("^([a-g])(#|b)?(-?\\d)$", RegexOption.IGNORE_CASE).find(s.trim()) ?: return null
        var n = noteNames[m.groupValues[1].lowercase()]!!; if (m.groupValues[2] == "#") n++ else if (m.groupValues[2] == "b") n--
        return (m.groupValues[3].toInt() + 1) * 12 + n
    }
    /** Reads the regions of an SFZ file (samples, key and velocity ranges, round robin position, group). Sample paths are relative to the .sfz. */
    fun parseSfz(text: String): List<SfzRegion> {
        val clean = text.lines().joinToString("\n") { it.substringBefore("//") }
        val out = ArrayList<SfzRegion>(); val scopes = HashMap<String, MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null; var curName = ""; var defaultPath = ""
        fun flush() { val c = cur ?: return; if (curName != "region") return
            val merged = HashMap<String, String>(); for (s in listOf("global", "master", "group")) scopes[s]?.let { merged.putAll(it) }; merged.putAll(c)
            val sample = merged["sample"] ?: return; val k = merged["key"]?.let(::key); val lo = k ?: merged["lokey"]?.let(::key) ?: 0; val hi = k ?: merged["hikey"]?.let(::key) ?: lo.takeIf { merged.containsKey("lokey") } ?: 127
            out.add(SfzRegion((merged["default_path"] ?: defaultPath) + sample.replace('\\', '/'), lo, hi, merged["lovel"]?.toIntOrNull() ?: 1, merged["hivel"]?.toIntOrNull() ?: 127, merged["seq_position"]?.toIntOrNull() ?: 1, merged["group"]?.toIntOrNull() ?: 0)) }
        val tokenRx = Regex("<(\\w+)>|(\\w+)=([^\\s]+(?:\\s+(?![\\w]+=|<)[^\\s=<]+)*)")
        for (m in tokenRx.findAll(clean)) {
            if (m.groupValues[1].isNotEmpty()) { flush(); curName = m.groupValues[1]; cur = HashMap(); if (curName != "region") scopes[curName] = cur!!; if (curName == "global") scopes.clear().also { scopes["global"] = cur!! } }
            else { val k = m.groupValues[2]; val v = m.groupValues[3].trim(); if (k == "default_path") defaultPath = v.replace('\\', '/'); cur?.put(k, v) }
        }
        flush(); return out
    }

    /** A plan from SFZ regions: a region's key chooses the pad (36..51 = pads 1..16, otherwise the distinct keys in order). */
    fun planFromSfz(name: String, regions: List<SfzRegion>): Plan {
        val keys = regions.map { it.lokey }.distinct().sorted(); val inGm = keys.all { it in 36..51 }
        val pads = Array(16) { mutableListOf<Zone>() }; val dropped = mutableListOf<Entry>()
        for (r in regions) {
            val pad = if (inGm) r.lokey - 36 else keys.indexOf(r.lokey)
            if (pad !in 0..15) { dropped.add(Entry(r.sample)); continue }
            pads[pad].add(Zone(Entry(r.sample), r.lovel.coerceIn(1, 127), r.hivel.coerceIn(1, 127)))
        }
        return Plan(name, Array(16) { pads[it].toList() }, dropped, if (dropped.isNotEmpty()) "${dropped.size} region(s) did not fit on the 16 pads." else "")
    }
}
