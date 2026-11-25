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

    private fun copyFile(context: Context) = File(File(context.cacheDir, "watch"), "fullskat-watch.apk")

    /**
     * Copies the bundled APK into the cache (Android reads versions only from files, and the copy is what gets streamed
     * to the watch) and reads its version. Blocking.
     */
    fun load(context: Context): BundledApk {
        val file = copyFile(context)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            if (!tmp.renameTo(file)) return BundledApk.Broken
        } catch (e: FileNotFoundException) {
            return BundledApk.NotBundled
        }
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return BundledApk.Broken
        if (info.packageName != WATCH_PACKAGE) return BundledApk.Broken
        val minSdk = info.applicationInfo?.minSdkVersion ?: 0
        return BundledApk.Ready(AppVersion(info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info)), minSdk, file, file.length())
    }

    /** Drops the cached copy (tens of megabytes) once the installer screen is gone. */
    fun clear(context: Context) {
        copyFile(context).parentFile?.deleteRecursively()
    }
}
