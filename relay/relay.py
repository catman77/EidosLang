#!/usr/bin/env python3
"""
EidoLang relay: the one reachable member every private swarm needs.

Two phones behind VPN or CGNAT can each open outbound connections but neither can accept one, so
they can never meet directly. This node has a public address and an open port, so both sides talk
to it outbound and delivery works regardless of what their networks allow.

It handles the two things that failed without it:

  * discovery - BEP44 heads are stored and served here, because publishing to the public DHT proved
    unreliable: a head stored on 7 nodes was not findable from another device.
  * data - it joins the swarm, pulls the segment from the sender and seeds it to the recipient.

It never sees plaintext. Heads are signed public metadata; segments are R15 encrypted envelopes.
A head is only accepted if its Ed25519 signature verifies and its target matches SHA1(key||salt),
so the relay cannot be used as an open dump and cannot forge anything.
"""
import hashlib
import hashlib
import json
import os
import socket
import threading
import time
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import libtorrent as lt
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.exceptions import InvalidSignature

SWARM_PORT = int(os.environ.get("SWARM_PORT", "6881"))
HTTP_PORT = int(os.environ.get("HTTP_PORT", "6882"))
STATE = Path(os.environ.get("STATE_DIR", "/var/lib/eidolang-relay"))
HEADS = STATE / "heads"
SEGMENTS = STATE / "segments"
DATA = STATE / "data"
MAX_HEAD_BYTES = 2048
#: One file of a conversation segment. Real ones are a few tens of kilobytes.
MAX_SEGMENT_FILE_BYTES = 2 * 1024 * 1024
#: Files per segment. A conversation carries a manifest, a descriptor, identities and messages.
MAX_SEGMENT_FILES = 128
DIRECTORY = STATE / "directory"
# An avatar is a small PNG; the cap is what keeps the directory from becoming an image host.
MAX_CARD_BYTES = 256 * 1024
MAX_AVATAR_B64 = 192 * 1024
DIRECTORY_MAX_RESULTS = 50
HEAD_TTL_SECONDS = 30 * 24 * 3600


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


def bencode(x):
    if isinstance(x, int):
        return b"i" + str(x).encode() + b"e"
    if isinstance(x, bytes):
        return str(len(x)).encode() + b":" + x
    if isinstance(x, list):
        return b"l" + b"".join(bencode(v) for v in x) + b"e"
    if isinstance(x, dict):
        return b"d" + b"".join(bencode(k) + bencode(v) for k, v in sorted(x.items())) + b"e"
    raise TypeError(type(x))


def verify_head(target_hex, raw):
    """Accept only a well-formed, self-consistent, correctly signed BEP44 mutable item."""
    if len(raw) > MAX_HEAD_BYTES:
        return "head too large"
    try:
        item, end = bdecode(raw)
    except Exception:
        return "not bencode"
    if end != len(raw) or not isinstance(item, dict):
        return "trailing bytes"
    if set(item) != {b"k", b"salt", b"seq", b"sig", b"v"}:
        return "unexpected fields"
    k, salt, seq, sig = item[b"k"], item[b"salt"], item[b"seq"], item[b"sig"]
    if len(k) != 32 or len(sig) != 64 or len(salt) > 64 or not isinstance(seq, int) or seq < 0:
        return "bad field sizes"
    if hashlib.sha1(k + salt).hexdigest() != target_hex:
        return "target does not match key and salt"
    signable = (bencode(b"salt") + bencode(salt) + bencode(b"seq") + bencode(seq) +
                bencode(b"v") + bencode(item[b"v"]))
    try:
        Ed25519PublicKey.from_public_bytes(k).verify(sig, signable)
    except InvalidSignature:
        return "bad signature"
    except Exception:
        return "cannot verify"
    return None


