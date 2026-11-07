package com.konecto.series76.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.lerp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.konecto.series76.R
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoType
import kotlin.math.roundToInt

/** Konecto wordmark (Figma "konecto-logo-sin-nube", 279×40). */
@Composable
fun KonectoLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.konecto_logo),
        contentDescription = "Konecto",
        modifier = modifier.size(width = 279.dp, height = 40.dp),
    )
}

/** Icon from the Figma icon set, drawn in [tint] (Color.Unspecified keeps the original fill). */
@Composable
fun KIcon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, size: Dp = 24.dp) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
}

/** Round icon button (Figma "Icon Button" / "Back": 40dp, icon inset 20%). */
@Composable
fun KIconButton(
    @DrawableRes id: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    tint: Color = KonectoColors.Content,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KIcon(id, contentDescription, tint = tint, size = iconSize) }
}

/** Figma "Button": 48dp tall, radius 60, Callout text. Primary = #5D4CD6, secondary = #2B2B2B. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    loading: Boolean = false,
) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(60.dp))
            .background(if (primary) KonectoColors.Primary else KonectoColors.Secondary)
            .clickable(enabled = !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, style = KonectoType.Callout, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Elliptical radial gradient of the Figma cards. Center and radii are fractions of the card size; Compose only has
 * circular radial brushes, so the circle is drawn in a vertically scaled space (= Figma's `gradientTransform`).
 */
@Immutable
class CardGlow(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val stops: List<Pair<Float, Color>>,
)

/** The three list-card variants of Figma "Chat Screen" (3:816); the list cycles through them. */
val CardGlows = listOf(
    CardGlow(
        0.80f, 0.20f, 0.70f, 0.80f,
        listOf(0f to Color(0xFF7B5EA7), 0.3f to Color(0xFF543D88), 0.45f to Color(0xFF412C79), 0.6f to Color(0xFF2D1B69), 1f to Color(0xFF1A0F3C)),
    ),
    CardGlow(
        0.20f, 0.30f, 0.90f, 0.80f,
        listOf(0f to Color(0xFF6A3FA0), 0.3f to Color(0xFF4C2D85), 0.6f to Color(0xFF2D1B69), 1f to Color(0xFF1A0F3C)),
    ),
    CardGlow(
        0.75f, 0.15f, 0.75f, 0.85f,
        listOf(0f to Color(0xFF6B3FA8), 0.275f to Color(0xFF4C2D89), 0.55f to Color(0xFF2D1B69), 0.775f to Color(0xFF211451), 1f to Color(0xFF140C38)),
    ),
)

/** Suggestion cards of Figma "Conversation Screen" (31:1108): centered glow. */
val SuggestionGlow = CardGlow(
    0.5f, 0.5f, 0.5f, 0.5f,
    listOf(0f to Color(0xFF3D2D6E), 0.5f to Color(0xFF2C214F), 1f to Color(0xFF1A1530)),
)

fun Modifier.radialGlow(glow: CardGlow): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val center = Offset(w * glow.centerX, h * glow.centerY)
    val rx = (w * glow.radiusX).coerceAtLeast(1f)
    val sy = (h * glow.radiusY).coerceAtLeast(1f) / rx
    val brush = Brush.radialGradient(*glow.stops.toTypedArray(), center = center, radius = rx)
    // Rect that covers the whole card once the y axis is scaled by sy around the center (the brush clamps outside).
    val top = center.y - center.y / sy
    val bottom = center.y + (h - center.y) / sy
    onDrawBehind {
        scale(scaleX = 1f, scaleY = sy, pivot = center) {
            drawRect(brush, topLeft = Offset(0f, top), size = Size(w, bottom - top))
        }
    }
}

/** Figma "Card / type 2" (Chat Screen): 94dp, radius 16, radial gradient + white 9 % border, H4 + Metadata 2, chevron. */
@Composable
fun ListCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    glow: CardGlow = CardGlows[0],
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(94.dp)
            .clip(shape)
            .radialGlow(glow)
            .border(1.dp, KonectoColors.CardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = KonectoType.H4, color = KonectoColors.Content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                leading?.invoke()
                Text(subtitle, style = KonectoType.Metadata2, color = KonectoColors.SubContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showChevron) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                KIcon(R.drawable.ic_chevron_right, null, tint = Color.White, size = 20.dp)
            }
        }
    }
}

