package com.konecto.series76.ui.feature.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.konecto.series76.R
import com.konecto.series76.domain.model.AnswerStatus
import com.konecto.series76.domain.model.ElectricalPoint
import com.konecto.series76.domain.model.ProductCard
import com.konecto.series76.domain.model.SendState
import com.konecto.series76.domain.model.ServiceRating
import com.konecto.series76.ui.components.KIcon
import com.konecto.series76.ui.theme.KonectoColors
import com.konecto.series76.ui.theme.KonectoType
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/*
 * Chat components. The mockup has no design for bubbles/product cards, so they follow its tokens:
 * user = Dark/Primary, assistant = Dark/Secondary, radius 16, Poppins.
 */

private val UserShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
private val AssistantShape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

@Composable
fun UserBubble(item: MessageItem, isOnline: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(UserShape)
                .background(if (item.sendState == SendState.FAILED) KonectoColors.Primary.copy(alpha = 0.55f) else KonectoColors.Primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text(item.content, style = KonectoType.Body2, color = Color.White) }
        Row(Modifier.padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (item.sendState) {
                SendState.PENDING -> {
                    KIcon(R.drawable.ic_clock, null, tint = KonectoColors.SubContent, size = 12.dp)
                    Text(
                        if (item.sending) "Sending…" else if (isOnline) "Queued" else "Waiting for connection",
                        style = KonectoType.Metadata2, color = KonectoColors.SubContent,
                    )
                }
                SendState.FAILED -> Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    KIcon(R.drawable.ic_exclamation_circle, null, tint = KonectoColors.Danger, size = 12.dp)
                    Text("Not sent${item.error?.let { ": $it" } ?: ""} · Tap to retry", style = KonectoType.Metadata2, color = KonectoColors.Danger)
                }
                SendState.SENT -> Text(item.time, style = KonectoType.Metadata2, color = KonectoColors.Placeholder)
            }
        }
    }
}

@Composable
fun AssistantMessage(item: MessageItem, onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(AssistantShape)
                .background(KonectoColors.Secondary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item.status?.takeIf { it != AnswerStatus.ANSWERED }?.let { StatusChip(it) }
            Markdown(
                content = item.content,
                colors = markdownColor(text = KonectoColors.Content),
                typography = markdownTypography(text = KonectoType.Body2, paragraph = KonectoType.Body2),
            )
        }
        item.products.forEach { ProductCardView(it) }
        if (item.suggestions.isNotEmpty()) SuggestionChips(item.suggestions, onSuggestion)
        Text(item.time, style = KonectoType.Metadata2, color = KonectoColors.Placeholder, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun StatusChip(status: AnswerStatus) {
    val color = when (status) {
        AnswerStatus.NOT_FOUND, AnswerStatus.NO_MATCH -> KonectoColors.Danger
        AnswerStatus.DATA_UNAVAILABLE, AnswerStatus.CLARIFICATION_NEEDED -> KonectoColors.Warning
        else -> KonectoColors.SubContent
    }
    Text(
        status.label,
        style = KonectoType.Metadata1,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionChips(suggestions: List<String>, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { s ->
            Text(
                s,
                style = KonectoType.Metadata1,
                color = KonectoColors.Content,
                modifier = Modifier
                    .clip(RoundedCornerShape(60.dp))
                    .background(KonectoColors.Black)
                    .border(1.dp, KonectoColors.Primary, RoundedCornerShape(60.dp))
                    .clickable { onClick(s) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Actuator card with the data from the DB (never generated by the LLM). Tap to expand. */
@Composable
fun ProductCardView(product: ProductCard, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(product.partNumber) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(KonectoColors.Black.copy(alpha = 0.92f))
            .border(1.dp, if (product.isAlternative) KonectoColors.Border else KonectoColors.Primary.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(product.partNumber, style = KonectoType.H4, color = KonectoColors.Content, modifier = Modifier.weight(1f))
            Text(
                if (product.isAlternative) "Alternative" else "Match",
                style = KonectoType.Metadata1,
                color = if (product.isAlternative) KonectoColors.SubContent else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (product.isAlternative) KonectoColors.Secondary else KonectoColors.Primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        val motor = product.motorPowerW?.let { " · $it W" } ?: ""
        Text(
            "${product.enclosure.replaceFirstChar { it.uppercase() }} · ${product.powerSupply.label}$motor",
            style = KonectoType.Metadata2, color = KonectoColors.SubContent,
        )
        if (!product.dataAvailable) {
            Text("No data in the datasheet (N/A) for this model.", style = KonectoType.Metadata2, color = KonectoColors.Warning)
        } else {
            product.ratings.forEach { r ->
                LabeledValue(serviceLabel(r.serviceType), "${r.torqueInLb} in-lbs (${r.torqueNm} Nm)")
            }
        }
        if (expanded) {
            if (product.ratings.isNotEmpty()) {
                SectionTitle("Ratings")
                product.ratings.forEach { r -> Text(ratingDetail(r), style = KonectoType.Metadata2, color = KonectoColors.Content) }
            }
            if (product.electrical.isNotEmpty()) {
                SectionTitle("Electrical")
                product.electrical.forEach { e -> Text(electricalDetail(e), style = KonectoType.Metadata2, color = KonectoColors.Content) }
            }
            SectionTitle("Certifications")
            Text(
                product.certifications.joinToString(", ").ifEmpty { "—" } + if (product.csaCertified) "" else " (not CSA certified)",
                style = KonectoType.Metadata2, color = KonectoColors.Content,
            )
            product.source?.let {
                Text("Source: ${it.table} · page ${it.page}", style = KonectoType.Metadata2, color = KonectoColors.Placeholder)
            }
        }
        Text(if (expanded) "Hide details" else "Show details", style = KonectoType.Metadata1, color = KonectoColors.PrimaryLight)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", style = KonectoType.Metadata2, color = KonectoColors.SubContent)
        Text(value, style = KonectoType.Metadata2.copy(fontWeight = FontWeight.SemiBold), color = KonectoColors.Content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = KonectoType.Metadata1, color = KonectoColors.SubContent, modifier = Modifier.padding(top = 4.dp))
}

private fun serviceName(type: String) = when (type) {
    "on_off" -> "On/Off"
    "modulating" -> "Modulating"
    else -> "General" // 3-phase table: one rating without the On/Off-Modulating split
}

private fun serviceLabel(type: String) = "${serviceName(type)} torque"

private fun ratingDetail(r: ServiceRating): String = buildString {
    append(serviceName(r.serviceType))
    append(": duty ").append(r.dutyCyclePct).append("%")
    r.cyclesPerHour?.let { append(" · ").append(it).append(" cycles/h") }
    r.startsPerHour?.let { append(" · ").append(it).append(" starts/h") }
}

private fun electricalDetail(e: ElectricalPoint): String =
    "${e.frequencyHz?.let { "$it Hz" } ?: "Any frequency"}: ${e.operatingSpeedS} s · FLA ${e.flaA} A · LRA ${e.lraA} A"

/** "typing…" bubble while the agent answers. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier.clip(AssistantShape).background(KonectoColors.Secondary).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(KonectoColors.Content))
        }
    }
}
