# R13.1 catalog hash correction

The historical R13.1 generator wrote a self-referential `catalog_hash` incorrectly: it computed a hash, inserted it, hashed again, and stored the second value without defining a stable self-hash convention.

Historical stored value:
`3585215ae8fc695f4258a9dc3c08484f9e536674c71ac7ed85175203e0fc7860`

R14 defines the catalog identity unambiguously as:

`SHA256(canonical JSON of the catalog object with the catalog_hash field removed)`

Correct production value:
`da2d3b72ff8d41abb8c293ce1deca44e99cb1d1a696e9834997395c6f6218005`

No glyph definition, palette entry, size, pose, length, thickness, or outline primitive changed. This is only a hash-definition repair required before Android byte-level conformance.
