# Scattered-capture recovery-margin calibration

This directory records the 2026-08-05 calibration of the number of received
symbols beyond `K` needed for 90% recovery under a **uniform scattered-erasure
model**. It is implementation research, not part of the codec protocol and not a
recovery guarantee.

## Method

For a received set of at least `K` symbols, at most `R` columns of the `R x (K+R)`
parity-check matrix are erased. The erased symbols are uniquely recoverable exactly
when those columns are linearly independent over GF(2). The test-only
`StructuralRecovery` oracle therefore measures a removal order by incrementally
building a column-echelon basis and stopping at the first dependent column. It does
not allocate payload symbols or perform right-hand-side arithmetic.

This equivalence was checked against the production payload decoder across random
erasures, exact removal-order margins, peeling residual widths, and bounded residual
solves. `RecoveryMarginCalibration` is opt-in and excluded from normal test runs.

The committed configuration dataset contains:

- 43 training configurations and 20 untouched holdout configurations;
- `K = 256..16000`, `R/K = 0.05..1.00`, and the fixed profile `N1 = 7`;
- 231,500 removal-order trials, using 10-25 graph seeds per configuration; and
- a separate 20,000-trial, 20-seed study at `K = R = 2939`.

Every trial shuffles all `K + R` symbol indices. Each prefix is consequently a
uniform sample without replacement. The model does **not** describe contiguous,
page-correlated, source-first, or other structured losses.

## Fitted formula

Let:

```text
q = R / K

raw_D90 = A0 + A1*q + A2*q^2
          + K^alpha * (B0 + B1*q + B2*q^2)

alpha = 0.942
A0 =  3.89710238270933
A1 = -0.965383067608691
A2 =  0.992055340058366
B0 = -0.0000434665437222602
B1 =  0.002922770664875
B2 =  0.00162881641655029

estimated_D90 = max(0, floor(raw_D90 + 0.5) + 1)
estimated received count = K + estimated_D90
```

The final `+1` is an empirical model guard after rounding, not a codec guarantee.
The raw fit has 0.2615-code RMSE on the training configurations and 0.3348-code
RMSE on the 20 holdouts. The guarded integer estimate did not underestimate any
committed training or holdout `D90`; it overestimated by 0-1 codes in training and
0-2 codes in holdout.

The selected form can also be read as `A(q) + K^0.942*B(q)`: two quadratic
functions of the repair ratio and one size exponent. The guarded result is monotonic
over the calibrated `K` domain at 5%-step repair ratios.

Example margins:

| K | 15% repair | 25% repair | 50% repair | 75% repair | 100% repair |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 5 | 5 | 6 | 7 | 8 |
| 2,939 | 6 | 6 | 8 | 10 | 13 |
| 4,000 | 6 | 7 | 9 | 12 | 16 |
| 8,000 | 7 | 8 | 13 | 19 | 26 |
| 16,000 | 9 | 12 | 21 | 33 | 46 |

At `K = R = 2939`, the 20,000-trial combined result is `D90 = 12`. Nineteen of
the twenty individual 1,000-trial graph-seed cells also report 12 and one reports
13. The guarded formula returns 13. This supports a cheap population estimator;
the data does not justify a seed-specific correction.

## Residual-solve bounds

The formula above estimates **mathematical recoverability with an unbounded complete
residual solve**. A caller that refuses residual systems above a fixed width adds a
second completion condition.

With a limit of 8,192 residual unknowns, a 100-trial diagnostic produced:

| K | R | Unbounded D90 | Bounded D90 |
| ---: | ---: | ---: | ---: |
| 2,939 | 2,939 | 12 | 12 |
| 8,000 | 8,000 | 25 | 25 |
| 10,000 | 8,500 | 26 | 141 |
| 16,000 | 16,000 | 45 | 3,498 |

If `R <= 8192`, the residual width cannot exceed the limit because no more than
`R` symbols are erased at `K` received symbols. The formula is therefore unaffected.
Above that boundary the peeling core can trigger a sharp, configuration-dependent
increase. The unbounded formula must not be used as a caller-bounded 90% estimate
there. More bounded-decoder calibration, a different residual policy, or an explicit
fallback is required.

## Reproduction

Run the Java calibration harness with a comma-separated `K:R` grid:

```powershell
.\gradlew.bat --% test --rerun-tasks --tests *RecoveryMarginCalibration -Dldpc.calibrate=3000 -Dldpc.calibrate.seeds=20 -Dldpc.calibrate.configs=750:150,1500:600,2939:2939 -Dldpc.calibrate.output=build/reports/recovery-margin.csv
```

Add `-Dldpc.calibrate.maxResidualUnknowns=8192` to model a bounded caller. Then
reproduce the formula and holdout report without third-party Python packages:

```powershell
python .\scripts\fit_recovery_margin.py
```

Committed data:

- [`scattered-margin-configurations.csv`](scattered-margin-configurations.csv) -
  combined configuration-level training and holdout observations;
- [`scattered-margin-seeds-k2939-r2939.csv`](scattered-margin-seeds-k2939-r2939.csv) -
  the focused seed-variation study.

The simulator seeds, capture-order seeds, quantile convention, and configuration
grid are deterministic in `RecoveryMarginCalibration`.
