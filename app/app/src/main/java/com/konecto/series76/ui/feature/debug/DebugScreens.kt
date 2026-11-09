package com.konecto.series76.ui.feature.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konecto.series76.R
import com.konecto.series76.domain.model.DebugStep
import com.konecto.series76.domain.model.DebugTrace
import com.konecto.series76.ui.components.CardGlows
import com.konecto.series76.ui.components.ContentMaxWidth
import com.konecto.series76.ui.components.KIcon
import com.konecto.series76.ui.components.KonectoAppBar
import com.konecto.series76.ui.components.ListCard
import com.konecto.series76.ui.components.OfflineBanner
import com.konecto.series76.ui.components.PillButton
import com.konecto.series76.ui.components.ServerUrlSheet
import com.konecto.series76.ui.components.radialGlow
import com.konecto.series76.ui.components.screenInsets
import com.konecto.series76.ui.feature.settings.ServerSettingsViewModel
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoType
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
private fun DebugScaffold(title: String, onBack: () -> Unit, isOnline: Boolean, content: @Composable ColumnScope.() -> Unit) {
    // The bubble background is shared by the NavHost. Wide windows: a centered column.
    Box(Modifier.fillMaxSize().screenInsets(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
            KonectoAppBar(title, onBack)
            OfflineBanner(isOnline)
            content()
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text, style = KonectoType.Body2, color = KonectoColors.SubContent, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
    )
}

// ---------------------------------------------------------------- Debug · chats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugChatsRoute(
    onBack: () -> Unit,
    onOpenChat: (sessionId: String, label: String, userName: String) -> Unit,
    onServerChanged: () -> Unit,
    viewModel: DebugChatsViewModel = hiltViewModel(),
    serverViewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val server by serverViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { serverViewModel.saved.collect { changed -> if (changed) onServerChanged() } }

    DebugScaffold("Debug", onBack, state.isOnline) {
        PullToRefreshBox(state.isRefreshing, viewModel::refresh, Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "server") {
                    ListCard(
                        title = "Backend URL",
                        subtitle = state.serverUrl,
                        onClick = serverViewModel::open,
                        leading = { KIcon(R.drawable.ic_settings, null, tint = KonectoColors.SubContent, size = 14.dp) },
                    )
                }
                state.groups.forEach { group ->
                    item(key = "user-${group.user.id}") { UserHeader(group) }
                    items(group.chats, key = { it.id }) { item ->
                        ListCard(
                            title = item.title,
                            subtitle = "${item.label} · ${item.subtitle}",
                            onClick = { onOpenChat(item.id, item.label, group.user.name) },
                        )
                    }
                }
                if (!state.loaded) {
                    item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator(color = KonectoColors.Primary) } }
                } else if (state.groups.isEmpty() && state.error == null) {
                    item { EmptyText("No chats yet.") }
                }
                if (state.error != null) item { EmptyText("Couldn't load the chats: ${state.error}") }
            }
        }
    }
    if (server.visible) {
        ServerUrlSheet(
            currentUrl = server.currentUrl, draft = serverViewModel.draft, test = server.test, saving = server.saving,
            error = server.error, onDraftChange = serverViewModel::onDraftChange, onTest = serverViewModel::test,
            onSave = serverViewModel::save, onDismiss = serverViewModel::dismiss,
        )
    }
}

/** Section header of the debug chat list: whose chats follow. */
@Composable
private fun UserHeader(group: DebugUserGroup) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            group.user.name + if (group.isMe) " (you)" else "",
            style = KonectoType.H4, color = KonectoColors.PrimaryLight, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val role = if (group.user.isAdmin) " · admin" else ""
        val count = group.chats.size.let { if (it == 1) "1 chat" else "$it chats" }
        Text("${group.user.email}$role · $count", style = KonectoType.Metadata2, color = KonectoColors.SubContent, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Debug · answers of a chat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugAnswersRoute(
    onBack: () -> Unit,
    onOpenAnswer: (messageId: String, title: String) -> Unit,
    viewModel: DebugAnswersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DebugScaffold("Debug - ${viewModel.chatLabel}", onBack, state.isOnline) {
        PullToRefreshBox(state.isRefreshing && state.loaded, viewModel::refresh, Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    DebugAnswerCard(item, viewModel.userName) {
                        onOpenAnswer(item.id, "Debug - ${viewModel.chatLabel} - ${item.label}")
                    }
                }
                if (state.isRefreshing && state.items.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator(color = KonectoColors.Primary) } }
                } else if (state.loaded && state.items.isEmpty() && state.error == null) {
                    item { EmptyText("This chat has no answers yet.") }
                }
                if (state.error != null) item { EmptyText("Couldn't load the answers: ${state.error}") }
            }
        }
    }
}

