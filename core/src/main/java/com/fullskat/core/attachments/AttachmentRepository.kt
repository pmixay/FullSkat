package com.fullskat.core.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.fullskat.core.net.ApiClient
import com.fullskat.core.net.ApiException
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Images attached to messages: prepared on the device (orientation applied, scaled to at most 1568 px, JPEG),
 * uploaded, and shown again from a disk cache plus a small memory cache, so a thumbnail is fetched once.
 */
class AttachmentRepository(
    private val context: Context,
    private val api: ApiClient,
    private val session: SessionStore,
) {
    private val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Reads an image the user picked or shot and makes it upload-ready: upright, at most [maxSide] px, JPEG. */
    suspend fun prepare(uri: Uri, maxSide: Int = MAX_SIDE): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Measuring only: in this mode decodeStream always returns null; whether it is an image shows in the size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw IllegalArgumentException("unreadable image")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("not an image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalArgumentException("unreadable image")
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(ExifInterface(it)) } ?: 0
        encode(scale(decoded, maxSide, rotation))
    }

    /** Uploads prepared JPEG bytes; the server checks and normalises them again. */
    suspend fun upload(jpeg: ByteArray): AttachmentRef {
        val token = session.token() ?: throw ApiException.Unauthorized("not signed in")
        val ref = api.uploadAttachment(token, jpeg, "image/jpeg")
        withContext(Dispatchers.IO) { File(dir, "${ref.id}_full.jpg").writeBytes(jpeg) } // shown from the cache from now on
        return ref
    }

    /** The image of [ref] (a thumbnail when [thumb]), from memory, disk or the server; null when it is gone or offline. */
    suspend fun bitmap(ref: AttachmentRef, thumb: Boolean): Bitmap? {
        val key = ref.id + if (thumb) "_thumb" else "_full"
        memory.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            var file = File(dir, "$key.jpg")
            if (!file.exists() && thumb && File(dir, "${ref.id}_full.jpg").exists()) file = File(dir, "${ref.id}_full.jpg")
            if (!file.exists()) {
                val token = session.token() ?: return@withContext null
                val bytes = try { api.downloadAttachment(token, ref.id, thumb) } catch (e: ApiException) { null } ?: return@withContext null
                file = File(dir, "$key.jpg").apply { writeBytes(bytes) }
            }
            decode(file, if (thumb) THUMB_SIDE else MAX_SIDE)
        } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    fun clearCache() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        const val MAX_SIDE = 1568
        const val THUMB_SIDE = 320

        fun exifRotation(exif: ExifInterface): Int = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        fun scale(src: Bitmap, maxSide: Int, rotation: Int): Bitmap {
            val k = minOf(1f, maxSide.toFloat() / max(src.width, src.height))
            if (k == 1f && rotation == 0) return src
            val m = Matrix().apply { postScale(k, k); postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        fun encode(bitmap: Bitmap, quality: Int = 85): ByteArray =
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
    }
}
