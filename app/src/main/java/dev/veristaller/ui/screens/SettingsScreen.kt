package dev.veristaller.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.SplitMode
import dev.veristaller.root.DeviceFacts
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.BottomNav
import dev.veristaller.ui.components.ExportLogButton
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.MonoText
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.InfoPurple
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SuccessGreen
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.SurfaceLow
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onHome: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val probing by vm.probing.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var installer by rememberSaveable(settings.installerPackage) {
        mutableStateOf(settings.installerPackage)
    }

    ScreenFrame(
        title = "Settings",
        subtitle = "execution · safety · diagnostics",
        bottom = {
            BottomNav(
                selected = "settings",
                onHome = onHome,
                onSettings = {},
            )
        },
    ) {
        Spacer(Modifier.height(8.dp))

        SectionLabel("Execution")
        VeriCard {
            ToggleRow(
                title = "Dry run",
                detail = "Print the commands without installing or replacing anything.",
                checked = settings.dryRun,
                onCheckedChange = { value -> vm.updateSettings { it.copy(dryRun = value) } },
            )
            Divider()
            Text(
                "Split filtering",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Text(
                "Smart keeps only the ABIs and density this device uses.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = SplitMode.entries.map { it to it.label },
                selected = settings.splitMode,
                onSelect = { mode -> vm.updateSettings { it.copy(splitMode = mode) } },
            )
            Divider()
            ToggleRow(
                title = "Clear compiled code",
                detail = "Deletes oat/ so Android recompiles the patched APK.",
                checked = settings.clearOat,
                onCheckedChange = { value -> vm.updateSettings { it.copy(clearOat = value) } },
            )
            Divider()
            ToggleRow(
                title = "Smoke test after swap",
                detail = "Launch the patched app once and scan logcat for a fatal crash.",
                checked = settings.smokeTest,
                onCheckedChange = { value -> vm.updateSettings { it.copy(smokeTest = value) } },
            )
            Divider()
            Text(
                "Installer package",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Text(
                "Reported to PackageManager as the installer. Default com.android.vending.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = installer,
                onValueChange = { installer = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    vm.updateSettings { it.copy(installerPackage = installer.trim()) }
                }),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        vm.updateSettings { it.copy(installerPackage = installer.trim()) }
                    },
                    enabled = installer.trim() != settings.installerPackage &&
                        installer.trim().matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+){2,}$")),
                ) {
                    Text("Save", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = {
                        installer = "com.android.vending"
                        vm.updateSettings { it.copy(installerPackage = "com.android.vending") }
                    },
                ) {
                    Text("Reset", color = TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("Safety")
        VeriCard {
            Text(
                "No backup is taken before a swap. If a swap fails, reinstall the app to recover.",
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber,
            )
            Divider()
            Text("Theme", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                options = listOf("system" to "System", "dark" to "Dark", "light" to "Light"),
                selected = settings.theme,
                onSelect = { value -> vm.updateSettings { it.copy(theme = value) } },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Dynamic colour",
                detail = "Use Android 12+ wallpaper colours.",
                checked = settings.dynamicColor,
                onCheckedChange = { value -> vm.updateSettings { it.copy(dynamicColor = value) } },
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("Feedback")
        VeriCard {
            ToggleRow(
                title = "Vibrate when a job finishes",
                detail = "A short pulse on success or failure.",
                checked = settings.vibrate,
                onCheckedChange = { value -> vm.updateSettings { it.copy(vibrate = value) } },
            )
            Divider()
            ToggleRow(
                title = "Verbose command output",
                detail = "Include every command's stdout and stderr in the job log.",
                checked = settings.verboseLogs,
                onCheckedChange = { value -> vm.updateSettings { it.copy(verboseLogs = value) } },
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("Diagnostics")
        VeriCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Root probe",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                    Text(
                        if (probing) "checking…" else "Last run now",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Pill(
                    text = when {
                        facts == null -> "?"
                        facts?.isRoot == true -> "root"
                        else -> "no root"
                    },
                    color = when {
                        facts?.isRoot == true -> SuccessGreen
                        facts == null -> TextTertiary
                        else -> DangerRed
                    },
                    background = SurfaceHigh,
                )
            }
            Spacer(Modifier.height(8.dp))
            ProbeTable(facts)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::refresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Re-run", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(diagnostics(facts, settings)))
                }) {
                    Text("Copy diagnostics", color = TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            ExportLogButton(
                text = "Export diagnostics to a file",
                fileName = "veristaller-diagnostics.txt",
                content = { diagnostics(facts, settings) },
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("About")
        VeriCard {
            KeyValueRow("Veristaller", dev.veristaller.BuildConfig.VERSION_NAME)
            KeyValueRow("Storage", "root only · /data/app")
            KeyValueRow("Network", "none — no INTERNET permission")
            Spacer(Modifier.height(6.dp))
            Text(
                "Everything runs on this device. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
            Divider()
            TextButton(onClick = { vm.reopenDisclosure() }) {
                Text("Re-read the modification disclosure", color = PrimaryBlue)
            }
            Text(
                "Licences: Android Open Source Project (Apache 2.0), Kotlin (Apache 2.0), " +
                    "Jetpack Compose (Apache 2.0), libsu (Apache 2.0), " +
                    "kotlinx.serialization (Apache 2.0).",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProbeTable(facts: DeviceFacts?) {
    if (facts == null) {
        Text("No probe data yet.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        return
    }
    KeyValueRow("Manager", facts.manager.ifBlank { "—" })
    KeyValueRow("uid", facts.uid.ifBlank { "—" }, mono = true)
    KeyValueRow("Android", "${facts.release} · SDK ${facts.sdk}")
    KeyValueRow("Device", facts.model)
    KeyValueRow("ABI", facts.abiList.joinToString().ifBlank { "—" })
    KeyValueRow(
        "SELinux",
        facts.selinux,
        valueColor = if (facts.selinux == "enforcing") TextPrimary else WarningAmber,
    )
    KeyValueRow("Free /data", formatBytes(facts.freeBytes))
    KeyValueRow("Root shell", facts.shellMode.ifBlank { "—" })
    if (facts.missingTools.isNotEmpty()) {
        KeyValueRow("Missing", facts.missingTools.joinToString(), valueColor = DangerRed)
    }
    facts.error?.let { KeyValueRow("Error", it, valueColor = DangerRed) }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = title) {
                onCheckedChange(!checked)
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = switchColors(),
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = PrimaryBlue,
    checkedTrackColor = PrimaryBlue.copy(alpha = 0.3f),
    uncheckedThumbColor = TextTertiary,
    uncheckedTrackColor = SurfaceHigh,
    uncheckedBorderColor = OutlineColor,
)

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) PrimaryBlue.copy(alpha = 0.16f) else SurfaceHigh)
                    .clickable(onClickLabel = "Select $label", onClick = { onSelect(value) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (active) PrimaryBlue else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(OutlineColor.copy(alpha = 0.6f)),
    )
}

private fun diagnostics(
    facts: DeviceFacts?,
    settings: dev.veristaller.data.Settings,
): String = buildString {
    appendLine("Veristaller ${dev.veristaller.BuildConfig.VERSION_NAME}")
    appendLine("dryRun=${settings.dryRun}")
    appendLine("theme=${settings.theme}")
    appendLine("dynamicColor=${settings.dynamicColor}")
    appendLine("splitMode=${settings.splitMode.name}")
    appendLine("clearOat=${settings.clearOat}")
    appendLine("smokeTest=${settings.smokeTest}")
    appendLine("verboseLogs=${settings.verboseLogs}")
    appendLine("vibrate=${settings.vibrate}")
    appendLine("uid=${facts?.uid ?: "?"}")
    appendLine("root=${facts?.isRoot ?: false}")
    appendLine("manager=${facts?.manager ?: "?"}")
    appendLine("android=${facts?.release ?: "?"} sdk=${facts?.sdk ?: 0}")
    appendLine("device=${facts?.model ?: "?"}")
    appendLine("abi=${facts?.abiList?.joinToString() ?: "?"}")
    appendLine("selinux=${facts?.selinux ?: "?"}")
    appendLine("free=${facts?.freeBytes ?: 0}")
    appendLine("shell=${facts?.shellMode ?: "?"}")
    appendLine("missing=${facts?.missingTools?.joinToString() ?: ""}")
    append("error=${facts?.error ?: ""}")
}
