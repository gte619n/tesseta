package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun RecentFeed(
    entries: List<DashboardFallbacks.LogEntry>,
    showViewAll: Boolean,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Recent")
                if (showViewAll) {
                    Text(
                        text = "VIEW ALL",
                        style = Hf.type.capsSm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            entries.forEachIndexed { i, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                if (entry.tone == Tone.Good) Hf.colors.goodBg else Hf.colors.canvas,
                                RoundedCornerShape(6.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = if (entry.tone == Tone.Good) Hf.colors.accentDim else Hf.colors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = Hf.type.bodyMd.copy(fontSize = 12.sp),
                            color = Hf.colors.textPrimary,
                        )
                        if (entry.meta != null) {
                            Text(
                                text = entry.meta,
                                style = Hf.type.monoSm,
                                color = Hf.colors.textTertiary,
                            )
                        }
                    }
                    Text(
                        text = entry.time,
                        style = Hf.type.monoSm.copy(fontSize = 11.sp),
                        color = Hf.colors.textSecondary,
                    )
                }
                if (i != entries.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Hf.colors.borderSubtle),
                    )
                }
            }
        }
    }
}
