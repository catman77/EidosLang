#!/usr/bin/env python3
import base64, hashlib, hmac, json
from pathlib import Path
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT=Path(__file__).resolve().parents[2]
g=json.loads((ROOT/'golden/r20_2/r20_2_history_vault_golden_v1.json').read_text())

def b64u(s):
    return base64.urlsafe_b64decode(s+'='*((4-len(s)%4)%4))

def canon(obj):
    return json.dumps(obj,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()

def hkdf_extract(salt,ikm):
    return hmac.new(salt,ikm,hashlib.sha256).digest()

def hkdf_expand(prk,info,length):
    out=b'';t=b'';c=1
    while len(out)<length:
        t=hmac.new(prk,t+info+bytes([c]),hashlib.sha256).digest();out+=t;c+=1
    return out[:length]

def hkdf(salt,ikm,info,length=32):
    return hkdf_expand(hkdf_extract(salt,ikm),info,length)

pkg=json.loads(g['vault_package_canonical_json'])
body=pkg['body']
assert hashlib.sha256(canon(body)).hexdigest()==pkg['package_id']==g['expected']['package_id']
exporter=json.loads(body['exporter_identity_bundle_json'])
pub=serialization.load_der_public_key(b64u(exporter['device']['body']['signing_public_key_b64']))
pub.verify(b64u(pkg['exporter_signature_b64']),canon(body),ec.ECDSA(hashes.SHA256()))
print('PASS independent Python history-vault package SHA256/ECDSA authentication')

secret_export=json.loads(g['recovery_secret_export_canonical_json'])
secret_body=secret_export['body']
recovery_secret=b64u(secret_body['secret_b64'])
assert hashlib.sha256(recovery_secret).hexdigest() == secret_body['recovery_key_id'].split(':',1)[1]

descriptor=json.loads(body['vault_descriptor_json'])
vault_id=descriptor['vault_id']
epoch=json.loads(body['epoch_json'][0])
eb=epoch['body']
aad_epoch=canon({
    'active_device_ids':eb['active_device_ids'],
    'epoch':eb['epoch'],
    'previous_epoch_id':eb['previous_epoch_id'],
    'recovery_key_id':eb['recovery_key_id'],
    'vault_id':eb['vault_id'],
    'wrap_type':'EidoHistoryVaultEpochRecoveryWrapV1',
})
wrap_key=hkdf(vault_id.encode(),recovery_secret,b'EIDOLANG-R20.2-HISTORY-RECOVERY-WRAP')
epoch_key=AESGCM(wrap_key).decrypt(b64u(eb['recovery_nonce_b64']),b64u(eb['recovery_ciphertext_b64']),aad_epoch)
assert len(epoch_key)==32

entry=json.loads(body['entry_json'][0]); x=entry['body']
entry_key=hkdf(x['message_id'].encode(),epoch_key,f"EIDOLANG-R20.2-HISTORY-ENTRY|{x['vault_id']}|{x['epoch_id']}".encode())
aad_entry=canon({
    'conversation_id':x['conversation_id'],
    'entry_type':'EidoHistoryVaultEntryV1',
    'entry_version':'1.0.0',
    'epoch':x['epoch'],
    'epoch_id':x['epoch_id'],
    'message_id':x['message_id'],
    'vault_id':x['vault_id'],
})
plain=AESGCM(entry_key).decrypt(b64u(x['nonce_b64']),b64u(x['ciphertext_b64']),aad_entry)
payload=json.loads(plain)
message=json.loads(payload['message_envelope_json'])
assert message['message_id']==g['expected']['message_id']==x['message_id']
assert hashlib.sha256(payload['document_json'].encode()).hexdigest()==g['expected']['document_content_hash']
print('PASS independent Python recovery-wrap/HKDF/history-entry AES-GCM decryption')

grant=json.loads(g['device_grant_canonical_json']); gb=grant['body']
alice=json.loads(g['alice_bundle_canonical_json'])
alice_pub=serialization.load_der_public_key(b64u(alice['device']['body']['signing_public_key_b64']))
alice_pub.verify(b64u(grant['signature_b64']),canon(gb),ec.ECDSA(hashes.SHA256()))
assert hashlib.sha256(canon(gb)).hexdigest()==grant['grant_id']
a2_priv=serialization.load_der_private_key(b64u(g['test_private_keys']['alice_secondary_encryption_private_pkcs8_b64']),password=None)
granted=a2_priv.decrypt(
    b64u(gb['wrapped_epoch_key_b64']),
    padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA1()),algorithm=hashes.SHA256(),label=None)
)
assert granted==epoch_key
print('PASS independent Python ECDSA-authenticated RSA-OAEP active-device epoch grant')
