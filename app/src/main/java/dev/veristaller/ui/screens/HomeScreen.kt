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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.JobUiState
import dev.veristaller.domain.model.Outcome
import dev.veristaller.root.DeviceFacts
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.IconAction
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.VeriCard
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
    onInstall: () -> Unit,
    onSettings: () -> Unit,
    onOpenResult: () -> Unit,
) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    var showRootDetails by rememberSaveable { mutableStateOf(false) }

    ScreenFrame(
        title = "Veristaller",
        actions = {
            RootChip(facts) {
                vm.refresh()
                showRootDetails = true
            }
            IconAction(Icons.Filled.Settings, "Settings", onSettings)
        },
    ) {
        Spacer(Modifier.height(6.dp))
        InstallCard(
            running = job.running,
            rootState = facts?.isRoot,
            onClick = onInstall,
        )
        if (job.running || job.outcome != null) {
            Spacer(Modifier.height(12.dp))
            LastJobCard(job = job, onOpen = onOpenResult)
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showRootDetails) {
        AlertDialog(
            onDismissRequest = { showRootDetails = false },
            title = { Text("Root") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValueRow("Manager", facts?.manager ?: "checking…")
                    KeyValueRow("Shell", facts?.shellMode?.ifBlank { "—" } ?: "—")
                    KeyValueRow("UID", facts?.uid ?: "—")
                    KeyValueRow("ABI", facts?.abiList?.joinToString() ?: "—")
                    KeyValueRow("SELinux", facts?.selinux ?: "—")
                    val missing = facts?.missingTools.orEmpty()
                    if (missing.isNotEmpty()) {
                        KeyValueRow("Missing tools", missing.joinToString(), valueColor = DangerRed)
                    }
                    facts?.error?.let { KeyValueRow("Error", it, valueColor = DangerRed) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRootDetails = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun RootChip(facts: DeviceFacts?, onClick: () -> Unit) {
    val (text, color) = when {
        facts == null -> "…" to TextTertiary
        facts.isRoot -> "root" to SuccessGreen
        else -> "no root" to DangerRed
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50))
            .clickable(onClickLabel = "Root status", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun InstallCard(running: Boolean, rootState: Boolean?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceHigh)
            .border(1.dp, OutlineColor, RoundedCornerShape(22.dp))
            .clickable(onClickLabel = "Install", onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(PrimaryBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (running) Icons.Filled.Refresh else Icons.Filled.Add,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (running) "Job running" else "Install",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Text(
                text = when {
                    running -> "Tap to see progress"
                    rootState == null -> "Checking root…"
                    rootState -> "Bundle + patched APK"
                    else -> "Root access required"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rootState == true || running) TextSecondary else WarningAmber,
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
private fun LastJobCard(job: JobUiState, onOpen: () -> Unit) {
    VeriCard(
        modifier = Modifier.clickable(
            enabled = !job.running,
            onClickLabel = "Open result",
            onClick = onOpen,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    maxLines = 1,
                )
                Text(
                    text = if (job.running) job.title.ifBlank { "Working…" } else "Last result",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            val (status, color) = when (job.outcome) {
                is Outcome.Success -> "done" to SuccessGreen
                is Outcome.Failed -> "failed" to DangerRed
                is Outcome.DryRun -> "dry run" to WarningAmber
                else -> "running" to PrimaryBlue
            }
            Pill(text = status, color = color, background = color.copy(alpha = 0.09f))
        }
    }
}
