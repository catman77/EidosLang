# R22 Android onboarding

## Primary installation

First launch offers:

`Создать основной профиль`

Only this action creates the R15/R20 user root.

The messenger then creates an explicit epoch-0 roster for the local device when none exists.

## Secondary installation

First launch offers:

`Подключить как дополнительное устройство`

The product flow reuses the exact R20 objects.

### Secondary step 1

Import an existing public identity bundle for the owner.

No private root key is present.

### Secondary step 2

Generate local secondary signing/decryption keys and export:

`EidoDeviceEnrollmentRequestV1`.

The request contains a possession proof from the new signing key.

### Primary approval

Open `Устройства` on the primary device.

Import the enrollment request.

The primary root:

1. verifies the possession proof;
2. issues the normal R20 `DeviceCertificateV1`;
3. adds the device to the current own-device registry;
4. issues the next root-signed roster.

The UI exports:

- authorized public device bundle;
- root-signed roster.

No new R22 approval protocol object is introduced.

### Secondary completion

Import the authorized device bundle.

Then import the root-signed roster.

The secondary app preloads the owner's original public device bundle into its local own-device
registry so the roster can be validated against all referenced certified devices.

Only after roster admission does the installation enter the messenger.

## Revocation

The primary Devices screen can revoke any non-current own device.

Revocation emits the ordinary next R20 roster epoch.

The current primary device cannot revoke itself through this UI; root-authority migration is a
different protocol problem and is not invented in R22.
