package com.junbingao.remotecontrol.android.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * The three ways a file reaches the composer — the photo library, the camera and the document
 * browser — each a launcher that presents nothing until it is told to. `docs/IOS.md` § "The
 * composer": the `+` menu's buttons set a flag and the presenters sit on the composer beside each
 * other, because a presenter built inside a menu leaves with the menu.
 */
class AttachmentPicker internal constructor(private val start: () -> Unit) {
    fun launch() = start()
}

/**
 * The system photo picker, images only, several at a time: the counterpart of `.photosPicker`
 * with `matching: .images`. It hands over only what the person chose, which is why the app holds
 * no photo permission at all.
 */
@Composable
fun rememberPhotoPicker(maxSelection: Int, onPicked: (List<Uri>) -> Unit): AttachmentPicker {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxSelection.coerceAtLeast(2)),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    return remember(launcher) {
        AttachmentPicker { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

/** The document browser, any kind of file, several at a time: `.fileImporter` over `.item`. */
@Composable
fun rememberDocumentPicker(onPicked: (List<Uri>) -> Unit): AttachmentPicker {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return remember(launcher) { AttachmentPicker { launcher.launch(arrayOf("*/*")) } }
}

/**
 * The camera app, taking one photo: the counterpart of `CameraCapture`
 * (`UIImagePickerController` with `.camera`). The shot is written to the one directory the file
 * provider shares, read back once and deleted, so no photo outlives the attachment it became.
 *
 * Launch it only after [rememberCameraAccessRequest] said yes: an app that declares the camera
 * permission may not hand the camera app a picture to take without holding it, and the iPhone
 * asks for the camera at this same moment.
 */
@Composable
fun rememberCameraCapture(onCapture: (ByteArray) -> Unit, onFailure: (Throwable) -> Unit = {}): AttachmentPicker {
    val context = LocalContext.current
    // The path survives the activity being rebuilt while the camera app is in front.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pending?.let(::File) ?: return@rememberLauncherForActivityResult
        pending = null
        try {
            if (saved && file.length() > 0) onCapture(file.readBytes())
        } catch (failure: Exception) {
            onFailure(failure)
        } finally {
            file.delete()
        }
    }
    return remember(launcher) {
        AttachmentPicker {
            val file = CameraShot.file(context)
            pending = file.path
            try {
                launcher.launch(CameraShot.uri(context, file))
            } catch (failure: ActivityNotFoundException) {
                pending = null
                file.delete()
                onFailure(failure)
            }
        }
    }
}

/** Where a camera shot is written: `cache/camera/`, the directory `res/xml/file_paths.xml` shares. */
internal object CameraShot {
    fun file(context: Context): File {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(directory, "shot-${System.currentTimeMillis()}.jpg")
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