class Swarm:
    """libtorrent session that seeds whatever it is asked to carry."""

    def __init__(self):
        DATA.mkdir(parents=True, exist_ok=True)
        self.session = lt.session({
            "listen_interfaces": f"0.0.0.0:{SWARM_PORT}",
            "enable_dht": True,
            "enable_lsd": False,
            # The host has a public address; asking a router to map ports would be pointless.
            "enable_upnp": False,
            "enable_natpmp": False,
            "enable_outgoing_utp": True,
            "enable_incoming_utp": True,
            "alert_mask": lt.alert.category_t.error_notification | lt.alert.category_t.status_notification,
        })
        self.lock = threading.Lock()
        threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self):
        while True:
            self.session.pop_alerts()
            time.sleep(2)

    def carry(self, infohash_hex):
        """Join the swarm for this v2 infohash and keep seeding it."""
        with self.lock:
            for h in self.session.get_torrents():
                ih = h.info_hashes()
                if ih.has_v2() and str(ih.v2) == infohash_hex:
                    return "already carrying"
            magnet = f"magnet:?xt=urn:btmh:1220{infohash_hex}"
            params = lt.parse_magnet_uri(magnet)
            params.save_path = str(DATA)
            self.session.add_torrent(params)
            return "joined"

    def status(self):
        with self.lock:
            out = []
            for h in self.session.get_torrents():
                s = h.status()
                ih = h.info_hashes()
                out.append({
                    "infohash": str(ih.v2) if ih.has_v2() else str(ih.v1),
                    "seeding": bool(s.is_seeding),
                    "progress": round(s.progress, 3),
                    "peers": s.num_peers,
                })
            return out

    def dht_nodes(self):
        return int(self.session.status().dht_nodes) if hasattr(self.session, "status") else -1


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "eidolang-relay"

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)

    def _reply(self, code, body=b"", ctype="application/octet-stream"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, code, obj):
        self._reply(code, json.dumps(obj).encode(), "application/json")

    def do_GET(self):
        if self.path.startswith("/directory/available"):
            from urllib.parse import parse_qs, urlparse
            q = parse_qs(urlparse(self.path).query)
            nick = (q.get("nickname") or [""])[0].strip()
            uid = (q.get("user_id") or [""])[0].strip()
            if len(nick) < 2:
                return self._json(400, {"error": "nickname too short"})
            return self._json(200, {"available": not nickname_taken(nick, uid), "nickname": nick})
        if self.path.startswith("/directory/search"):
            q = ""
            if "?" in self.path:
                from urllib.parse import parse_qs, urlparse
                q = (parse_qs(urlparse(self.path).query).get("q") or [""])[0]
            q = q.strip().lower()
            if len(q) < 2:
                return self._json(400, {"error": "query too short"})
            out = []
            for f in sorted(DIRECTORY.glob("*.json")):
                try:
                    e = json.loads(f.read_text())
                except Exception:
                    continue
                if not e.get("visible", True):
                    continue
                if q in e.get("nickname", "").lower():
                    out.append(e)
                    if len(out) >= DIRECTORY_MAX_RESULTS:
                        break
            return self._json(200, {"results": out, "capped": len(out) >= DIRECTORY_MAX_RESULTS})
        if self.path == "/health":
            return self._json(200, {
                "ok": True,
                "dht_nodes": self.server.swarm.dht_nodes(),
                "carrying": self.server.swarm.status(),
            })
        if self.path.startswith("/head/"):
            target = self.path[len("/head/"):]
            if not _is_sha1(target):
                return self._json(400, {"error": "bad target"})
            f = HEADS / f"{target}.bin"
            if not f.exists():
                return self._json(404, {"error": "not found"})
            return self._reply(200, f.read_bytes())
        if self.path.startswith("/segment/") and self.path.endswith("/status"):
            # How much of this segment has actually gone out.
            #
            # The author cannot see a collection it did not serve: its own onion observes pickups,
            # and a recipient that took the bytes from here instead leaves no trace on it. So a
            # delivered message sat at one tick forever, and the copy nobody needed was never
            # dropped. Reporting the count closes both — the author asks, marks it delivered, and
            # then removes it with the signed delete it already had.
            infohash = self.path[len("/segment/"):-len("/status")]
            if not _is_sha256(infohash):
                return self._json(400, {"error": "bad segment id"})
            d = SEGMENTS / infohash
            files = sorted(d.glob("*.bin")) if d.is_dir() else []
            served = sum(1 for f in files if (d / (f.stem + ".served")).exists())
            return self._json(200, {"files": len(files), "served": served})
        if self.path.startswith("/segment/"):
            parts = _segment_parts(self.path, "/segment/")
            if parts is None:
                return self._json(400, {"error": "bad segment path"})
            f = SEGMENTS / parts[0] / f"{parts[1]}.bin"
            if not f.is_file():
                return self._json(404, {"error": "not found"})
            body = f.read_bytes()
            # Marked after the read, so a file that could not be produced is not counted as sent.
            (f.parent / (f.stem + ".served")).touch(exist_ok=True)
            return self._reply(200, body)
        return self._json(404, {"error": "no such endpoint"})

    def do_PUT(self):
        if self.path.startswith("/directory"):
            from urllib.parse import parse_qs, urlparse
            visible = (parse_qs(urlparse(self.path).query).get("visible") or ["1"])[0] != "0"
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_CARD_BYTES:
                return self._json(400, {"error": "bad length"})
            raw = self.rfile.read(length)
            try:
                card = json.loads(raw)
            except Exception:
                return self._json(400, {"error": "not json"})
            user = (card.get("identity") or {}).get("user") or {}
            claimed = user.get("user_id") or ""
            derived = derive_user_id(user)
            # The directory slot is the hash of the root public key, so nobody can publish over
            # somebody else's entry. It does NOT stop anyone from listing themselves under a
            # nickname that is already taken — a nickname directory cannot, and the client says so.
            if not derived or derived != claimed:
                return self._json(400, {"error": "user_id does not match the key"})
            nickname = ((card.get("profile") or {}).get("nickname") or "").strip()
            DIRECTORY.mkdir(parents=True, exist_ok=True)
            f = DIRECTORY / f"{derived}.json"
            if not nickname:
                f.unlink(missing_ok=True)
                return self._json(200, {"withdrawn": derived})
            # No uniqueness rule. Names are self-certifying on the client — "nick#abcd", where the
            # suffix comes from the root key — so two people may hold the same nickname and still be
            # told apart. Enforcing uniqueness here would make this server the one thing the product
            # cannot work without, which is exactly what it is not meant to be.
            f.write_text(json.dumps({
                "user_id": derived,
                "nickname": nickname[:40],
                "avatar_png_b64": ((card.get("profile") or {}).get("avatar_png_b64") or "")[:MAX_AVATAR_B64],
                # Stored verbatim. Re-serialising it here would reorder keys and break the
                # client's strict canonical parse, which is the whole basis of card validation.
                "card_json": raw.decode("utf-8", "strict"),
                "visible": visible,
            }))
            return self._json(200, {"published": derived, "nickname": nickname[:40]})
        if self.path.startswith("/segment/"):
            return self._put_segment()
        if not self.path.startswith("/head/"):
            return self._json(404, {"error": "no such endpoint"})
        target = self.path[len("/head/"):]
        if not _is_sha1(target):
            return self._json(400, {"error": "bad target"})
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_HEAD_BYTES:
            return self._json(400, {"error": "bad length"})
        raw = self.rfile.read(length)
        problem = verify_head(target, raw)
        if problem:
            return self._json(400, {"error": problem})

        # Monotonic sequence, exactly as BEP44 requires: never replace newer with older.
        f = HEADS / f"{target}.bin"
        new_seq = bdecode(raw)[0][b"seq"]
        if f.exists():
            try:
                old_seq = bdecode(f.read_bytes())[0][b"seq"]
                if new_seq < old_seq:
                    return self._json(409, {"error": "stale sequence", "stored": old_seq})
            except Exception:
                pass
        HEADS.mkdir(parents=True, exist_ok=True)
        tmp = f.with_suffix(".tmp")
        tmp.write_bytes(raw)
        tmp.replace(f)
        return self._json(200, {"stored": target, "seq": new_seq})

    def _put_segment(self):
        """
        Keep one file of an encrypted segment so the sender need not be online when it is collected.

        This is the locker, and it is the whole reason the relay still exists in delivery. Without
        it both phones have to be awake, in the app and through Tor at the same instant, which is
        not a way anybody can use a messenger. The bytes are the same segment files the onion serves
        and the swarm carries — nothing new goes on the wire — and they are encrypted to the
        recipient, so this stores what it cannot read and verifies nothing about it. The recipient
        still runs the admission gate over whatever it gets, exactly as before.
        """
        parts = _segment_parts(self.path, "/segment/")
        if parts is None:
            return self._json(400, {"error": "bad segment path"})
        infohash, name = parts
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_SEGMENT_FILE_BYTES:
            return self._json(400, {"error": "bad length"})
        if not head_names_infohash(infohash):
            return self._json(409, {"error": "no signed head refers to this segment"})
        d = SEGMENTS / infohash
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{name}.bin"
        if not f.exists() and len(list(d.glob("*.bin"))) >= MAX_SEGMENT_FILES:
            return self._json(409, {"error": "too many files in this segment"})
        raw = self.rfile.read(length)
        tmp = f.with_suffix(".tmp")
        tmp.write_bytes(raw)
        tmp.replace(f)
        return self._json(200, {"stored": name, "bytes": len(raw)})

    def do_DELETE(self):
        """
        Drop a segment once its author knows it has been collected.

        Authenticated, and by the key that is already in play: the signature is checked against the
        publisher key of the stored head that vouches for this segment, so only whoever signed that
        head can remove what it refers to. Without this, "delete" would be an unauthenticated way to
        wipe other people's undelivered messages — a far worse hole than the storage it tidies.
        Nothing in the locker is meant to be permanent; this simply stops it holding what nobody
        needs any more, which is the whole of its privacy cost.
        """
        if not self.path.startswith("/segment/"):
            return self._json(404, {"error": "no such endpoint"})
        infohash = self.path[len("/segment/"):].strip("/")
        if not _is_sha256(infohash):
            return self._json(400, {"error": "bad segment id"})
        length = int(self.headers.get("Content-Length", "0"))
        if length != 64:
            return self._json(400, {"error": "signature must be 64 bytes"})
        sig = self.rfile.read(64)
        key = head_key_for_infohash(infohash)
        if key is None:
            return self._json(404, {"error": "no head refers to this segment"})
        try:
            Ed25519PublicKey.from_public_bytes(key).verify(sig, b"eidolang-drop:" + infohash.encode())
        except (InvalidSignature, Exception):
            return self._json(403, {"error": "bad signature"})
        d = SEGMENTS / infohash
        removed = 0
        if d.is_dir():
            for f in list(d.glob("*.bin")) + list(d.glob("*.served")):
                f.unlink(missing_ok=True)
                if f.suffix == ".bin":
                    removed += 1
            if not any(d.iterdir()):
                d.rmdir()
        return self._json(200, {"dropped": infohash, "files": removed})

    def do_POST(self):
        if not self.path.startswith("/carry/"):
            return self._json(404, {"error": "no such endpoint"})
        infohash = self.path[len("/carry/"):]
        if len(infohash) != 64 or any(c not in "0123456789abcdef" for c in infohash):
            return self._json(400, {"error": "bad infohash"})
        return self._json(200, {"infohash": infohash, "result": self.server.swarm.carry(infohash)})


