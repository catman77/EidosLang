# R14.1 — Editor Completion

R14 established the source scaffold and canonical glyph-bound content model. R14.1 closes
the editor-local gaps that do not require identity or networking.

## 1. Hit testing is representation-derived

Selection no longer uses a fixed-radius nearest-center heuristic.

For a tap \(p\) and glyph instance \(g\), R14.1 computes the inverse instance transform and
tests \(p\) against the actual catalog render primitive:

- filled circle;
- capsule for black sticks, including the catalog's discrete base pose;
- rectangle/square;
- ellipse/circle;
- triangle;
- diamond;
- stroked plus/cross.

The test implementation lives in `core-model` and has no Android dependency.

## 2. Group-aware and multi-selection semantics

A grouped glyph is selected as its complete group. Explicit multi-select can add/remove
whole groups or ungrouped glyphs. Group and ungroup remain content actions, not UI-only
metadata.

## 3. Strict canonical import

`EidogramParser.parseCanonical` accepts only bytes that round-trip exactly through
`EidogramCanonical.documentJson`.

Therefore import rejects:
- unknown/extra fields;
- duplicate JSON keys;
- floating-point numbers;
- lineage/catalog mismatch;
- malformed action history;
- valid-but-noncanonical JSON serialization.

## 4. Draft persistence

The current canonical document is stored in app-internal storage as
`eidogram-draft-v1.json`. Loading uses the same strict canonical parser as explicit import.
Undo/redo stacks are intentionally session-local; immutable document history is persisted.

## 5. External import/export

The editor uses Android's Storage Access Framework contracts:
- `OpenDocument` for import;
- `CreateDocument("application/json")` for export.

Exported bytes are exactly the canonical bytes used for the document content hash.

## 6. Android instrumentation gate

An Android instrumentation smoke test is included and checks the editor shell and canvas.
It is not claimed as executed in this artifact environment because Android SDK/emulator are
not installed here.
