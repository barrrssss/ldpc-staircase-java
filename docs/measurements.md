# Measurements

First-run numbers from the opt-in harnesses in `src/test` and an Android
instrumentation benchmark. They characterize implementation behavior; none of them
is a performance guarantee.

- Date: 2026-07-23
- Machine: AMD Ryzen 7 5700U (8C/16T), Windows 11 Pro
- Runtime: OpenJDK 25.0.3, single warmed JVM, wall clock
- Library: `ldpc-staircase-java` 0.1.0

Reproduce with:

```sh
./gradlew test --tests "*DecoderBenchmark" -Dldpc.bench=1
./gradlew test --tests "*LossSimulation" -Dldpc.sim=50
```

## Time

`N1 = 7`, `R = K/2`, `S = 818` (a representative medium symbol payload).

| K | R | build | encode | peel decode (R/2 lost) | residual decode (R lost) | residual unknowns |
| --- | --- | --- | --- | --- | --- | --- |
| 1,000 | 500 | 5 ms | 16 ms | 2 ms | 9 ms | 498 |
| 2,500 | 1,250 | 5 ms | 2 ms | 2 ms | 27 ms | 1,227 |
| 5,000 | 2,500 | 5 ms | 6 ms | 5 ms | 235 ms | 2,469 |
| 10,000 | 5,000 | 14 ms | 9 ms | 13 ms | 2,066 ms | 4,949 |

The `K = 10,000`, `R = 5,000`, `S = 818` row is a stress case where a dense
GF(2¹⁶) Reed–Solomon implementation was estimated at about 401 MiB of recovery
working set and roughly 1.9 hours of decode. Generation and normal recovery here are
milliseconds, and the expensive path is the residual solve rather than encoding.

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

`ldpc-staircase-dotnet` runs the same benchmark at the same parameters and loss seeds;
its README carries the table. On this machine the .NET Release build is roughly 1.6×
slower on the residual-solve column (3,262 ms at `K = 10,000`) and comparable
elsewhere. Both implementations report identical residual widths and ranks at every
size, which is a useful cross-check on top of the conformance vectors.

## Mobile residual-solve regression (2026-08-04)

A physical Android debug build exposed a runtime-specific regression that the warmed
desktop JVM concealed: a near-threshold `K = 2,939`, `R = 2,939`, `S = 248` decode
from 2,979 received symbols took about 77 seconds. Progress reached roughly 35%
quickly and then slowed sharply because late Gauss–Jordan pivots operate on dense rows.

An opt-in instrumentation benchmark reproduces that shape with a fixed graph and
fixed scattered `K + 40` received set. On an API 36 x86_64 emulator, in a debug APK,
the same benchmark was measured before and after Java 0.1.1:

| implementation | decode | 35% | 50% | 75% | 100% |
| --- | ---: | ---: | ---: | ---: | ---: |
| byte-array VarHandle RHS, source-first pivots | 390.514 s | 20.749 s | 115.139 s | 276.855 s | 390.511 s |
| packed `long[]` RHS, repair-first pivots | 2.455 s | 2.076 s | 2.098 s | 2.184 s | 2.452 s |

That is a **159×** reduction in measured decode time. The two changes are deliberately
non-normative:

- residual right-hand sides are copied once into ordinary `long[]` arrays, removing
  repeated byte-array `VarHandle` dispatch from the elimination hot loop; and
- unknown staircase repair variables are pivoted before source variables because
  each occurs in at most two parity equations, reducing early matrix fill-in.

All Java tests and byte-exact cross-language vectors pass unchanged. A warmed HotSpot
guard benchmark of the same shape remains sub-second (roughly 0.21–0.38 s across five
received-set seeds after the change), confirming that Android ART was the main
regression surface.

The same harness was then run on the physical arm64 Android device that exposed the
original 77-second decode. The optimized debug APK measured 720 ms on its first run
and 711 ms on an immediate repeat. After strengthening the harness to compare all
2,939 recovered source symbols byte-for-byte, one post-install cold outlier measured
3.420 s and its immediate repeat measured 712 ms. A final old/new A/B run measured
710 ms. Every strengthened run recovered byte-identical source data, and Android
reported thermal status 0 (no throttling).

For completeness, the controlled old-code half of that A/B took 498.948 s wall time
for the same fixed graph and received set (35% at 33.391 s, 50% at 167.583 s, and 75%
at 453.709 s). The phone entered Doze during the headless old run, so that wall-time
ratio overstates normal foreground slowdown and must not be presented as a user-facing
speedup. The defensible runtime result is: the original physical foreground decode
took about 77 seconds; the optimized exact-shape benchmark is normally about 0.71
seconds and its worst observed cold outlier was 3.42 seconds.

## Recovery margin D

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
But a caller must account for the tail: p95 is 7 and the observed maximum 9, at only
50 trials. A real margin has to come from many more trials against a chosen target
failure probability, which this harness does not yet do.

**Correlated loss is expensive, and that is the layout's problem to solve.** A single
contiguous run of lost indices costs a mean `D` of about 90 at `K = 2000`, with p95 at
367 — two orders of magnitude worse than scattered loss, and it does not shrink with
`K`. Consecutive encoding symbol indices must not land on the same printed sheet. This
is the transport interleaving requirement, restated as a measurement.

**Interleaving works.** The whole-page model loses 30-symbol runs but scatters the
failure domains, and `D` drops back to about 4 — close to random loss. That is the
shape a transport layout should aim for.

**`N1 = 7` earns its place.** Dropping to `N1 = 5` makes every model worse: 3× on
random loss, 9× on whole-page. The default profile choice holds up.

`repair first` is 0 by construction: losing only repair symbols leaves the source
intact, which is the systematic property.

## Scattered-capture D90 calibration (2026-08-05)

A test-only structural rank oracle now makes large recovery-margin studies practical:
when at least `K` symbols are received, recovery is equivalent to linear independence
of the erased parity-check columns. One incremental rank pass finds the exact `D` for
a shuffled removal order without allocating or solving symbol right-hand sides. The
oracle is regression-checked against the payload decoder.

The resulting calibration covers 231,500 trials across 43 training and 20 untouched
holdout configurations (`K = 256..16000`, `R/K = 0.05..1.00`, `N1 = 7`) plus a
focused 20,000-trial graph-seed study. A six-coefficient guarded formula predicts
the scattered-loss `D90` with sub-code raw RMSE on the holdouts. Full methodology,
coefficients, data, limitations, and reproduction commands are in
[`docs/calibration/README.md`](calibration/README.md).

This closes neither the correlated-loss/interleaving gate nor the caller-bounded
residual-solve gate. In particular, an 8,192-variable residual limit is irrelevant
when `R <= 8192`, but above that boundary it can make the required margin hundreds or
thousands of symbols larger than the mathematical rank threshold. A caller must
model its own bound rather than apply the unbounded formula outside its validated
domain.

## What is still missing

These runs cover desktop time and several loss patterns. Not covered here:

- peak RAM as a real measurement rather than the model in `docs/decoder-design.md`;
- optimized Android recovery time and heap on a weak physical device class;
- target-probability calibration for correlated/interleaved physical losses (the
  scattered 90% target is now measured separately above);
- an interleaving scheme to test, as opposed to the synthetic page grouping above; and
- differential testing against an independent implementation.
