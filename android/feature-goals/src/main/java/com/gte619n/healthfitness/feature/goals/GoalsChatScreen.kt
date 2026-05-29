package com.gte619n.healthfitness.feature.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.core.chat.ChatMessage
import com.gte619n.healthfitness.core.chat.ChatThread
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
    editorFor: (String) -> ProposalEdit?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        ChatTopBar(onBack = onBack)
        ChatThread(
            scope = GoalChatScope,
            messages = state.messages,
            streaming = state.streaming,
            onSend = onSend,
            error = state.error,
            modifier = Modifier.fillMaxWidth().weight(1f),
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
}

@Composable
private fun ChatTopBar(onBack: () -> Unit) {
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
        Column {
            Text("New goal", style = Hf.type.headingLg.copy(fontSize = 18.sp), color = Hf.colors.textPrimary)
            Text("Plan a roadmap with the assistant", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
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
    HealthFitnessTheme {
        GoalsChatScreen(
            state = GoalsChatUiState(
                messages = listOf(
                    ChatMessage.User(id = "u1", text = "Help me get my LDL under 100"),
                    proposalMsg,
                ),
                editors = mapOf("a1" to editor),
            ),
            onBack = {},
            onSend = {},
            onSave = {},
            onDiscard = {},
            onOpenGoal = {},
            editorFor = { if (it == "a1") editor else null },
        )
    }
}
