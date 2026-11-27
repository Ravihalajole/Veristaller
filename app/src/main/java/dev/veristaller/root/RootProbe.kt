package dev.veristaller.root

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

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
    /** How root is reached: a persistent shell, or one `su -c` per command. */
    val shellMode: String = "",
    /** UID this app runs as. KernelSU/Magisk store their grant against it. */
    val appUid: Int = -1,
    /** First 16 hex chars of the APK signing certificate (changes if re-signed). */
    val appSignature: String = "",
    /** Root manager apps PackageManager can see, by display name. */
    val managers: List<String> = emptyList(),
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
                _facts.value = stamp(
                    fallback(
                        root.error ?: "Root not granted to Veristaller. " +
                            "Allow it in KernelSU/Magisk/APatch, then tap the root chip to re-check.",
                    ),
                )
                return
            }
            val output = shell.exec(RootOps.probe())
            if (!output.ok) {
                _facts.value = stamp(
                    fallback(output.combined().ifBlank { "Root probe failed" })
                        .copy(shellMode = root.mode),
                )
                return
            }
            _facts.value = stamp(parse(output.stdout).copy(shellMode = root.mode))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Never leave the chip stuck on "checking…": a thrown probe is a
            // failed probe, with the reason shown in the root details dialog.
            _facts.value = stamp(fallback("Root probe failed: ${t.message ?: t.javaClass.simpleName}"))
        } finally {
            _busy.value = false
        }
    }

    /**
     * Attach the facts that describe this install rather than the device.
     * KernelSU keys its grant by app UID, and a re-signed APK cannot be
     * update-installed, so both change whenever CI signs with a new key.
     */
    private fun stamp(facts: DeviceFacts): DeviceFacts = facts.copy(
        appUid = Process.myUid(),
        appSignature = signatureHash(),
        managers = RootManagers.names(context),
    )

    private fun signatureHash(): String = runCatching {
        @Suppress("DEPRECATION")
        val signatures = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .signatures
        val der = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching ""
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }.getOrDefault("")

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
