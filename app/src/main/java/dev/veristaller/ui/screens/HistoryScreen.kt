@file:OptIn(ExperimentalFoundationApi::class)

package dev.veristaller.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.data.BackupRecord
import dev.veristaller.data.JobRecord
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.BottomNav
import dev.veristaller.ui.components.InfoNote
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.MonoText
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.TonalButton
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.components.formatDateTime
import dev.veristaller.ui.components.formatRelative
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.InfoPurple
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.SurfaceLow
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary

@Composable
fun HistoryScreen(
    vm: AppViewModel,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    val jobs by vm.history.collectAsStateWithLifecycle()
    val backups by vm.backups.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var detail by remember { mutableStateOf<JobRecord?>(null) }
    var confirmRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupRecord?>(null) }
    var actionsFor by remember { mutableStateOf<JobRecord?>(null) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = actionsFor
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(vm.readLog(target).toByteArray())
                }
            }
        }
    }

    ScreenFrame(
        title = "History",
        subtitle = "${jobs.size} jobs · ${backups.size} backups",
        bottom = {
            BottomNav(
                selected = "history",
                onHome = onHome,
                onHistory = {},
                onSettings = onSettings,
            )
        },
    ) {
        Spacer(Modifier.height(6.dp))
        error?.let { message ->
            ErrorBox(message) { vm.dismissError() }
            Spacer(Modifier.height(10.dp))
        }
        Segmented(
            options = listOf("Jobs", "Backups"),
            selected = tab,
            onSelect = { tab = it },
        )
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            if (jobs.isEmpty()) {
                EmptyState("No jobs yet", "Every install and restore is recorded here with its result.")
            } else {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                var lastDay = ""
                jobs.forEach { job ->
                    val day = runCatching {
                        dateFormat.format(java.util.Date(job.timestamp))
                    }.getOrDefault("")
                    if (day != lastDay) {
                        SectionLabel(day)
                        lastDay = day
                    }
                    JobRow(
                        record = job,
                        onClick = { detail = job },
                        onLongClick = { actionsFor = job },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                TonalButton(text = "Clear history", onClick = vm::clearHistory)
            }
        } else {
            val total = backups.sumOf { it.sizeBytes }
            InfoNote("Backups live in /data/local/tmp/veristaller/backups and survive app updates.")
            if (backups.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                EmptyState("No backups", "A verified backup is created before every swap.")
            } else {
                VeriCard {
                    SectionLabel("Storage")
                    KeyValueRow("Total", formatBytes(total))
                    KeyValueRow("Count", backups.size.toString())
                    KeyValueRow(
                        "Retention",
                        if (settings.keepBackups == 0) "keep all" else "keep ${settings.keepBackups} per app",
                    )
                }
                Spacer(Modifier.height(10.dp))
                backups.forEach { backup ->
                    BackupRow(
                        backup = backup,
                        onRestore = { confirmRestore = backup },
                        onDelete = { confirmDelete = backup },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    detail?.let { record ->
        JobDetailDialog(vm = vm, record = record, onDismiss = { detail = null })
    }

    actionsFor?.let { record ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(record.label) },
            text = { Text("Actions for this job", style = MaterialTheme.typography.bodySmall, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { actionsFor = null }) { Text("Close") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        exportLauncher.launch("veristaller-${record.id}.log")
                    }) {
                        Text("Export log", color = PrimaryBlue)
                    }
                    backups.firstOrNull { it.path == record.backupPath }?.let { backup ->
                        TextButton(onClick = {
                            confirmRestore = backup
                            actionsFor = null
                        }) {
                            Text("Restore backup", color = PrimaryBlue)
                        }
                    }
                    TextButton(onClick = {
                        vm.deleteJob(record)
                        actionsFor = null
                    }) {
                        Text("Delete entry", color = DangerRed)
                    }
                }
            },
        )
    }

    confirmRestore?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Restores ${target.versionName ?: target.versionCode} " +
                        "(${formatBytes(target.sizeBytes)}) over the current base.apk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    vm.restore(target)
                }) {
                    Text("Restore", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this backup?") },
            text = {
                Text(
                    "The ${target.versionName ?: target.versionCode} copy of base.apk will be removed " +
                        "from /data. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.deleteBackup(target)
                }) {
                    Text("Delete", color = DangerRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun JobRow(record: JobRecord, onClick: () -> Unit, onLongClick: () -> Unit) {
    val accent = statusColor(record.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClickLabel = "Open job details",
                onLongClickLabel = "Job actions",
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = record.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = PrimaryBlue,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(record.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(
                text = "${record.action} · ${record.versionName ?: ""} · " +
                    "${formatRelative(record.timestamp)} · ${record.durationMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (record.errorMessage != null) {
                Text(
                    record.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = DangerRed,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Pill(
            text = record.status.replace('_', ' '),
            color = accent,
            background = accent.copy(alpha = 0.09f),
        )
    }
}

@Composable
private fun BackupRow(
    backup: BackupRecord,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    VeriCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    backup.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = "${backup.versionName ?: backup.versionCode} · ${formatBytes(backup.sizeBytes)} · " +
                        formatDateTime(backup.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete backup",
                    tint = TextTertiary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        MonoText(backup.path, color = TextTertiary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("sha256 ${backup.sha256.take(8)}…", color = TextTertiary)
            Pill("${backup.owner} · ${backup.mode}", color = TextTertiary)
        }
        Spacer(Modifier.height(8.dp))
        TonalButton(text = "Restore", onClick = onRestore)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) PrimaryBlue.copy(alpha = 0.16f) else SurfaceHigh)
                    .clickable(onClickLabel = "Show $option", onClick = { onSelect(index) })
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) PrimaryBlue else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .border(1.dp, OutlineColor, RoundedCornerShape(18.dp))
            .padding(vertical = 32.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(InfoPurple))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun JobDetailDialog(vm: AppViewModel, record: JobRecord, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.label) },
        text = {
            Column {
                KeyValueRow("Package", record.packageName, mono = true)
                KeyValueRow("Action", record.action)
                KeyValueRow("Status", record.status.replace('_', ' '), valueColor = statusColor(record.status))
                KeyValueRow("Duration", "${record.durationMs} ms")
                KeyValueRow("When", formatDateTime(record.timestamp))
                KeyValueRow("Base", record.basePath ?: "—", mono = true, valueColor = TextSecondary)
                KeyValueRow("Backup", record.backupPath ?: "—", mono = true, valueColor = TextSecondary)
                KeyValueRow("Log", record.logPath ?: "not saved", mono = true, valueColor = TextTertiary)
                record.errorCode?.let {
                    KeyValueRow("Error code", it, mono = true, valueColor = DangerRed)
                }
                record.errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = DangerRed)
                }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Timeline")
                val lines = vm.readLog(record).lineSequence().toList().takeLast(12)
                if (lines.isEmpty()) {
                    Text("No step events were recorded.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                } else {
                    lines.forEach { line ->
                        MonoText(line, color = if (line.contains("[event]")) TextSecondary else TextTertiary)
                    }
                }
                val commands = lines.filter { it.contains("\$ ") }
                if (commands.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel("Commands")
                    commands.forEach { MonoText(it, color = TextTertiary) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = PrimaryBlue) }
        },
    )
}
