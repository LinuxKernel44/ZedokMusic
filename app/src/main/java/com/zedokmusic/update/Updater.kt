package com.zedokmusic.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.zedokmusic.settings.AppSettings
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from the project's GitHub Releases (no account, nothing personal is sent: a generic user agent and the plain request).
 * Flow: check -> show the release notes -> download (resumable) -> verify SHA-256 and that the APK is signed by the same key as the
 * installed app -> hand it to Android's installer. Android itself also refuses an APK signed by another key.
 */
object Updater {
    enum class Phase { Idle, Checking, UpToDate, Available, Downloading, Verifying, Ready, NeedsPermission, Failed }

    private const val REPO = "LinuxKernel44/ZedokMusic"
    private const val PREFS = "zedok_update"
    private const val CHECK_EVERY_MS = 24L * 3600 * 1000

    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var phase by mutableStateOf(Phase.Idle); private set
    var release by mutableStateOf<ReleaseInfo?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var downloaded by mutableLongStateOf(0); private set
    var total by mutableLongStateOf(0); private set
    var lastChecked by mutableLongStateOf(0); private set
    /** Shown once after the app was replaced by a newer build. */
    var justUpdatedTo by mutableStateOf<String?>(null)
    @Volatile private var cancel = false

    fun init(a: Application) {
        if (this::app.isInitialized) return
        app = a
        lastChecked = prefs.getLong("lastChecked", 0)
        val now = installedVersionName()
        val before = prefs.getString("seenVersion", null)
        if (before != null && before != now) { justUpdatedTo = now; cleanDownloads() }
        prefs.edit().putString("seenVersion", now).apply()
    }

    fun installedVersionName(): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0.0.0"
    private fun installed(): Version = Version.parse(installedVersionName()) ?: Version(0, 0, 0, "")
    var skipped: String get() = prefs.getString("skipped", "") ?: ""; set(v) { prefs.edit().putString("skipped", v).apply() }
    private val debuggable get() = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Where releases come from. A debug build can point at another server with a file named update_url in the app's files folder (for tests). */
    private fun releasesUrl(): String =
        (if (debuggable) File(app.filesDir, "update_url").takeIf { it.exists() }?.readText()?.trim() else null) ?: "https://api.github.com/repos/$REPO/releases?per_page=15"

