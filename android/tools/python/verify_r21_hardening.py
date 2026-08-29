#!/usr/bin/env python3
import base64, hashlib, json
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT=Path(__file__).resolve().parents[2]
g=json.loads((ROOT/'golden/r21/r21_hardening_golden_v1.json').read_text())

def b64u(s):
    return base64.urlsafe_b64decode(s+'='*((4-len(s)%4)%4))

def canon(obj):
    return json.dumps(obj,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()

backup=json.loads(g['recovery_backup_canonical_json'])
body=backup['body']
assert hashlib.sha256(canon(body)).hexdigest()==backup['backup_id']==g['expected']['backup_id']
print('PASS independent Python R21 recovery-backup canonical SHA-256 identity')

parts=g['recovery_code'].split(':')
assert len(parts)==5 and parts[0]=='eidolang-recovery-v1'
vault_id=parts[1]
key_id=parts[2]+':'+parts[3]
key=b64u(parts[4])
assert 'hvbk:'+hashlib.sha256(key).hexdigest()==key_id==body['backup_key_id']
aad=canon({
    'backup_key_id':body['backup_key_id'],
    'backup_type':'EidoHistoryVaultRecoveryBackupV1',
    'backup_version':'1.0.0',
    'recovery_key_id':body['recovery_key_id'],
    'vault_id':body['vault_id'],
})
secret=AESGCM(key).decrypt(b64u(body['nonce_b64']),b64u(body['ciphertext_b64']),aad)
assert len(secret)==32
assert 'hvr:'+hashlib.sha256(secret).hexdigest()==body['recovery_key_id']==g['expected']['recovery_key_id']
assert vault_id==body['vault_id']==g['expected']['vault_id']
print('PASS independent Python R21 AES-GCM recovery backup opens exact recovery-secret identity')

anchor=json.loads(g['rollback_anchor_canonical_json'])
assert hashlib.sha256(canon(anchor['body'])).hexdigest()==anchor['anchor_id']==g['expected']['anchor_id']
ct=bytearray(b64u(body['ciphertext_b64']));ct[0]^=1
failed=False
try:
    AESGCM(key).decrypt(b64u(body['nonce_b64']),bytes(ct),aad)
except Exception:
    failed=True
assert failed
print('PASS independent Python R21 rollback-anchor SHA-256 and backup AEAD mutation rejection')