def nickname_taken(nickname, by_user_id):
    """Is this name held by somebody other than by_user_id?

    A linear scan over the directory. At this scale that is the right amount of machinery; an index
    would be a second source of truth to keep consistent for no measurable gain.
    """
    want = nickname.strip().lower()
    if not DIRECTORY.exists():
        return False
    for f in DIRECTORY.glob("*.json"):
        try:
            e = json.loads(f.read_text())
        except Exception:
            continue
        if e.get("nickname", "").strip().lower() == want and e.get("user_id") != by_user_id:
            return True
    return False


def derive_user_id(user):
    """Recompute user_id the way the client does: SHA-256 over the canonical user body.

    Deliberately no signature checking here — the relay is not a trust anchor. The client validates
    the bundle on import, so a card that does not hold together is rejected where it matters. What
    this does buy is that a directory entry cannot be written over by someone who does not hold the
    corresponding root key.
    """
    key = user.get("root_signing_public_key_b64")
    alg = user.get("signature_algorithm")
    if not isinstance(key, str) or not isinstance(alg, str):
        return None
    body = ('{"identity_type":"EidoUserIdentityV1","identity_version":"1.0.0",'
            f'"root_signing_public_key_b64":{json.dumps(key)},'
            f'"signature_algorithm":{json.dumps(alg)}}}')
    return hashlib.sha256(body.encode()).hexdigest()


