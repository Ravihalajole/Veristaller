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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.CheckResult
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.CheckRowView
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
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

@Composable
fun ReviewScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val patch by vm.patch.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val appRunning by vm.appRunning.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val acknowledged by vm.acknowledged.collectAsStateWithLifecycle()

    val checks = remember(bundle, patch, installed, facts, appRunning, settings) {
        vm.checks()
    }
    val splits = remember(bundle, facts, settings) { vm.selectedSplits() }
    var showCommands by rememberSaveable { mutableStateOf(false) }
    var confirmStart by rememberSaveable { mutableStateOf(false) }

    val failed = checks.filter { it.severity == CheckResult.Severity.FAIL }
    val pendingAck = checks.filter {
        it.severity == CheckResult.Severity.WARN && it.requiresAck && it.id !in acknowledged
    }
    val ready = failed.isEmpty() && pendingAck.isEmpty()

    ScreenFrame(
        title = "Review",
        subtitle = "Step 3 of 3",
        onBack = onBack,
        footer = {
            Column(modifier = Modifier.padding(20.dp)) {
                PrimaryButton(
                    text = when {
                        failed.isNotEmpty() -> "Fix required"
                        settings.dryRun -> "Dry run (no changes)"
                        else -> "Start install"
                    },
                    enabled = ready,
                    onClick = { if (settings.dryRun) onStart() else confirmStart = true },
                )
                Spacer(Modifier.height(8.dp))
                TonalButton(text = "Back", onClick = onBack)
                if (pendingAck.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Acknowledge ${pendingAck.size} warning${if (pendingAck.size == 1) "" else "s"} to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
            }
        },
    ) {
        Spacer(Modifier.height(4.dp))

        if (settings.dryRun) {
            InfoNote(
                "Dry run is on. Nothing will be installed or replaced — the commands are printed only.",
                tint = InfoPurple,
            )
        }

        VeriCard {
            SectionLabel("Target")
            KeyValueRow(
                "App",
                listOfNotNull(installed?.label, patch?.packageName).joinToString(" · "),
            )
            KeyValueRow("Version", patch?.versionName ?: patch?.versionCode?.toString() ?: "—")
            KeyValueRow(
                "Action",
                "replace base.apk · install ${splits.size} split${if (splits.size == 1) "" else "s"}",
            )
            KeyValueRow(
                "Payload",
                "${bundle?.format?.label ?: "—"} · ${formatBytes((bundle?.totalBytes ?: 0L) + (patch?.sizeBytes ?: 0L))}",
            )
            KeyValueRow(
                "Current path",
                installed?.baseApk ?: "resolved after install",
                mono = true,
                valueColor = TextSecondary,
            )
        }

        Spacer(Modifier.height(12.dp))

        VeriCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Checks")
                Spacer(Modifier.weight(1f))
                val passed = checks.count { it.severity == CheckResult.Severity.PASS }
                Pill(
                    text = "$passed/${checks.size} pass",
                    color = if (failed.isEmpty()) PrimaryBlue else DangerRed,
                    background = (if (failed.isEmpty()) PrimaryBlue else DangerRed).copy(alpha = 0.08f),
                )
            }
            checks.forEach { check ->
                CheckRowView(
                    check = check,
                    acknowledged = check.id in acknowledged,
                    onToggleAck = vm::toggleAck,
                )
            }
        }

        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DangerRed.copy(alpha = 0.07f))
                    .border(1.dp, DangerRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(DangerRed))
                Spacer(Modifier.width(9.dp))
                Text(
                    "${failed.size} check${if (failed.size == 1) "" else "s"} must pass before starting. " +
                        "Nothing has been changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DangerRed,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceHigh)
                .clickable(onClickLabel = if (showCommands) "Hide commands" else "Show commands") {
                    showCommands = !showCommands
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (showCommands) "Hide commands" else "Show commands",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${vm.previewCommands().size} lines",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }

        if (showCommands) {
            Spacer(Modifier.height(8.dp))
            MonoBlock(vm.previewCommands().joinToString("\n"))
            Spacer(Modifier.height(6.dp))
            Text(
                "These are the exact privileged steps. Paths are resolved on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }

        if (settings.clearOat) {
            Spacer(Modifier.height(10.dp))
            InfoNote("Compiled code in oat/ will be deleted so the patched APK is recompiled.")
        }

        Spacer(Modifier.height(20.dp))
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Replace base.apk?") },
            text = {
                Text(
                    "${installed?.label ?: patch?.packageName.orEmpty()} will be stopped, the bundle " +
                        "will be installed, and its base.apk replaced with the patched file. " +
                        "A hash-verified backup is kept first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    onStart()
                }) {
                    Text("Start", fontWeight = FontWeight.SemiBold, color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text("Cancel") }
            },
        )
    }
}
