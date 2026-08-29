# R14 methodology — Android editor as a strict renderer/editor of R13.1

R14 does not define a new eidographic vocabulary. The editor is an implementation of the frozen `EidoGlyphCatalogV1`.

The UI may transform instances, but cannot create new base glyph types.

Wire geometry remains integer fixed-point:

\[
T=(c_x,c_y,s_x,s_y,\theta_{mdeg})\in\mathbb Z^5.
\]

Content identity is based on the action history. Snapshot identity is based on the replayed scene. Therefore two documents may have equal snapshot hashes but distinct content hashes.

The Android renderer is deliberately separated from canonical content logic:
- `core-model`: semantics and replay
- `core-canonical`: canonical JSON + SHA-256
- `core-render`: Compose drawing only
- `feature-library`: finite glyph picker
- `feature-editor`: editing interaction

This prevents pixels or UI state from silently becoming the message identity.
