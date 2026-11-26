package dev.veristaller.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.veristaller.data.Settings
import dev.veristaller.domain.JobPlan
import dev.veristaller.domain.Preflight
import dev.veristaller.domain.model.ApkInfo
import dev.veristaller.domain.model.BundleInfo
import dev.veristaller.domain.model.CheckResult
import dev.veristaller.domain.model.ErrorCode
import dev.veristaller.domain.model.InstalledAppInfo
import dev.veristaller.domain.model.JobOptions
import dev.veristaller.domain.model.JobSpec
import dev.veristaller.domain.model.SplitApk
import dev.veristaller.domain.model.SplitMode
import dev.veristaller.platform.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(private val container: AppContainer) : ViewModel() {

    val facts = container.probe.facts
    val probing = container.probe.busy
    val job = container.jobRunner.state

    /** True once DataStore has emitted, so the disclosure dialog never flashes. */
    private val _settingsReady = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = _settingsReady.asStateFlow()

    val settings: StateFlow<Settings> = container.settings.flow
        .onEach { _settingsReady.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _bundle = MutableStateFlow<BundleInfo?>(null)
    val bundle: StateFlow<BundleInfo?> = _bundle.asStateFlow()

    private val _patch = MutableStateFlow<ApkInfo?>(null)
    val patch: StateFlow<ApkInfo?> = _patch.asStateFlow()

    private val _installed = MutableStateFlow<InstalledAppInfo?>(null)
    val installed: StateFlow<InstalledAppInfo?> = _installed.asStateFlow()

    private val _appRunning = MutableStateFlow(false)
    val appRunning: StateFlow<Boolean> = _appRunning.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _acknowledged = MutableStateFlow<Set<String>>(emptySet())
    val acknowledged: StateFlow<Set<String>> = _acknowledged.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // Drop a previously denied shell so a grant in KernelSU/Magisk is
            // picked up without force-stopping the app. Never reset mid-job.
            if (!job.value.running) withContext(Dispatchers.IO) { container.rootShell.reset() }
            container.probe.refresh()
        }
    }

    fun importBundle(uri: Uri) {
        viewModelScope.launch {
            _busy.value = "Reading bundle…"
            _error.value = null
            try {
                val info = withContext(Dispatchers.IO) {
                    val staged = container.staging.import(uri)
                    container.bundleInspector.inspect(
                        staged.file,
                        facts.value?.abiList.orEmpty(),
                        container.densityBucket,
                    )
                }
                _bundle.value = info
                _acknowledged.value = emptySet()
                val patchPackage = _patch.value?.packageName
                if (patchPackage != null && patchPackage == info.packageName) {
                    loadInstalled(info.packageName)
                } else {
                    _installed.value = null
                    _appRunning.value = false
                }
                if (info.packageName == null) {
                    _error.value = "Could not read the base APK inside this bundle."
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not read this file"
            } finally {
                _busy.value = null
            }
        }
    }

    fun importPatch(uri: Uri) {
        viewModelScope.launch {
            _busy.value = "Reading APK…"
            _error.value = null
            try {
                val info = withContext(Dispatchers.IO) {
                    val staged = container.staging.import(uri)
                    container.apkInspector.inspect(staged.file)
                }
                _patch.value = info
                _acknowledged.value = emptySet()
                loadInstalled(info.packageName)
            } catch (t: Throwable) {
                _error.value = t.message ?: "Could not read this APK"
            } finally {
                _busy.value = null
            }
        }
    }

    private suspend fun loadInstalled(packageName: String) {
        val info = container.installedReader.read(packageName)
        _installed.value = info
        _appRunning.value = info != null && container.probe.isRunning(packageName)
    }

    fun toggleAck(id: String) {
        _acknowledged.value = if (id in _acknowledged.value) {
            _acknowledged.value - id
        } else {
            _acknowledged.value + id
        }
    }

    /** Splits this device would install for [mode], using the current facts. */
    fun selectedSplits(mode: SplitMode = settings.value.splitMode): List<SplitApk> {
        val bundle = _bundle.value ?: return emptyList()
        return container.bundleInspector.select(
            bundle,
            facts.value?.abiList.orEmpty(),
            container.densityBucket,
            mode,
            container.deviceLocales,
        )
    }

    fun checks(): List<CheckResult> = Preflight.evaluate(
        bundle = _bundle.value,
        patch = _patch.value,
        installed = _installed.value,
        facts = facts.value,
        isRunning = _appRunning.value,
        options = options(settings.value),
        splits = selectedSplits(),
    )

    fun previewCommands(): List<String> {
        val spec = buildSpec() ?: return emptyList()
        return JobPlan.preview(spec, selectedSplits())
    }

    fun startJob() {
        val spec = buildSpec() ?: return
        if (job.value.running) {
            _error.value = ErrorCode.E_ALREADY_RUNNING.hint
            return
        }
        if (!container.jobRunner.start(spec)) {
            _error.value = ErrorCode.E_ALREADY_RUNNING.hint
        }
    }

    fun buildSpec(): JobSpec? {
        val bundle = _bundle.value ?: return null
        val patch = _patch.value ?: return null
        val installed = _installed.value ?: return null
        val device = facts.value ?: return null
        return JobSpec(
            jobId = "j-" + System.currentTimeMillis().toString(36),
            bundle = bundle,
            patch = patch,
            installed = installed,
            options = options(settings.value),
            deviceAbis = device.abiList,
            densityBucket = container.densityBucket,
            deviceLocales = container.deviceLocales,
            packageLabel = installed.label,
            acknowledgedWarnings = _acknowledged.value,
            requiredWarnings = checks()
                .filter { it.severity == CheckResult.Severity.WARN && it.requiresAck }
                .map { it.id }
                .toSet(),
        )
    }

    fun options(current: Settings): JobOptions = JobOptions(
        installerPackage = current.installerPackage.ifBlank { "com.android.vending" },
        clearOat = current.clearOat,
        dryRun = current.dryRun,
        splitMode = current.splitMode,
        verboseLogs = current.verboseLogs,
        smokeTest = current.smokeTest,
    )

    fun launchIntent(packageName: String): android.content.Intent? =
        container.installedReader.launchIntent(packageName)

    fun clearSelection() {
        _bundle.value = null
        _patch.value = null
        _installed.value = null
        _appRunning.value = false
        _acknowledged.value = emptySet()
        _error.value = null
        container.jobRunner.clearOutcome()
    }

    fun dismissError() {
        _error.value = null
    }

    fun reopenDisclosure() {
        viewModelScope.launch { container.settings.update { it.copy(disclosureAccepted = false) } }
    }

    fun acceptDisclosure() {
        viewModelScope.launch {
            container.settings.update { it.copy(disclosureAccepted = true) }
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            container.settings.update(transform)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel(container) as T
                }
            }
    }
}
