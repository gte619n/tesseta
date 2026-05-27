package com.gte619n.healthfitness.feature.workouts.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Cover-photo picker + display. Wraps
 * `ActivityResultContracts.GetContent("image-slash-star")`; the
 * launcher result is a content `Uri` that the caller is expected to
 * convert via `UriUploads.from(contentResolver, uri)` before handing
 * to the repository.
 *
 * Shows an indeterminate progress bar while [uploading] is true.
 */
@Composable
fun CoverPhotoUploader(
    currentUrl: String?,
    uploading: Boolean,
    onPickImage: (Uri) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let(onPickImage) },
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Hf.colors.canvasMuted)
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
                .clickable { launcher.launch(IMAGE_MIME) },
        ) {
            if (currentUrl != null) {
                HfAsyncImage(
                    model = currentUrl,
                    contentDescription = "Cover photo",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = Hf.colors.textTertiary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Tap to add cover photo",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            if (uploading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    color = Hf.colors.accent,
                    trackColor = Hf.colors.canvasMuted,
                )
            }
        }
        if (currentUrl != null && !uploading) {
            Spacer(Modifier.size(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = Hf.colors.alert,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Remove photo", color = Hf.colors.alert, style = Hf.type.bodySm)
                }
            }
        }
        // Note: a stable padding below the card matches the form's
        // visual rhythm — the screen wraps in a scroll container.
        Spacer(Modifier.padding(top = 4.dp))
    }
}

private const val IMAGE_MIME = "image/*"
