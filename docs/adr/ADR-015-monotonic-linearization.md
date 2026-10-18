# ADR-015: Monotonic Tone Curve Linearization Method

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-18 |
| Author | Kitalon Labs / NeoGold Project |
| Deciders | Antigravity AI / Lead Architect |
| Component | `:engine:colorscience` |
| Verifies | FR-16, NG-013, TC-EST-02 |

---

## Context
In NeoGold's offline calibration pipeline (`docs/4_System_Architecture.md` Section 5.4), each smartphone camera capture exhibits a device-specific, non-linear tone reproduction curve (OETF / gamma / S-curve) that deviates substantially from standardized sRGB gamma. To reliably map raw patch responses to absolute CIE XYZ (D65) values, the pipeline linearizes observed camera responses using the card's neutral gray ramp.

Card v1 (`card/spec/card-v1.md`) provides 7 gray patches (`G1`–`G7`) with $L^*$ targets ranging from 90 down to 18, plus duplicate patches. The linearization engine must fit a strictly monotonic function $f_c(V_{obs}) \to V_{lin}$ for each channel $c \in \{R, G, B\}$ such that:
$$V_{obs, 1} \le V_{obs, 2} \implies f_c(V_{obs, 1}) \le f_c(V_{obs, 2})$$

## Decision
We adopt **monotone piecewise-linear interpolation** preceded by **isotonic regression using the Pool Adjacent Violators Algorithm (PAVA)**.

## Alternatives Evaluated

### 1. Unconstrained Polynomial Regression (Rejected)
- Higher-order polynomials (e.g., degree 3 to 5) suffer from Runge's phenomenon, oscillating between calibration points.
- Monotonicity is not mathematically guaranteed across the full dynamic range $[0, 1]$, which can produce inverted gradients in dark skin patches.

### 2. Monotone Cubic Hermite Splines / PCHIP (Rejected)
- While Piecewise Cubic Hermite Interpolating Polynomials (PCHIP) preserve monotonicity, derivative estimation at boundary points is acutely sensitive to small measurement noise when only 6 to 7 knots are available.
- PCHIP can produce local plateaus (zero derivative) between closely spaced knots, flattening contrast response in the mid-tones.
- Increases computational complexity and floating-point divergence potential between target ABIs.

### 3. Monotone Piecewise-Linear with PAVA (Accepted)
- **Unconditional Monotonicity:** Monotone piecewise-linear interpolation is strictly monotonic by construction if its knot sequence $(x_i, y_i)$ is non-decreasing.
- **Robust Noise Filtering:** In real-world handheld smartphone captures, slight non-uniform illumination or sensor noise can occasionally cause two adjacent gray patches to exhibit an inverted raw reading. PAVA computes the optimal least-squares isotonic sequence in $O(N)$ operations, resolving any inversion without user intervention.
- **Stability with $\ge 6$ Patches:** Tested thoroughly on 6 and 7 gray patch configurations; remains numerically stable without ill-conditioned matrix operations.
- **Exact Node Fidelity:** Preserves measured spectrophotometer reflectance targets directly at the calibration nodes.

## Consequences
- The engine guarantees non-decreasing outputs for all inputs in $[0, 1]$.
- No matrix inversions or iterative solvers are required during linearization, keeping runtime well within the Helio G81/G85 200 ms budget (doc 4 Section 5.6).
- Endpoints are anchored at $(0.0, 0.0)$ and $(1.0, 1.0)$, providing safe linear clamping for underexposed or saturated pixels.
