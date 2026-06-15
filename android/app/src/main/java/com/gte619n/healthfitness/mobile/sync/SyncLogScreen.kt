package com.gte619n.healthfitness.mobile.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.data.sync.SyncDiagnostics
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * IMPL-STAB (Workstream B) — a debug-only window into the in-memory sync error
 * ring ([SyncDiagnostics]). Reachable from the More hub. Turns the old "blank
 * no-op" failure mode into something a developer (or a support session) can read:
 * what failed, against which table/entity, and the server's own message.
 */
@HiltViewModel
class SyncLogViewModel @Inject constructor(
    val diagnostics: SyncDiagnostics,
) : ViewModel() {
    fun clear() = diagnostics.clearAll()
}

@Composable
fun SyncLogScreen(
    onBack: () -> Unit,
    viewModel: SyncLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.diagnostics.recent.collectAsStateWithLifecycle()
    val timeFmt = rememberTimeFormat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Hf.colors.textPrimary,
                )
            }
            SectionTitle(text = "Sync log")
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                TextButton(onClick = viewModel::clear) { Text("Clear") }
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = "No sync errors recorded this session.",
                style = Hf.type.bodyMd,
                color = Hf.colors.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            items(entries) { e ->
                HfCard(transparent = true, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = e.source + (e.httpCode?.let { " · HTTP $it" } ?: " · network"),
                                style = Hf.type.capsSm,
                                color = if (e.terminal) Hf.colors.alert else Hf.colors.warn,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = timeFmt.format(Date(e.atMillis)),
                                style = Hf.type.capsSm,
                                color = Hf.colors.textTertiary,
                            )
                        }
                        e.table?.let {
                            Text(
                                text = it + (e.entityId?.let { id -> " / $id" } ?: ""),
                                style = Hf.type.bodySm,
                                color = Hf.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = e.message,
                            style = Hf.type.bodyMd,
                            color = Hf.colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberTimeFormat(): SimpleDateFormat =
    androidx.compose.runtime.remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
