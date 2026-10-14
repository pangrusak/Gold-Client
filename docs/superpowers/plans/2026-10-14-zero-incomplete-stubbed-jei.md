# Zero Incomplete and Stubbed JEI Export Implementation Plan

**Goal:** Reduce genuine incompleteness in the JEI 1.12.2 JavaScript export through semantic lowering and deliberate adapter dispatch, claiming zero only when the regenerated report proves it.

**Architecture:** Preserve semantic IR, improve control-flow lowering, then replace unavailable declaration stubs with adapter-backed exports. Keep runtime/API absence distinct from translator incompleteness.

**Constraints:** Work directly on `main`; preserve README and unrelated changes; do not suppress diagnostics; verify with Maven and a regenerated JEI report.

## Tasks

1. Add failing translator/exporter tests that distinguish unsupported IR, declaration methods, constructors, bridges, and adapter-backed methods.
2. Implement and test structured lowering for remaining valid branches, switches, joins, and exception regions while retaining honest failure for malformed graphs.
3. Implement deliberate adapter dispatch for abstract/native/declaration methods with stable symbols and metadata.
4. Run the full Maven suite/build, regenerate the JEI export, classify every remaining incomplete/stubbed entry, and report exact counts.

