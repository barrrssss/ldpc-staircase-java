# Measurements

First-run numbers from the opt-in harnesses in `src/test`. They exist to inform
the shared specification's validation gates; none of them is a product guarantee, and none of them was
taken on the platform that matters most (a weak Android phone).

- Date: 2026-07-23
- Machine: AMD Ryzen 7 5700U (8C/16T), Windows 11 Pro
- Runtime: OpenJDK 25.0.3, single warmed JVM, wall clock
- Library: `ldpc-staircase-java` 0.1.0

Reproduce with:

```sh
./gradlew test --tests "*DecoderBenchmark" -Dldpc.bench=1
./gradlew test --tests "*LossSimulation" -Dldpc.sim=50
```

## Time (the shared specification gate 5)

`N1 = 7`, `R = K/2`, `S = 818` (the V32-H QR payload used throughout the shared specification).

| K | R | build | encode | peel decode (R/2 lost) | residual decode (R lost) | residual unknowns |
| --- | --- | --- | --- | --- | --- | --- |
| 1,000 | 500 | 5 ms | 16 ms | 2 ms | 9 ms | 498 |
| 2,500 | 1,250 | 5 ms | 2 ms | 2 ms | 27 ms | 1,227 |
| 5,000 | 2,500 | 5 ms | 6 ms | 5 ms | 235 ms | 2,469 |
| 10,000 | 5,000 | 14 ms | 9 ms | 13 ms | 2,066 ms | 4,949 |

The `K = 10,000`, `R = 5,000`, `S = 818` row is the shared specification's motivating case — the plan
the shared specification's seatbelt rejects, where dense GF(2¹⁶) Reed-Solomon is estimated at about
401 MiB of restore working set and roughly 1.9 hours of decode. Generation and normal
recovery here are milliseconds, and the expensive path is the residual solve rather
than anything in encoding.

Two honest qualifications:

- The encode column is noisy at these sizes (16 ms then 2 ms as the JIT warms). Treat
  it as "single-digit milliseconds", not as a scaling curve.
- The residual rows are the **worst** decode at each size: the full `R`-symbol loss
  budget, where peeling contributes almost nothing and the solve runs at nearly its
  full width. Those particular decodes also *failed* — the rank fell a handful short
  of the unknown count — which is exactly the non-MDS margin the next section
  measures. The time is what a decode costs when it works this hard, not what a
  successful decode costs.

The quadratic term is visible: `u` doubles from 2,469 to 4,949 and the solve grows
about 9×. This is the number a memory and time seatbelt must be built from
(`docs/decoder-design.md` has the formula), and the reason
`DecodeOptions.withMaxResidualUnknowns` exists.

## Recovery margin D (the shared specification gates 7 and 8)

`D` is the number of received symbols beyond `K` at the largest tolerable loss. 50
trials per cell, `S = 64`; each trial binary-searches the removal order for the longest
prefix that still decodes.

| Config | Loss model | mean D | p95 D | max D |
| --- | --- | --- | --- | --- |
| K=500 R=250 N1=7 | random | 1.80 | 5 | 8 |
| K=500 R=250 N1=7 | contiguous band | 14.60 | 79 | 87 |
| K=500 R=250 N1=7 | whole page (30) | 3.24 | 8 | 41 |
| K=500 R=250 N1=7 | source first | 1.66 | 5 | 6 |
| K=500 R=250 N1=7 | repair first | 0.00 | 0 | 0 |
| K=2000 R=1000 N1=7 | random | 3.14 | 7 | 9 |
| K=2000 R=1000 N1=7 | contiguous band | 89.78 | 367 | 382 |
| K=2000 R=1000 N1=7 | whole page (30) | 4.08 | 11 | 15 |
| K=2000 R=1000 N1=7 | source first | 1.40 | 6 | 6 |
| K=2000 R=1000 N1=7 | repair first | 0.00 | 0 | 0 |
| K=2000 R=500 N1=7 | random | 2.34 | 6 | 6 |
| K=2000 R=500 N1=7 | contiguous band | 24.50 | 155 | 243 |
| K=2000 R=500 N1=7 | whole page (30) | 2.16 | 5 | 6 |
| K=2000 R=1000 N1=5 | random | 10.26 | 16 | 23 |
| K=2000 R=1000 N1=5 | contiguous band | 137.28 | 414 | 477 |
| K=2000 R=1000 N1=5 | whole page (30) | 37.80 | 191 | 263 |

Four things fall out of this table.

**Scattered loss is cheap.** Mean `D` around 2–3 for random loss is consistent with the
`K + 2.43` average RFC 6816 section 7.1 reports for `K = 1024`, `R = 512`, `N1 = 7`.
But the tail is what a backup product must budget: p95 is 7 and the observed maximum 9,
at only 50 trials. A real margin has to come from many more trials against a chosen
target failure probability, which this harness does not yet do.

**Correlated loss is expensive, and that is the layout's problem to solve.** A single
contiguous run of lost indices costs a mean `D` of about 90 at `K = 2000`, with p95 at
367 — two orders of magnitude worse than scattered loss, and it does not shrink with
`K`. Consecutive encoding symbol indices must not land on the same printed sheet. This
is the shared specification's page-interleaving requirement, restated as a measurement.

**Interleaving works.** The whole-page model loses 30-symbol runs but scatters the
pages, and `D` drops back to about 4 — close to random loss. That is the shape a
encoded set layout should aim for.

**`N1 = 7` earns its place.** Dropping to `N1 = 5` makes every model worse: 3× on
random loss, 9× on whole-page. the shared specification's initial choice holds up.

`repair first` is 0 by construction: losing only repair symbols leaves the source
intact, which is the systematic property.

## What is still missing

Against the shared specification's gates, these runs cover 5 (desktop time), and start 7 and 8 (loss
patterns). Not covered here:

- peak RAM as a real measurement rather than the model in `docs/decoder-design.md`;
- Android recovery time and heap on a weak device;
- a chosen target decoding-failure probability, and enough trials to measure `D`
  against it;
- an interleaving scheme to test, as opposed to the synthetic page grouping above; and
- differential testing against an independent implementation.
