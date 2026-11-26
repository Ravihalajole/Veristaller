package dev.veristaller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.JobLogLine
import dev.veristaller.domain.model.StepId
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.PrimaryButton
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.StepStrip
import dev.veristaller.ui.components.formatClock
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.MonoStyle
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SuccessGreen
import dev.veristaller.ui.theme.TerminalBg
import dev.veristaller.ui.theme.TerminalText
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

@Composable
fun ProgressScreen(vm: AppViewModel, onHome: () -> Unit) {
    val job by vm.job.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var paused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(job.log.size, paused) {
        if (!paused && job.log.isNotEmpty()) {
            listState.animateScrollToItem(job.log.lastIndex)
        }
    }

    ScreenFrame(
        title = job.packageName.ifBlank { "Working" },
        subtitle = if (job.running) "Do not close the app" else "Finished",
        scroll = false,
        footer = {
            Column(modifier = Modifier.padding(20.dp)) {
                PrimaryButton(
                    text = if (job.running) "Run in background" else "Continue",
                    onClick = onHome,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (job.running) {
                        "The job keeps running while this screen stays open. Closing the app can stop it mid-swap."
                    } else {
                        "The result screen has the details."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        },
    ) {
        Spacer(Modifier.height(4.dp))
        StepStrip(step = job.step, completed = job.completed)
        if (job.outcome is dev.veristaller.domain.model.Outcome.Failed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Stopped at ${job.step?.label ?: "an early check"}. See the log below.",
                style = MaterialTheme.typography.bodySmall,
                color = DangerRed,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.title.ifBlank { "Starting…" },
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                val index = job.step?.let { StepId.entries.indexOf(it) + 1 } ?: 0
                Text(
                    text = if (job.step != null) "Step $index of ${StepId.entries.size}" else job.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            if (job.running) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(PrimaryBlue),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        val progress = job.progress
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = PrimaryBlue,
                trackColor = OutlineColor,
            )
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = PrimaryBlue,
                trackColor = OutlineColor,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Log")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { paused = !paused }) {
                Text(if (paused) "Resume" else "Pause", color = PrimaryBlue)
            }
            TextButton(onClick = { expanded = true }) {
                Text("Expand", color = PrimaryBlue)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(TerminalBg)
                .border(1.dp, OutlineColor, RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    // Any manual drag pauses auto-scroll until Resume is tapped.
                    detectVerticalDragGestures { _, _ -> paused = true }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (job.log.isEmpty()) {
                item {
                    Text(
                        "waiting for the first command…",
                        style = MonoStyle,
                        color = TextTertiary,
                    )
                }
            }
            items(job.log) { line ->
                LogRow(line)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Full job log") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(job.log) { line -> LogRow(line) }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun LogRow(line: JobLogLine) {
    val color = when (line.kind) {
        JobLogLine.Kind.OK -> SuccessGreen
        JobLogLine.Kind.WARN -> WarningAmber
        JobLogLine.Kind.ERR -> DangerRed
        JobLogLine.Kind.DIM -> TextTertiary
        JobLogLine.Kind.NORMAL -> TerminalText
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = formatClock(line.time),
            style = MonoStyle,
            color = TextTertiary,
            modifier = Modifier.width(62.dp),
        )
        Text(
            text = line.text,
            style = MonoStyle,
            color = color,
            fontWeight = if (line.kind == JobLogLine.Kind.ERR) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
