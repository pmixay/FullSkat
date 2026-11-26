package com.fullskat.phone.watchinstall

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileNotFoundException

/** The watch app carried inside the phone app (release builds only; see phone/build.gradle.kts). */
sealed interface BundledApk {
    data object Loading : BundledApk

    /** A debug build of the phone app: the watch app is not in it. */
    data object NotBundled : BundledApk

    /** The asset is there but Android cannot read it as an APK. */
    data object Broken : BundledApk

    data class Ready(val version: AppVersion, val minSdk: Int, val file: File, val size: Long) : BundledApk
}

object BundledWatchApk {
    const val ASSET = "watch/fullskat-watch.apk"
    private const val STALE_MS = 15 * 60_000L

    /**
     * Copies the bundled APK into the cache (Android reads versions only from files, and the copy is what gets streamed
     * to the watch) and reads its version. Each screen gets its own copy and deletes it when it closes ([delete]);
     * copies left behind by a killed process are removed here. Blocking.
     */
    fun load(context: Context): BundledApk {
        val dir = File(context.cacheDir, "watch").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { it.delete() }
        val file = File(dir, "fullskat-watch-$now.apk")
        try {
            context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } }
        } catch (e: FileNotFoundException) {
            file.delete()
            return BundledApk.NotBundled
        }
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != WATCH_PACKAGE) {
            file.delete()
            return BundledApk.Broken
        }
        val version = AppVersion(info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info))
        return BundledApk.Ready(version, info.applicationInfo?.minSdkVersion ?: 0, file, file.length())
    }

    /** Drops a screen's copy (tens of megabytes) once it is gone. */
    fun delete(apk: BundledApk) {
        (apk as? BundledApk.Ready)?.file?.delete()
    }
}
