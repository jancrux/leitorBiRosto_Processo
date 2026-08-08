package pt.leiturabi.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.leiturabi.data.PendingFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pasta de trabalho para ficheiros ainda por enviar. */
fun Context.pendingDir(): File = File(cacheDir, "pendentes").apply { mkdirs() }

fun newCaptureFile(context: Context): File {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
    return File(context.pendingDir(), "FOTO_$stamp.jpg")
}

val deviceName: String
    get() = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replaceFirstChar { it.uppercase() }

/**
 * Copia um ficheiro escolhido pelo utilizador (content://) para a cache da app,
 * porque o OkHttp precisa de um [File] real para o envio multipart.
 */
suspend fun copyToCache(context: Context, uri: Uri): PendingFile? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "ficheiro"
    var size = 0L

    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
        }
    }

    val mime = resolver.getType(uri) ?: guessMime(name)
    val destination = File(context.pendingDir(), "${System.currentTimeMillis()}_${name.sanitized()}")

    try {
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null
    } catch (_: Exception) {
        return@withContext null
    }

    PendingFile(
        name = name,
        mime = mime,
        sizeBytes = if (size > 0) size else destination.length(),
        localPath = destination.absolutePath,
    )
}

fun fileToPending(file: File, mime: String = "image/jpeg"): PendingFile =
    PendingFile(name = file.name, mime = mime, sizeBytes = file.length(), localPath = file.absolutePath)

private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

private fun guessMime(name: String): String = when {
    name.endsWith(".pdf", true) -> "application/pdf"
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".webp", true) -> "image/webp"
    else -> "image/jpeg"
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Ultima posicao conhecida, sem dependencias do Google Play Services. */
@SuppressLint("MissingPermission")
fun lastKnownLocation(context: Context): Location? {
    val granted = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }
}
