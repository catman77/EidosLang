# R20 Android onboarding source boundary

R20 adds two AndroidKeyStore adapters.

## Primary device

`AndroidRootAuthority`

- uses the existing non-exportable `eidolang.root-signing.v1` key;
- signs secondary-device certificates and roster bodies;
- never exposes the root private key.

## Secondary device

`AndroidSecondaryDeviceIdentityStore`

- does not create another user root;
- generates local signing and RSA decryption keys under separate AndroidKeyStore aliases;
- exports a possession-signed enrollment request;
- installs only a root-authorized public identity bundle;
- uses the local private device keys for message signing/decryption.

Aliases:

```text
eidolang.secondary-device-signing.v1
eidolang.secondary-device-decrypt.v1
```

## Source/runtime distinction

The Android adapter source is included, but no Android SDK/emulator is available in the
artifact environment. AndroidKeyStore enrollment was therefore not runtime-executed here.

The final first-launch QR/file onboarding workflow remains product UX work; the cryptographic
protocol and KeyStore boundary are already defined.