/**
 * Swipe the card all the way left to delete (Figma "Card / Type 12" in Chat Screen 3:816: pink #E91E63→#C2185B panel
 * with the trash icon). There is no half-open state: released before [SWIPE_DELETE_THRESHOLD] of the width (and without
 * a fast fling) the card springs back; past it, the card slides off and [onDelete] is called (it opens the confirmation).
 * While [awaitingConfirm] is true the card stays off-screen; when it turns false (cancelled) the card slides back.
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    awaitingConfirm: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    // Plain float state while dragging (no coroutine per drag delta); animated only when released or reset.
    var offset by remember { mutableFloatStateOf(0f) }
    suspend fun settle(target: Float) = animate(offset, target) { value, _ -> offset = value }
    LaunchedEffect(awaitingConfirm) { if (!awaitingConfirm && offset != 0f) settle(0f) }
    val flingPx = with(LocalDensity.current) { SWIPE_DELETE_FLING.toPx() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(94.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(shape)
            .semantics { customActions = listOf(CustomAccessibilityAction("Delete chat") { onDelete(); true }) },
    ) {
        if (offset < 0f) {
            // Feedback only (not a button): the icon grows until the release point is reached.
            val progress = if (widthPx > 0f) (-offset / (widthPx * SWIPE_DELETE_THRESHOLD)).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(KonectoColors.DeleteTop, KonectoColors.DeleteBottom)))
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                KIcon(
                    R.drawable.ic_trash, null, tint = Color.White, size = 24.dp,
                    modifier = Modifier.graphicsLayer {
                        val s = 0.7f + 0.3f * progress
                        scaleX = s
                        scaleY = s
                        alpha = 0.45f + 0.55f * progress
                    },
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(-widthPx, 0f) },
                    onDragStopped = { velocity ->
                        if (-offset > widthPx * SWIPE_DELETE_THRESHOLD || velocity < -flingPx) {
                            settle(-widthPx)
                            onDelete()
                        } else {
                            settle(0f)
                        }
                    },
                ),
        ) { content() }
    }
}

private const val SWIPE_DELETE_THRESHOLD = 0.5f
private val SWIPE_DELETE_FLING = 1200.dp // per second

/** Figma "App Bar": 72dp, back button + H3 title. */
@Composable
fun KonectoAppBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KIconButton(R.drawable.ic_arrow_left, "Back", onBack)
        Text(title, style = KonectoType.H3, color = KonectoColors.Content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * Figma "[Section] Footer / Chat Input", variant "C · Glow" of the chat-bar exploration: the field shares the
 * suggestion cards' radial fill and #3A3060 border; on focus the border becomes a gradient and the purple shadow grows.
 * The send button is a gradient circle, dimmed while there is nothing to send. Multi-line: the button stays at the bottom.
 */
@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 24.dp,
) {
    val canSend = value.isNotBlank()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val glow by animateFloatAsState(if (focused) 1f else 0f, label = "inputGlow")
    val sendAlpha by animateFloatAsState(if (canSend) 1f else 0.4f, label = "sendAlpha")
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = KonectoType.Body.copy(color = KonectoColors.Content),
            cursorBrush = SolidColor(KonectoColors.PrimaryLight),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            interactionSource = interaction,
            modifier = Modifier
                .weight(1f)
                .shadow(
                    lerp(6.dp, 10.dp, glow), shape,
                    ambientColor = lerp(InputShadow, InputShadowFocused, glow),
                    spotColor = lerp(InputShadow, InputShadowFocused, glow),
                )
                .heightIn(min = 48.dp)
                .clip(shape)
                .radialGlow(InputGlow)
                .border(BorderStroke(1.dp, if (focused) InputFocusBorder else SolidColor(KonectoColors.SuggestionBorder)), shape),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Ask me anything...", style = KonectoType.Body, color = KonectoColors.InputPlaceholder)
                    inner()
                }
            },
        )
        Box(
            Modifier
                .padding(bottom = 4.dp) // centered on a one-line (48dp) field, bottom-aligned when it grows
                .size(40.dp)
                .graphicsLayer { alpha = sendAlpha }
                .shadow(if (canSend) 8.dp else 0.dp, CircleShape, ambientColor = SendShadow, spotColor = SendShadow)
                .clip(CircleShape)
                .background(SendGradient)
                .clickable(enabled = canSend, role = Role.Button, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { KIcon(R.drawable.ic_send, "Send", tint = Color.White) }
    }
}

/** Chat bar fill: like [SuggestionGlow] but lit from the top-left (CSS `ellipse 60% 100% at 30% 0%`). */
private val InputGlow = CardGlow(
    0.3f, 0f, 0.6f, 1f,
    listOf(0f to Color(0xFF3D2D6E), 0.5f to Color(0xFF2C214F), 1f to Color(0xFF1A1530)),
)
private val InputFocusBorder = Brush.horizontalGradient(listOf(Color(0xFF9D90FF), Color(0xFF5D4CD6), Color(0xFF7B5EA7)))
private val InputShadow = Color(0x336B4FBE) // 0 2 8 rgba(107,79,190,0.2)
private val InputShadowFocused = Color(0x666B4FBE) // 0 2 14 rgba(107,79,190,0.4)
private val SendGradient = Brush.linearGradient(listOf(Color(0xFF7B5EA7), KonectoColors.Primary)) // 135°
private val SendShadow = Color(0x735D4CD6) // 0 4 12 rgba(93,76,214,0.45)

/** Figma "Card / Suggestion" (Conversation Screen): 187dp, centered glow, #3A3060 border, soft purple shadow. */
@Composable
fun SuggestionCard(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(187.dp)
            .heightIn(min = 102.dp)
            .shadow(6.dp, shape, ambientColor = SuggestionShadow, spotColor = SuggestionShadow)
            .clip(shape)
            .radialGlow(SuggestionGlow)
            .border(1.dp, KonectoColors.SuggestionBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = KonectoType.Metadata1, color = KonectoColors.Content)
        Text(subtitle, style = KonectoType.Metadata2, color = KonectoColors.SubContent)
    }
}

private val SuggestionShadow = Color(0x336B4FBE) // Figma: 0 2 8 rgba(107,79,190,0.2)

/** Connectivity banner: shown while offline (the cache keeps working). */
@Composable
fun OfflineBanner(isOnline: Boolean, modifier: Modifier = Modifier, pending: Int = 0) {
    AnimatedVisibility(!isOnline, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(KonectoColors.Black.copy(alpha = 0.85f))
                .border(1.dp, KonectoColors.Warning.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KIcon(R.drawable.ic_globe, null, tint = KonectoColors.Warning, size = 18.dp)
            val extra = if (pending > 0) " · $pending pending" else ""
            Text("Offline · showing saved data$extra", style = KonectoType.Metadata2, color = KonectoColors.Content)
        }
    }
}

@Composable
fun VerticalSpace(height: Dp) = Spacer(Modifier.height(height))
