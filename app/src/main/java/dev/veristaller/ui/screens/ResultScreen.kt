package dev.veristaller.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.Outcome
import dev.veristaller.domain.model.SwapDetails
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.ExportLogTextButton
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.MonoText
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.PrimaryButton
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
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
    var showRaw by rememberSaveable { mutableStateOf(false) }

    val outcome = job.outcome
    val accent = when (outcome) {
        is Outcome.Success -> SuccessGreen
        is Outcome.Failed -> DangerRed
        else -> InfoPurple
    }
    val headline = when (outcome) {
        is Outcome.Success -> "Patched APK is live"
        is Outcome.Failed -> "Install failed"
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
                        icon = Icons.Filled.PlayArrow,
                        onClick = {
                            val intent: Intent? = vm.launchIntent(job.packageName)
                            if (intent != null) {
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    )

                    is Outcome.Failed -> PrimaryButton(
                        text = "Try again",
                        icon = Icons.Filled.Refresh,
                        onClick = onTryAgain,
                    )

                    else -> PrimaryButton(
                        text = "Home",
                        icon = Icons.Filled.Home,
                        onClick = onHome,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(logText))
                    }) {
                        Text("Copy log", color = TextSecondary)
                    }
                    ExportLogTextButton(
                        text = "Save log",
                        fileName = "veristaller-${job.jobId ?: "result"}-diagnostics.txt",
                        content = { diagnosticsText },
                    )
                }
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
            is Outcome.Success -> SuccessBody(details = outcome.details)

            is Outcome.Failed -> FailureBody(
                title = outcome.error.code.title,
                message = outcome.error.message,
                hint = outcome.error.hint,
                raw = outcome.error.raw,
                showRaw = showRaw,
                onToggleRaw = { showRaw = !showRaw },
            )

            is Outcome.DryRun -> VeriCard {
                SectionLabel("Dry run")
                Text(
                    text = "${outcome.commands.size} commands were printed. Nothing was installed or replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(outcome.commands.joinToString("\n")))
                }) {
                    Text("Copy commands", color = PrimaryBlue)
                }
            }

            null -> Text(
                "Start an install to see a result here.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SuccessBody(details: SwapDetails) {
    VeriCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Installed file")
            Spacer(Modifier.weight(1f))
            Pill("verified", SuccessGreen, SuccessGreen.copy(alpha = 0.1f))
        }
        MonoText(details.basePath, color = PrimaryBlue)
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Size", formatBytes(details.sizeBytes))
        KeyValueRow("Owner", details.owner, mono = true)
        KeyValueRow("Mode", details.mode, mono = true)
        KeyValueRow("SELinux", details.selinux, mono = true)
    }
}

@Composable
private fun FailureBody(
    title: String,
    message: String,
    hint: String,
    raw: String?,
    showRaw: Boolean,
    onToggleRaw: () -> Unit,
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
            Text(hint, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        }
        if (!raw.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onToggleRaw) {
                Text(
                    text = if (showRaw) "Hide raw output" else "Show raw output",
                    color = TextSecondary,
                )
            }
            if (showRaw) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TerminalBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    MonoText(raw.take(1600), color = TerminalText)
                }
            }
        }
    }
}
