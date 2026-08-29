# R21 freshness no-go

## Statement

For a fresh offline recovery device, assume:

1. package signatures are valid and unforgeable;
2. multiple historical packages signed by authorized devices may exist;
3. the device has no previously trusted monotonic anchor;
4. there is no trusted external freshness oracle.

Then the device cannot determine from signatures alone whether a valid package is the newest valid package.

## Proof

Let \(P_0\) be a valid signed backup produced at time \(t_0\), and \(P_1\) a later valid signed backup produced at \(t_1>t_0\).

Both satisfy the same local cryptographic predicate:

\[
VerifySignature(P_i)=true.
\]

An attacker can present only \(P_0\) to a fresh offline device.

Because the device has no trusted state containing \(P_1\), no trusted monotonic counter greater than the state in \(P_0\), and no external oracle, its observable evidence is compatible with two worlds:

- world A: \(P_0\) is latest;
- world B: \(P_1\) exists but is withheld.

The observations are identical.

Therefore no local deterministic verifier can distinguish A from B.

## Consequence

R21 marks first acceptance as:

`BOOTSTRAP_FRESHNESS_UNPROVEN`.

After acceptance, a local rollback anchor can prove monotonic extension relative to that accepted state.

True fresh-device latestness needs an additional trusted source, for example another current authorized device, a user-held checkpoint, or some externally anchored monotonic publication. R21 does not invent one without a separate product/protocol requirement.
