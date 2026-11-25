package to.pubky.maps.android.photos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.pubky.maps.write.PhotoInput
import to.pubky.maps.write.PhotoLaunchers
import to.pubky.maps.write.PhotoResult
import to.pubky.maps.write.PreparedPhoto
import java.io.File

/** Hands camera photos to the camera app: a file in the cache's camera/ folder (res/xml/photo_files.xml). */
class PhotoFileProvider : FileProvider()

/** Photos from Android's photo picker (no storage permission) or the camera app, prepared by [PhotoPreparer]. */
val AndroidPhotoInput = PhotoInput(rememberLaunchers = { onResult -> rememberAndroidPhotoLaunchers(onResult) })

@Composable
private fun rememberAndroidPhotoLaunchers(onResult: (PhotoResult) -> Unit): PhotoLaunchers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onResult)
    var keepLocation by rememberSaveable { mutableStateOf(false) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun prepare(read: () -> PreparedPhoto) {
        report(PhotoResult.Preparing)
        scope.launch {
            val result =
                withContext(Dispatchers.Default) {
                    try {
                        PhotoResult.Ready(read())
                    } catch (e: PhotoException) {
                        PhotoResult.Failed(e.message)
                    } catch (e: OutOfMemoryError) {
                        PhotoResult.Failed("The photo is too large for this phone (${e.message ?: "out of memory"}).")
                    } catch (
                        // Anything else a photo app or a decoder throws must not take the app down.
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        PhotoResult.Failed("The photo could not be read (${e.message ?: e::class.simpleName}).")
                    }
                }
            report(result)
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri == null) {
                report(PhotoResult.Cancelled)
            } else {
                prepare { PhotoPreparer.prepare(context.contentResolver, uri, keepLocation) }
            }
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val file = cameraFile?.let(::File)
            cameraFile = null
            if (!taken || file == null || !file.isFile) {
                file?.delete()
                report(PhotoResult.Cancelled)
            } else {
                prepare {
                    try {
                        PhotoPreparer.prepare(context.contentResolver, Uri.fromFile(file), keepLocation)
                    } finally {
                        file.delete()
                    }
                }
            }
        }
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    return PhotoLaunchers(
        choose = { keep ->
            keepLocation = keep
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        take =
            if (hasCamera) {
                { keep ->
                    keepLocation = keep
                    val file = cameraFileFor(context)
                    cameraFile = file.path
                    try {
                        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.photos", file))
                    } catch (_: ActivityNotFoundException) {
                        cameraFile = null
                        report(PhotoResult.Failed("There is no camera app on this phone."))
                    }
                }
            } else {
                null
            },
    )
}

/** A new file for the camera app to write a photo into. */
private fun cameraFileFor(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, "photo-${System.currentTimeMillis()}.jpg")
}
