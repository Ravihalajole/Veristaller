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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.data.BackupRecord
import dev.veristaller.data.JobRecord
import dev.veristaller.domain.model.JobUiState
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
import dev.veristaller.ui.components.formatRelative
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.InfoPurple
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
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onOpenResult: () -> Unit,
) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()

    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    var showRootDetails by rememberSaveable { mutableStateOf(false) }
    var confirmBackup by rememberSaveable { mutableStateOf<BackupRecord?>(null) }
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
                onHistory = onHistory,
                onSettings = onSettings,
            )
        },
    ) {
        Spacer(Modifier.height(4.dp))
        recovery?.let { incomplete ->
            VeriCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, WarningAmber, RoundedCornerShape(14.dp)),
            ) {
                SectionLabel("Interrupted job")
                Text(
                    "A previous run stopped while base.apk was being replaced for " +
                        "${incomplete.packageName}. Restore the verified backup to be safe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { vm.restoreIncomplete() }) {
                        Text("Restore backup", color = WarningAmber, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { vm.dismissRecovery() }) {
                        Text("Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        NewInstallCard(enabled = facts?.isRoot == true, onClick = onNewInstall)
        DeviceCard(facts)
        LastJobCard(
            job = job,
            lastRecord = history.firstOrNull(),
            lastBackup = backups.maxByOrNull { it.createdAt },
            onOpen = onOpenResult,
            onRestore = { confirmBackup = it },
        )
        InfoNote("Only /data/app is modified. A hash-verified backup is taken before every swap.")
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

    confirmBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmBackup = null },
            title = { Text("Restore last backup?") },
            text = {
                Text(
                    "Replace base.apk with ${backup.versionName ?: backup.versionCode}. " +
                        "The app will be stopped and a verified hash check will run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.restore(backup)
                    confirmBackup = null
                }) {
                    Text("Restore", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBackup = null }) { Text("Cancel") }
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
private fun LastJobCard(
    job: JobUiState,
    lastRecord: JobRecord?,
    lastBackup: BackupRecord?,
    onOpen: () -> Unit,
    onRestore: (BackupRecord) -> Unit,
) {
    VeriCard(modifier = Modifier.padding(top = 12.dp)) {
        SectionLabel("Last job")
        when {
            job.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(PrimaryBlue))
                Spacer(Modifier.width(10.dp))
                Text("Job running · ${job.title}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }

            lastRecord != null -> Column {
                Row(
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
                            text = lastRecord.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryBlue,
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(lastRecord.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text(
                            text = "${lastRecord.versionName ?: "?"} · ${formatRelative(lastRecord.timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Pill(
                        text = lastRecord.status.replace('_', ' '),
                        color = statusColor(lastRecord.status),
                        background = statusColor(lastRecord.status).copy(alpha = 0.09f),
                    )
                }
                if (lastBackup != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { onRestore(lastBackup) },
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            "Restore last backup · ${lastBackup.versionName ?: lastBackup.versionCode}",
                            color = PrimaryBlue,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            else -> Text(
                "No jobs yet. Pick a bundle to begin.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
fun statusColor(status: String): Color = when (status) {
    JobRecord.STATUS_SUCCESS, JobRecord.STATUS_RESTORED -> SuccessGreen
    JobRecord.STATUS_ROLLED_BACK -> WarningAmber
    JobRecord.STATUS_FAILED -> DangerRed
    else -> InfoPurple
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
                Bullet("A verified backup is taken first, and restored automatically if anything fails.")
                Text(
                    "A modified app can break after an app update, or trigger its own tamper checks. " +
                        "In the worst case a mistake can require a factory reset or ROM reflash. " +
                        "Keep the backup until you have tested it.",
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
