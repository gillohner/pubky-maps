package to.pubky.maps.android.photos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import to.pubky.maps.write.PreparedPhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Why a photo could not be prepared, in words for the user. */
class PhotoException(
    override val message: String,
) : Exception(message)

/**
 * Prepares a photo for posting, on the phone: it is decoded (JPEG, PNG, WebP, and HEIC/HEIF where
 * Android reads it, from Android 9), turned upright, scaled to at most [MAX_EDGE] pixels and
 * re-encoded as JPEG. Re-encoding writes no metadata at all, so the camera make and model, the phone,
 * times and the GPS spot are gone; with `keepLocation` (Photo locations set to Public) only the GPS
 * spot is written back.
 */
object PhotoPreparer {
    /** The longest edge of a posted photo, in pixels. */
    const val MAX_EDGE = 2048

    const val JPEG_QUALITY = 85

    /** Larger files are refused before decoding. */
    const val MAX_INPUT_BYTES = 40 * 1024 * 1024

    fun prepare(
        resolver: ContentResolver,
        uri: Uri,
        keepLocation: Boolean,
    ): PreparedPhoto {
        val bytes =
            try {
                resolver.openInputStream(uri)?.use(::readCapped)
            } catch (e: SecurityException) {
                throw PhotoException("Pubky Maps may not read this photo (${e.message ?: "no permission"}).")
            } ?: throw PhotoException("The photo could not be read.")
        return prepare(bytes, keepLocation)
    }

    fun prepare(
        bytes: ByteArray,
        keepLocation: Boolean,
    ): PreparedPhoto {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        val orientation =
            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        val latLong = FloatArray(2).takeIf { exif?.getLatLong(it) == true }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoException(unreadable(bytes))
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw PhotoException(unreadable(bytes))
        val upright = upright(scaled(decoded), orientation)
        val jpeg =
            ByteArrayOutputStream().use { out ->
                if (!upright.compress(
                        Bitmap.CompressFormat.JPEG,
                        JPEG_QUALITY,
                        out,
                    )
                ) {
                    throw PhotoException("The photo could not be converted.")
                }
                out.toByteArray()
            }
        val keep = keepLocation && latLong != null
        return PreparedPhoto(
            bytes = if (keep && latLong != null) withLocation(jpeg, latLong[0].toDouble(), latLong[1].toDouble()) else jpeg,
            mimeType = "image/jpeg",
            width = upright.width,
            height = upright.height,
            keptLocation = keep,
            removedLocation = latLong != null && !keep,
        )
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_INPUT_BYTES) throw PhotoException("The photo is larger than ${MAX_INPUT_BYTES / (1024 * 1024)} MB.")
        }
        return out.toByteArray()
    }

    private fun unreadable(bytes: ByteArray): String =
        if (isHeif(bytes) && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            "This phone cannot read HEIC photos. Choose a JPEG photo."
        } else {
            "This file is not a photo Pubky Maps can read."
        }

    private fun isHeif(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            String(bytes, 8, 4, Charsets.US_ASCII) in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

    private fun scaled(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val factor = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).roundToInt().coerceAtLeast(1),
            (bitmap.height * factor).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** The bitmap as the photo's orientation tag says to show it (the tag itself is not written again). */
    private fun upright(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.setRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.setRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.setRotate(-90f)
            }

            else -> {
                return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** [jpeg] with only a GPS spot written into it. */
    private fun withLocation(
        jpeg: ByteArray,
        lat: Double,
        lon: Double,
    ): ByteArray {
        val file = File.createTempFile("photo", ".jpg")
        try {
            file.writeBytes(jpeg)
            val exif = ExifInterface(file.path)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, rational(lat))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, rational(lon))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** Degrees as EXIF's "d/1,m/1,s/10000". */
    internal fun rational(degrees: Double): String {
        val value = abs(degrees)
        val whole = value.toInt()
        val minutesFull = (value - whole) * 60
        val minutes = minutesFull.toInt()
        val seconds = ((minutesFull - minutes) * 60 * 10_000).roundToInt()
        return "$whole/1,$minutes/1,$seconds/10000"
    }
}
