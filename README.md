# ldpc-staircase (Java)

A general-purpose **systematic, fixed-rate LDPC-Staircase erasure code over GF(2)**,
following the RFC 5170 section 6 core constrained to the profile defined by the
shared specification.
Given `K` equal-length source symbols it produces `R` repair symbols by sparse XOR,
and a decoder reconstructs the source from `K + D` received symbols.

This is the Java implementation of the cross-language `ldpc-staircase` contract. The
normative specification and the conformance vectors live in the
[`ldpc-staircase-spec`](https://github.com/barrrssss/ldpc-staircase-spec) repository,
vendored here as the git submodule
[`external/ldpc-staircase-spec`](external/ldpc-staircase-spec). This repo is also the
vectors' generator; `ldpc-staircase-dotnet` verifies the same bytes.

The library is deliberately **abstract** — symbol byte arrays in, symbol byte arrays
out. It performs no I/O, compression, encryption, framing, or page layout, and has
**no runtime dependencies**.

Current package version: **0.1.2**. It implements specification version **0.1.0**.
See [`CHANGELOG.md`](CHANGELOG.md) for implementation changes and the shared
specification's [`HISTORY.md`](external/ldpc-staircase-spec/HISTORY.md) for
cross-implementation decisions.

After the first Maven Central release, depend on it with:

```kotlin
implementation("io.github.barrrssss:ldpc-staircase:0.1.2")
```

## Not an MDS code

`erasure16` guarantees recovery from **any `K` of `K + R`** shards. This code does
not. It trades that exact guarantee for sparse XOR encoding and normally cheap,
low-memory recovery. The number of symbols actually needed is `K + D`, where `D`
depends on the graph, the seed, the decoder, and — heavily — the **shape** of the
loss. Always read completion from `DecodeResult.isComplete()`, never from a
received-symbol count.

Measured `D` on this implementation is in [`docs/measurements.md`](docs/measurements.md).
The short version at `K = 2000`, `R = 1000`, `N1 = 7`: about 3 for scattered loss, and
about 90 (p95 = 367) when the lost symbols are one contiguous run of indices. That gap
is the reason a physical transport should interleave source and repair symbols across
failure domains; the transport layout remains outside this library.

## Profile (summary)

- RFC 5170 section 6 LDPC-Staircase core; systematic; one symbol per transport unit.
- Fixed `K` and `R`, decided before encoding; equal source/repair symbol length `S`.
- `N1 = 7` ones per source column by default; deterministic Park–Miller graph seed.
- Decode = degree-one peeling to a fixed point, then **at most one** complete residual
  GF(2) solve. No inactivation, no elimination-to-peeling cycles, no `G > 1`, no
  rateless stage.

See [`external/ldpc-staircase-spec/SPEC.md`](external/ldpc-staircase-spec/SPEC.md) for
the full normative contract, and [`docs/decoder-design.md`](docs/decoder-design.md) for
how the decoder is built and what it costs.

## Public API

```java
LdpcStaircase codec = LdpcStaircase.create(sourceSymbolCount, repairSymbolCount, seed);
// or ..., seed, leftDegree) to override N1

codec.encode(symbols);                       // fills repair slots [K..K+R) in place
DecodeResult result = codec.decode(symbols, symbolPresent);
boolean ok = codec.verify(symbols);          // every parity equation XORs to zero

if (result.isComplete()) {
    byte[] data = LdpcStaircase.join(symbols, originalLength);
}

byte[][] symbols = LdpcStaircase.split(data, sourceSymbolCount, symbolSize);
```

`decode` mutates both arrays: recovered symbols are written back and marked present.
Missing repair symbols fall out of the same equations and are recovered too. A
received set that does not determine the source is **not** an exception — it returns
`isComplete() == false` and lists what is still missing.

`DecodeOptions` bounds and instruments a decode:

```java
codec.decode(symbols, present, DecodeOptions.defaults()
        .withMaxResidualUnknowns(4096)               // refuse an oversized solve
        .withProgressListener((done, total) -> updateBar(done, total)));

codec.decode(symbols, present, DecodeOptions.defaults()
        .withResidualSolve(false));                  // peeling only (analysis, not production)
```

`ParityCheckMatrix` is public so loss simulation and layout work can inspect the
graph. `PmmsRandom` exposes the normative PRNG. Invalid parameters and malformed
symbol arrays throw `LdpcStaircaseException`.

## Cost

At `K = 10,000`, `R = 5,000`, `S = 818`, this implementation builds the matrix in
14 ms, encodes in 9 ms, and peels a 2,500-symbol loss in 13 ms. A decode that has to
run the residual solve at the full repair budget takes about 2 s. See
[`docs/measurements.md`](docs/measurements.md) for the full table and caveats.

## Build and test

The build requires **JDK 17 or newer** because Gradle 9 runs on Java 17+. Published
classes remain **Java 11 compatible**: every Java compile task uses `--release 11`,
which checks both class-file level and Java 11 API availability. Uses the Gradle wrapper.

```sh
git submodule update --init   # pull the spec and conformance vectors
./gradlew build               # compile + run all tests
./gradlew test                # unit tests + ConformanceTest + ReferenceCorpusTest
```

`ConformanceTest` is the cross-language gate: it parses
`external/ldpc-staircase-spec/vectors/MANIFEST.json` and asserts byte-exact
reproduction of every PRNG, matrix, encode, and decode vector (and verifies the
SHA-256 of each blob).

`ReferenceCorpusTest` is the independent-oracle gate of specification decision 0002.
Cross-language agreement alone cannot rule out one shared misreading of `SPEC.md`,
because this repository also generates the vectors. So the spec repo carries a
dependency-free Python checker derived from the specification rather than from either
implementation, plus a differential corpus at
`external/ldpc-staircase-spec/reference/corpus.json`. The test runs that corpus through
this codec and compares every field with the checker's own answer.

It needs Python 3.11+ on `PATH` and runs the checker as an ordinary subprocess. Without
an interpreter the corpus cases are skipped so a developer build still runs; the release
gate turns that into a failure:

```sh
./gradlew test -Dldpc.referenceCorpus.required=true    # or LDPC_REFERENCE_CORPUS_REQUIRED=1
./gradlew test -Dldpc.python=/path/to/python3          # or LDPC_PYTHON
```

Three opt-in measurement harnesses are excluded from normal runs:

```sh
./gradlew test --tests "*DecoderBenchmark" -Dldpc.bench=1
./gradlew test --tests "*LossSimulation" -Dldpc.sim=200
./gradlew test --tests "*RecoveryMarginCalibration" -Dldpc.calibrate=2000
```

The calibration harness uses a test-only structural rank oracle to measure many
scattered capture sets efficiently. Its fitted D90 formula, committed datasets,
caller-bound warning, and reproduction options are documented in
[`docs/calibration/README.md`](docs/calibration/README.md).

`./gradlew generateVectors` rewrites the vectors inside the submodule. That is a
**specification change** — they are the cross-language oracle — so run it only
alongside a `SPEC.md` change, review the diff, and commit it in
`ldpc-staircase-spec`.

## Maven Central publication

The Kotlin DSL build configures the Central Portal target, GPG signing, sources and
Javadoc JARs, and the required POM metadata. Before the first release, register the
`io.github.barrrssss` namespace in Central Portal and configure a Portal user token
plus a published GPG key outside the repository, following the
[publishing plugin guide](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

Stage a signed deployment manually with:

```sh
./gradlew clean build publishToMavenCentral
```

Then inspect and publish the deployment in Central Portal. Credentials and private
keys must stay in the user Gradle configuration or environment; this repository does
not define CI.

## Status

Pre-1.0 release candidate. The library is intentionally general-purpose and defines
only symbol-level coding behavior.

## License

[Apache License 2.0](LICENSE).

Apache-2.0 was chosen over a shorter permissive licence specifically because this is
patent-adjacent code: section 3 grants every user an express patent licence from the
contributors, which MIT, BSD and 0BSD do not, and its defensive-termination clause ends
that grant for anyone who brings a patent claim over the work. For an erasure codec that
costs the licensor nothing and is the difference between a user having patent rights and
having none.

This project independently implements the algorithmic profile described by
[RFC 5170](https://www.rfc-editor.org/rfc/rfc5170.html). No OpenFEC or other
third-party codec source was copied. See [`NOTICE`](NOTICE) for attribution. The
provenance and engineering IPR guardrails are not a legal opinion.
