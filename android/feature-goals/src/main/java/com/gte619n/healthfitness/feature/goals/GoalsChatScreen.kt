package com.gte619n.healthfitness.feature.goals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.core.chat.ChatMessage
import com.gte619n.healthfitness.core.chat.ChatThread
import com.gte619n.healthfitness.data.goals.ChatThreadResponse
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun GoalsChatRoute(
    onBack: () -> Unit,
    onOpenGoal: (String) -> Unit,
    viewModel: GoalsChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoalsChatScreen(
        state = state,
        onBack = onBack,
        onSend = viewModel::send,
        onSave = viewModel::commit,
        onDiscard = viewModel::discard,
        onOpenGoal = onOpenGoal,
        onDeleteThread = viewModel::deleteThread,
        editorFor = viewModel::editorFor,
    )
}

@Composable
fun GoalsChatScreen(
    state: GoalsChatUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSave: (messageId: String) -> Unit,
    onDiscard: (messageId: String) -> Unit,
    onOpenGoal: (String) -> Unit,
    onDeleteThread: (threadId: String) -> Unit,
    editorFor: (String) -> ProposalEdit?,
) {
    // Track whether the threads side-panel is open and which thread has a
    // pending delete confirmation dialog.
    var threadsOpen by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Hf.colors.canvas),
        ) {
            ChatTopBar(
                onBack = onBack,
                threadCount = state.threads.size,
                onToggleThreads = { threadsOpen = !threadsOpen },
            )
            ChatThread(
                scope = GoalChatScope,
                messages = state.messages,
                streaming = state.streaming,
                onSend = onSend,
                error = state.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { message ->
                // Tool-result slot: render the editable proposal card for this
                // assistant message, backed by the ViewModel's editor state.
                val editor = editorFor(message.id)
                if (editor != null) {
                    GoalProposalCard(
                        edit = editor,
                        committedGoalId = state.committedGoalIds[message.id],
                        saving = message.id in state.savingMessageIds,
                        onSave = { onSave(message.id) },
                        onDiscard = { onDiscard(message.id) },
                        onOpenGoal = onOpenGoal,
                    )
                }
            }
        }

        // Thread history panel slides in from the right.
        AnimatedVisibility(
            visible = threadsOpen,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            ThreadsPanel(
                threads = state.threads,
                activeThreadId = state.threadId,
                deletingThreadIds = state.deletingThreadIds,
                onClose = { threadsOpen = false },
                onDeleteRequest = { confirmDeleteId = it },
            )
        }

        // Confirmation dialog: shown when the user taps the trash icon.
        if (confirmDeleteId != null) {
            val targetId = confirmDeleteId!!
            AlertDialog(
                onDismissRequest = { confirmDeleteId = null },
                title = {
                    Text(
                        "Delete this conversation?",
                        style = Hf.type.headingSm,
                        color = Hf.colors.textPrimary,
                    )
                },
                text = {
                    Text(
                        "The thread and all its messages will be permanently removed.",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteThread(targetId)
                            confirmDeleteId = null
                            // Close the panel if the deleted thread was the last one.
                            if (state.threads.size <= 1) threadsOpen = false
                        },
                    ) {
                        Text("Delete", color = Hf.colors.alert)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteId = null }) {
                        Text("Cancel", color = Hf.colors.textSecondary)
                    }
                },
                containerColor = Hf.colors.surface,
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    onBack: () -> Unit,
    threadCount: Int,
    onToggleThreads: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.canvas)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back",
            tint = Hf.colors.textPrimary,
            modifier = Modifier
                .size(34.dp)
                .clickable { onBack() }
                .padding(7.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("New goal", style = Hf.type.headingLg.copy(fontSize = 18.sp), color = Hf.colors.textPrimary)
            Text("Plan a roadmap with the assistant", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
        }
        // Threads icon — shows count badge when there are past threads.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clickable(enabled = threadCount > 0, onClick = onToggleThreads)
                .alpha(if (threadCount > 0) 1f else 0.35f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = "Past conversations ($threadCount)",
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Slide-in panel listing all threads. Each row shows the thread title plus a
 * trash icon that fires [onDeleteRequest] (caller shows the confirmation dialog).
 */
@Composable
private fun ThreadsPanel(
    threads: List<ChatThreadResponse>,
    activeThreadId: String?,
    deletingThreadIds: Set<String>,
    onClose: () -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(Hf.colors.surface)
            .padding(top = 0.dp),
    ) {
        // Panel header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Conversations",
                style = Hf.type.headingSm.copy(fontSize = 15.sp),
                color = Hf.colors.textPrimary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Close",
                tint = Hf.colors.textSecondary,
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onClose() }
                    .padding(5.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderDefault),
        )

        if (threads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No past conversations",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(threads, key = { it.threadId }) { thread ->
                    ThreadRow(
                        thread = thread,
                        isActive = thread.threadId == activeThreadId,
                        isDeleting = thread.threadId in deletingThreadIds,
                        onDeleteRequest = onDeleteRequest,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(0.5.dp)
                            .background(Hf.colors.borderSubtle),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(
    thread: ChatThreadResponse,
    isActive: Boolean,
    isDeleting: Boolean,
    onDeleteRequest: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) Hf.colors.accentBg else Hf.colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title.ifBlank { "Untitled conversation" },
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = thread.updatedAt.take(10), // "YYYY-MM-DD"
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
        // Trash icon / progress spinner while deleting.
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Hf.colors.textTertiary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete conversation",
                    tint = Hf.colors.alert,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onDeleteRequest(thread.threadId) },
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun GoalsChatPreview() {
    val proposalMsg = ChatMessage.Assistant(
        id = "a1",
        text = "Here's a draft roadmap to get your **LDL under 100**. Edit anything, then save.",
        toolResult = GoalsFixtures.proposal,
        toolResultId = "a1",
    )
    val editor = ProposalEdit.from(GoalsFixtures.proposal)
    val fakeThreads = listOf(
        ChatThreadResponse("t1", "Lower LDL under 100", "2026-05-01T10:00:00Z", "2026-05-20T10:00:00Z"),
        ChatThreadResponse("t2", "12-week strength base", "2026-04-01T10:00:00Z", "2026-04-15T10:00:00Z"),
    )
    HealthFitnessTheme {
        GoalsChatScreen(
            state = GoalsChatUiState(
                messages = listOf(
                    ChatMessage.User(id = "u1", text = "Help me get my LDL under 100"),
                    proposalMsg,
                ),
                editors = mapOf("a1" to editor),
                threads = fakeThreads,
            ),
            onBack = {},
            onSend = {},
            onSave = {},
            onDiscard = {},
            onOpenGoal = {},
            onDeleteThread = {},
            editorFor = { if (it == "a1") editor else null },
        )
    }
}
