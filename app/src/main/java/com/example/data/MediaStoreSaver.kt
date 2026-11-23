package com.example.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.domain.AppLanguageController
import com.example.domain.FfmpegMath
import java.io.File
import java.io.FileInputStream

data class SavedMediaResult(
    val uri: Uri,
    val displayPath: String,
    val sizeBytes: Long,
    val mimeType: String
)

object MediaStoreSaver {

    private const val SUBFOLDER = "FFmpeg"

    /**
     * Saves a finished file into Movies/FFmpeg (for video) or Music/FFmpeg /
     * Download/FFmpeg (for audio/other) via MediaStore on API 29+ so it immediately appears
     * in Android Gallery & Recent Files without needing storage permissions or external scanners.
     */
    fun saveToPublicStorage(
        context: Context,
        sourceFile: File,
        desiredFileName: String
    ): SavedMediaResult {
        val mimeType = FfmpegMath.resolveMimeType(desiredFileName)
        val isVideo = FfmpegMath.isVideoFileName(desiredFileName)
        val isAudio = mimeType.startsWith("audio/")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Try primary collection first (Movies/FFmpeg for video, Music/FFmpeg for audio)
            val candidates: List<Pair<Uri, String>> = when {
                isVideo -> listOf(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER",
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
                )
                isAudio -> listOf(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/$SUBFOLDER",
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
                )
                else -> listOf(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
                )
            }

            var lastError: Throwable? = null
            for ((collection, relativePath) in candidates) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, desiredFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val s = AppLanguageController.currentStrings()
                    val insertedUri = resolver.insert(collection, values)
                        ?: throw IllegalStateException(s.errMediaStoreInsertFailed)

                    try {
                        resolver.openOutputStream(insertedUri)?.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(out, bufferSize = 256 * 1024)
                            }
                        } ?: throw IllegalStateException(s.errOutputStreamFailed)

                        val completeValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        resolver.update(insertedUri, completeValues, null, null)

                        return SavedMediaResult(
                            uri = insertedUri,
                            displayPath = "$relativePath/$desiredFileName",
                            sizeBytes = sourceFile.length(),
                            mimeType = mimeType
                        )
                    } catch (e: Throwable) {
                        runCatching { resolver.delete(insertedUri, null, null) }
                        throw e
                    }
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            val s = AppLanguageController.currentStrings()
            throw IllegalStateException(
                s.errSaveFailed(lastError?.message ?: s.errUnknownMediaStore),
                lastError
            )
        } else {
            // API 24-28 fallback: /sdcard/Movies/FFmpeg + MediaScannerConnection
            @Suppress("DEPRECATION")
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                SUBFOLDER
            )
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val targetFile = File(moviesDir, desiredFileName)
            FileInputStream(sourceFile).use { input ->
                targetFile.outputStream().use { out ->
                    input.copyTo(out, bufferSize = 256 * 1024)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
            return SavedMediaResult(
                uri = uri,
                displayPath = "Movies/$SUBFOLDER/$desiredFileName",
                sizeBytes = targetFile.length(),
                mimeType = mimeType
            )
        }
    }
}