def _is_sha1(s):
    return len(s) == 40 and all(c in "0123456789abcdef" for c in s)


def _is_sha256(s):
    return len(s) == 64 and all(c in "0123456789abcdef" for c in s)


def head_key_for_infohash(infohash):
    """The publisher key of a stored head that points at this segment, or None."""
    want = bytes.fromhex(infohash)
    for f in HEADS.glob("*.bin"):
        try:
            item, _ = bdecode(f.read_bytes())
            if item[b"v"][b"i"] == want:
                return item[b"k"]
        except Exception:
            continue
    return None


def head_names_infohash(infohash):
    """
    Is there a stored head that points at this segment?

    The relay verifies no segment: it cannot, the contents are encrypted to the recipient and it
    holds no key. What it can do is refuse to store bytes nobody has vouched for. A head is signed
    and its signature is checked on the way in, so requiring one that names this infohash binds
    every stored segment to a statement somebody made with their own key — and stops the locker
    being a free file host for the rest of the internet.
    """
    want = bytes.fromhex(infohash)
    for f in HEADS.glob("*.bin"):
        try:
            item, _ = bdecode(f.read_bytes())
            # The head value is a compact bencoded dict, not JSON: `i` carries the v2 infohash as
            # raw bytes. Reading it as JSON — as this first did — rejected every segment ever
            # offered, which the locker test caught on its first run.
            if item[b"v"][b"i"] == want:
                return True
        except Exception:
            continue
    return False


