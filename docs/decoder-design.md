# Decoder design

How `LdpcStaircase.decode` is built, what each stage costs, and why the profile is
shaped the way it is. The normative behaviour is in
[`SPEC.md`](../external/ldpc-staircase-spec/SPEC.md) section 6; this document is the
reasoning behind it.

## The two stages

Decoding is one pass of **degree-one peeling** followed, only when peeling stalls, by
**one complete residual GF(2) solve**. The profile forbids iterating between them.

### Stage 1: peeling

Each row of the parity check matrix is an equation constraining its symbols to XOR to
zero. The decoder keeps two things per row:

- `unknown(i)` — how many of the row's variables are not yet known, and
- `accum(i)` — the XOR of the ones that are.

When `unknown(i)` falls to one, the equation says the remaining variable *is*
`accum(i)`. Assigning it can drop another row to one unknown, so resolved rows are
pushed onto a stack and drained to a fixed point.

Two implementation notes worth keeping:

- **The accumulator becomes the symbol.** When a row resolves, its accumulator buffer
  already holds the recovered bytes, so it is handed straight over as the symbol
  rather than copied. That removes one `S`-byte allocation and copy per recovery.
- **Dead accumulators are released.** A row whose `unknown` count reaches zero can
  never contribute again, so its accumulator is dropped immediately. Live accumulators
  are bounded by the number of *unresolved* rows rather than by `R`, which matters
  because each one costs `S` bytes.

Cost is linear: every matrix entry is visited a bounded number of times, so peeling is
`O(entries)` time — about `N1·K + 2R` symbol XORs — and `O(unresolved rows · S)`
memory beyond the symbols themselves. A row is pushed at most once, because
`unknown(i)` only ever decreases.

### Stage 2: the residual solve

Peeling stalls when every unresolved row still holds two or more unknowns. Those rows
form a linear system over GF(2) in the still-unknown variables, with each row's
accumulator as its right-hand side. The decoder reduces the coefficient matrix to
**reduced row echelon form**, applying every row operation to the symbol right-hand
sides as well.

A variable is recovered exactly when it is a pivot variable whose RREF row mentions no
other variable. That is the maximal set the received symbols determine: anything else
is genuinely a free variable, undetermined by the equations. This is also the reason
the profile can forbid a second peeling pass — RREF already captures every linear
consequence of the residual system, so nothing is left for peeling to find.

Coefficients are packed as `long` bitsets (`⌈u/64⌉` words per row), so the coefficient
half of a row operation is ~64 columns per instruction. Residual right-hand sides are
also copied once into packed `long[]` arrays; this keeps the millions of elimination
XORs on direct array operations and avoids Android ART's byte-array `VarHandle`
dispatch in the hot loop.

```text
time   ≈ u · m · (u/64 words + S bytes)
memory ≈ m · ⌈u/64⌉ · 8 bytes   (coefficients)
       + m · ⌈S/8⌉ · 8 bytes    (packed right-hand sides)
```

where `u` is the number of unknowns and `m` the number of unresolved rows.

This is the decoder's only quadratic term and its entire risk surface. It is why
`DecodeOptions.withMaxResidualUnknowns` exists: a product that must not exceed a heap
budget sets the bound and gets a reported failure instead of an `OutOfMemoryError`.
the shared specification's gate 9 — derive an LDPC-specific memory seatbelt — is measured against this
formula, not against the shared specification's Reed-Solomon one.

### Why the residual solve is not optional

A peeling-only decoder is far cheaper and strictly weaker: it strands sessions whose
received symbols fully determine the source but whose sparse graph happens to stall.
Near the code's threshold that is the *normal* case, not an edge case — the vector
generator could not find a single peeling-only success at the full `R`-symbol loss
budget in 20,000 attempts, while the residual solve recovers a large fraction of them.
`LdpcStaircaseTest.residualSolveRecoversWherePeelingStalls` pins that difference.

`withResidualSolve(false)` exists to isolate the stages for measurement. It is not a
production setting.

## Why peeling and RREF, and nothing else

the shared specification's profile deliberately excludes the techniques that would normally come next
— permanent or predetermined inactivation, repeated elimination-to-peeling cycles,
multiple symbols per packet. Those are the parts of the design space where the IPR
picture is least settled (see the ADR's guardrails), and the constrained decoder is
already fast enough for the product's operating point: at `K = 10,000` a clean peel is
13 ms and a full-budget residual solve is about 2 s.

Repair variables are mapped before source variables in the residual system. A
staircase repair variable occurs in at most two parity equations, so this simple
sparsity-aware order eliminates the low-degree part first and avoids needless early
fill-in without changing RREF or the recovered result.

## Symbol arithmetic

Everything the code does to payloads is XOR. Encoding and peeling use a `long`-wide
`VarHandle` view over `byte[]` for eight bytes per instruction, with a byte tail.
Native byte order is safe precisely because XOR is byte-order agnostic: the view
permutes both operands and the result identically, so the bytes written are the same
on every platform. The quadratic residual stage converts each live right-hand side
once and uses direct `long[]` XOR instead; the recovered symbols are converted back to
their exact byte length after RREF.

## What determines the graph

The matrix is a pure function of `(K, R, N1, seed)`. One Park–Miller stream started at
`seed` supplies every draw, and the three construction phases run in a fixed order, so
the sequence of draws — and therefore the graph — is fixed. `PmmsRandomTest` asserts
RFC 5170's own validation criterion (seed 1, 10,000th value `1043618065`); if that
holds, an independent implementation of the same recurrence walks the identical stream.

The one subtlety is the scaling from a raw 31-bit state into `[0, maxv)`. SPEC.md
section 3 pins the IEEE 754 binary64 form RFC 5170 specifies, which is **not**
interchangeable with exact 64-bit integer arithmetic: `maxv · state` reaches 2⁶² and is
rounded to a 53-bit mantissa, so the two disagree on values that land within about
2.4 × 10⁻⁷ of an integer boundary. Both operations are correctly rounded on the JVM and
.NET, so the floating-point form is the reproducible choice — and the one that keeps
the door open to differential-testing against an external oracle.
