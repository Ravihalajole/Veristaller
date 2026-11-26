package dev.veristaller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.JobUiState
import dev.veristaller.domain.model.Outcome
import dev.veristaller.root.DeviceFacts
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.BottomNav
import dev.veristaller.ui.components.InfoNote
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SuccessGreen
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onNewInstall: () -> Unit,
    onSettings: () -> Unit,
    onOpenResult: () -> Unit,
) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()

    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    var showRootDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.disclosureAccepted, settingsReady) {
        showDisclosure = settingsReady && !settings.disclosureAccepted
    }

    ScreenFrame(
        title = "Veristaller",
        subtitle = "root installer",
        actions = {
            Box(modifier = Modifier.clickable(onClickLabel = "Root probe details") {
                vm.refresh()
                showRootDetails = true
            }) {
                RootChip(facts?.isRoot, facts?.manager)
            }
        },
        bottom = {
            BottomNav(
                selected = "home",
                onHome = {},
                onSettings = onSettings,
            )
        },
    ) {
        Spacer(Modifier.height(4.dp))
        NewInstallCard(enabled = facts?.isRoot == true, onClick = onNewInstall)
        DeviceCard(facts)
        LastJobCard(job = job, onOpen = onOpenResult)
        InfoNote("Only /data/app is modified. No backup is taken — reinstall the app to undo a failed swap.")
        Spacer(Modifier.height(10.dp))
    }

    if (showDisclosure) {
        DisclosureDialog(
            onAccept = {
                vm.acceptDisclosure()
                showDisclosure = false
            },
        )
    }

    if (showRootDetails) {
        AlertDialog(
            onDismissRequest = { showRootDetails = false },
            title = { Text("Root probe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValueRow("Manager", facts?.manager ?: "checking…")
                    KeyValueRow("UID", facts?.uid ?: "—")
                    KeyValueRow("ABI", facts?.abiList?.joinToString() ?: "—")
                    KeyValueRow("SELinux", facts?.selinux ?: "—")
                    KeyValueRow(
                        "Missing tools",
                        facts?.missingTools?.joinToString().takeUnless { it.isNullOrBlank() } ?: "none",
                    )
                    facts?.error?.let { KeyValueRow("Probe", it, valueColor = DangerRed) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRootDetails = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun RootChip(isRoot: Boolean?, manager: String?) {
    val (text, color) = when {
        isRoot == null -> "checking…" to TextTertiary
        isRoot -> "${manager?.ifBlank { "su" } ?: "su"} · root" to SuccessGreen
        else -> "no root" to DangerRed
    }
    Pill(text = text, color = color, background = color.copy(alpha = 0.08f))
}

@Composable
private fun NewInstallCard(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceHigh)
            .border(1.dp, OutlineColor, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClickLabel = "Start a new install", onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(PrimaryBlue.copy(alpha = 0.14f))
                .border(1.dp, PrimaryBlue.copy(alpha = 0.25f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "New install",
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) TextPrimary else TextSecondary,
            )
            Text(
                text = if (enabled) "Split bundle + patched APK" else "Root access is required",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DeviceCard(facts: DeviceFacts?) {
    VeriCard(modifier = Modifier.padding(top = 14.dp)) {
        SectionLabel("Device")
        KeyValueRow("Root manager", facts?.manager?.ifBlank { "—" } ?: "checking…")
        KeyValueRow(
            "System",
            if (facts == null) "—" else "Android ${facts.release} · SDK ${facts.sdk}",
        )
        KeyValueRow("ABI", facts?.primaryAbi?.ifBlank { "—" } ?: "—")
        KeyValueRow(
            "SELinux",
            facts?.selinux?.ifBlank { "unknown" } ?: "—",
            valueColor = if (facts?.selinux == "enforcing") TextPrimary else WarningAmber,
        )
        KeyValueRow(
            "Free on /data",
            if (facts == null || facts.freeBytes == 0L) "—" else formatBytes(facts.freeBytes),
        )
        val missing = facts?.missingTools.orEmpty()
        if (missing.isNotEmpty()) {
            KeyValueRow("Missing tools", missing.joinToString(", "), valueColor = DangerRed)
        }
        facts?.error?.let { KeyValueRow("Probe", it, valueColor = DangerRed) }
    }
}

@Composable
private fun LastJobCard(job: JobUiState, onOpen: () -> Unit) {
    VeriCard(modifier = Modifier.padding(top = 12.dp)) {
        SectionLabel("Last job")
        when {
            job.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(PrimaryBlue))
                Spacer(Modifier.width(10.dp))
                Text("Job running · ${job.title}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }

            job.outcome != null -> Row(
                modifier = Modifier.fillMaxWidth().clickable(
                    onClickLabel = "Open last result",
                    onClick = onOpen,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = job.packageName.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryBlue,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        job.packageName.ifBlank { "Last job" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                    Text("Open result", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                val (status, color) = when (job.outcome) {
                    is Outcome.Success -> "success" to SuccessGreen
                    is Outcome.Failed -> "failed" to DangerRed
                    is Outcome.DryRun -> "dry run" to WarningAmber
                    else -> "unknown" to TextTertiary
                }
                Pill(text = status, color = color, background = color.copy(alpha = 0.09f))
            }

            else -> Text(
                "No result yet. Pick a bundle to begin.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun DisclosureDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Before you start") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Veristaller writes inside installed app directories. Read this once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Bullet("Root is required. Every privileged action runs through your root manager's su.")
                Bullet("Only /data/app is touched. System partitions stay read-only.")
                Bullet("No backup is taken. A failed swap may leave the app unusable until you reinstall it.")
                Text(
                    "A modified app can break after an app update, or trigger its own tamper checks. " +
                        "In the worst case a mistake can require a factory reset or ROM reflash. " +
                        "Keep a copy of the original APK if you may need it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("I understand", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Row {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(PrimaryBlue),
        )
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
