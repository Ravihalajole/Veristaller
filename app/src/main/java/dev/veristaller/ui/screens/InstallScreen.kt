package dev.veristaller.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.veristaller.domain.model.ApkInfo
import dev.veristaller.domain.model.BundleInfo
import dev.veristaller.domain.model.CheckResult
import dev.veristaller.domain.model.InstalledAppInfo
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.components.CheckRow
import dev.veristaller.ui.components.ErrorBox
import dev.veristaller.ui.components.KeyValueRow
import dev.veristaller.ui.components.Pill
import dev.veristaller.ui.components.PrimaryButton
import dev.veristaller.ui.components.ScreenFrame
import dev.veristaller.ui.components.SectionLabel
import dev.veristaller.ui.components.VeriCard
import dev.veristaller.ui.components.formatBytes
import dev.veristaller.ui.theme.DangerRed
import dev.veristaller.ui.theme.OutlineColor
import dev.veristaller.ui.theme.PrimaryBlue
import dev.veristaller.ui.theme.SuccessGreen
import dev.veristaller.ui.theme.SurfaceHigh
import dev.veristaller.ui.theme.SurfaceLow
import dev.veristaller.ui.theme.TextPrimary
import dev.veristaller.ui.theme.TextSecondary
import dev.veristaller.ui.theme.TextTertiary
import dev.veristaller.ui.theme.WarningAmber

private val BUNDLE_MIME = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/vnd.android.package-archive",
    "application/octet-stream",
)

private val APK_MIME = arrayOf("application/vnd.android.package-archive")

/**
 * One screen for the whole setup: pick the bundle, pick the patched APK, install.
 * Details (preflight checks) stay collapsed unless asked for.
 */
@Composable
fun InstallScreen(vm: AppViewModel, onBack: () -> Unit, onStart: () -> Unit) {
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val patch by vm.patch.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showDetails by rememberSaveable { mutableStateOf(false) }

    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBundle(uri) else vm.dismissError()
    }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPatch(uri) else vm.dismissError()
    }

    val checks = remember(bundle, patch, installed, facts, settings) { vm.checks() }
    val splits = remember(bundle, facts, settings) { vm.selectedSplits() }
    val failed = checks.filter { it.severity == CheckResult.Severity.FAIL }
    val passed = checks.count { it.severity == CheckResult.Severity.PASS }
    val ready = failed.isEmpty() && bundle != null && patch != null && busy == null

    val reason = when {
        busy != null -> busy
        facts == null -> "Checking root…"
        facts?.isRoot != true -> "Root access is required"
        bundle == null -> "Pick a bundle"
        patch == null -> "Pick the patched APK"
        failed.isNotEmpty() -> failed.first().title
        else -> null
    }

    ScreenFrame(
        title = "Install",
        onBack = onBack,
        actions = {
            if (settings.dryRun) {
                Pill("dry run", WarningAmber, WarningAmber.copy(alpha = 0.1f))
            }
        },
        footer = {
            Column(modifier = Modifier.padding(20.dp)) {
                PrimaryButton(
                    text = if (settings.dryRun) "Dry run" else "Install",
                    icon = Icons.Filled.PlayArrow,
                    enabled = ready,
                    onClick = onStart,
                )
                if (reason != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (busy != null || facts == null || failed.isEmpty()) {
                            TextTertiary
                        } else {
                            DangerRed
                        },
                    )
                }
            }
        },
    ) {
        Spacer(Modifier.height(6.dp))

        PickerTile(
            title = "Bundle",
            value = bundle?.file?.name ?: "Choose .apks, .apkm or .xapk",
            detail = bundle?.let {
                "${it.format.label} · ${it.splits.size} splits · ${formatBytes(it.totalBytes)}"
            },
            picked = bundle != null,
            onClick = { bundlePicker.launch(BUNDLE_MIME) },
        )

        Spacer(Modifier.height(10.dp))

        PickerTile(
            title = "Patched APK",
            value = patch?.file?.name ?: "Choose the replacement .apk",
            detail = patch?.let { "${it.versionName ?: "?"} · ${formatBytes(it.sizeBytes)}" },
            picked = patch != null,
            onClick = { apkPicker.launch(APK_MIME) },
        )

        if (bundle != null || patch != null) {
            Spacer(Modifier.height(14.dp))
            TargetCard(bundle = bundle, patch = patch, installed = installed, splitCount = splits.size)
        }

        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DangerRed.copy(alpha = 0.07f))
                    .border(1.dp, DangerRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                failed.forEach { check ->
                    Text(
                        text = check.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerRed,
                    )
                    Text(
                        text = check.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }
        }

        if (bundle != null || patch != null) {
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceHigh)
                    .clickable(onClickLabel = if (showDetails) "Hide details" else "Show details") {
                        showDetails = !showDetails
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.List, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Details",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$passed/${checks.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed.isEmpty()) TextSecondary else DangerRed,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (showDetails) {
                Spacer(Modifier.height(8.dp))
                VeriCard {
                    checks.forEach { CheckRow(it) }
                }
            }
        }

        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            ErrorBox(message) { vm.dismissError() }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PickerTile(
    title: String,
    value: String,
    detail: String?,
    picked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceLow)
            .border(
                1.dp,
                if (picked) PrimaryBlue.copy(alpha = 0.4f) else OutlineColor,
                RoundedCornerShape(18.dp),
            )
            .clickable(onClickLabel = "Choose $title", onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (picked) SuccessGreen.copy(alpha = 0.12f) else PrimaryBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (picked) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = null,
                tint = if (picked) SuccessGreen else PrimaryBlue,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (picked) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TargetCard(
    bundle: BundleInfo?,
    patch: ApkInfo?,
    installed: InstalledAppInfo?,
    splitCount: Int,
) {
    VeriCard {
        SectionLabel("Target")
        KeyValueRow(
            "App",
            installed?.label ?: patch?.packageName ?: bundle?.packageName ?: "—",
        )
        KeyValueRow(
            "Package",
            bundle?.packageName ?: patch?.packageName ?: "—",
            mono = true,
            valueColor = TextSecondary,
        )
        KeyValueRow(
            "Version",
            listOfNotNull(patch?.versionName, patch?.versionCode?.toString()).joinToString(" · ")
                .ifBlank {
                    listOfNotNull(bundle?.versionName, bundle?.versionCode?.toString()).joinToString(" · ")
                }
                .ifBlank { "—" },
        )
        KeyValueRow(
            "Splits",
            if (splitCount == 0) "—" else "$splitCount for this device",
        )
    }
}
