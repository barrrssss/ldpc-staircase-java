# Changelog

## Unreleased

- Adopted specification **0.1.1**. It adds three matrix vectors and one encode vector that
  exercise `SPEC.md` section 4.3, the low-degree-raising pass that no committed vector
  previously caused to add a single entry. Every 0.1.0 vector is byte-identical at 0.1.1,
  so this implementation passes unchanged; the new cases simply cover ground that was
  uncovered.
- Adopted the section 6 correction: decoding recovers and reports every symbol the
  received set determines, with completion still judged on the source symbols alone.
  Section 6 had said reconstructing missing repair symbols was not required, which
  contradicted `dec-all-repair-missing`. This implementation already did the right thing.
- Extended `VectorGenerator` with the four section 4.3 cases. Confirmed first that a
  no-change regeneration produces a zero diff, then that the new manifest is additive in
  every section.

- Tracked the specification's extended differential corpus. The 2026-09-01 independence
  review found that `SPEC.md` section 4.3, which raises rows of left degree below two,
  added no entry in any committed vector or any corpus case — so two implementations could
  have disagreed about it entirely without any gate noticing. Five corpus cases now
  exercise both of its branches, and this implementation agrees with the independent
  reference checker on all 36 cases.
- Adopted the `SPEC.md` section 2 clarification that `N1 >= 1` is required always, not only
  when `R >= 1`. This implementation already enforced it; the specification now states it.
  No behavior changed.

- Added `ReferenceCorpusTest`, the independent-oracle gate of specification decision
  0002. It runs the differential corpus committed at
  `external/ldpc-staircase-spec/reference/corpus.json` through this codec and compares
  the matrix serialization, encoded symbols, completion flag, recovered symbol count,
  recovered source and repair index lists, still-missing source indices, whether peeling
  alone sufficed, and the post-decode source bytes against the answers of the
  specification repository's dependency-free Python checker. All 31 corpus cases agree.
  Test-only; nothing here changes the library, its API, or its dependencies.
- Declared the spec submodule's `vectors/` and `reference/` directories as inputs of the
  `test` task, so Gradle no longer reports the tests up to date after the vectors or the
  reference checker change.
- Adopted the `SPEC.md` section 6 clarification that decoding requires at least one
  present symbol. This implementation already enforced it; the specification now states
  it. No behavior changed.

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
