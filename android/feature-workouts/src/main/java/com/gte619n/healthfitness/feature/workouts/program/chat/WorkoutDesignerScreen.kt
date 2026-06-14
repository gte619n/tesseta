package com.gte619n.healthfitness.feature.workouts.program.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.core.chat.ChatThread
import com.gte619n.healthfitness.data.workouts.program.chat.ProgramChatThreadResponse
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.feature.workouts.program.dayOfWeekLabel
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.sync.OfflineNotice
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun WorkoutDesignerRoute(
    onBack: () -> Unit,
    onOpenProgram: (String) -> Unit,
    viewModel: WorkoutDesignerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.isOnline.collectAsStateWithLifecycle()
    WorkoutDesignerScreen(
        state = state,
        online = online,
        onBack = onBack,
        onToggleDay = viewModel::toggleTrainingDay,
        onSetDayGym = viewModel::setDayGym,
        onSetGoal = viewModel::setGoal,
        onSend = { if (online) viewModel.send(it) },
        onSave = viewModel::commit,
        onDiscard = viewModel::discard,
        onOpenProgram = onOpenProgram,
        onDeleteThread = viewModel::deleteThread,
        editorFor = viewModel::editorFor,
        issuesFor = viewModel::issuesFor,
    )
}

@Composable
fun WorkoutDesignerScreen(
    state: WorkoutDesignerUiState,
    onBack: () -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onSetDayGym: (DayOfWeek, String) -> Unit,
    onSetGoal: (String?) -> Unit,
    onSend: (String) -> Unit,
    onSave: (messageId: String) -> Unit,
    onDiscard: (messageId: String) -> Unit,
    onOpenProgram: (String) -> Unit,
    onDeleteThread: (threadId: String) -> Unit,
    editorFor: (String) -> ProgramProposalEdit?,
    issuesFor: (String) -> List<String>,
    online: Boolean = true,
) {
    var threadsOpen by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Hf.colors.canvas)) {
            HfScreenHeader(
                title = "Design a program",
                subtitle = "Plan a periodized program with the coach",
                onBack = onBack,
                trailing = {
                    val threadCount = state.threads.size
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(enabled = threadCount > 0) { threadsOpen = !threadsOpen }
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
                },
            )
            if (!online) {
                OfflineNotice(
                    message = "Designing a program uses AI and needs an internet connection.",
                )
            }

            if (!state.started) {
                // Pre-chat setup form: training days + gym per day + optional goal.
                DesignerSetupForm(
                    setup = state.setup,
                    trt = state.trt,
                    onToggleDay = onToggleDay,
                    onSetDayGym = onSetDayGym,
                    onSetGoal = onSetGoal,
                    onStart = onSend,
                    error = state.error,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                ChatThread(
                    scope = WorkoutDesignerChatScope,
                    messages = state.messages,
                    streaming = state.streaming,
                    onSend = onSend,
                    error = state.error,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { message ->
                    val editor = editorFor(message.id)
                    if (editor != null) {
                        ProgramProposalCard(
                            edit = editor,
                            issues = issuesFor(message.id),
                            committedProgramId = state.committedProgramIds[message.id],
                            saving = message.id in state.savingMessageIds,
                            onSave = { onSave(message.id) },
                            onDiscard = { onDiscard(message.id) },
                            onOpenProgram = onOpenProgram,
                        )
                    }
                }
            }
        }

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

        if (confirmDeleteId != null) {
            val targetId = confirmDeleteId!!
            AlertDialog(
                onDismissRequest = { confirmDeleteId = null },
                title = { Text("Delete this conversation?", style = Hf.type.headingSm, color = Hf.colors.textPrimary) },
                text = {
                    Text(
                        "The thread and all its messages will be permanently removed.",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteThread(targetId)
                        confirmDeleteId = null
                        if (state.threads.size <= 1) threadsOpen = false
                    }) { Text("Delete", color = Hf.colors.alert) }
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
private fun DesignerSetupForm(
    setup: DesignerSetupState,
    trt: com.gte619n.healthfitness.domain.workouts.trt.TrtContext?,
    onToggleDay: (DayOfWeek) -> Unit,
    onSetDayGym: (DayOfWeek, String) -> Unit,
    onSetGoal: (String?) -> Unit,
    onStart: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    var brief by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (setup.gyms.isEmpty() && !setup.loading) {
            HfCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Add a gym first", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The coach designs around the equipment at your gyms. Add one under Workouts › Gyms, then come back.",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
        }

        // TRT/labs panel — surfaced up front when on TRT (informs the brief).
        trt?.let { if (it.shouldShow) TrtLabsPanel(it) }

        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                CapsLabel("Training days", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in setup.trainingDays
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (selected) Hf.colors.accent else Hf.colors.canvas,
                                    RoundedCornerShape(9.dp),
                                )
                                .border(
                                    0.5.dp,
                                    if (selected) Hf.colors.accent else Hf.colors.borderDefault,
                                    RoundedCornerShape(9.dp),
                                )
                                .clickable { onToggleDay(day) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                dayOfWeekLabel(day).take(1),
                                style = Hf.type.bodyMd,
                                color = if (selected) Hf.colors.textInverse else Hf.colors.textPrimary,
                            )
                        }
                    }
                }

                if (setup.trainingDays.isNotEmpty() && setup.gyms.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    CapsLabel("Gym per day", color = Hf.colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    DayOfWeek.entries.filter { it in setup.trainingDays }.forEach { day ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dayOfWeekLabel(day),
                                style = Hf.type.bodyMd,
                                color = Hf.colors.textPrimary,
                                modifier = Modifier.width(48.dp),
                            )
                            GymDropdown(
                                gyms = setup.gyms,
                                selectedId = setup.dayLocations[day],
                                onSelect = { onSetDayGym(day, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (setup.goals.isNotEmpty()) {
            HfCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    CapsLabel("Link a goal (optional)", color = Hf.colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    GoalDropdown(
                        goals = setup.goals,
                        selectedId = setup.goalId,
                        onSelect = onSetGoal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                CapsLabel("Your brief", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                BriefField(value = brief, onChange = { brief = it })
                Spacer(Modifier.height(4.dp))
                Text(
                    "e.g. \"5 weeks, 3 days then 4 days, ease me back in, I'm restarting TRT.\"",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            }
        }

        if (error != null) {
            Text(error, style = Hf.type.bodySm, color = Hf.colors.alert)
        }

        val canStart = setup.isReady && brief.isNotBlank()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (canStart) 1f else 0.4f)
                .background(Hf.colors.accent, RoundedCornerShape(10.dp))
                .clickable(enabled = canStart) { onStart(brief.trim()) }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Start designing", style = Hf.type.bodyMd, color = Hf.colors.textInverse)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BriefField(value: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .background(Hf.colors.canvas, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = Hf.type.bodyMd.copy(color = Hf.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Hf.colors.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("Describe the program you want", style = Hf.type.bodyMd, color = Hf.colors.textQuaternary)
                }
                inner()
            },
        )
    }
}

@Composable
private fun GymDropdown(
    gyms: List<GymOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = gyms.firstOrNull { it.locationId == selectedId }?.name ?: "Select gym"
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
                .background(Hf.colors.canvas, RoundedCornerShape(7.dp))
                .clickable { expanded = true }
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Text(selectedName, style = Hf.type.bodyMd, color = Hf.colors.textPrimary, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            gyms.forEach { gym ->
                DropdownMenuItem(
                    text = { Text(gym.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                    onClick = { onSelect(gym.locationId); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun GoalDropdown(
    goals: List<GoalOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = goals.firstOrNull { it.goalId == selectedId }?.title ?: "No goal"
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
                .background(Hf.colors.canvas, RoundedCornerShape(7.dp))
                .clickable { expanded = true }
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            Text(selectedTitle, style = Hf.type.bodyMd, color = Hf.colors.textPrimary, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("No goal", style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                onClick = { onSelect(null); expanded = false },
            )
            goals.forEach { goal ->
                DropdownMenuItem(
                    text = { Text(goal.title, style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                    onClick = { onSelect(goal.goalId); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ThreadsPanel(
    threads: List<ProgramChatThreadResponse>,
    activeThreadId: String?,
    deletingThreadIds: Set<String>,
    onClose: () -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(Hf.colors.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Conversations", style = Hf.type.headingSm.copy(fontSize = 15.sp), color = Hf.colors.textPrimary)
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Close",
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(30.dp).clickable { onClose() }.padding(5.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Hf.colors.borderDefault))

        if (threads.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No past conversations", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
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
    thread: ProgramChatThreadResponse,
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
                text = thread.title?.ifBlank { "Untitled conversation" } ?: "Untitled conversation",
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
                maxLines = 2,
            )
            thread.updatedAt?.let {
                Spacer(Modifier.height(2.dp))
                Text(it.take(10), style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.size(22.dp).clickable { onDeleteRequest(thread.threadId) },
                )
            }
        }
    }
}
