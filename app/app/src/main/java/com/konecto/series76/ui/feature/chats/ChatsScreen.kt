package com.konecto.series76.ui.feature.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konecto.series76.R
import com.konecto.series76.ui.components.CardGlows
import com.konecto.series76.ui.components.DeleteChatSheet
import com.konecto.series76.ui.components.KIcon
import com.konecto.series76.ui.components.KIconButton
import com.konecto.series76.ui.components.KonectoLogo
import com.konecto.series76.ui.components.ListCard
import com.konecto.series76.ui.components.OfflineBanner
import com.konecto.series76.ui.components.PillButton
import com.konecto.series76.ui.components.SwipeToDelete
import com.konecto.series76.ui.components.isLandscape
import com.konecto.series76.ui.components.screenInsets
import com.konecto.series76.ui.components.background.BubbleBackground
import com.konecto.series76.ui.components.background.BubblePresets
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoTheme
import com.konecto.series76.ui.theme.KonectoType

@Composable
fun ChatsRoute(
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onDebug: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loggedOut.collect { onLoggedOut() } }
    ChatsScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onDebug = { if (state.isAdmin) onDebug() },
        onLogout = viewModel::logout,
        onDelete = viewModel::requestDelete,
    )
    if (state.deleteCandidate != null) {
        DeleteChatSheet(onConfirm = viewModel::confirmDelete, onDismiss = viewModel::cancelDelete)
    }
}

/**
 * Figma "Chat Screen" (3:816: gradient cards, full swipe to delete) + "Delete Screen" (36:1318) confirmation sheet.
 * The bubble background is shared by the NavHost. Landscape: header and buttons in a side pane, the list on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    state: ChatsUiState,
    onRefresh: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onDebug: () -> Unit,
    onLogout: () -> Unit,
    onDelete: (SessionItem) -> Unit,
) {
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().screenInsets()) {
            Column(Modifier.width(SIDE_PANE_WIDTH).fillMaxHeight()) {
                ChatsHeader(state, onNewChat, onLogout)
                Box(Modifier.weight(1f))
                ChatsActions(onNewChat, onDebug, showDebug = state.isAdmin)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ChatsList(state, onRefresh, onOpenChat, onDelete, Modifier.weight(1f).padding(top = 6.dp))
                RefreshError(state)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().screenInsets()) {
            ChatsHeader(state, onNewChat, onLogout)
            ChatsList(state, onRefresh, onOpenChat, onDelete, Modifier.weight(1f).padding(top = 12.dp))
            RefreshError(state)
            ChatsActions(onNewChat, onDebug, showDebug = state.isAdmin)
        }
    }
}

private val SIDE_PANE_WIDTH = 320.dp

@Composable
private fun ChatsHeader(state: ChatsUiState, onNewChat: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp)) {
            KIconButton(R.drawable.ic_plus_square, "New chat", onNewChat, tint = Color.White)
            Box(Modifier.weight(1f))
            KIconButton(R.drawable.ic_log_out, "Log out", onLogout, tint = Color.White)
        }
        KonectoLogo(Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp))
        OfflineBanner(state.isOnline, Modifier.padding(top = 12.dp), pending = state.pendingCount)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsList(
    state: ChatsUiState,
    onRefresh: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDelete: (SessionItem) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // A chat that moves to the top (new or just answered) would stay above the viewport because LazyColumn keeps the
    // previous first item anchored; follow it when the user was already at the top.
    val firstId = state.sessions.firstOrNull()?.id
    LaunchedEffect(firstId) { if (firstId != null && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.sessions, key = { _, it -> it.id }) { index, item ->
                SwipeToDelete(
                    onDelete = { onDelete(item) },
                    awaitingConfirm = state.deleteCandidate?.id == item.id,
                    modifier = Modifier.animateItem(),
                ) {
                    ListCard(
                        title = item.title,
                        subtitle = item.subtitle,
                        onClick = { onOpenChat(item.id) },
                        glow = CardGlows[index % CardGlows.size],
                        leading = if (item.pendingSync) {
                            { KIcon(R.drawable.ic_clock, "Not synced", tint = KonectoColors.Warning, size = 14.dp) }
                        } else null,
                    )
                }
            }
            if (state.loaded && state.sessions.isEmpty()) {
                item {
                    Text(
                        if (state.isOnline) "No chats yet.\nStart one with New Chat." else "No saved chats on this phone.",
                        style = KonectoType.Body2, color = KonectoColors.SubContent, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RefreshError(state: ChatsUiState) {
    if (state.error != null && state.isOnline) {
        Text(
            "Couldn't refresh: ${state.error}",
            style = KonectoType.Metadata2, color = KonectoColors.Danger, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
        )
    }
}

@Composable
private fun ChatsActions(onNewChat: () -> Unit, onDebug: () -> Unit, showDebug: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 8.dp).padding(bottom = 15.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PillButton("New Chat", onNewChat, Modifier.fillMaxWidth())
        if (showDebug) PillButton("Debug", onDebug, Modifier.fillMaxWidth(), primary = false)
    }
}

@Preview(widthDp = 402, heightDp = 874)
@Composable
private fun ChatsPreview() = KonectoTheme {
    Box(Modifier.fillMaxSize()) {
        BubbleBackground(BubblePresets.Main, Modifier.fillMaxSize())
        ChatsScreen(
            ChatsUiState(
                sessions = listOf(
                    SessionItem("1", "Give me the name of this piece", "08 August 2024 | 03:23 AM", false),
                    SessionItem("2", "Lore ipsum", "08 August 2024 | 03:23 AM", true),
                ),
                loaded = true,
                isAdmin = true,
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}
