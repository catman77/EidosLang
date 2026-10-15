#!/usr/bin/env python3
"""Verify recovery after FULL uninstall on a phone, exclusively in .recoverycheck.

org.eidolang.app is never uninstalled or cleared. Only this driver's own guarded fixture
package may be removed. Encrypted backup files are kept in Download and in the report directory.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

CHECK = "org.eidolang.app.recoverycheck"
OWNER = "org.eidolang.app"


def main():
    android = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    args = parser.parse_args()
    args.report_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    apk = android / "app/build/outputs/apk/recoveryCheck/app-recoveryCheck.apk"
    tests = android / "app/build/outputs/apk/androidTest/recoveryCheck/app-recoveryCheck-androidTest.apk"

    def adb(*command, binary=False):
        result = subprocess.run(["adb", "-s", args.serial, *map(str, command)], capture_output=True, timeout=120, check=True)
        return result.stdout if binary else result.stdout.decode().replace("\r", "")

    def uid(package):
        text = adb("shell", "pm", "list", "packages", "-U", package)
        match = re.search(rf"^package:{re.escape(package)} uid:(\d+)\b", text, re.MULTILINE)
        return int(match.group(1)) if match else None

    for file, package in ((apk, CHECK), (tests, CHECK + ".test")):
        text = subprocess.run([str(args.aapt2), "dump", "badging", str(file)], capture_output=True, check=True).stdout.decode()
        if not re.search(rf"^package: name='{re.escape(package)}' ", text, re.MULTILINE):
            parser.error("Refusing an APK outside the separate recovery-check application ID")
    if f"package:{CHECK}" in adb("shell", "pm", "list", "packages", "-u", CHECK).splitlines():
        parser.error("A recovery-check installation already exists. It will not be reset automatically.")
    owner_uid = uid(OWNER)
    reports = []

    def install():
        for path in (apk, tests):
            adb("install", "-r", "-t", path)
        assert uid(CHECK) != owner_uid

    def step(method, mode):
        output = adb("shell", "am", "instrument", "-w", "-e", "class",
            "org.eidolang.app.AccountRecoveryTest#" + method,
            CHECK + ".test/androidx.test.runner.AndroidJUnitRunner")
        (args.report_dir / f"{mode}-{method}.log").write_text(output)
        if not re.search(r"^OK \(1 test\)$", output, re.MULTILINE):
            raise RuntimeError(output[-7000:])
        print("PASS", mode, method, flush=True)

    def remove_fixture():
        assert CHECK == "org.eidolang.app.recoverycheck" and CHECK != OWNER
        assert uid(CHECK) is not None and uid(CHECK) != owner_uid
        result = adb("uninstall", CHECK)  # deliberately no -k; private data AND Keystore are removed
        assert "Success" in result
        assert uid(OWNER) == owner_uid

    for mode, seed in (("portable-primary", "seedRecoverablePrimaryAndExport"),
                       ("legacy-primary", "seedLegacyHardwarePrimaryAndExport")):
        install()
        step(seed, mode)
        backup = adb("exec-out", "run-as", CHECK, "cat", "cache/recovery-test/backup.eidobackup", binary=True)
        expected = adb("exec-out", "run-as", CHECK, "cat", "cache/recovery-test/expected.json", binary=True)
        (args.report_dir / f"{mode}.eidobackup").write_bytes(backup)
        (args.report_dir / f"{mode}-expected.json").write_bytes(expected)
        shared = f"/sdcard/Download/eidolang-check-{mode}.eidobackup"
        adb("push", args.report_dir / f"{mode}.eidobackup", shared)
        remove_fixture()
        assert adb("exec-out", "cat", shared, binary=True) == backup
        install()
        adb("shell", "run-as", CHECK, "mkdir", "-p", "cache/recovery-test")
        for name, data in (("backup.eidobackup", backup), ("expected.json", expected)):
            host = args.report_dir / f"transfer-{name}"
            host.write_bytes(data)
            remote = "/data/local/tmp/eidolang-recovery-" + name
            adb("push", host, remote)
            adb("shell", "chmod", "444", remote)
            adb("shell", "run-as", CHECK, "cp", remote, "cache/recovery-test/" + name)
        step("rejectWrongPasswordAndEditedFileBeforeAnyKeyOrProfileWrite", mode)
        step("restoreAfterFullRemovalAndVerifyEveryPart", mode)
        adb("shell", "am", "force-stop", CHECK)
        step("reopenRestoredProfileAfterProcessRestart", mode)
        assert uid(OWNER) == owner_uid
        reports.append({"mode": mode, "full_removal_including_keys": True,
            "backup_survived_in_phone_downloads": True, "backup_sha256": hashlib.sha256(backup).hexdigest(),
            "same_user_id": True, "nickname_avatar_contacts_messages_captions_restored": True,
            "publisher_and_onion_secrets_restored": True,
            "original_root_and_device_keys_restored": mode == "portable-primary"})
        if mode == "portable-primary":
            remove_fixture()
    result = {"serial": args.serial, "owner_uid_unchanged": owner_uid,
        "owner_never_uninstalled_or_cleared": True, "checks": reports}
    (args.report_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("Report:", args.report_dir / "result.json", flush=True)


if __name__ == "__main__":
    main()
