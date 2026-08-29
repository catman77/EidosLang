# R15 identity proof stability correction introduced in R18

## Problem

`DeviceId` is derived from the device certificate body, but an ECDSA root signature over that
body is randomized. Re-signing the unchanged body can therefore produce different canonical
public-bundle bytes while preserving the exact same semantic user/device identity.

Treating the full bundle bytes as identity would incorrectly fork one device into multiple
contact/archive representations.

## Correction

Identity key remains:

`(UserId, DeviceId)`.

A root signature is certificate proof, not identity.

R18 changes contact persistence to allow a newly verified proof for the same IDs to replace
the stored bundle.

For normal Android process restarts, `AndroidKeystoreIdentityStore` now caches the verified
root signature corresponding to the current certificate-body hash in app preferences. The
cached signature is reused only if it verifies against the root public key.

If the cache is lost, a fresh valid signature may be generated without changing `UserId` or
`DeviceId`.
