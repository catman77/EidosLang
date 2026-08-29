# R20 device authorization / revocation protocol

## Enrollment

Secondary:

1. obtain the owner's public identity;
2. generate P-256 signing and RSA-2048 decryption keys locally;
3. create `EidoDeviceEnrollmentRequestV1`;
4. self-sign the request with the new device signing key.

Primary/root authority:

1. parse canonical request;
2. verify signing-key possession proof;
3. verify key IDs equal hashes of supplied public keys;
4. issue root-signed `DeviceCertificateV1`;
5. add device to the next root-signed roster.

## Roster identity

\[
RosterId=SHA256(CanonicalJSON(RosterBody)).
\]

The root signature is proof and is outside `RosterId`.

## Revocation semantics

Revocation is not deletion of a certificate. A certificate remains a historical statement that
the root once authorized the device key body.

Current permission is the intersection:

\[
ValidCertificate(Device)\land Device\in Active(CurrentRoster).
\]

A revoked device stays in the monotone revoked set so rollback/re-activation attempts are
detectable.

## Fresh-device bootstrap

A new device has no earlier roster state, so it may accept a root-signed current snapshot at
epoch \(n>0\), provided every referenced device certificate is known.

After that bootstrap, only exact successor rosters are accepted.
