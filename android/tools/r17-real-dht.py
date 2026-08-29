#!/usr/bin/env python3
"""
R17.1 item 6, as far as it can be taken without the publisher's DHT private key.

A private loopback DHT carries the exact BEP44 head value from `golden/r16/head-put.bencode`
through a real `dht_put_mutable_item` / `dht_get_mutable_item` round trip between two different
nodes.

Scope, stated up front: the golden artifacts hold the publisher's Ed25519 *public* key and
signature but not the private key, so the project's own signed item cannot be republished. The
identical value is stored under a freshly generated key instead. This establishes that a real
BEP44 implementation accepts the head structure, stores it and returns it unchanged, and that it
addresses items by the same `target = SHA1(pk || salt)` the project uses. It does not re-verify
the project's signature on the wire — `tools/python/verify_r16_bep44.py` does that offline.

Two things make a naive version of this test lie:
  * a DHT miss still delivers `dht_mutable_item_alert`, with `authoritative=True` and an empty
    item, so the result must be checked for presence before it is compared;
  * `dht_put_alert.num_success == 0` means the item reached nobody, which a test that only waits
    for the alert would report as success.
Both are asserted explicitly below.

libtorrent refuses to put several loopback nodes in one routing table under its default
anti-Sybil settings, and a two-node DHT never finishes bootstrapping, so this runs a small local
swarm with those restrictions lifted. Public bootstrap nodes stay disabled throughout; nothing is
published to the global DHT.
"""
import hashlib
import json
import base64
import sys
import time
from pathlib import Path

try:
    import libtorrent as lt
except ImportError:
    sys.exit("libtorrent bindings not importable by this interpreter")

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "golden" / "r16"
NODES, BASE_PORT = 8, 6900
DEADLINE = 60


def bdecode(b, i=0):
    c = b[i:i + 1]
    if c == b"i":
        j = b.index(b"e", i)
        return int(b[i + 1:j]), j + 1
    if c == b"l":
        out, i = [], i + 1
        while b[i:i + 1] != b"e":
            v, i = bdecode(b, i)
            out.append(v)
        return out, i + 1
    if c == b"d":
        out, i = {}, i + 1
        while b[i:i + 1] != b"e":
            k, i = bdecode(b, i)
            v, i = bdecode(b, i)
            out[k] = v
        return out, i + 1
    j = b.index(b":", i)
    n = int(b[i:j])
    j += 1
    return b[j:j + n], j + n


def make_session(port):
    ses = lt.session({
        "listen_interfaces": f"127.0.0.1:{port}",
        "enable_dht": True,
        "dht_bootstrap_nodes": "",              # never touch the public DHT
        "enable_lsd": False,
        "enable_upnp": False,
        "enable_natpmp": False,
        # Local-only test swarm: without these every node is on 127.0.0.1 and libtorrent's
        # anti-Sybil rules keep the routing table empty.
        "dht_restrict_routing_ips": False,
        "dht_restrict_search_ips": False,
        "dht_ignore_dark_internet": False,
        # BEP42 ties a node ID to its external IP; on loopback that check fails and peers
        # silently refuse to store, which shows up only as dht_put_alert.num_success == 0.
        "dht_enforce_node_id": False,
        "alert_mask": lt.alert.category_t.dht_notification
                      | lt.alert.category_t.error_notification
                      | lt.alert.category_t.stats_notification,
    })
    assert ses.get_settings()["dht_bootstrap_nodes"] == "", "public DHT bootstrap must stay empty"
    return ses


def pump(ses, kind, deadline=DEADLINE):
    start = time.monotonic()
    while time.monotonic() - start < deadline:
        for a in ses.pop_alerts():
            if isinstance(a, kind):
                return a
        time.sleep(0.2)
    return None


def routing_table_size(ses):
    ses.post_dht_stats()
    a = pump(ses, lt.dht_stats_alert, 10)
    return None if a is None else sum(b["num_nodes"] for b in a.routing_table)