    fun metered(): Boolean = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private fun http(url: String, range: Long = 0): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ZedokMusic-Updater"); setRequestProperty("Accept", "application/vnd.github+json")
        if (range > 0) setRequestProperty("Range", "bytes=$range-")
    }

    /** Called at start-up: checks at most once a day, when the user left automatic checks on. */
    fun autoCheck() {
        if (!AppSettings.updAuto.on || phase == Phase.Checking) return
        if (System.currentTimeMillis() - lastChecked < CHECK_EVERY_MS) return
        check(silent = true)
    }

    /** Looks for a newer release. [silent]: no error is shown (automatic checks never nag), and a skipped version stays hidden. */
    fun check(silent: Boolean = false) {
        if (phase == Phase.Checking || phase == Phase.Downloading || phase == Phase.Verifying) return
        phase = Phase.Checking; error = null
        Thread {
            try {
                val c = http(releasesUrl())
                val code = c.responseCode
                if (code != 200) throw IllegalStateException(if (code == 403 || code == 429) "GitHub is limiting requests right now. Try again later." else "GitHub answered $code.")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val found = ReleaseParser.pick(body, AppSettings.updPre.on) { r -> fetchSums(r) }
                lastChecked = System.currentTimeMillis(); prefs.edit().putLong("lastChecked", lastChecked).apply()
                if (found != null && found.version > installed() && !(silent && found.version.toString() == skipped)) { release = found; phase = Phase.Available }
                else { release = null; phase = Phase.UpToDate }
            } catch (e: Exception) {
                release = null
                if (silent) phase = Phase.Idle else { error = friendly(e); phase = Phase.Failed }
            }
        }.start()
    }

    private fun fetchSums(r: org.json.JSONObject): String? {
        val assets = r.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) assets.getJSONObject(i).let { a ->
            if (a.optString("name").equals("SHA256SUMS", true)) return runCatching { http(a.optString("browser_download_url")).inputStream.bufferedReader().use { it.readText() } }.getOrNull()
        }
        return null
    }

    private fun friendly(e: Exception) = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "No connection. Check your internet and try again."
        else -> e.message ?: "Something went wrong."
    }

    private fun dir() = File(app.cacheDir, "updates").also { it.mkdirs() }
    fun cleanDownloads() { runCatching { File(app.cacheDir, "updates").deleteRecursively() } }

    /** Downloads the APK (resuming a partial file), verifies it, and stops at [Phase.Ready]. */
    fun download() {
        val r = release ?: return
        if (phase == Phase.Downloading || phase == Phase.Verifying) return
        cancel = false; phase = Phase.Downloading; error = null; downloaded = 0; total = r.apkSize
        Thread {
            try {
                val part = File(dir(), r.apkName + ".part"); val done = File(dir(), r.apkName)
                dir().listFiles()?.filter { it.name != part.name && it.name != done.name }?.forEach { it.delete() }  // older updates
                done.delete()
                var have = if (part.exists()) part.length() else 0L
                if (r.apkSize in 1..have) { part.delete(); have = 0 }
                var c = http(r.apkUrl, have)
                if (have > 0 && c.responseCode != 206) { c.disconnect(); part.delete(); have = 0; c = http(r.apkUrl) }  // the server did not resume
                if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (${c.responseCode}).")
                if (total <= 0) total = have + c.contentLengthLong.coerceAtLeast(0)
                downloaded = have
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(have)
                    c.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancel) { phase = Phase.Available; return@Thread }
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); downloaded += n
                        }
                    }
                }
                phase = Phase.Verifying
                val problem = verify(part, r)
                if (problem != null) { part.delete(); throw IllegalStateException(problem) }
                if (!part.renameTo(done)) throw IllegalStateException("Could not store the update.")
                phase = Phase.Ready
            } catch (e: Exception) { error = friendly(e); phase = Phase.Failed }
        }.start()
    }

    fun cancelDownload() { cancel = true }

    /** Null when the file is the release we expect and is signed by the same key as this app; else the reason it is refused. */
    private fun verify(f: File, r: ReleaseInfo): String? {
        if (r.apkSize > 0 && f.length() != r.apkSize) return "The download is incomplete. Try again."
        if (r.sha256 != null) {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != r.sha256) return "The downloaded file does not match its checksum, so it was discarded."
        }
        val pm = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: return "The file is not a valid app package."
        if (apk.packageName != app.packageName) return "The file is for a different app."
        val mine = pm.getPackageInfo(app.packageName, flags)
        fun sigs(p: android.content.pm.PackageInfo) = p.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }?.toSet() ?: emptySet()
        if (sigs(apk).isEmpty() || sigs(apk) != sigs(mine)) return "The update is signed with a different key than the installed app, so it was refused."
        return null
    }

    /** Opens Android's installer for the verified file. [beforeInstall] lets the app save the project first. */
    fun install(beforeInstall: () -> Unit) {
        val r = release ?: return
        val f = File(dir(), r.apkName); if (!f.exists()) { error = "The update file is gone. Download it again."; phase = Phase.Failed; return }
        if (!app.packageManager.canRequestPackageInstalls()) {
            phase = Phase.NeedsPermission
            runCatching { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        beforeInstall()
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true).putExtra(Intent.EXTRA_RETURN_RESULT, false)
        runCatching { app.startActivity(i) }.onFailure { error = "Could not open the installer: ${it.message}"; phase = Phase.Failed }
    }

    /** After the user comes back from the "install unknown apps" screen. */
    fun resumeAfterPermission() { if (phase == Phase.NeedsPermission && app.packageManager.canRequestPackageInstalls()) phase = Phase.Ready }

    fun dismiss() { if (phase != Phase.Downloading && phase != Phase.Verifying) { phase = Phase.Idle } }
    fun skipThisVersion() { release?.let { skipped = it.version.toString() }; phase = Phase.Idle }
}