def _segment_parts(path, prefix):
    """`/segment/<64 hex infohash>/<64 hex name>` or None."""
    rest = path[len(prefix):]
    parts = rest.split("/")
    if len(parts) != 2 or not _is_sha256(parts[0]) or not _is_sha256(parts[1]):
        return None
    return parts[0], parts[1]


def sweep():
    """Drop heads nobody refreshed, so the relay does not grow without bound."""
    while True:
        cutoff = time.time() - HEAD_TTL_SECONDS
        for f in HEADS.glob("*.bin"):
            if f.stat().st_mtime < cutoff:
                f.unlink(missing_ok=True)
        # Segments age out on the same clock as the heads that vouch for them.
        for d in SEGMENTS.glob("*"):
            if not d.is_dir():
                continue
            for f in list(d.glob("*.bin")) + list(d.glob("*.served")):
                if f.stat().st_mtime < cutoff:
                    f.unlink(missing_ok=True)
            if not any(d.iterdir()):
                d.rmdir()
        time.sleep(3600)


#: A stalled peer may hold a worker thread this long, and no longer.
CONNECTION_TIMEOUT_S = 30


class TlsThreadingHTTPServer(ThreadingHTTPServer):
    """
    TLS handshakes performed in the worker thread instead of in the accept loop.

    The relay used to wrap its *listening* socket: `httpd.socket = ctx.wrap_socket(httpd.socket)`.
    That makes every handshake happen inside `accept()`, before the connection is handed to a
    thread and with no timeout at all, so `ThreadingHTTPServer` buys nothing — the serialisation
    happens one step earlier than the threads. A single client that opens a connection and never
    finishes the handshake then blocks the entire server, permanently.

    Not hypothetical. The relay answered its last request at 2026-08-24T10:56Z and was still wedged
    four days later, while the container reported `Up`, the port stayed bound and `docker logs`
    showed no error — so from every angle except an actual request it looked healthy. A public
    address receives a steady supply of port scanners that connect and vanish; reproduced offline
    in `work/out/relay-repro/repro.py`, where one silent client stops the old wiring answering
    anybody and leaves this one serving.
    """

    daemon_threads = True
    tls_ctx = None

    def finish_request(self, request, client_address):
        # Runs on the worker thread, so a peer that stalls costs one thread and a timeout rather
        # than the whole listener.
        request.settimeout(CONNECTION_TIMEOUT_S)
        if self.tls_ctx is None:
            self.RequestHandlerClass(request, client_address, self)
            return
        try:
            tls = self.tls_ctx.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            # A scanner speaking plain HTTP at a TLS port, or one that left mid-handshake. Routine
            # on a public address, and not worth a traceback per packet.
            return
        try:
            self.RequestHandlerClass(tls, client_address, self)
        finally:
            # `wrap_socket` detaches the plain socket, so the base class's `shutdown_request` is
            # left holding a dead descriptor and never closes the real one. Without this the
            # process leaks a file descriptor per request — the same outage, arriving slower.
            try:
                tls.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            tls.close()


def main():
    HEADS.mkdir(parents=True, exist_ok=True)
    SEGMENTS.mkdir(parents=True, exist_ok=True)
    swarm = Swarm()
    threading.Thread(target=sweep, daemon=True).start()
    httpd = TlsThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    httpd.swarm = swarm

    # TLS is not optional: the app forbids cleartext traffic (R22 hardening), so a plain-HTTP relay
    # is unreachable from it by design. A self-signed certificate pinned in the app keeps that
    # property intact rather than punching a hole in it.
    cert, key = Path("/opt/tls/cert.pem"), Path("/opt/tls/key.pem")
    scheme = "http"
    if cert.exists() and key.exists():
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(certfile=str(cert), keyfile=str(key))
        httpd.tls_ctx = ctx
        scheme = "https"
    print(f"eidolang-relay: swarm on {SWARM_PORT}, {scheme} on {HTTP_PORT}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
