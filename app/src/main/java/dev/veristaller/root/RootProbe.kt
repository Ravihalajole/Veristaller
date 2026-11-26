package dev.veristaller.root

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceFacts(
    val isRoot: Boolean = false,
    val uid: String = "",
    val manager: String = "",
    val sdk: Int = 0,
    val release: String = "",
    val model: String = "",
    val abiList: List<String> = emptyList(),
    val abi32List: List<String> = emptyList(),
    val abi64List: List<String> = emptyList(),
    val selinux: String = "",
    val freeBytes: Long = 0L,
    val missingTools: List<String> = emptyList(),
    val error: String? = null,
) {
    val primaryAbi: String get() = abiList.firstOrNull() ?: ""
}

class RootProbe(
    private val context: Context,
    private val shell: RootShell,
) {

    private val _facts = MutableStateFlow<DeviceFacts?>(null)
    val facts: StateFlow<DeviceFacts?> = _facts.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun refresh() {
        _busy.value = true
        try {
            val root = shell.checkRoot()
            if (!root.ok) {
                _facts.value = fallback(
                    root.error ?: "Root not granted to Veristaller. " +
                        "Allow it in KernelSU/Magisk/APatch, then tap the root chip to re-check.",
                )
                return
            }
            val output = shell.exec(RootOps.probe())
            if (!output.ok) {
                _facts.value = fallback(output.combined().ifBlank { "Root probe failed" })
                return
            }
            _facts.value = parse(output.stdout)
        } finally {
            _busy.value = false
        }
    }

    suspend fun isRunning(packageName: String): Boolean {
        val pids = shell.exec(RootOps.isRunning(packageName)).stdout.trim()
        if (pids.isNotEmpty()) return true
        return shell.exec(RootOps.processesFor(packageName)).stdout.trim().isNotEmpty()
    }

    private fun parse(raw: String): DeviceFacts {
        val values = HashMap<String, String>()
        val missing = ArrayList<String>()
        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("MISSING=") -> missing.add(line.removePrefix("MISSING=").trim())
                line.contains('=') -> {
                    val key = line.substringBefore('=')
                    values[key] = line.substringAfter('=')
                }
            }
        }
        fun abiList(key: String): List<String> = values[key].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val abis = abiList("ABI")
        return DeviceFacts(
            isRoot = values["UID"] == "0",
            uid = values["UID"].orEmpty(),
            manager = values["MGR"].orEmpty().ifBlank { "su" },
            sdk = values["SDK"]?.toIntOrNull() ?: Build.VERSION.SDK_INT,
            release = values["REL"].orEmpty().ifBlank { Build.VERSION.RELEASE },
            model = values["MODEL"].orEmpty().ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" },
            abiList = abis.ifEmpty { Build.SUPPORTED_ABIS.toList() },
            abi32List = abiList("ABI32"),
            abi64List = abiList("ABI64"),
            selinux = values["SEL"].orEmpty().ifBlank { "unknown" },
            freeBytes = (values["FREE"]?.toLongOrNull() ?: 0L) * 1024L,
            missingTools = missing,
            error = null,
        )
    }

    private fun fallback(error: String): DeviceFacts = DeviceFacts(
        isRoot = false,
        uid = "-",
        manager = "none",
        sdk = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        abiList = Build.SUPPORTED_ABIS.toList(),
        abi32List = Build.SUPPORTED_32_BIT_ABIS.toList(),
        abi64List = Build.SUPPORTED_64_BIT_ABIS.toList(),
        selinux = "unknown",
        freeBytes = 0L,
        error = error,
    )
}
