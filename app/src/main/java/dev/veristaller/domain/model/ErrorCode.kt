package dev.veristaller.domain.model

enum class ErrorCode(val title: String, val hint: String) {
    E_ROOT_DENIED(
        "Root access was not granted",
        "Open your root manager and allow Veristaller, then try again.",
    ),
    E_TOOL_MISSING(
        "A required system tool is missing",
        "This ROM does not ship the expected toybox tools.",
    ),
    E_BUNDLE_CORRUPT(
        "This file isn't a valid bundle",
        "Pick another file. Supported: .apks, .apkm, .xapk, .apk.",
    ),
    E_BUNDLE_UNSUPPORTED(
        "Unrecognised bundle format",
        "The archive has no known split layout.",
    ),
    E_PKG_MISMATCH(
        "Bundle and patch are different apps",
        "Both files must target the same package name.",
    ),
    E_APP_NOT_FOUND(
        "The app isn't installed",
        "Install the bundle first, then swap the base APK.",
    ),
    E_SIGNATURE_CONFLICT(
        "Existing install has a different signature",
        "Uninstall the current app (this erases its data) or use a matching bundle.",
    ),
    E_INSTALL_FAILED(
        "PackageManager rejected the install",
        "See the raw output below. Try the ABI-only split mode.",
    ),
    E_VERSION_DOWNGRADE(
        "The bundle is older than the installed app",
        "Android refuses version downgrades. Uninstall the newer app first (its data will be erased) or pick a matching bundle.",
    ),
    E_NOT_ENOUGH_SPACE(
        "Not enough free space",
        "Free up space on /data and try again.",
    ),
    E_PATH_UNRESOLVED(
        "Couldn't find the app's APK folder",
        "The code path layout is unknown on this ROM. Export the log.",
    ),
    E_PATH_UNSAFE(
        "Refusing to write outside /data/app",
        "The resolved path failed the safety check. Nothing was changed.",
    ),
    E_SWAP_FAILED(
        "Couldn't replace base.apk",
        "The backup is intact. Check free space and the log.",
    ),
    E_BACKUP_FAILED(
        "Couldn't create a verified backup",
        "Nothing was replaced. Check free space and try again.",
    ),
    E_VERITY_LOCKED(
        "The system protects this APK (fs-verity)",
        "The original was restored. This ROM may not allow file swaps.",
    ),
    E_SELINUX_DENIED(
        "Couldn't set the APK security label",
        "The original was restored. Check dmesg for avc denials.",
    ),
    E_VERIFY_FAILED(
        "The swap didn't verify",
        "Size, ownership or SELinux label was wrong. The backup was restored.",
    ),
    E_ROLLBACK_FAILED(
        "Manual recovery needed",
        "The backup could not be restored automatically. Use the path in the log.",
    ),
    E_ALREADY_RUNNING(
        "A job is already running",
        "Wait for the current job to finish.",
    ),
    E_ACK_REQUIRED(
        "Warnings were not acknowledged",
        "Return to Review and tick each warning before starting.",
    ),
    E_ALREADY_INSTALLED_CONFLICT(
        "Existing install has a different signature",
        "Uninstall the current app (this erases its data) or use a matching bundle.",
    ),
    E_UNKNOWN(
        "Something failed",
        "Export the log for details.",
    ),
}
