package com.gte619n.healthfitness.feature.workouts.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gte619n.healthfitness.domain.workouts.PendingUpload

/**
 * Converts an Android [Uri] (from `GetContent`) into a [PendingUpload],
 * resolving display name and MIME type via [android.content.ContentResolver].
 * Kept in the feature layer (needs framework `Uri`) — the domain
 * [PendingUpload] stays platform-free via the `source` lambda.
 */
fun Uri.toPendingUpload(context: Context): PendingUpload {
    val resolver = context.contentResolver
    val mime = resolver.getType(this) ?: "image/*"
    var name = "upload"
    runCatching {
        resolver.query(this, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.let { name = it }
            }
        }
    }
    val uri = this
    return PendingUpload(
        filename = name,
        mimeType = mime,
        source = { resolver.openInputStream(uri) ?: error("Cannot open $uri") },
    )
}
