#!/usr/bin/env python3
"""Fit and validate the scattered-capture D90 estimator using only the stdlib."""

from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT / "docs" / "calibration" / "scattered-margin-configurations.csv"
DEFAULT_SEEDS = ROOT / "docs" / "calibration" / "scattered-margin-seeds-k2939-r2939.csv"


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source))


def features(k: float, q: float, alpha: float) -> list[float]:
    power = k**alpha
    return [1.0, q, q * q, power, power * q, power * q * q]


def solve(matrix: list[list[float]], values: list[float]) -> list[float]:
    """Gaussian elimination with partial pivoting for a small dense system."""
    size = len(values)
    augmented = [matrix[row][:] + [values[row]] for row in range(size)]
    for column in range(size):
        pivot = max(range(column, size), key=lambda row: abs(augmented[row][column]))
        if abs(augmented[pivot][column]) < 1e-12:
            raise ValueError("singular normal-equation matrix")
        augmented[column], augmented[pivot] = augmented[pivot], augmented[column]
        scale = augmented[column][column]
        for item in range(column, size + 1):
            augmented[column][item] /= scale
        for row in range(size):
            if row == column:
                continue
            factor = augmented[row][column]
            if factor == 0.0:
                continue
            for item in range(column, size + 1):
                augmented[row][item] -= factor * augmented[column][item]
    return [augmented[row][size] for row in range(size)]


def fit(rows: Iterable[dict[str, str]], alpha: float) -> list[float]:
    width = 6
    xtx = [[0.0] * width for _ in range(width)]
    xty = [0.0] * width
    for row in rows:
        x = features(float(row["k"]), float(row["repair_ratio"]), alpha)
        y = float(row["d90"])
        for i in range(width):
            xty[i] += x[i] * y
            for j in range(width):
                xtx[i][j] += x[i] * x[j]
    return solve(xtx, xty)


def raw_prediction(row: dict[str, str], alpha: float, coefficients: list[float]) -> float:
    return sum(
        coefficient * value
        for coefficient, value in zip(
            coefficients,
            features(float(row["k"]), float(row["repair_ratio"]), alpha),
        )
    )


def guarded_margin(raw: float) -> int:
    # Round to the closest code, then add one code of empirical model guard.
    return max(0, math.floor(raw + 0.5) + 1)


def rmse(rows: Iterable[dict[str, str]], alpha: float, coefficients: list[float]) -> float:
    errors = [raw_prediction(row, alpha, coefficients) - float(row["d90"]) for row in rows]
    return math.sqrt(sum(error * error for error in errors) / len(errors))


def policy_metrics(
    rows: Iterable[dict[str, str]], alpha: float, coefficients: list[float]
) -> tuple[int, float, int]:
    differences = [
        guarded_margin(raw_prediction(row, alpha, coefficients)) - int(row["d90"])
        for row in rows
    ]
    return -min(differences), sum(differences) / len(differences), max(differences)


def choose_alpha(training: list[dict[str, str]]) -> tuple[float, list[float]]:
    best: tuple[float, float, list[float]] | None = None
    for step in range(100, 1201):
        alpha = step / 1000.0
        coefficients = fit(training, alpha)
        error = rmse(training, alpha, coefficients)
        if best is None or error < best[0]:
            best = (error, alpha, coefficients)
    assert best is not None
    return best[1], best[2]


def verify_monotonic_policy(alpha: float, coefficients: list[float]) -> None:
    def estimate(k: int, q: float) -> int:
        row = {"k": str(k), "repair_ratio": str(q)}
        return guarded_margin(raw_prediction(row, alpha, coefficients))

    # The calibrated app-facing domain: K=256..16000 and 5%-step redundancy.
    ratios = [value / 100.0 for value in range(5, 101, 5)]
    for k in range(256, 16_001):
        values = [estimate(k, ratio) for ratio in ratios]
        if any(left > right for left, right in zip(values, values[1:])):
            raise AssertionError(f"guarded estimator is not monotonic in repair ratio at K={k}")
    for ratio in ratios:
        previous = estimate(256, ratio)
        for k in range(257, 16_001):
            current = estimate(k, ratio)
            if current < previous:
                raise AssertionError(
                    f"guarded estimator is not monotonic in K at repair ratio {ratio}"
                )
            previous = current


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--seed-study", type=Path, default=DEFAULT_SEEDS)
    args = parser.parse_args()

    rows = read_rows(args.data)
    training = [row for row in rows if row["dataset"] == "train"]
    holdout = [row for row in rows if row["dataset"] == "holdout"]
    alpha, coefficients = choose_alpha(training)
    verify_monotonic_policy(alpha, coefficients)

    print(f"training configurations: {len(training)}")
    print(f"holdout configurations:  {len(holdout)}")
    print(f"alpha: {alpha:.3f}")
    labels = ["A0", "A1", "A2", "B0", "B1", "B2"]
    for label, coefficient in zip(labels, coefficients):
        print(f"{label}: {coefficient:.15g}")
    print()
    for label, sample in (("training", training), ("holdout", holdout)):
        under, mean_over, max_over = policy_metrics(sample, alpha, coefficients)
        print(
            f"{label}: raw RMSE={rmse(sample, alpha, coefficients):.4f}; "
            f"guarded max under={under}; mean over={mean_over:.3f}; max over={max_over}"
        )
        if under > 0:
            raise AssertionError(f"guarded estimator underestimated {label} D90 by {under}")

    print("\nholdout predictions:")
    print("K,R,measured_D90,raw_prediction,guarded_D90")
    for row in holdout:
        raw = raw_prediction(row, alpha, coefficients)
        print(
            f'{row["k"]},{row["r"]},{row["d90"]},{raw:.4f},{guarded_margin(raw)}'
        )

    if args.seed_study.exists():
        seed_rows = [row for row in read_rows(args.seed_study) if row["scope"] == "seed"]
        seed_d90 = [int(row["d90"]) for row in seed_rows]
        print(
            f"\nseed study: {len(seed_rows)} seeds; per-seed D90 range "
            f"{min(seed_d90)}..{max(seed_d90)}"
        )


if __name__ == "__main__":
    main()
