package to.pubky.maps.android.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Photos are re-encoded on the phone: camera and phone details and (unless asked) the GPS spot never leave it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PhotoPreparerTest {
    /** A JPEG as a phone camera writes it: make, model, software, time, GPS and an orientation. */
    private fun cameraPhoto(
        width: Int = 400,
        height: Int = 300,
        orientation: Int = ExifInterface.ORIENTATION_ROTATE_90,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        val file = File.createTempFile("camera", ".jpg")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_MAKE, "Pixelmaker")
                setAttribute(ExifInterface.TAG_MODEL, "Phone 9 Pro")
                setAttribute(ExifInterface.TAG_SOFTWARE, "Camera 1.2")
                setAttribute(ExifInterface.TAG_DATETIME, "2026:11:25 10:00:00")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "47/1,22/1,123456/10000")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "8/1,32/1,301234/10000")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun exif(bytes: ByteArray) = ExifInterface(ByteArrayInputStream(bytes))

    @Test
    fun cameraAndPhoneDetailsAndTheLocationAreRemoved() {
        val original = cameraPhoto()
        assertTrue("the test photo has a location", exif(original).getLatLong(FloatArray(2)))

        val prepared = PhotoPreparer.prepare(original, keepLocation = false)

        val out = exif(prepared.bytes)
        assertNull(out.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(out.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(out.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertNull(out.getAttribute(ExifInterface.TAG_DATETIME))
        assertFalse(out.getLatLong(FloatArray(2)))
        assertTrue(prepared.removedLocation)
        assertFalse(prepared.keptLocation)
        assertEquals("image/jpeg", prepared.mimeType)
    }

    @Test
    fun thePhotoIsTurnedUprightSinceItsOrientationTagIsGone() {
        val prepared = PhotoPreparer.prepare(cameraPhoto(400, 300, ExifInterface.ORIENTATION_ROTATE_90), keepLocation = false)

        assertEquals(300, prepared.width)
        assertEquals(400, prepared.height)
        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
        assertEquals(300, decoded.width)
        assertEquals(400, decoded.height)
    }

    @Test
    fun withPhotoLocationsPublicOnlyTheLocationIsKept() {
        val prepared = PhotoPreparer.prepare(cameraPhoto(), keepLocation = true)

        val out = exif(prepared.bytes)
        val latLong = FloatArray(2)
        assertTrue(out.getLatLong(latLong))
        assertEquals(47.3701, latLong[0].toDouble(), 0.001)
        assertEquals(8.5417, latLong[1].toDouble(), 0.001)
        assertNull(out.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(out.getAttribute(ExifInterface.TAG_MODEL))
        assertTrue(prepared.keptLocation)
    }

    @Test
    fun largePhotosAreScaledDown() {
        val prepared = PhotoPreparer.prepare(cameraPhoto(5000, 1000, ExifInterface.ORIENTATION_NORMAL), keepLocation = false)

        assertEquals(PhotoPreparer.MAX_EDGE, prepared.width)
        assertEquals("the shape stays", 410, prepared.height)
    }

    @Test
    fun pngAndWebpBecomeJpegToo() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val prepared = PhotoPreparer.prepare(png, keepLocation = true)

        assertEquals("image/jpeg", prepared.mimeType)
        assertEquals(0xFF.toByte(), prepared.bytes[0])
        assertEquals(0xD8.toByte(), prepared.bytes[1])
        assertFalse("a photo without a location gets none", prepared.keptLocation)
        assertFalse(prepared.removedLocation)
    }

    @Test
    fun somethingThatIsNotAPhotoIsRefusedWithAReason() {
        val error = assertThrows(PhotoException::class.java) { PhotoPreparer.prepare("not a photo".toByteArray(), keepLocation = false) }
        assertEquals("This file is not a photo Pubky Maps can read.", error.message)
    }

    @Test
    fun degreesAreWrittenAsExifRationals() {
        assertEquals("47/1,22/1,123456/10000", PhotoPreparer.rational(47.370096))
        assertEquals("8/1,0/1,0/10000", PhotoPreparer.rational(-8.0))
    }

    @Test
    fun aPhotoThatCannotBeOpenedIsRefusedWithAReason() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val gone = Uri.fromFile(File("/nonexistent/photo.jpg"))

        val error = assertThrows(PhotoException::class.java) { PhotoPreparer.prepare(resolver, gone, keepLocation = false) }

        assertTrue(error.message, error.message.startsWith("The photo could not be read"))
    }

    @Test
    fun aReadThatFailsPartwayIsRefusedWithAReason() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Uri.parse("content://photos.example/1")
        val failing =
            object : InputStream() {
                private var given = 0

                override fun read(): Int = if (given++ < 10) 0xFF else throw IOException("connection reset")
            }
        shadowOf(resolver).registerInputStream(uri, failing)

        val error = assertThrows(PhotoException::class.java) { PhotoPreparer.prepare(resolver, uri, keepLocation = false) }

        assertEquals("The photo could not be read (connection reset).", error.message)
    }
}
