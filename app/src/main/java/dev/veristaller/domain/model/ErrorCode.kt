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
        "Check free space and the log. A failed swap may leave the app unusable.",
    ),
    E_VERITY_LOCKED(
        "The system protects this APK (fs-verity)",
        "This ROM may not allow file swaps. Reinstall the app to recover.",
    ),
    E_SELINUX_DENIED(
        "Couldn't set the APK security label",
        "Check dmesg for avc denials. Reinstall the app to recover.",
    ),
    E_VERIFY_FAILED(
        "The swap didn't verify",
        "Size, ownership or the SELinux label was wrong. Reinstall the app to recover.",
    ),
    E_ALREADY_RUNNING(
        "A job is already running",
        "Wait for the current job to finish.",
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
