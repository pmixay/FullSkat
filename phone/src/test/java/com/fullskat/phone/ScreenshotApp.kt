package com.fullskat.phone

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap

/**
 * The phone app as the screenshot tests run it. Robolectric's native runtime (it backs both graphics and SQLite)
 * must be loaded by one thread: when a background thread (Room's queries, the foreground hooks' sync) and the test
 * thread (drawing) loaded it at the same time, the JVM aborted with "JniConstants: Class not found: java/nio/...".
 * So it is loaded here, on the main thread, before any test code runs, and the foreground hooks (live events and a
 * session refresh against an unreachable server) stay off.
 */
class ScreenshotApp : PhoneApp() {
    override val followsForeground: Boolean = false

    override fun onCreate() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).recycle()
        SQLiteDatabase.create(null).close()
        super.onCreate()
    }
}
