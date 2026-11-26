package dev.veristaller.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.BundleInfo
import dev.veristaller.domain.model.SplitApk
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.InfoNote
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.MonoText
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.PrimaryButton
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.TonalButton
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.InfoPurple
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.SurfaceLow
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

private val BUNDLE_MIME = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/vnd.android.package-archive",
    "application/octet-stream",
)

@Composable
fun BundleScreen(vm: AppViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBundle(uri)
        else vm.dismissError()
    }

    ScreenFrame(
        title = "Bundle",
        subtitle = "Step 1 of 3",
        onBack = onBack,
        footer = {
            Column(modifier = Modifier.padding(20.dp)) {
                PrimaryButton(
                    text = if (bundle == null) "Choose a bundle" else "Continue",
                    enabled = bundle != null && busy == null && bundle?.packageName != null,
                    onClick = { if (bundle == null) picker.launch(BUNDLE_MIME) else onNext() },
                )
                if (bundle != null) {
                    Spacer(Modifier.height(8.dp))
                    TonalButton(text = "Pick another file", onClick = {
                        picker.launch(BUNDLE_MIME)
                    })
                }
            }
        },
    ) {
        Spacer(Modifier.height(4.dp))
        InfoNote("Veristaller reads the bundle, installs the splits for this device, then swaps base.apk.")
        Spacer(Modifier.height(6.dp))

        if (bundle == null) {
            PickCard(
                title = if (busy != null) busy ?: "" else "Choose .apks, .apkm or .xapk",
                subtitle = "Tap to open the system file picker",
                onClick = { picker.launch(BUNDLE_MIME) },
            )
        } else {
            BundleCard(bundle!!)
            Spacer(Modifier.height(12.dp))
            SplitTable(bundle!!, vm)
        }

        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            ErrorBox(message) { vm.dismissError() }
        }

        if (bundle != null && settings.splitMode != dev.veristaller.domain.model.SplitMode.SMART) {
            Spacer(Modifier.height(8.dp))
            InfoNote("Split mode is ${settings.splitMode.label}. Change it in Settings.")
        }
    }
}

@Composable
private fun BundleCard(bundle: BundleInfo) {
    VeriCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(InfoPurple.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bundle.format.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = InfoPurple,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bundle.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = "${bundle.format.label} · ${formatBytes(bundle.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Pill(
                text = bundle.format.label.uppercase(),
                color = InfoPurple,
                background = InfoPurple.copy(alpha = 0.1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Package", bundle.packageName ?: "unknown", mono = true)
        KeyValueRow(
            "Version",
            listOfNotNull(bundle.versionName, bundle.versionCode?.toString())
                .joinToString(" · ")
                .ifBlank { "unknown" },
        )
        KeyValueRow("Splits", "${bundle.splits.size}")
        if (bundle.packageName == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "The base APK inside could not be read. The bundle may be corrupt.",
                style = MaterialTheme.typography.bodySmall,
                color = DangerRed,
            )
        }
    }
}

@Composable
private fun SplitTable(bundle: BundleInfo, vm: AppViewModel) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val chosenIds = remember(bundle, facts, settings) {
        vm.selectedSplits().map { it.entryName }.toSet()
    }
    VeriCard {
        SectionLabel("Splits")
        bundle.splits.take(10).forEach { split ->
            SplitRow(split, split.entryName in chosenIds)
        }
        if (bundle.splits.size > 10) {
            Text(
                "+ ${bundle.splits.size - 10} more splits",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${chosenIds.size} selected for this device · ${bundle.splits.size - chosenIds.size} skipped",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun SplitRow(split: SplitApk, chosen: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (chosen) PrimaryBlue else OutlineColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = split.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = if (chosen) TextPrimary else TextTertiary,
                maxLines = 1,
            )
            if (!chosen) {
                Text(
                    "skipped on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Pill(
            text = split.qualifier,
            color = if (chosen) PrimaryBlue else TextTertiary,
            background = if (chosen) PrimaryBlue.copy(alpha = 0.08f) else Color.Transparent,
        )
    }
}

@Composable
fun PickCard(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineColor, RoundedCornerShape(20.dp))
            .clickable(onClickLabel = "Select $title", onClick = onClick)
            .padding(vertical = 30.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PrimaryBlue.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
fun ErrorBox(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DangerRed.copy(alpha = 0.08f))
            .border(1.dp, DangerRed.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Dismiss error", onClick = onDismiss)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = DangerRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = DangerRed)
            Text(
                "Tap to dismiss",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
fun MatchStrip(label: String, state: MatchState, detail: String) {
    val (icon, color) = when (state) {
        MatchState.OK -> "✓" to dev.veristaller.ui.theme.SuccessGreen
        MatchState.WARN -> "!" to WarningAmber
        MatchState.BAD -> "×" to DangerRed
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            label,
            modifier = Modifier.width(84.dp),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Text(
            detail,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
    }
}

enum class MatchState { OK, WARN, BAD }

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(dev.veristaller.ui.theme.TerminalBg)
            .border(1.dp, OutlineColor, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        MonoText(text, color = dev.veristaller.ui.theme.TerminalText)
    }
}
