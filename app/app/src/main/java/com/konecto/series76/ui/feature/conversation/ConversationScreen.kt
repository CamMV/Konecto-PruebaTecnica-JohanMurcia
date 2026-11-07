package com.konecto.series76.ui.feature.conversation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konecto.series76.ui.components.ChatInput
import com.konecto.series76.ui.components.ContentMaxWidth
import com.konecto.series76.ui.components.KonectoAppBar
import com.konecto.series76.ui.components.OfflineBanner
import com.konecto.series76.ui.components.SuggestionCard
import com.konecto.series76.ui.components.background.BubbleBackground
import com.konecto.series76.ui.components.background.BubblePresets
import com.konecto.series76.ui.components.isLandscape
import com.konecto.series76.ui.components.screenInsets
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoTheme
import com.konecto.series76.ui.theme.KonectoType

/** Cards of the empty state (Figma "[Section] Content / Card carousel"). */
private data class StarterSuggestion(val title: String, val subtitle: String, val query: String, val prefill: Boolean)

private val STARTERS = listOf(
    StarterSuggestion("What pieces are 220 V?", "List all the pieces of 220 V", "What pieces are 220 V?", prefill = false),
    StarterSuggestion("Write me a recommendation for", "Get a recomendation piece for your project", "Write me a recommendation for ", prefill = true),
    StarterSuggestion("Look up a part number", "Torque, speed and power of 761A00-11300000/A", "What is the torque of 761A00-11300000/A?", prefill = false),
)

@Composable
fun ConversationRoute(onBack: () -> Unit, viewModel: ConversationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConversationScreen(
        state = state,
        input = viewModel.input,
        onBack = onBack,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::send,
        onSuggestion = viewModel::useSuggestion,
        onRetry = viewModel::retry,
    )
}

/** Figma "Conversation Screen" (31:1108). */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    input: String,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestion: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val showTyping = state.awaitingAnswer && state.isOnline
    val itemCount = state.messages.size + if (showTyping) 1 else 0
    // Follow the newest item: jump on open (no animation through the whole history), animate afterwards.
    val lastKey = if (showTyping) "typing" else state.messages.lastOrNull()?.id
    var scrolledOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lastKey) {
        if (lastKey == null) return@LaunchedEffect
        if (scrolledOnce) listState.animateScrollToItem(itemCount - 1) else listState.scrollToItem(itemCount - 1)
        scrolledOnce = true
    }
    val landscape = isLandscape()

    // The bubble background is shared by the NavHost. Wide windows: a centered reading column.
    Box(Modifier.fillMaxSize().screenInsets(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
            KonectoAppBar(state.title, onBack)
            OfflineBanner(state.isOnline)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    // Few fixed cards: a scrollable Row (not lazy) so they can share the tallest card's height.
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = if (landscape) 12.dp else 28.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 11.dp)
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        STARTERS.forEach { s ->
                            SuggestionCard(s.title, s.subtitle, onClick = { onSuggestion(s.query, s.prefill) }, modifier = Modifier.fillMaxHeight())
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.messages, key = { it.id }) { m ->
                            if (m.isUser) UserBubble(m, state.isOnline, onRetry = { onRetry(m.id) })
                            else AssistantMessage(m, onSuggestion = { onSuggestion(it, false) })
                        }
                        if (showTyping) item(key = "typing") { TypingIndicator() }
                    }
                }
            }
            if (state.refreshError != null && state.isOnline) {
                Text(
                    "Couldn't update this chat: ${state.refreshError}",
                    style = KonectoType.Metadata2, color = KonectoColors.Danger, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            ChatInput(
                value = input, onValueChange = onInputChange, onSend = onSend,
                bottomPadding = if (landscape) 8.dp else 24.dp,
            )
        }
    }
}

@Preview(widthDp = 402, heightDp = 874)
@Composable
private fun EmptyConversationPreview() = KonectoTheme {
    Box(Modifier.fillMaxSize()) {
        BubbleBackground(BubblePresets.Main, Modifier.fillMaxSize())
        ConversationScreen(ConversationUiState(), "", {}, {}, {}, { _, _ -> }, {})
    }
}
