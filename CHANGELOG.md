# Changelog

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
