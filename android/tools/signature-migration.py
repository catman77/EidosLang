#!/usr/bin/env python3
"""Exercise an old signed app and the migration app on one fresh rooted emulator.

The legacy APK is rebuilt from 2efeb51 with LegacyMigrationFixtureTest added to its
test sources, then both legacy APKs are signed with a separate disposable key.
This fixture is not the published R22.1 APK and cannot establish its update compatibility.
This driver refuses real phones and existing installations. It never uninstalls.
"""
import argparse
import hashlib
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path

OLD = "org.eidolang.app"
NEW = "org.eidolang.app.migration"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--legacy-apk", type=Path, required=True)
    parser.add_argument("--legacy-tests", type=Path, required=True)
    parser.add_argument("--migration-apk", type=Path, required=True)
    parser.add_argument("--migration-tests", type=Path, required=True)
    parser.add_argument("--standard-apk", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use a fresh disposable emulator; real phones are not accepted")

    def adb(*command, check=True, data=None):
        result = subprocess.run(["adb", "-s", args.serial, *command], input=data, capture_output=True, timeout=120)
        if check and result.returncode:
            raise RuntimeError(result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))
        return result

    for package in (OLD, NEW, OLD + ".test", NEW + ".test"):
        installed = adb("shell", "pm", "path", package, check=False)
        if installed.stdout.strip():
            raise SystemExit(f"Refusing to modify an existing installation: {package}")
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u").stdout.strip() == b"0", "A rootable emulator image is required"

    def uid(package):
        output = adb("shell", "cmd", "package", "list", "packages", "-U", package).stdout.decode()
        return re.search(rf"package:{re.escape(package)} uid:(\d+)\b", output).group(1)

    def copy(source, target, names):
        target_uid = uid(target)
        directory = f"/data/user/0/{target}/files/exchange"
        adb("shell", f"mkdir -p {directory}; chown {target_uid}:{target_uid} /data/user/0/{target}/files {directory}")
        for name in names:
            data = adb("exec-out", "cat", f"/data/user/0/{source}/files/exchange/{name}").stdout
            assert data, f"Empty exchange file: {name}"
            adb("shell", f"cat > {directory}/{name}", data=data)
            adb("shell", "chown", f"{target_uid}:{target_uid}", f"{directory}/{name}")

    completed = []

    def step(package, class_name, method):
        adb("shell", "am", "force-stop", package)
        result = adb("shell", "am", "instrument", "-w", "-e", "class", f"org.eidolang.app.{class_name}#{method}", f"{package}.test/androidx.test.runner.AndroidJUnitRunner")
        output = result.stdout.decode(errors="replace").replace("\r", "")
        if not re.search(r"^OK \(1 test\)$", output, re.MULTILINE):
            raise RuntimeError(output + result.stderr.decode(errors="replace"))
        completed.append(method)
        print("PASS", method, flush=True)

    for apk in (args.legacy_apk, args.legacy_tests):
        adb("install", "-t", str(apk))
    old_uid = uid(OLD)
    step(OLD, "LegacyMigrationFixtureTest", "createExistingProfileAndTranscript")
    failed_update = adb("install", "-r", str(args.standard_apk), check=False)
    assert b"INSTALL_FAILED_UPDATE_INCOMPATIBLE" in failed_update.stdout + failed_update.stderr
    print("PASS original signature conflict reproduced", flush=True)
    for apk in (args.migration_apk, args.migration_tests):
        adb("install", "-t", str(apk))
    assert uid(NEW) != old_uid
    copy(OLD, NEW, ("owner-card.json", "roster-before.json"))
    step(NEW, "SignatureMigrationTest", "createRequestAndPreserveProfile")
    step(NEW, "SignatureMigrationTest", "rejectAuthorizationFromAnotherOwner")
    copy(NEW, OLD, ("enrollment-request.json",))
    step(OLD, "LegacyMigrationFixtureTest", "authorizeNewInstallationAndExportHistory")
    copy(OLD, NEW, ("authorized-bundle.json", "roster.json", "vault-package.json", "recovery-backup.json", "recovery-code.txt", "expected-history.json", "conversation-id.txt"))
    step(NEW, "SignatureMigrationTest", "installCertificateButRequireRoster")
    step(NEW, "SignatureMigrationTest", "completeEnrollmentAfterRestart")
    step(NEW, "SignatureMigrationTest", "restoreAvailableHistoryAndSend")
    copy(NEW, OLD, ("new-message.json",))
    step(OLD, "LegacyMigrationFixtureTest", "verifyOriginalProfileAndAcceptNewMessage")
    step(NEW, "SignatureMigrationTest", "reopenMigratedProfile")
    step(NEW, "SignatureMigrationTest", "unreadableArchiveIsReportedWithoutResettingProfile")
    step(OLD, "LegacyMigrationFixtureTest", "exportOriginalNetworkIdentity")
    copy(OLD, NEW, ("original-network.json",))
    step(NEW, "SignatureMigrationTest", "independentNetworkIdentity")
    # Both actual home screens must be able to serve their avatars at the same time.
    forwards = []
    try:
        for package in (OLD, NEW):
            adb("shell", "am", "force-stop", package)
            adb("shell", "am", "start", "-W", "-n", f"{package}/org.eidolang.app.MainActivity")
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for port in (6883, 6884):
            forwarded = adb("forward", "tcp:0", f"tcp:{port}").stdout.decode().strip()
            forwards.append(forwarded)
            for attempt in range(40):
                try:
                    with http.open(f"http://127.0.0.1:{forwarded}/avatar", timeout=2) as response:
                        assert response.read().startswith(b"\x89PNG\r\n\x1a\n")
                    break
                except Exception:
                    if attempt == 39:
                        raise
                    time.sleep(1)
        print("PASS original and migration servers operate concurrently", flush=True)
    finally:
        for forwarded in forwards:
            adb("forward", "--remove", f"tcp:{forwarded}")
    assert uid(OLD) == old_uid
    receipt = {
        "fixture_source": "2efeb51 (reconstructed source, disposable signing key; not public R22.1)",
        "old_package": OLD,
        "new_package": NEW,
        "legacy_uid_preserved": True,
        "same_user_identity": True,
        "nickname_and_avatar_preserved": True,
        "old_messages_and_captions_preserved_in_original": True,
        "legacy_vault_captions": "not exported by the original app; remain readable in the original installation",
        "new_device_authorized": True,
        "independent_onion_keys_and_ports": True,
        "parallel_http_servers": True,
        "old_primary_retirement": "not supported; the primary root stays in the original installation",
        "passed": completed,
        "migration_apk_sha256": hashlib.sha256(args.migration_apk.read_bytes()).hexdigest(),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    print("Validation receipt:", args.report, flush=True)


if __name__ == "__main__":
    main()
