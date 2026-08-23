# Changelog

## 0.1.2 — 2026-08-23

- Replaced the `ParityCheckMatrix` `int[][]` row/column adjacency forest with flat
  primitive compressed-sparse storage (`rowOffsets`/`rowEntries`,
  `columnOffsets`/`columnEntries`) and a bounded two-pass primitive build. Construction
  allocates no object or primitive array per row or column, and
  the encode/decode hot loops traverse the flat arrays without materializing a row array
  per visit. PRNG consumption, adjacency, canonical serialization, encoded repair bytes
  and decode results are unchanged and byte-exact; a new `FlatGraphParityTest`
  differentially pins the flat build against a test-only copy of the previous
  object-forest algorithm across boundary, calibrated and randomized cells.
- Added a test-only structural recovery oracle and opt-in scattered-capture margin
  calibration harness.
- Added reproducible D50/D90/D95/D99 datasets, a cross-validated empirical D90
  formula, and explicit analysis of caller-imposed residual-solve bounds.
- Added Maven Central publication metadata and migrated the build to Gradle Kotlin DSL.
- Kept the build on JDK 17+ while enforcing Java 11 API and class compatibility with
  `--release 11`.

This is a protocol- and API-compatible implementation release. The flat storage
changes runtime memory use but not graph construction or codec results; the
specification and conformance vector payloads remain at 0.1.0 and are unchanged.

## 0.1.1 — 2026-08-04

- Packed residual-solver right-hand sides into `long[]` rows once before elimination,
  removing repeated byte-array view dispatch from the hot loop.
- Ordered unresolved repair variables before source variables to reduce matrix
  fill-in during dense Gauss–Jordan elimination.
- Added large near-threshold mobile-runtime measurements and old/new differential
  verification across 864 cases and nine geometries.
- Removed consuming-application references from library documentation and comments.

This is a protocol- and API-compatible implementation patch. The specification and
conformance vector payloads remain at 0.1.0 and are unchanged.

## 0.1.0 — 2026-07-23

- Initial Java 11 implementation of the constrained RFC 5170 LDPC-Staircase profile.
- Initial vector generator and byte-exact conformance suite.
