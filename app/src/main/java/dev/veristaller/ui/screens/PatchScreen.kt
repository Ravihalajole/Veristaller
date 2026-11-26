package dev.veristaller.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.ApkInfo
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.InfoNote
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.MonoText
import dev.veristaller.ui.components.PrimaryButton
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.TonalButton
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary

private val APK_MIME = arrayOf("application/vnd.android.package-archive")

@Composable
fun PatchScreen(vm: AppViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val patch by vm.patch.collectAsStateWithLifecycle()
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPatch(uri)
        else vm.dismissError()
    }

    ScreenFrame(
        title = "Patched APK",
        subtitle = "Step 2 of 3",
        onBack = onBack,
        footer = {
            Column(modifier = Modifier.padding(20.dp)) {
                PrimaryButton(
                    text = if (patch == null) "Choose the patched APK" else "Review",
                    enabled = patch != null && busy == null,
                    onClick = { if (patch == null) picker.launch(APK_MIME) else onNext() },
                )
                if (patch != null) {
                    Spacer(Modifier.height(8.dp))
                    TonalButton(text = "Pick another file", onClick = {
                        picker.launch(APK_MIME)
                    })
                }
            }
        },
    ) {
        Spacer(Modifier.height(4.dp))
        InfoNote("This file replaces the installed base.apk. Splits are untouched.")

        if (patch == null) {
            Spacer(Modifier.height(10.dp))
            PickCard(
                title = if (busy != null) busy ?: "" else "Choose the patched .apk",
                subtitle = "Same package as the bundle",
                onClick = { picker.launch(APK_MIME) },
            )
        } else {
            Spacer(Modifier.height(10.dp))
            PatchCard(patch!!)
            Spacer(Modifier.height(12.dp))
            VeriCard {
                SectionLabel("Compared with the bundle")
                MatchStrip(
                    label = "package",
                    state = when {
                        bundle?.packageName == null -> MatchState.WARN
                        bundle?.packageName == patch!!.packageName -> MatchState.OK
                        else -> MatchState.BAD
                    },
                    detail = if (bundle?.packageName == patch!!.packageName) {
                        "same · ${patch!!.packageName}"
                    } else {
                        "${bundle?.packageName ?: "?"} vs ${patch!!.packageName}"
                    },
                )
                MatchStrip(
                    label = "version",
                    state = when {
                        bundle?.versionCode == null -> MatchState.WARN
                        bundle?.versionCode == patch!!.versionCode -> MatchState.OK
                        else -> MatchState.WARN
                    },
                    detail = "${bundle?.versionCode ?: "?"} bundle · ${patch!!.versionCode} patch",
                )
                MatchStrip(
                    label = "signature",
                    state = when {
                        bundle?.packageName == null -> MatchState.WARN
                        installed == null -> MatchState.WARN
                        installed?.certSha256 == patch!!.certSha256 -> MatchState.OK
                        else -> MatchState.WARN
                    },
                    detail = when {
                        installed == null -> "not installed yet"
                        installed?.certSha256 == patch!!.certSha256 -> "matches installed app"
                        else -> "differs from installed app (expected)"
                    },
                )
                if (bundle?.packageName != null && bundle?.packageName != patch!!.packageName) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Both files must belong to the same app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = dev.veristaller.ui.theme.DangerRed,
                    )
                }
            }
        }

        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            ErrorBox(message) { vm.dismissError() }
        }
    }
}

@Composable
private fun PatchCard(patch: ApkInfo) {
    VeriCard {
        Text(
            text = patch.file.name,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 2,
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Package", patch.packageName, mono = true)
        KeyValueRow(
            "Version",
            listOfNotNull(patch.versionName, patch.versionCode.toString()).joinToString(" · "),
        )
        KeyValueRow("Size", formatBytes(patch.sizeBytes))
        KeyValueRow("SDK", "min ${patch.minSdk} · target ${patch.targetSdk}")
        KeyValueRow("ABI", patch.abis.joinToString().ifBlank { "universal" })
        KeyValueRow(
            "Certificate",
            if (patch.certSha256.isBlank()) "unavailable" else "${patch.certShort}…",
            mono = true,
            valueColor = TextSecondary,
        )
        if (patch.certSha256.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            MonoText("sha256 ${patch.certSha256.take(32)}…", color = TextSecondary)
        }
    }
}
