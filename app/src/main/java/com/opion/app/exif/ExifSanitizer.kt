package com.opion.app.exif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.opion.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

enum class Strategy {
    STRIP_TAGS,
    REENCODE
}

data class SanitizeResult(
    val ok: Boolean,
    val file: File? = null,
    val shareUri: Uri? = null,
    val fileName: String = "",
    val mimeType: String = "image/jpeg",
    val removedTags: Int = 0,
    val strategy: Strategy? = null,
    val note: String = "",
    val error: String? = null
) {
    companion object {
        fun fail(message: String) = SanitizeResult(ok = false, error = message)
    }
}

object ExifSanitizer {

    private const val MAX_DIMENSION = 4096
    private const val JPEG_QUALITY = 92
    private const val BUFFER = 64 * 1024

    private val SENSITIVE_TAGS: List<String> = listOf(
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_MAKER_NOTE,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        "XPTitle",
        "XPComment",
        "XPAuthor",
        "XPKeywords",
        "XPSubject",
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE
    )

    private val EXTRA_TAGS: List<String> = listOf(
        "Xmp",
        "GPSImgDirection",
        "GPSImgDirectionRef"
    )

    suspend fun sanitize(context: Context, source: Uri): SanitizeResult =
        withContext(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "work").apply { mkdirs() }
            val cleanDir = File(context.cacheDir, "clean").apply { mkdirs() }

            workDir.listFiles()?.forEach { it.delete() }
            cleanDir.listFiles()?.forEach { it.delete() }

            val defaultName = context.getString(R.string.exif_default_name)
            val defaultBase = context.getString(R.string.exif_default_basename)

            val displayName = queryDisplayName(context, source) ?: defaultName
            val baseName = displayName.substringBeforeLast('.', displayName).ifBlank { defaultBase }
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            val mime = (context.contentResolver.getType(source) ?: "image/jpeg").lowercase(Locale.US)

            val isJpeg = mime.contains("jpeg") || mime.contains("jpg") ||
                    ext == "jpg" || ext == "jpeg"

            val raw = File(workDir, "raw.bin")
            try {
                context.contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(raw).use { out -> input.copyTo(out, BUFFER) }
                } ?: return@withContext SanitizeResult.fail(
                    context.getString(R.string.exif_err_open)
                )
            } catch (t: Throwable) {
                return@withContext SanitizeResult.fail(
                    context.getString(
                        R.string.exif_err_read,
                        t.message ?: t.javaClass.simpleName
                    )
                )
            }

            if (raw.length() == 0L) {
                raw.delete()
                return@withContext SanitizeResult.fail(
                    context.getString(R.string.exif_err_empty)
                )
            }

            var removed = 0
            var strategy: Strategy? = null

            if (isJpeg) {
                strategy = try {
                    removed = stripExifTags(raw)
                    Strategy.STRIP_TAGS
                } catch (t: Throwable) {
                    null
                }
            }

            val outFile: File
            val outMime: String

            if (strategy == Strategy.STRIP_TAGS) {
                outMime = "image/jpeg"
                outFile = File(cleanDir, "${baseName}_clean.jpg")
                if (!raw.renameTo(outFile)) {
                    raw.copyTo(outFile, overwrite = true)
                    raw.delete()
                }
            } else {
                outMime = "image/jpeg"
                outFile = File(cleanDir, "${baseName}_clean.jpg")
                val ok = reencode(raw, outFile)
                raw.delete()
                if (!ok) {
                    outFile.delete()
                    return@withContext SanitizeResult.fail(
                        context.getString(R.string.exif_err_format)
                    )
                }
                strategy = Strategy.REENCODE
                removed = -1
            }

            val shareUri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
            } catch (t: Throwable) {
                outFile.delete()
                return@withContext SanitizeResult.fail(
                    context.getString(R.string.exif_err_share)
                )
            }

            SanitizeResult(
                ok = true,
                file = outFile,
                shareUri = shareUri,
                fileName = outFile.name,
                mimeType = outMime,
                removedTags = removed,
                strategy = strategy,
                note = if (strategy == Strategy.REENCODE)
                    context.getString(R.string.exif_note_reencode)
                else ""
            )
        }

    fun saveToGallery(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Opion"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out, BUFFER) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun stripExifTags(file: File): Int {
        val exif = ExifInterface(file.absolutePath)
        var removed = 0

        for (tag in SENSITIVE_TAGS + EXTRA_TAGS) {
            try {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    removed++
                }
            } catch (_: Throwable) {
            }
        }

        exif.saveAttributes()
        return removed
    }

    private fun reencode(src: File, dst: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            false
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(src.absolutePath, opts)
            if (decoded == null) {
                false
            } else {
                val sourceExif = try {
                    ExifInterface(src.absolutePath)
                } catch (_: Throwable) {
                    null
                }
                val upright = applyOrientation(decoded, sourceExif)

                val ok = FileOutputStream(dst).use { out ->
                    upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (upright !== decoded) upright.recycle()
                decoded.recycle()
                ok
            }
        }
    } catch (t: Throwable) {
        false
    }

    private fun applyOrientation(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        if (exif == null) return bitmap
        val degrees = try {
            exif.rotationDegrees
        } catch (_: Throwable) {
            0
        }
        val flipped = try {
            exif.isFlipped
        } catch (_: Throwable) {
            false
        }
        if (degrees == 0 && !flipped) return bitmap

        val matrix = Matrix()
        if (flipped) matrix.postScale(-1f, 1f)
        if (degrees != 0) matrix.postRotate(degrees.toFloat())

        return try {
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (out !== bitmap) out else bitmap
        } catch (t: Throwable) {
            bitmap
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, max: Int): Int {
        var sample = 1
        val longSide = maxOf(width, height)
        while (longSide / sample > max) sample *= 2
        return sample
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
    } catch (_: Throwable) {
        null
    }
}