/** Same look as [ListCard], but grows: "<user>: question", answer preview (2 lines), date · status. */
@Composable
private fun DebugAnswerCard(item: DebugAnswerItem, userName: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 94.dp)
            .clip(shape)
            .radialGlow(CardGlows[0])
            .border(1.dp, KonectoColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = KonectoColors.PrimaryLight)) { append("${userName ?: "User"}: ") }
                    append(item.question ?: "—")
                },
                style = KonectoType.Metadata1, color = KonectoColors.Content, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(item.answerPreview, style = KonectoType.Body2, color = KonectoColors.Content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.meta, style = KonectoType.Metadata2, color = KonectoColors.SubContent, maxLines = 1)
        }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            KIcon(R.drawable.ic_chevron_right, null, tint = Color.White, size = 20.dp)
        }
    }
}

// ---------------------------------------------------------------- Debug · one answer

@Composable
fun DebugAnswerRoute(onBack: () -> Unit, viewModel: DebugAnswerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DebugScaffold(state.title, onBack, isOnline = true) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DebugCard("Question" + (state.userName?.let { " · $it" } ?: "")) { Body(state.question ?: "—") }
            state.answer?.let { answer ->
                DebugCard("Answer") {
                    Markdown(
                        content = answer,
                        colors = markdownColor(text = KonectoColors.SubContent),
                        typography = markdownTypography(text = KonectoType.Metadata2, paragraph = KonectoType.Metadata2),
                    )
                }
            }
            val trace = state.trace
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator(color = KonectoColors.Primary)
                }
                trace == null -> {
                    DebugCard("Trace unavailable") { Body(state.error ?: "—") }
                    PillButton("Retry", viewModel::load, Modifier.fillMaxWidth())
                }
                else -> TraceCards(trace)
            }
        }
    }
}

@Composable
private fun TraceCards(trace: DebugTrace) {
    DebugCard("Data Retrieved") {
        if (trace.steps.isEmpty()) Body("No tool was called.")
        trace.steps.forEach { Body("• ${it.name}: ${it.retrievedSummary}") }
        if (trace.structuredPartNumbers.isNotEmpty()) Body("Cited parts: ${trace.structuredPartNumbers.joinToString(", ")}")
    }
    DebugCard("Tools Called", minHeight = 304) {
        if (trace.steps.isEmpty()) Body("—")
        trace.steps.forEachIndexed { i, step -> ToolStep(i + 1, step) }
    }
    DebugCard("Flow in LangChain") {
        Body("1. User query → create_agent (${trace.model})")
        trace.steps.forEachIndexed { i, s -> Body("${i + 2}. Tool call: ${s.name}${s.durationMs?.let { " ($it ms)" } ?: ""}") }
        val n = trace.steps.size + 2
        Body("$n. Structured output (AgentAnswer): status = ${trace.structuredStatus ?: "—"}")
        val grounding = if (trace.groundingOk) "ok" else "unverified numbers: ${trace.unverifiedNumbers.joinToString()}"
        Body("${n + 1}. Numeric grounding: $grounding${if (trace.groundingRetried) " (corrected once)" else ""}")
        Body("${n + 2}. Product cards built from the DB → answer")
        Meta("Latency ${trace.latencyMs} ms · tokens ${trace.inputTokens} in / ${trace.outputTokens} out")
        trace.error?.let { Body("Error: $it") }
        trace.structuredOutputJson?.let { Code(it) }
    }
}

@Composable
private fun ToolStep(index: Int, step: DebugStep) {
    var showResult by rememberSaveable(step.name + index) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$index. ${step.name}${step.durationMs?.let { " · $it ms" } ?: ""}",
            style = KonectoType.Metadata1, color = KonectoColors.Content,
        )
        Meta("Parameters")
        Code(step.argsJson)
        step.error?.let { Text("Error: $it", style = KonectoType.Metadata2, color = KonectoColors.Danger) }
        if (step.resultJson != null) {
            Text(
                if (showResult) "Hide data" else "Show data (${step.retrievedSummary})",
                style = KonectoType.Metadata1, color = KonectoColors.PrimaryLight,
                modifier = Modifier.clickable { showResult = !showResult }.padding(vertical = 2.dp),
            )
            if (showResult) Code(step.resultJson)
        }
    }
}

/** Figma "Card / Type 8–11" of the debug detail: title H4 + content; grows with its content. */
@Composable
private fun DebugCard(title: String, minHeight: Int = 94, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(KonectoColors.Secondary)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = KonectoType.H4, color = KonectoColors.Content)
        content()
    }
}

@Composable
private fun Body(text: String) = Text(text, style = KonectoType.Metadata2, color = KonectoColors.SubContent)

@Composable
private fun Meta(text: String) = Text(text, style = KonectoType.Metadata2, color = KonectoColors.Placeholder)

@Composable
private fun Code(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(KonectoColors.Black)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) { Text(text, style = KonectoType.Mono, color = KonectoColors.Content) }
}
