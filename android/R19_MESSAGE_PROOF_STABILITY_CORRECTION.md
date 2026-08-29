# R19 correction — MessageId vs ECDSA proof bytes

R15 defined:

\[
MessageId=SHA256(CanonicalJSON(EncryptedBody)).
\]

The ECDSA signature is outside this hash. ECDSA signing is randomized, so the same valid body
can in principle be signed twice and produce different signature bytes:

\[
Body_1=Body_2,
\quad
Sig_1\ne Sig_2,
\quad
MessageId_1=MessageId_2.
\]

Therefore a repository rule requiring complete envelope-byte equality for duplicate
`MessageId` was stronger than the identity definition.

R19 corrects the repository rule:

- same `MessageId` + same canonical encrypted body + valid signature proof => duplicate;
- same `(conversation, device, sender_seq)` with a different `MessageId` => equivocation;
- different bodies under the same `MessageId` => impossible hash/identity conflict and reject.

This mirrors the R18 correction that root-certificate ECDSA proof bytes are not themselves
part of `DeviceId`.
