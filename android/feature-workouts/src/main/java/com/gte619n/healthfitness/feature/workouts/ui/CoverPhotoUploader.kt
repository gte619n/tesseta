package com.gte619n.healthfitness.feature.workouts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.PendingUpload
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// Cover-photo card with an image picker (IMPL-AND-06). Renders the current
// photo or a placeholder; the picked Uri is converted to a PendingUpload via
// UriUploads and handed to onPick. Shows an indeterminate bar while uploading.
@Composable
fun CoverPhotoUploader(
    currentUrl: String?,
    onPick: (PendingUpload) -> Unit,
    onDelete: () -> Unit,
    uploading: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPick(uri.toPendingUpload(context))
    }
    val imageMime = "image/" + "*"

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Hf.colors.canvasMuted)
                .clickable(enabled = !uploading) { launcher.launch(imageMime) },
            contentAlignment = Alignment.Center,
        ) {
            if (currentUrl != null) {
                HfAsyncImage(
                    model = currentUrl,
                    contentDescription = "Cover photo",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else {
                Icon(
                    Icons.Filled.AddPhotoAlternate,
                    contentDescription = "Add cover photo",
                    tint = Hf.colors.textQuaternary,
                )
            }
        }
        if (uploading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Hf.colors.accent)
        }
        if (currentUrl != null && !uploading) {
            TextButton(onClick = onDelete) {
                Text("Remove photo", style = Hf.type.bodySm, color = Hf.colors.alert)
            }
        }
    }
}
