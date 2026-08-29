#!/usr/bin/env python3
"""
R17.1 items 2-5 and 7 against a real BitTorrent implementation.

Two independent libtorrent sessions on loopback: A seeds the fixed R16 golden segment, B adds it
by v2 magnet only and pulls metadata (BEP9) and data (BEP52) over the wire. Nothing is shared
between them at the filesystem level and no application file is copied.

Deliberately offline: DHT, LSD, UPnP/NAT-PMP and trackers are all disabled and B is pointed at A
with an explicit `connect_peer`. Nothing touches the public network, and nothing is published.

Run with the interpreter that has the libtorrent bindings, e.g.
    /usr/bin/python3 tools/r17-real-swarm.py
"""
import hashlib
import os
import shutil
import sys
import time
from pathlib import Path

try:
    import libtorrent as lt
except ImportError:
    sys.exit("libtorrent bindings not importable by this interpreter; see R17_DEPENDENCY_PIN.md")

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "golden" / "r16"
SEGMENT = GOLDEN / "segment"
WORK = Path(os.environ.get("R17_WORK", "/tmp/r17-swarm"))

PORT_A, PORT_B = 6881, 6882
DEADLINE = 120


def ids():
    out = {}
    for line in (GOLDEN / "ids.txt").read_text().splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree(root: Path) -> dict:
    return {
        str(p.relative_to(root)): digest(p)
        for p in sorted(root.rglob("*")) if p.is_file()
    }


def make_session(port: int):
    return lt.session({
        "listen_interfaces": f"127.0.0.1:{port}",
        "enable_dht": False,
        "enable_lsd": False,
        "enable_upnp": False,
        "enable_natpmp": False,
        "alert_mask": lt.alert.category_t.error_notification
                      | lt.alert.category_t.status_notification,
    })


def wait_until(predicate, what, deadline=DEADLINE):
    start = time.monotonic()
    while time.monotonic() - start < deadline:
        if predicate():
            return time.monotonic() - start
        time.sleep(0.2)
    raise SystemExit(f"TIMEOUT waiting for {what} after {deadline}s")


def main():
    expected = ids()
    shutil.rmtree(WORK, ignore_errors=True)
    (WORK / "b").mkdir(parents=True)

    info = lt.torrent_info(str(GOLDEN / "segment.torrent"))
    name = info.name()

    # libtorrent must independently agree with the R16 builder about the v2 infohash.
    v2 = str(info.info_hashes().v2)
    assert v2 == expected["torrent_infohash_v2"], f"v2 infohash mismatch: {v2}"
    print("PASS libtorrent recomputes the exact R16 v2 infohash from the fixed metainfo")

    # Seeder's payload is a private copy; B never sees this directory.
    seed_root = WORK / "a"
    shutil.copytree(SEGMENT, seed_root / name)
    original = tree(seed_root / name)
    assert len(original) == 6, original

    ses_a = make_session(PORT_A)
    ses_b = make_session(PORT_B)

    ha = ses_a.add_torrent({"ti": info, "save_path": str(seed_root), "flags": lt.torrent_flags.seed_mode})
    wait_until(lambda: ha.status().is_seeding, "seeder to enter seeding state")
    print("PASS session A seeds the fixed golden segment")

    # B starts from the magnet alone: no metainfo, no files.
    params = lt.parse_magnet_uri((GOLDEN / "magnet.txt").read_text().strip())
    params.save_path = str(WORK / "b")
    hb = ses_b.add_torrent(params)
    hb.connect_peer(("127.0.0.1", PORT_A))

    took = wait_until(lambda: hb.status().has_metadata, "B to pull metadata over BEP9")
    print(f"PASS session B obtains metadata from the swarm via magnet only ({took:.1f}s)")

    wait_until(lambda: hb.status().is_seeding, "B to complete the download")
    took = time.monotonic()
    print("PASS session B completes the transfer over a real BitTorrent connection")

    got = tree(WORK / "b" / name)
    assert got == original, f"transferred tree differs\nexpected={original}\ngot={got}"
    print("PASS every transferred file is byte-identical to the seeded original")

    # The v2 infohash of what B actually received must still be the R16 one.
    received = str(hb.torrent_file().info_hashes().v2)
    assert received == expected["torrent_infohash_v2"], received
    print("PASS the received torrent carries the exact R16 v2 infohash")

    print(f"INFOHASH_V2={received}")
    print(f"DOWNLOAD_DIR={WORK / 'b' / name}")
    print("ALL R17 REAL-SWARM TRANSFER CHECKS PASS")


if __name__ == "__main__":
    main()
