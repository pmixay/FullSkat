package com.fullskat.core

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.fullskat.core.attachments.AttachmentRepository
import com.fullskat.core.net.ApiClient
import com.fullskat.core.session.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Preparing a picked or shot photo for upload, with Android's real image decoder (Robolectric native graphics):
 * the result is a JPEG at most 1568 px on its long side, upright, with the picture's proportions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AttachmentPrepareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = AttachmentRepository(context, ApiClient("http://127.0.0.1:9"), SessionStore(context))

    private fun image(name: String, w: Int, h: Int, format: String): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(200, 120, 40)
        g.fillRect(0, 0, w, h)
        g.color = java.awt.Color.WHITE
        g.fillOval(w / 4, h / 4, w / 2, h / 2)
        g.dispose()
        return File(context.cacheDir, name).also { ImageIO.write(img, format, it) }
    }

    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        assertTrue("a JPEG", jpeg.size > 3 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val img = ImageIO.read(ByteArrayInputStream(jpeg))
        return img.width to img.height
    }

    @Test fun aCameraSizedPhotoIsScaledDownKeepingItsProportions() = runBlocking {
        val photo = image("photo.jpg", 4000, 3000, "jpg")
        val (w, h) = size(repo.prepare(Uri.fromFile(photo)))
        assertEquals(1568, w)
        assertEquals(1176, h)
    }

    @Test fun aSmallImageKeepsItsSize() = runBlocking {
        val (w, h) = size(repo.prepare(Uri.fromFile(image("small.png", 640, 480, "png"))))
        assertEquals(640 to 480, w to h)
    }

    @Test fun theExifOrientationOfACameraShotIsApplied() = runBlocking {
        val shot = image("shot.jpg", 3200, 2400, "jpg") // a phone in portrait writes landscape pixels plus "rotate 90"
        ExifInterface(shot).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val (w, h) = size(repo.prepare(Uri.fromFile(shot)))
        assertEquals(1176 to 1568, w to h) // upright: portrait
    }

    @Test fun somethingThatIsNotAnImageIsRefused() = runBlocking {
        val text = File(context.cacheDir, "notes.jpg").apply { writeText("not a picture") }
        try {
            repo.prepare(Uri.fromFile(text))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // the chat shows "Couldn't read the image"
        }
        try {
            repo.prepare(Uri.fromFile(File(context.cacheDir, "missing.jpg")))
            fail("expected an exception for a missing file")
        } catch (e: Exception) {
            assertTrue(e is java.io.IOException || e is IllegalArgumentException)
        }
    }
}
