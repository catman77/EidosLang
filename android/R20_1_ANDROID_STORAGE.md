# R20.1 Android secret-state storage

## Platform profile

Project minimum SDK remains 26.

R20.1 public DH protocol uses P-256 `ECDH`.

The compatibility storage model is:

```text
software/JCA P-256 ratchet + prekey private state
        ↓ canonical secret bytes
AndroidSecretBlobStore
        ↓ AES-256-GCM
AndroidKeyStore wrapping key
```

The KeyStore alias is:

```text
eidolang.forward-session-state-wrap.v1
```

## Stored secret blobs

Session:

```text
fs-secret-session-<session_id>.bin
```

Prekeys:

```text
fs-secret-prekeys-v1.bin
```

Only nonce+ciphertext are written to these files.

The filename/logical blob name is GCM AAD. Swapping two valid encrypted files therefore causes
authentication failure.

`AndroidSessionStateStore.load(sessionId)` additionally requires the decrypted snapshot's
internal `session_id` to equal the requested ID.

## API-31 optimization path

AndroidKeyStore's explicit key-agreement purpose can later hold DH private keys directly on
newer devices. This is a storage/backend optimization only; it must not alter the canonical
R20.1 session packet format.
