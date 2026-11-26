package com.vakarux.instadownload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.media.MediaScannerConnection
import android.provider.DocumentsContract
import android.provider.MediaStore

internal data class SaveOutcome(val fellBackToDefaultFolder: Boolean)

internal fun saveToDownloads(
    item: MediaResult,
    context: Context,
    settings: AppSettings,
    treeUri: String? = settings.downloadTreeUri,
    onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
): SaveOutcome {
    val base = item.baseName.ifBlank { "instagram_${System.currentTimeMillis()}" }
    val fileName = if (item.isVideo) "$base.mp4" else "$base.jpg"
    val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
    val (uri, fellBack) = try {
        saveTo(item, context, treeUri, fileName, mimeType, onProgress) to false
    } catch (e: Exception) {
        if (treeUri == null || treeUri != settings.downloadTreeUri) throw e
        settings.downloadTreeUri = null
        settings.downloadFolderName = AppSettings.DEFAULT_FOLDER_NAME
        saveTo(item, context, null, fileName, mimeType, onProgress) to true
    }
    DownloadHistory.add(context, DownloadRecord(
        uri.toString(), fileName, mimeType, System.currentTimeMillis(), item.meta,
        item.width.takeIf { it < Int.MAX_VALUE } ?: 0, item.height, item.durationSec
    ))
    return SaveOutcome(fellBack)
}

/**
 * Writes [item] to [out]. Photos whose bytes were already fetched for an on-screen preview are
 * copied straight from the in-memory preview cache instead of being downloaded a second time,
 * so showing a preview no longer doubles bandwidth usage. Returns true when the cache was used.
 */
private fun writeMedia(
    item: MediaResult,
    out: java.io.OutputStream,
    onProgress: (Long, Long) -> Unit
): Boolean {
    if (!item.isVideo) {
        val cached = InstagramDownloader.copyCachedPreviewTo(item.url, out)
        if (cached >= 0L) {
            onProgress(cached, cached)
            return true
        }
    }
    InstagramDownloader.downloadToStream(item.url, out, onProgress)
    return false
}

private fun saveTo(
    item: MediaResult,
    context: Context,
    downloadTreeUri: String?,
    fileName: String,
    mimeType: String,
    onProgress: (Long, Long) -> Unit
): Uri {
    val isVideo = item.isVideo
    val base = fileName.substringBeforeLast('.')

    if (downloadTreeUri != null) {
        val treeUri = Uri.parse(downloadTreeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val fileUri = DocumentsContract.createDocument(
            context.contentResolver, parent, mimeType, fileName
        ) ?: throw Exception(context.getString(R.string.error_create_file_in_folder))
        context.contentResolver.openOutputStream(fileUri)?.use { out ->
            writeMedia(item, out, onProgress)
        } ?: throw Exception(context.getString(R.string.error_write_to_folder))
        if (!isVideo) tagJpeg(item.meta) {
            context.contentResolver.openFileDescriptor(fileUri, "rw")?.use(it)
        }
        return fileUri
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InstaDownload")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            item.meta?.let {
                put(MediaStore.MediaColumns.TITLE, it.caption?.take(100) ?: base)
                put(MediaStore.MediaColumns.ARTIST, it.username)
            }
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw Exception(context.getString(R.string.error_create_file_in_downloads))
        resolver.openOutputStream(uri)?.use { out ->
            writeMedia(item, out, onProgress)
        }
        if (!isVideo) tagJpeg(item.meta) { resolver.openFileDescriptor(uri, "rw")?.use(it) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        // Also mark the row as not pending for the media scanner so the file shows up in
        // the gallery immediately after the write finishes.
        resolver.update(uri, values, null, null)
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(fileName), arrayOf(mimeType), null) }
        return uri
    } else {
        try {
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaDownload"
            ).apply { mkdirs() }
            val file = java.io.File(dir, fileName)
            file.outputStream().use { writeMedia(item, it, onProgress) }
            if (!isVideo) tagJpeg(item.meta) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use(it)
            }
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null) }
            return Uri.fromFile(file)
        } catch (e: SecurityException) {
            throw Exception(context.getString(R.string.error_storage_permission_lost), e)
        }
    }
}

private fun tagJpeg(meta: PostMeta?, withFd: ((ParcelFileDescriptor) -> Unit) -> Unit) {
    if (meta == null) return
    runCatching {
        withFd { pfd ->
            val exif = android.media.ExifInterface(pfd.fileDescriptor)
            meta.username?.let { exif.setAttribute(android.media.ExifInterface.TAG_ARTIST, it) }
            meta.caption?.let { exif.setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, it) }
            exif.setAttribute(
                android.media.ExifInterface.TAG_USER_COMMENT,
                listOfNotNull(meta.url, meta.song?.let { "Song: $it" }).joinToString("\n")
            )
            if (meta.takenAtSec > 0) exif.setAttribute(
                android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(meta.takenAtSec * 1000))
            )
            exif.saveAttributes()
        }
    }
}
