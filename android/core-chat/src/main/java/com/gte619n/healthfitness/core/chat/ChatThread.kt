package com.gte619n.healthfitness.core.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * The shared chat surface (assumption 16): a reverse-layout message list with
 * a bottom-pinned composer and an empty-state prompt list. The proposal /
 * tool-result is rendered by [toolResultSlot] so this module stays free of any
 * module's domain types — feature-goals passes in the GoalProposalCard.
 *
 * Styled with core-ui tokens (olive/oatmeal), not default Material colors.
 */
@Composable
fun ChatThread(
    scope: ChatScope,
    messages: List<ChatMessage>,
    streaming: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    toolResultSlot: @Composable (message: ChatMessage.Assistant) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message (index 0 in a reverse layout) whenever
    // the list grows or the last message's content changes during streaming.
    val newestSignature = messages.firstOrNull()?.let { msg ->
        when (msg) {
            is ChatMessage.Assistant -> "${msg.id}:${msg.text.length}:${msg.toolResult != null}"
            is ChatMessage.User -> msg.id
        }
    }
    LaunchedEffect(messages.size, newestSignature) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        // The window resizes for the IME, so imePadding() would double-count and
        // float the composer a full keyboard-height too high. We only need to
        // clear the navigation bar so the composer/send button sit just above the
        // keyboard (open) or the nav bar (closed).
        modifier = modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .navigationBarsPadding(),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(prompts = scope.suggestedPrompts, onSend = onSend)
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // reverseLayout: render newest-first; messages are stored
                    // oldest-first, so the list is consumed in reverse.
                    if (streaming) {
                        item(key = "typing") { TypingIndicator() }
                    }
                    items(
                        count = messages.size,
                        key = { messages[messages.lastIndex - it].id },
                    ) { i ->
                        when (val msg = messages[messages.lastIndex - i]) {
                            is ChatMessage.User -> UserMessage(msg.text)
                            is ChatMessage.Assistant -> AssistantMessage(msg, toolResultSlot)
                        }
                    }
                }
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = Hf.type.bodySm,
                color = Hf.colors.alert,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(enabled = !streaming, onSend = onSend)
    }
}

@Composable
private fun UserMessage(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .background(Hf.colors.accent, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(text, style = Hf.type.bodyMd, color = Hf.colors.textInverse)
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage.Assistant,
    toolResultSlot: @Composable (ChatMessage.Assistant) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.text.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .background(Hf.colors.surface, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    Markdown(
                        content = message.text,
                        colors = markdownColor(text = Hf.colors.textPrimary),
                        typography = markdownTypography(
                            text = Hf.type.bodyMd.copy(color = Hf.colors.textPrimary),
                        ),
                    )
                }
            }
        }
        if (message.toolResult != null) {
            Spacer(Modifier.height(if (message.text.isBlank()) 0.dp else 8.dp))
            toolResultSlot(message)
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .background(Hf.colors.surface, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            val transition = rememberInfiniteTransition(label = "typing")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 150, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .graphicsLayer { this.alpha = alpha }
                            .background(Hf.colors.textTertiary, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(prompts: List<String>, onSend: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "Plan a new goal",
            style = Hf.type.headingLg.copy(fontSize = 19.sp),
            color = Hf.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Describe what you want to achieve and I'll draft a phased roadmap you can edit before saving.",
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        prompts.forEach { prompt ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Hf.colors.surface, RoundedCornerShape(10.dp))
                    .clickable { onSend(prompt) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(prompt, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && enabled) {
            onSend(trimmed)
            text = ""
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message", style = Hf.type.bodyMd, color = Hf.colors.textTertiary) },
            textStyle = LocalTextStyle.current.merge(Hf.type.bodyMd),
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Hf.colors.canvas,
                unfocusedContainerColor = Hf.colors.canvas,
                disabledContainerColor = Hf.colors.canvas,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Hf.colors.accent,
                focusedTextColor = Hf.colors.textPrimary,
                unfocusedTextColor = Hf.colors.textPrimary,
            ),
        )
        val sendActive = text.isNotBlank() && enabled
        Box(
            modifier = Modifier
                .size(42.dp)
                .alpha(if (sendActive) 1f else 0.4f)
                .background(Hf.colors.accent, RoundedCornerShape(12.dp))
                .clickable(enabled = sendActive) { submit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Send",
                tint = Hf.colors.textInverse,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
