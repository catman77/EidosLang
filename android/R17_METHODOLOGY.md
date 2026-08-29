# R17 — Android P2P / Torrent Engine

## Status discipline
R17 separates **transport semantics** from **native-network execution**.

Implemented and executable here:
- transport contract;
- two-node reference transport;
- immutable swarm publication/fetch semantics;
- BEP44 monotonic-head semantics;
- mandatory R16 admission after fetch;
- Android WorkManager scheduling source;
- libtorrent 2.1.1 JNI/CMake integration surface.

Not executable in this container:
- Android NDK build;
- libtorrent native binary;
- public DHT / real swarm transfer.

Therefore R17 is `SOURCE COMPLETE / REFERENCE ADMISSION PASS / NATIVE NETWORK UNRESOLVED`, not falsely `NETWORK PASS`.

## Fundamental gate

\[
NetworkDownloaded 
ot\Rightarrow RepositoryAccepted.
\]

Only:

\[
NetworkDownloaded \land R16Verify=PASS \Rightarrow RepositoryAccepted.
\]

This rule is enforced by `NetworkAdmissionGate`.

## Backend pin
The native adapter is pinned to libtorrent **2.1.1**. WebTorrent is disabled because EidoLang needs ordinary BitTorrent/DHT, not WebRTC transport.

## Background policy
Normal periodic head refresh uses WorkManager with a connected-network constraint. Continuous invisible seeding is not treated as an unlimited background entitlement; Android runtime policy is handled explicitly rather than by an always-on hidden service.