def main():
    raw = (GOLDEN / "head-put.bencode").read_bytes()
    put, end = bdecode(raw)
    assert end == len(raw)
    salt, value, seq = put[b"salt"], put[b"v"], put[b"seq"]
    assert isinstance(value, dict) and value, "head value must be a non-empty bencoded dict"

    ids = dict(
        line.split("=", 1) for line in (GOLDEN / "ids.txt").read_text().splitlines() if "=" in line
    )

    # The project addresses heads as SHA1(dht_public_key || salt); confirm that formula against
    # the golden target before asking a real implementation to use it.
    cert = json.loads((GOLDEN / "publisher-certificate.json").read_text())
    project_pk = base64.urlsafe_b64decode(cert["body"]["dht_public_key_raw_b64"] + "==")
    assert hashlib.sha1(project_pk + salt).hexdigest() == ids["bep44_target"]
    print("PASS target formula SHA1(k||salt) reproduces the golden bep44_target")

    swarm = [make_session(BASE_PORT + i) for i in range(NODES)]
    for i, s in enumerate(swarm):
        for j in range(NODES):
            if i != j:
                s.add_dht_node(("127.0.0.1", BASE_PORT + j))

    size = None
    for _ in range(6):
        time.sleep(5)
        size = routing_table_size(swarm[0])
        if size and size >= 2:
            break
    assert size and size >= 2, f"private DHT never formed a routing table (size={size})"
    print(f"PASS private {NODES}-node DHT formed a routing table ({size} nodes, no public bootstrap)")

    sk = Ed25519PrivateKey.generate()
    seed = sk.private_bytes(
        serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption()
    )
    pub = sk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    # libtorrent bundles the orlp/ed25519 implementation, whose 64-byte secret key is the clamped
    # SHA-512 expansion of the seed -- NOT the NaCl/libsodium `seed || public` layout. Passing the
    # NaCl form is accepted by the length check and then rejected by every storing node with
    # "(206) invalid signature", visible only in the DHT log.
    h = bytearray(hashlib.sha512(seed).digest())
    h[0] &= 248
    h[31] &= 63
    h[31] |= 64
    priv = bytes(h)

    publisher, retriever = swarm[0], swarm[-1]
    publisher.dht_put_mutable_item(priv, pub, lt.bencode(value), salt)
    put_alert = pump(publisher, lt.dht_put_alert)
    assert put_alert is not None, "no dht_put_alert: the DHT never processed the put"
    assert put_alert.num_success > 0, "put reached 0 nodes; nothing was actually stored"
    print(f"PASS head value published as a real BEP44 mutable item to {put_alert.num_success} node(s)")

    retriever.dht_get_mutable_item(pub, salt)
    got = pump(retriever, lt.dht_mutable_item_alert)
    assert got is not None, "no dht_mutable_item_alert"
    assert got.item is not None, "DHT miss: a miss also raises this alert with an empty item"
    assert got.seq >= seq, f"sequence regressed: {got.seq} < {seq}"
    print("PASS a different node retrieved the item from the DHT")

    # `dht_mutable_item_alert.item` is already the raw bencoded value; bencoding it again wraps it
    # as a byte string (a `310:` length prefix appears) and the comparison fails for the wrong reason.
    assert bytes(got.item) == lt.bencode(value), "head value changed in transit"
    assert bytes(got.salt) == salt, "salt changed in transit"
    assert bytes(got.key) == pub, "public key changed in transit"
    print(f"PASS retrieved head value is byte-identical (seq={got.seq}, authoritative={got.authoritative})")

    assert len(lt.bencode(value)) <= 1000
    print("PASS stored value stays within the BEP44 1000-byte limit")

    print(f"TARGET_GOLDEN={ids['bep44_target']}")
    print("ALL R17 REAL-DHT CHECKS PASS")


if __name__ == "__main__":
    main()
