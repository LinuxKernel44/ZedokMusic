package com.zedokmusic.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One preset of a built-in plugin: values are normalized (0..1) by parameter name; parameters it does not list return to their defaults. */
class Preset(val key: String, val name: String, val category: String, val tags: List<String>, val values: Map<String, Float>, val state: Map<String, String>, val factory: Boolean, val file: File? = null)

/**
 * Presets of the built-in Zedok plugins. Factory presets ship in the APK (assets/presets/<plugin>.json, read only). User presets are
 * files in <workspace>/Presets/<plugin>/<Category>/<Name>.zpreset, shared by every project. Favourites are kept in <workspace>/Presets/favorites.json.
 */
class PresetLibrary(private val ctx: Context, private val workspace: File?) {
    private val factoryCache = HashMap<String, List<Preset>>()
    private fun root(plugin: String): File? = workspace?.let { File(it, "Presets/$plugin") }

    fun factory(plugin: String): List<Preset> = factoryCache.getOrPut(plugin) {
        runCatching {
            val o = JSONObject(ctx.assets.open("presets/$plugin.json").bufferedReader().use { it.readText() })
            val a = o.getJSONArray("presets")
            (0 until a.length()).map { i -> parse(a.getJSONObject(i), "f:$plugin:", true, null) }
        }.getOrDefault(emptyList())
    }

    fun user(plugin: String): List<Preset> {
        val dir = root(plugin) ?: return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "zpreset" }.mapNotNull { f ->
            runCatching { parse(JSONObject(f.readText()).also { if (!it.has("category")) it.put("category", f.parentFile?.takeIf { p -> p != dir }?.name ?: "User") }, "u:$plugin:${f.relativeTo(dir).path}", false, f) }.getOrNull()
        }.sortedBy { it.name.lowercase() }.toList()
    }

    fun all(plugin: String): List<Preset> = factory(plugin) + user(plugin)

    private fun parse(o: JSONObject, keyPrefix: String, factory: Boolean, file: File?): Preset {
        val name = o.getString("name")
        val vals = HashMap<String, Float>(); o.optJSONObject("values")?.let { v -> v.keys().forEach { k -> vals[k] = v.getDouble(k).toFloat() } }
        val st = HashMap<String, String>(); o.optJSONObject("state")?.let { v -> v.keys().forEach { k -> st[k] = v.getString(k) } }
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Preset(if (factory) keyPrefix + name else keyPrefix, name, o.optString("category", "User"), tags, vals, st, factory, file)
    }

    private fun safe(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 _.()+&'-]"), "_").trim('.', ' ').take(60)

    /** Writes a user preset; [values] are the parameters that differ from their defaults. Returns the preset, or null when there is no workspace. */
    fun save(plugin: String, name: String, category: String, tags: List<String>, values: Map<String, Float>, state: Map<String, String>): Preset? {
        val dir = root(plugin) ?: return null
        val cat = safe(category).ifEmpty { "User" }; val nm = safe(name); if (nm.isEmpty()) return null
        val d = File(dir, cat).apply { mkdirs() }
        val f = File(d, "$nm.zpreset")
        val o = JSONObject().put("format", 1).put("plugin", plugin).put("name", nm).put("category", cat).put("tags", JSONArray(tags))
            .put("values", JSONObject().also { v -> values.forEach { (k, x) -> v.put(k, x.toDouble()) } })
        if (state.isNotEmpty()) o.put("state", JSONObject().also { v -> state.forEach { (k, x) -> v.put(k, x) } })
        f.writeText(o.toString())
        return parse(o, "u:$plugin:${f.relativeTo(dir).path}", false, f)
    }

    fun delete(p: Preset): Boolean = !p.factory && p.file?.delete() == true

    fun rename(plugin: String, p: Preset, newName: String): Preset? {
        val f = p.file ?: return null; val nm = safe(newName); if (nm.isEmpty()) return null
        val dest = File(f.parentFile, "$nm.zpreset"); if (dest.exists()) return null
        val o = JSONObject(f.readText()).put("name", nm); dest.writeText(o.toString()); f.delete()
        return runCatching { parse(o, "u:$plugin:${dest.relativeTo(root(plugin)!!).path}", false, dest) }.getOrNull()
    }

    /** Copies a preset file into <workspace>/Exports/Presets so it can be shared; returns the new file. */
    fun export(plugin: String, p: Preset): File? {
        val ws = workspace ?: return null
        val d = File(ws, "Exports/Presets").apply { mkdirs() }
        val f = File(d, "${safe(plugin)} - ${safe(p.name)}.zpreset")
        val o = JSONObject().put("format", 1).put("plugin", plugin).put("name", p.name).put("category", p.category).put("tags", JSONArray(p.tags))
            .put("values", JSONObject().also { v -> p.values.forEach { (k, x) -> v.put(k, x.toDouble()) } })
        if (p.state.isNotEmpty()) o.put("state", JSONObject().also { v -> p.state.forEach { (k, x) -> v.put(k, x) } })
        return runCatching { f.writeText(o.toString()); f }.getOrNull()
    }

    /** Imports a .zpreset file for [plugin]; returns the new preset, or null if the file is not a preset of that plugin. */
    fun import(plugin: String, text: String): Preset? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optString("plugin") != plugin) return null
        val vals = HashMap<String, Float>(); o.optJSONObject("values")?.let { v -> v.keys().forEach { k -> vals[k] = v.getDouble(k).toFloat() } }
        val st = HashMap<String, String>(); o.optJSONObject("state")?.let { v -> v.keys().forEach { k -> st[k] = v.getString(k) } }
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return save(plugin, o.optString("name", "Imported"), o.optString("category", "Imported"), tags, vals, st)
    }

    // ---- favourites
    private val favFile get() = workspace?.let { File(it, "Presets/favorites.json") }
    private var favCache: MutableSet<String>? = null
    fun favorites(): MutableSet<String> = favCache ?: (runCatching { JSONArray(favFile!!.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } }.getOrNull() ?: mutableSetOf()).also { favCache = it }
    fun toggleFavorite(key: String): Boolean {
        val f = favorites(); val on = if (f.contains(key)) { f.remove(key); false } else { f.add(key); true }
        runCatching { favFile?.let { it.parentFile?.mkdirs(); it.writeText(JSONArray(f.toList()).toString()) } }
        return on
    }
}
