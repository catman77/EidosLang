#!/usr/bin/env python3
"""Check Samsung/Android reinstall retention using a separate, disposable application ID.

Never uninstall or clear org.eidolang.app. No fallback to a destructive reinstall is allowed.
The final fault-injection step removes keys only from org.eidolang.app.retentioncheck.
"""
import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

CHECK = "org.eidolang.app.retentioncheck"
OWNER = "org.eidolang.app"


def main():
    android = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, default=android / "app/build/outputs/apk/retentionCheck/app-retentionCheck.apk")
    parser.add_argument("--tests", type=Path, default=android / "app/build/outputs/apk/androidTest/retentionCheck/app-retentionCheck-androidTest.apk")
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    def adb(*command):
        result = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=120, check=True)
        return result.stdout.decode().replace("\r", "")

    def uid(package):
        text = adb("shell", "pm", "list", "packages", "-U", package)
        match = re.search(rf"^package:{re.escape(package)} uid:(\d+)\b", text, re.MULTILINE)
        return int(match.group(1)) if match else None

    for apk, package in ((args.apk, CHECK), (args.tests, CHECK + ".test")):
        badging = subprocess.run([str(args.aapt2), "dump", "badging", str(apk)], capture_output=True, check=True).stdout.decode()
        if not re.search(rf"^package: name='{re.escape(package)}' ", badging, re.MULTILINE):
            parser.error(f"Refusing APK outside the disposable application ID: {apk}")

    installed = adb("shell", "pm", "list", "packages", "-u", CHECK).splitlines()
    if any(line in (f"package:{CHECK}", f"package:{CHECK}.test") for line in installed):
        parser.error("The check installation already exists or retains data. Refusing to reset it.")
    owner_uid = uid(OWNER)
    for apk in (args.apk, args.tests):
        adb("install", "-t", str(apk))
    check_uid = uid(CHECK)
    assert check_uid is not None and check_uid != owner_uid
    steps = []

    def step(method):
        output = adb("shell", "am", "instrument", "-w", "-e", "class",
            "org.eidolang.app.ReinstallRetentionTest#" + method,
            CHECK + ".test/androidx.test.runner.AndroidJUnitRunner")
        if not re.search(r"^OK \(1 test\)$", output, re.MULTILINE):
            raise RuntimeError(output[-6000:])
        steps.append(method)
        print("PASS", method, flush=True)

    step("seedOnlyEmptyCheckInstallation")
    step("verifyRetainedProfileContactsAndCompleteMessages")
    # Show and cancel the actual system dialog. Never confirm a deletion through UI automation.
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.DELETE", "-d", "package:" + CHECK)
    try:
        adb("shell", "uiautomator", "dump", "/data/local/tmp/eidolang-retention-dialog.xml")
        dialog = adb("shell", "cat", "/data/local/tmp/eidolang-retention-dialog.xml")
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.with_suffix(".dialog.xml").write_text(dialog)
        tree = ET.fromstring(dialog[dialog.index("<?xml"):])
        checkboxes = [node.attrib.get("text", "") for node in tree.iter("node")
            if node.attrib.get("checkable") == "true" and
            node.attrib.get("resource-id", "").endswith(":id/keepData")]
        if not checkboxes:
            raise RuntimeError("The system uninstall dialog did not offer data retention")
        print("PASS system uninstall offers data retention", flush=True)
    finally:
        adb("shell", "input", "keyevent", "KEYCODE_BACK")

    # This is the package-manager equivalent of choosing Keep data in the system dialog.
    assert CHECK != OWNER and CHECK.endswith(".retentioncheck")
    result = adb("shell", "pm", "uninstall", "-k", CHECK)
    assert "Success" in result, result
    assert uid(OWNER) == owner_uid
    adb("install", "-r", "-t", str(args.apk))
    assert uid(CHECK) == check_uid
    step("verifyRetainedProfileContactsAndCompleteMessages")
    step("unreadableProfileIsNotTreatedAsANewInstallation")
    step("verifyRetainedProfileContactsAndCompleteMessages")
    step("missingKeysNeverRegenerateOverSavedData")
    assert uid(OWNER) == owner_uid
    args.report.write_text(json.dumps({
        "serial": args.serial,
        "check_package": CHECK,
        "check_uid": check_uid,
        "owner_uid_unchanged": owner_uid,
        "owner_never_uninstalled_or_cleared": True,
        "retained_data_option_offered": True,
        "uninstall_mode": "keep-data",
        "passed": steps,
        "limitation": "Full removal without retained data still removes non-exportable private keys.",
    }, ensure_ascii=False, indent=2) + "\n")
    print("Report:", args.report, flush=True)


if __name__ == "__main__":
    main()
