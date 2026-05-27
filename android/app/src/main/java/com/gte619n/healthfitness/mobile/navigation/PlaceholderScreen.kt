package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Per spec: each later IMPL replaces a `PlaceholderScreen` call with its
 * real screen. The body is intentionally minimal so the routing surface
 * is visible end-to-end even before any feature ships.
 */
@Composable
fun PlaceholderScreen(
    destination: String,
    nextImpl: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = destination,
            style = Hf.type.headingLg,
            color = Hf.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (nextImpl != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Ships in $nextImpl",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
