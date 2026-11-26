package dev.veristaller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.veristaller.domain.model.CheckResult
import dev.veristaller.domain.model.StepId
import dev.veristaller.ui.theme.Bg
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.InfoPurple
import dev.veristaller.ui.theme.MonoStyle
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SuccessGreen
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.SurfaceLow
import dev.veristaller.ui.theme.SurfaceTop
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScreenFrame(
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (title != null) {
            AppBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
        }
        val body = Modifier.weight(1f).fillMaxWidth()
        if (scroll) {
            Column(
                modifier = body
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                content = content,
            )
        } else {
            Column(modifier = body.padding(horizontal = 20.dp), content = content)
        }
        footer?.invoke()
        bottom?.invoke()
    }
}

@Composable
fun AppBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceLow)
                    .border(1.dp, OutlineColor, RoundedCornerShape(14.dp))
                    .clickable(onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}

@Composable
fun Pill(
    text: String,
    color: Color = TextSecondary,
    background: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
    )
}

@Composable
fun VeriCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineColor, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        modifier = modifier.padding(top = 6.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
    )
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = if (mono) MonoStyle else MaterialTheme.typography.bodySmall,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
fun MonoText(text: String, color: Color = TextPrimary, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = MonoStyle, color = color)
}

@Composable
fun CheckRowView(
    check: CheckResult,
    acknowledged: Boolean,
    onToggleAck: (String) -> Unit,
) {
    val tint = when (check.severity) {
        CheckResult.Severity.PASS -> SuccessGreen
        CheckResult.Severity.WARN -> WarningAmber
        CheckResult.Severity.FAIL -> DangerRed
    }
    val icon = when (check.severity) {
        CheckResult.Severity.PASS -> Icons.Filled.Check
        CheckResult.Severity.WARN -> Icons.Filled.Warning
        CheckResult.Severity.FAIL -> Icons.Filled.Close
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = check.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
            )
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (check.requiresAck) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (acknowledged) SuccessGreen.copy(alpha = 0.1f) else SurfaceHigh)
                        .border(
                            1.dp,
                            if (acknowledged) SuccessGreen.copy(alpha = 0.4f) else OutlineColor,
                            RoundedCornerShape(9.dp),
                        )
                        .clickable(
                            onClickLabel = if (acknowledged) {
                                "Clear acknowledgement: ${check.title}"
                            } else {
                                "Acknowledge: ${check.title}"
                            },
                            onClick = { onToggleAck(check.id) },
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (acknowledged) SuccessGreen else TextTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (acknowledged) "Acknowledged" else "I understand",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (acknowledged) SuccessGreen else TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
fun StepStrip(step: StepId?, completed: Set<StepId>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StepId.entries.forEach { id ->
            val done = id in completed
            val active = id == step
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> SuccessGreen
                                active -> PrimaryBlue
                                else -> SurfaceTop
                            },
                        )
                        .border(
                            1.dp,
                            if (active) PrimaryBlue else OutlineColor,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (active && !done) {
                        Box(
                            Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF00243D)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = id.label,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontSize = 9.sp),
                    color = if (done || active) TextPrimary else TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun BottomNav(
    selected: String,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(OutlineColor.copy(alpha = 0.6f)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceHigh)
                .padding(vertical = 8.dp),
        ) {
            NavItem("Home", Icons.Filled.Home, selected == "home", onHome, Modifier.weight(1f))
            NavItem("Settings", Icons.Filled.Settings, selected == "settings", onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) PrimaryBlue else TextTertiary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontSize = 10.sp),
            color = if (active) PrimaryBlue else TextTertiary,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Writes a text snapshot to a user-chosen location through SAF. */
@Composable
fun ExportLogButton(text: String, fileName: String, content: () -> String) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content().toByteArray())
                }
            }
        }
    }
    TonalButton(text = text, onClick = { launcher.launch(fileName) })
}

@Composable
fun InfoNote(text: String, tint: Color = InfoPurple) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(epochMs))

fun formatRelative(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    val minutes = diff / 60000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1440 -> "${minutes / 60} h ago"
        else -> "${minutes / 1440} d ago"
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
