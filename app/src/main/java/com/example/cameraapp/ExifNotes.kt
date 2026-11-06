package com.example.cameraapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.Normalizer
import kotlin.math.roundToInt

data class PhotoItem(val uri: Uri, val note: String)

/**
 * Sesli açıklamayı JPEG'in EXIF alanlarına yazar / okur.
 *
 * ExifInterface metni yalnızca ASCII yazabildiği için:
 *  - UserComment      : Türkçe karakterleri \uXXXX ile kodlanmış, kayıpsız sürüm
 *  - ImageDescription : diğer uygulamalarda okunabilsin diye ASCII'ye çevrilmiş sürüm
 */
object ExifNotes {

    fun encode(text: String): String = buildString {
        for (c in text) {
            if (c.code in 32..126 && c != '\\') append(c)
            else append("\\u%04x".format(c.code))
        }
    }

    fun decode(s: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    fun toAscii(s: String): String {
        val pre = s.replace('ı', 'i').replace('İ', 'I')
        return Normalizer.normalize(pre, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\x20-\\x7E]"), "?")
    }

    fun write(ctx: Context, uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, encode(text))
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, toAscii(text))
            exif.saveAttributes()
        }
        true
    } catch (e: Exception) {
        false
    }

    fun read(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val raw = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.takeIf { it.isNotBlank() }
                ?: exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            raw?.let { decode(it).trim() }?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }

    /** "META VERİ" sekmesinde gösterilecek özet. */
    fun describe(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val e = ExifInterface(stream)
            val lines = mutableListOf<String>()
            (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))
                ?.let { lines += "Tarih: " + it.replaceFirst(":", ".").replaceFirst(":", ".") }
            val dev = listOfNotNull(
                e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)
            ).joinToString(" ")
            if (dev.isNotBlank()) lines += "Cihaz: $dev"
            val w = e.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = e.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) lines += "Çözünürlük: $w × $h"
            e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { lines += "ISO: $it" }
            val exp = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            if (exp > 0) lines += if (exp < 1) "Pozlama: 1/${(1 / exp).roundToInt()} sn" else "Pozlama: $exp sn"
            val f = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (f > 0) lines += "Diyafram: f/$f"
            val fl = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            if (fl > 0) lines += "Odak uzaklığı: ${"%.1f".format(fl)} mm"
            if (!e.getAttribute(ExifInterface.TAG_USER_COMMENT).isNullOrBlank()) lines += "Sesli not: Var ✔"
            if (lines.isEmpty()) "EXIF verisi bulunamadı." else lines.joinToString("\n")
        } ?: "EXIF okunamadı."
    } catch (ex: Exception) {
        "EXIF okunamadı."
    }
}

object PhotoStore {

    /** Uygulamanın Pictures/CameraApp klasörüne kaydettiği tüm fotoğraflar (yeniden eskiye). */
    fun allUris(ctx: Context): List<Uri> {
        val out = mutableListOf<Uri>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/CameraApp%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out += ContentUris.withAppendedId(collection, c.getLong(idCol))
        }
        return out
    }

    /** Yalnızca sesli açıklaması olan fotoğraflar. */
    fun annotated(ctx: Context): List<PhotoItem> =
        allUris(ctx).mapNotNull { uri -> ExifNotes.read(ctx, uri)?.let { PhotoItem(uri, it) } }
}
