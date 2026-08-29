# R20.1 archive no-go — forward secrecy versus ciphertext-only destructive recovery

## Result

The existing requirements expose a real incompatibility.

Let:

- `FS` mean old session message keys are erased and not derivable from current session state;
- `Archive` contain only immutable network ciphertext;
- `Recover` require a fresh/current device state to reconstruct all old plaintext after local
  state deletion.

Then, without an independent recovery secret:

\[
\boxed{
FS \land CiphertextOnlyArchive \land DestructiveRecovery
}
\]

cannot all hold simultaneously.

## Proof

For a historical plaintext \(P_i\), let archived ciphertext be:

\[
C_i=AEAD_{K_i}(P_i).
\]

Forward secrecy requires that after advancing the session:

\[
K_i\notin State_{current}
\]

and \(K_i\) is not derivable from `State_current`.

But destructive recovery from only:

\[
(C_i,State_{current})
\]

requires enough information to recover \(P_i\), therefore enough information to reconstruct
or bypass \(K_i\).

That contradicts the forward-secrecy premise.

## Why wrapping the old R15 envelope is insufficient for the archive

R20.1 currently wraps canonical `EidogramMessageV1` bytes in a forward session packet.

This protects those bytes in transit if only the outer packet is retained.

However R16/R19 currently archive the inner R15 envelope. That envelope contains a content key
wrapped to long-lived RSA device keys.

Therefore:

\[
Compromise(RSA_{device}^{private})
+
R16Archive
\Rightarrow
DecryptHistoricalR15Messages.
\]

The outer R20.1 ratchet cannot protect an archive that discards the outer layer.

## Necessary next entity

A separate recovery domain is therefore necessary, not optional.

The next layer must introduce something equivalent to:

\[
HistoryVaultKey \neq ActiveSessionState.
\]

Historical plaintext (or its content key) can be re-encrypted for local/archive recovery under
that independent recovery domain.

Then the claims become separable:

- active session compromise does not reveal old session keys;
- archive recovery uses an independent explicitly protected recovery secret;
- compromise of the recovery secret is correctly recognized as a separate threat.

This necessity result defines R20.2.

## R20.2 closure

R20.2 introduces the necessary independent secret domain:

\[
HistoryVaultRecoverySecret \neq ActiveSessionState.
\]

Historical material is re-encrypted into independent random vault-epoch keys and the epoch
keys are wrapped under the recovery domain. This satisfies the necessity result above.

The closure is conditional on a real cutover:

\[
VerifiedMigration \land Delete(LegacyR16/R19Copies).
\]

If an old R16/R19 package remains available, later compromise of a corresponding historical
R15 RSA private key can still decrypt that legacy copy. R20.2 cannot retroactively make an
undeleted old ciphertext representation disappear.

