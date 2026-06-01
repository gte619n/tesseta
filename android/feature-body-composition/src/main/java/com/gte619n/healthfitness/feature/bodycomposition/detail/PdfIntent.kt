package com.gte619n.healthfitness.feature.bodycomposition.detail

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import java.io.File

/**
 * Downloads the DEXA PDF, writes it to `cacheDir/dexa-pdfs/dexa-{id}.pdf` and
 * launches a system ACTION_VIEW intent over a FileProvider URI.
 *
 * The `<provider>` with authority `"${context.packageName}.fileprovider"` and
 * its `res/xml` paths element are declared in the app manifest (added
 * separately — see IMPL-AND-05 report).
 */
suspend fun viewDexaPdf(context: Context, repo: DexaScanRepository, scanId: String) {
    val bytes = repo.downloadPdf(scanId)
    val cacheDir = File(context.cacheDir, "dexa-pdfs").apply { mkdirs() }
    val file = File(cacheDir, "dexa-$scanId.pdf").apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}
