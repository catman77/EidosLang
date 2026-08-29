# R17 validation

Executed in this artifact environment:

- reference two-node transport/admission: 5 PASS;
- R16 dynamic regression: 10 PASS;
- R16 fixed golden regression: 5 PASS;
- independent Python BEP52 regression: 2 PASS;
- independent Python BEP44 regression: 3 PASS;
- R15 fixed crypto regression: 7 PASS;
- R14.1 editor/canonical regression: 13 PASS.

Total executed PASS assertions: **45**.

Not executed:

- Android Gradle/NDK build;
- libtorrent native session on Android;
- real BitTorrent swarm transfer;
- real BEP44 DHT get/put.

The reference transport is explicitly marked as a test double and is not presented as BitTorrent. Native BEP44 PUT fails closed until R17.1.
