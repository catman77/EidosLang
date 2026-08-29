#!/usr/bin/env python3
import base64, hashlib, hmac, json, sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / "golden/r20_1/r20_1_forward_session_golden_v1.json"

def b64u(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * ((4 - len(s) % 4) % 4))

def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac.new(salt, ikm, hashlib.sha256).digest()

def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    out = b""
    t = b""
    c = 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([c]), hashlib.sha256).digest()
        out += t
        c += 1
    return out[:length]

def root_step(root: bytes, dh: bytes):
    prk = hkdf_extract(root, dh)
    out = hkdf_expand(prk, b"EIDOLANG-R20.1-ROOT", 64)
    return out[:32], out[32:]

def message_step(ck: bytes):
    return (
        hmac.new(ck, b"\x01", hashlib.sha256).digest(),
        hmac.new(ck, b"\x02", hashlib.sha256).digest(),
    )

def header_bytes(h):
    return json.dumps(
        {
            "dh_public_b64": h["dh_public_b64"],
            "message_number": h["message_number"],
            "previous_chain_length": h["previous_chain_length"],
            "session_id": h["session_id"],
        },
        sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode()

g = json.loads(GOLDEN.read_text())

# Alice before Bob's first fresh-DH response.
a = json.loads(g["alice_state_before_response_json"])
rp = json.loads(g["response_packet_json"])
rb = rp["body"]
rh = rb["header"]

a_priv = serialization.load_der_private_key(b64u(a["self_dh_private_b64"]), password=None)
b_new_pub = serialization.load_der_public_key(b64u(rh["dh_public_b64"]))
dh = a_priv.exchange(ec.ECDH(), b_new_pub)
r1, recv_ck = root_step(b64u(a["root_key_b64"]), dh)
mk, _ = message_step(recv_ck)
pt = AESGCM(mk).decrypt(
    b64u(rb["nonce_b64"]),
    b64u(rb["ciphertext_b64"]),
    header_bytes(rh),
)
assert pt == b"golden-response"
print("PASS independent Python P-256 ECDH/root-ratchet/AES-GCM response")

# Bob before receiving Alice's new DH; packet 2 arrives before packet 0/1.
b = json.loads(g["bob_state_before_out_of_order_json"])
packets = [json.loads(x) for x in g["out_of_order_packets_json"]]
p2 = packets[2]["body"]
h2 = p2["header"]

b_priv = serialization.load_der_private_key(b64u(b["self_dh_private_b64"]), password=None)
a_new_pub = serialization.load_der_public_key(b64u(h2["dh_public_b64"]))
dh2 = b_priv.exchange(ec.ECDH(), a_new_pub)
_, recv2 = root_step(b64u(b["root_key_b64"]), dh2)

keys = []
ck = recv2
for n in range(3):
    mk_n, ck = message_step(ck)
    keys.append(mk_n)

for i in [2, 0, 1]:
    body = packets[i]["body"]
    h = body["header"]
    plain = AESGCM(keys[i]).decrypt(
        b64u(body["nonce_b64"]),
        b64u(body["ciphertext_b64"]),
        header_bytes(h),
    )
    assert plain == f"golden-{i}".encode()
print("PASS independent Python out-of-order chain-key derivation for 2,0,1")

# RFC 5869 Appendix A.1 independently.
ikm = bytes([0x0B]) * 22
salt = bytes.fromhex("000102030405060708090a0b0c")
info = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9")
prk = hkdf_extract(salt, ikm)
okm = hkdf_expand(prk, info, 42)
assert prk.hex() == "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
assert okm.hex() == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
print("PASS independent Python RFC5869 vector A.1")
