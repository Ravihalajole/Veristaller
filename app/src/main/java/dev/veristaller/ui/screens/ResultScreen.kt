package dev.veristaller.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.JobUiState
import dev.veristaller.domain.model.Outcome
import dev.veristaller.domain.model.SwapDetails
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.ExportLogButton
import dev.veristaller.ui.components.InfoNote
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.MonoText
import dev.veristaller.ui.components.PrimaryButton
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.TonalButton
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.InfoPurple
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SuccessGreen
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.TerminalBg
import dev.veristaller.ui.theme.TerminalText
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

@Composable
fun ResultScreen(
    vm: AppViewModel,
    onHome: () -> Unit,
    onTryAgain: () -> Unit,
) {
    val job by vm.job.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val outcome = job.outcome
    val accent = when (outcome) {
        is Outcome.Success -> SuccessGreen
        is Outcome.Failed -> DangerRed
        else -> InfoPurple
    }
    val headline = when (outcome) {
        is Outcome.Success -> "Patched APK is live"
        is Outcome.Failed -> "Couldn't complete"
        is Outcome.DryRun -> "Dry run complete"
        null -> "No result"
    }
    val status = when (outcome) {
        is Outcome.Success -> "success"
        is Outcome.Failed -> "failed"
        is Outcome.DryRun -> "dry_run"
        null -> "none"
    }

    val logText = job.log.joinToString("\n") { "${it.time}  ${it.text}" }
    val diagnosticsText = buildString {
        appendLine("Veristaller diagnostics ${dev.veristaller.BuildConfig.VERSION_NAME}")
        appendLine("jobId=${job.jobId ?: "none"}")
        appendLine("package=${job.packageName}")
        appendLine("status=$status")
        appendLine("step=${job.step?.name ?: "none"}")
        appendLine("errorCode=${(outcome as? Outcome.Failed)?.error?.code?.name ?: "none"}")
        appendLine("basePath=${(outcome as? Outcome.Success)?.details?.basePath ?: "none"}")
        appendLine("root=${facts?.isRoot ?: false} manager=${facts?.manager ?: "?"}")
        appendLine("android=${facts?.release ?: "?"} sdk=${facts?.sdk ?: 0}")
        appendLine("device=${facts?.model ?: "?"}")
        appendLine("abi=${facts?.abiList?.joinToString() ?: "?"}")
        appendLine("selinux=${facts?.selinux ?: "?"}")
        appendLine("free=${facts?.freeBytes ?: 0}")
        appendLine("missing=${facts?.missingTools?.joinToString() ?: ""}")
        appendLine("--- log ---")
        append(logText)
    }

    ScreenFrame(
        title = job.packageName.ifBlank { "Result" },
        footer = {
            Column(modifier = Modifier.padding(20.dp)) {
                when (outcome) {
                    is Outcome.Success -> PrimaryButton(
                        text = "Open app",
                        onClick = {
                            val intent: Intent? = vm.launchIntent(job.packageName)
                            if (intent != null) {
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    )

                    is Outcome.Failed -> {
                        PrimaryButton(text = "Try again", onClick = onTryAgain)
                        Spacer(Modifier.height(8.dp))
                        TonalButton(text = "Back to home", onClick = onHome)
                    }

                    is Outcome.DryRun -> {
                        val commands = outcome.commands
                        PrimaryButton(text = "Back to home", onClick = onHome)
                        Spacer(Modifier.height(8.dp))
                        TonalButton(
                            text = "Copy commands",
                            onClick = { clipboard.setText(AnnotatedString(commands.joinToString("\n"))) },
                        )
                    }

                    else -> PrimaryButton(text = "Back to home", onClick = onHome)
                }
                Spacer(Modifier.height(8.dp))
                TonalButton(text = "Copy log", onClick = {
                    clipboard.setText(AnnotatedString(job.log.joinToString("\n") { it.text }))
                })
                Spacer(Modifier.height(8.dp))
                ExportLogButton(
                    text = "Export log + device facts",
                    fileName = "veristaller-${job.jobId ?: "result"}-diagnostics.txt",
                    content = { diagnosticsText },
                )
            }
        },
    ) {
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.13f))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (outcome is Outcome.Failed) Icons.Filled.Close else Icons.Filled.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = headline, style = MaterialTheme.typography.titleLarge, color = accent)
                Text(
                    text = job.packageName.ifBlank { "No job selected" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (outcome) {
            is Outcome.Success -> SuccessBody(job = job, details = outcome.details)

            is Outcome.Failed -> FailureBody(
                title = outcome.error.code.title,
                message = outcome.error.message,
                hint = outcome.error.hint,
                raw = outcome.error.raw,
            )

            is Outcome.DryRun -> {
                VeriCard {
                    SectionLabel("Plan")
                    Text(
                        "No changes were made. Turn off Dry run in Settings to execute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TerminalBg)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        MonoText(outcome.commands.take(40).joinToString("\n"), color = TerminalText)
                    }
                }
            }

            null -> InfoNote("Start a job to see a result here.")
        }

        Spacer(Modifier.height(12.dp))
        VeriCard {
            SectionLabel("Timeline")
            KeyValueRow("Status", status.replace('_', ' '))
            KeyValueRow("Step", job.step?.label ?: "—")
            if (job.log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LogSnippet(logLines = job.log.takeLast(4).joinToString("\n") { it.text })
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SuccessBody(job: JobUiState, details: SwapDetails) {
    VeriCard {
        SectionLabel("Installed file")
        Row {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(SuccessGreen),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                MonoText(details.basePath, color = PrimaryBlue)
                Spacer(Modifier.height(2.dp))
                Text(
                    "verified on disk",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Owner", details.owner, mono = true)
        KeyValueRow("Mode", details.mode, mono = true)
        KeyValueRow("SELinux", details.selinux, mono = true)
        KeyValueRow("Size", formatBytes(details.sizeBytes))
    }

    Spacer(Modifier.height(10.dp))
    InfoNote(
        "An app update replaces base.apk and undoes the patch. There is no automatic undo — " +
            "reinstall the app if you need to revert.",
        tint = SuccessGreen,
    )

    if (job.log.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        VeriCard {
            SectionLabel("Last log lines")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TerminalBg)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                MonoText(job.log.takeLast(6).joinToString("\n") { it.text }, color = TerminalText)
            }
        }
    }
}

@Composable
private fun FailureBody(
    title: String,
    message: String,
    hint: String,
    raw: String?,
) {
    VeriCard {
        SectionLabel("What happened")
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceHigh)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(WarningAmber),
            )
            Text(hint, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        }
        if (!raw.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Raw output", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(TerminalBg)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                MonoText(raw.take(1600), color = TextSecondary)
            }
        }
    }
}

@Composable
private fun LogSnippet(logLines: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TerminalBg)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        MonoText(logLines, color = TerminalText)
    }
}
