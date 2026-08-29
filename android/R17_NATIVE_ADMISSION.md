# R17.1 native admission protocol

R17 must not be promoted to `NETWORK PASS` until all of these hold on Android:

1. Build `eidolang_torrent` with pinned libtorrent 2.1.1 for arm64-v8a and x86_64.
2. Start two independent libtorrent sessions.
3. Seed the fixed R16 golden segment from A.
4. Fetch it at B using the v2 magnet/infohash without sharing application files directly.
5. B runs `NetworkAdmissionGate` and obtains the exact R16 `segment_id`.
6. Publish the signed BEP44 head from A; B retrieves the same `seq`, value and target through DHT.
7. Corrupt a downloaded file after libtorrent completion; R16 admission must reject it.
8. Save and restore libtorrent session/DHT state and repeat lookup.
9. Validate process restart / WorkManager scheduling behavior.

Until then, JNI network status remains `UNRESOLVED`.
