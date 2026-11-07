package com.konecto.series76.ui.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konecto.series76.R
import com.konecto.series76.ui.components.KIconButton
import com.konecto.series76.ui.components.KonectoLogo
import com.konecto.series76.ui.components.OfflineBanner
import com.konecto.series76.ui.components.PillButton
import com.konecto.series76.ui.components.ServerUrlSheet
import com.konecto.series76.ui.components.isLandscape
import com.konecto.series76.ui.components.screenInsets
import com.konecto.series76.ui.components.VerticalSpace
import com.konecto.series76.ui.components.background.BubbleBackground
import com.konecto.series76.ui.components.background.BubblePresets
import com.konecto.series76.ui.feature.settings.ServerSettingsViewModel
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoTheme
import com.konecto.series76.ui.theme.KonectoType

@Composable
fun LoginRoute(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
    serverViewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val server by serverViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loggedIn.collect { onLoggedIn() } }
    LoginScreen(
        state = state,
        email = viewModel.email,
        password = viewModel.password,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
        onOpenServer = serverViewModel::open,
    )
    if (server.visible) {
        ServerUrlSheet(
            currentUrl = server.currentUrl, draft = serverViewModel.draft, test = server.test, saving = server.saving,
            error = server.error, onDraftChange = serverViewModel::onDraftChange, onTest = serverViewModel::test,
            onSave = serverViewModel::save, onDismiss = serverViewModel::dismiss,
        )
    }
}

/** Figma "Login" (3:657). Landscape: logo on the left, form on the right. */
@Composable
fun LoginScreen(
    state: LoginUiState,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenServer: () -> Unit,
) {
    val landscape = isLandscape()
    Box(Modifier.fillMaxSize()) {
        BubbleBackground(BubblePresets.Login, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().screenInsets()) {
            val minHeight = maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { KonectoLogo() }
                    CenteredScrollColumn(minHeight, Modifier.weight(1f)) {
                        LoginForm(state, email, password, onEmailChange, onPasswordChange, onSubmit)
                    }
                }
            } else {
                CenteredScrollColumn(minHeight, Modifier.fillMaxWidth()) {
                    KonectoLogo()
                    VerticalSpace(43.dp)
                    LoginForm(state, email, password, onEmailChange, onPasswordChange, onSubmit)
                }
            }
        }
        OfflineBanner(state.isOnline, Modifier.screenInsets().padding(top = 8.dp))
        KIconButton(
            R.drawable.ic_settings, "Backend server", onOpenServer,
            modifier = Modifier.align(Alignment.TopEnd).screenInsets().padding(12.dp),
            tint = Color.White.copy(alpha = 0.8f),
        )
    }
}

/** Scrollable column whose content is centered while it fits in [minHeight] (e.g. with the keyboard closed). */
@Composable
private fun CenteredScrollColumn(minHeight: Dp, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).heightIn(min = minHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun ColumnScope.LoginForm(
    state: LoginUiState,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Text("Username", style = KonectoType.Label)
    VerticalSpace(7.dp)
    LoginField(
        value = email,
        onValueChange = onEmailChange,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
    )
    VerticalSpace(6.dp)
    Text("Password", style = KonectoType.Label)
    VerticalSpace(6.dp)
    LoginField(
        value = password,
        onValueChange = onPasswordChange,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSubmit() }),
        trailing = {
            KIconButton(
                id = if (showPassword) R.drawable.ic_eye_off else R.drawable.ic_eye,
                contentDescription = if (showPassword) "Hide password" else "Show password",
                onClick = { showPassword = !showPassword },
                tint = KonectoColors.Secondary,
                size = 40.dp,
                iconSize = 20.dp,
            )
        },
    )
    VerticalSpace(24.dp)
    PillButton("Log in", onSubmit, Modifier.width(250.dp), loading = state.loading)
    VerticalSpace(12.dp)
    Text(
        state.error.orEmpty(),
        style = KonectoType.Metadata2,
        color = KonectoColors.Danger,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(280.dp).height(48.dp),
    )
}

/** Figma field: 250×40, radius 10, #D9D9D9. */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = KonectoType.Body2.copy(color = KonectoColors.Black),
        cursorBrush = SolidColor(KonectoColors.Primary),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.width(250.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(KonectoColors.Field),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(start = 12.dp, end = if (trailing == null) 12.dp else 0.dp)) { inner() }
                trailing?.invoke()
            }
        },
    )
}

@Preview(widthDp = 402, heightDp = 874)
@Composable
private fun LoginPreview() = KonectoTheme {
    LoginScreen(LoginUiState(), "ana@konecto.test", "", {}, {}, {}, {})
}
