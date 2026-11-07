package com.konecto.series76.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.konecto.series76.domain.model.ServerHealth
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoType

private val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Figma "[Section] Footer / Bottom Sheet" (Delete Screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteChatSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KonectoColors.Black,
        shape = SheetShape,
        dragHandle = null,
        scrimColor = KonectoColors.Scrim,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Delete Chat", style = KonectoType.H3, color = KonectoColors.Content)
            Box(Modifier.fillMaxWidth().height(1.dp).background(KonectoColors.Border))
            Text(
                "Are you sure you want to delete the chat?",
                style = KonectoType.Body, color = KonectoColors.SubContent, textAlign = TextAlign.Center,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PillButton("Cancel", onDismiss, Modifier.weight(1f), primary = false)
                PillButton("Clear Chat", onConfirm, Modifier.weight(1f))
            }
        }
    }
}

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val health: ServerHealth) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

/** Editor of the backend URL (runtime state). Opened from Login (⚙) and from Debug. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerUrlSheet(
    currentUrl: String,
    draft: String,
    test: ConnectionTest,
    saving: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KonectoColors.Black,
        shape = SheetShape,
        scrimColor = KonectoColors.Scrim,
    ) {
        Column(
            // Scrollable: in landscape with the keyboard open the sheet is shorter than its content.
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Backend server", style = KonectoType.H3, color = KonectoColors.Content)
            Text("Current: $currentUrl", style = KonectoType.Metadata2, color = KonectoColors.SubContent)
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                textStyle = KonectoType.Body.copy(color = KonectoColors.Content),
                cursorBrush = SolidColor(KonectoColors.Primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(KonectoColors.Secondary)
                    .border(1.dp, KonectoColors.Border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("http://10.0.2.2:8001", style = KonectoType.Body, color = KonectoColors.Placeholder)
                    inner()
                },
            )
            val (msg, color) = when (test) {
                ConnectionTest.Idle -> null to KonectoColors.SubContent
                ConnectionTest.Running -> "Testing…" to KonectoColors.SubContent
                is ConnectionTest.Ok -> with(test.health) {
                    (if (ok) "✓ Server OK · ${actuators} actuators" else "⚠ Server degraded (database ${if (dbUp) "up" else "down"})") to
                        (if (ok) KonectoColors.Success else KonectoColors.Warning)
                }
                is ConnectionTest.Failed -> "✕ ${test.message}" to KonectoColors.Danger
            }
            if (msg != null) Text(msg, style = KonectoType.Metadata2, color = color)
            if (error != null) Text(error, style = KonectoType.Metadata2, color = KonectoColors.Danger)
            Text(
                "Saving a different server logs you out and clears the chats saved on this phone.",
                style = KonectoType.Metadata2, color = KonectoColors.Placeholder,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton("Test", onTest, Modifier.weight(1f), primary = false, loading = test == ConnectionTest.Running)
                PillButton("Save", onSave, Modifier.weight(1f), loading = saving)
            }
        }
    }
}
