package com.fullskat.phone

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The phone app as the screenshot tests run it.
 *
 * Robolectric's native runtime looks up its `java.nio` buffer classes lazily ("JniConstants"), on the first native
 * call that touches a buffer. When that first call came from a background thread (the screens decode attachment
 * images on one), the lookup failed and the JVM aborted with "JniConstants: Class not found: java/nio/FloatBuffer".
 * So those calls are made here first, on the main thread, before any test code runs: a buffer copy, an image
 * encode/decode, and SQLite. The foreground hooks (live events, a session refresh against an unreachable server)
 * stay off too, so nothing else starts in the background.
 */
class ScreenshotApp : PhoneApp() {
    override val followsForeground: Boolean = false

    override fun onCreate() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsToBuffer(ByteBuffer.allocate(bitmap.byteCount))
        bitmap.copyPixelsFromBuffer(ByteBuffer.allocate(bitmap.byteCount))
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        BitmapFactory.decodeByteArray(png, 0, png.size)?.recycle()
        bitmap.recycle()
        SQLiteDatabase.create(null).close()
        super.onCreate()
    }
}
