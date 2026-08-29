# R19 destructive-recovery protocol

## Same-device local-state loss

1. Keep the Android Keystore identity/decryption keys.
2. Delete or recreate the messenger database/application message state.
3. Supply either:
   - the complete `EidoArchivePackageV1`; or
   - the local verified R16 segment store.
4. Verify all archive objects before writes.
5. Reconstruct public identities.
6. Reconstruct canonical conversation descriptors.
7. Verify and decrypt every unique R15 message.
8. Reject sequence equivocation and message-DAG cycles.
9. Insert messages in deterministic topological order.
10. Recompute conversation heads and render timeline eidograms.

## Full package vs segments

A full package restores aliases, titles, empty conversations and unused contacts.

R16 segments alone restore only state that is actually represented by those immutable archive
objects. R19 never invents missing local presentation metadata from transport data.

## Out of scope

Private-key loss and restore to a different device are not solved by this protocol. R19 must
fail rather than pretend ciphertext is recoverable without the historical decryption key.
