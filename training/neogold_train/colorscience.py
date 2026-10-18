"""
Color science mathematical routines replicating :engine:colorscience.
Maintains parity with Kotlin implementation within 1e-4 across all conversions,
linearization, least-squares color corrections, and CIEDE2000.
"""

from __future__ import annotations
import math
from dataclasses import dataclass
from typing import List, Tuple
import numpy as np


@dataclass(frozen=True)
class Rgb:
    r: float
    g: float
    b: float


@dataclass(frozen=True)
class Xyz:
    x: float
    y: float
    z: float


@dataclass(frozen=True)
class Lab:
    l: float
    a: float
    b: float


# CIE standard constants (CIE 15:2004)
CIE_EPSILON = 216.0 / 24389.0  # (6/29)^3
CIE_KAPPA = 24389.0 / 27.0      # (29/3)^3

# D65 normalized reference white point (Yn = 1.0)
D65_X = 0.95047
D65_Y = 1.00000
D65_Z = 1.08883
D65_WHITE = Xyz(D65_X, D65_Y, D65_Z)

# sRGB to XYZ (D65) matrix
M_SRGB_TO_XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
], dtype=np.float64)

# Exact inverse XYZ to sRGB matrix
M_XYZ_TO_SRGB = np.array([
    [3.2404548360214087, -1.5371388501025751, -0.4985315468684810],
    [-0.9692663898756538,  1.8760109288424911,  0.0415560823466735],
    [0.0556434196042137, -0.2040258542676982,  1.0572251624579290],
], dtype=np.float64)

SRGB_LINEAR_THRESHOLD = 0.04045
LINEAR_SRGB_THRESHOLD = 0.04045 / 12.92


def srgb_channel_to_linear(c: float) -> float:
    if c <= SRGB_LINEAR_THRESHOLD:
        return c / 12.92
    return math.pow((c + 0.055) / 1.055, 2.4)


def linear_channel_to_srgb(c: float) -> float:
    if c <= LINEAR_SRGB_THRESHOLD:
        return c * 12.92
    return 1.055 * math.pow(max(0.0, c), 1.0 / 2.4) - 0.055


def srgb_to_linear(rgb: Rgb) -> Rgb:
    return Rgb(
        r=srgb_channel_to_linear(rgb.r),
        g=srgb_channel_to_linear(rgb.g),
        b=srgb_channel_to_linear(rgb.b),
    )


def linear_to_srgb(rgb: Rgb) -> Rgb:
    return Rgb(
        r=linear_channel_to_srgb(rgb.r),
        g=linear_channel_to_srgb(rgb.g),
        b=linear_channel_to_srgb(rgb.b),
    )


def linear_rgb_to_xyz(rgb: Rgb) -> Xyz:
    v = np.array([rgb.r, rgb.g, rgb.b], dtype=np.float64)
    res = M_SRGB_TO_XYZ @ v
    return Xyz(x=float(res[0]), y=float(res[1]), z=float(res[2]))


def xyz_to_linear_rgb(xyz: Xyz) -> Rgb:
    v = np.array([xyz.x, xyz.y, xyz.z], dtype=np.float64)
    res = M_XYZ_TO_SRGB @ v
    return Rgb(r=float(res[0]), g=float(res[1]), b=float(res[2]))


def srgb_to_xyz(rgb: Rgb) -> Xyz:
    return linear_rgb_to_xyz(srgb_to_linear(rgb))


def xyz_to_srgb(xyz: Xyz) -> Rgb:
    return linear_to_srgb(xyz_to_linear_rgb(xyz))


def xyz_to_lab(xyz: Xyz, white_point: Xyz = D65_WHITE) -> Lab:
    xr = xyz.x / white_point.x
    yr = xyz.y / white_point.y
    zr = xyz.z / white_point.z

    fx = math.pow(xr, 1.0 / 3.0) if xr > CIE_EPSILON else (CIE_KAPPA * xr + 16.0) / 116.0
    fy = math.pow(yr, 1.0 / 3.0) if yr > CIE_EPSILON else (CIE_KAPPA * yr + 16.0) / 116.0
    fz = math.pow(zr, 1.0 / 3.0) if zr > CIE_EPSILON else (CIE_KAPPA * zr + 16.0) / 116.0

    l = 116.0 * fy - 16.0
    a = 500.0 * (fx - fy)
    b = 200.0 * (fy - fz)
    return Lab(l=l, a=a, b=b)


def lab_to_xyz(lab: Lab, white_point: Xyz = D65_WHITE) -> Xyz:
    fy = (lab.l + 16.0) / 116.0
    fx = lab.a / 500.0 + fy
    fz = fy - lab.b / 200.0

    fx3 = fx * fx * fx
    fz3 = fz * fz * fz

    xr = fx3 if fx3 > CIE_EPSILON else (116.0 * fx - 16.0) / CIE_KAPPA
    yr = (lab.l / CIE_KAPPA) if lab.l <= 8.0 else fy * fy * fy
    zr = fz3 if fz3 > CIE_EPSILON else (116.0 * fz - 16.0) / CIE_KAPPA

    return Xyz(
        x=xr * white_point.x,
        y=yr * white_point.y,
        z=zr * white_point.z,
    )


def srgb_to_lab(rgb: Rgb) -> Lab:
    return xyz_to_lab(srgb_to_xyz(rgb))


def lab_to_srgb(lab: Lab) -> Rgb:
    return xyz_to_srgb(lab_to_xyz(lab))


def ciede2000(
    lab1: Lab,
    lab2: Lab,
    k_l: float = 1.0,
    k_c: float = 1.0,
    k_h: float = 1.0,
) -> float:
    """
    CIEDE2000 color-difference formula following Sharma et al. (2005).
    Replicates Kotlin :engine:colorscience:Ciede2000 exactly.
    """
    l1, a1, b1 = lab1.l, lab1.a, lab1.b
    l2, a2, b2 = lab2.l, lab2.a, lab2.b

    if l1 == l2 and a1 == a2 and b1 == b2:
        return 0.0

    pow25_7 = 6103515625.0  # 25^7

    c1 = math.hypot(a1, b1)
    c2 = math.hypot(a2, b2)
    c_bar = (c1 + c2) / 2.0

    c_bar7 = math.pow(c_bar, 7.0)
    g = 0.5 * (1.0 - math.sqrt(c_bar7 / (c_bar7 + pow25_7)))

    a1_prime = (1.0 + g) * a1
    a2_prime = (1.0 + g) * a2

    c1_prime = math.hypot(a1_prime, b1)
    c2_prime = math.hypot(a2_prime, b2)

    h1_prime = math.degrees(math.atan2(b1, a1_prime))
    if h1_prime < 0.0:
        h1_prime += 360.0

    h2_prime = math.degrees(math.atan2(b2, a2_prime))
    if h2_prime < 0.0:
        h2_prime += 360.0

    delta_l_prime = l2 - l1
    delta_c_prime = c2_prime - c1_prime

    if c1_prime * c2_prime == 0.0:
        delta_h_prime_deg = 0.0
    else:
        diff = h2_prime - h1_prime
        if abs(diff) <= 180.0:
            delta_h_prime_deg = diff
        elif diff > 180.0:
            delta_h_prime_deg = diff - 360.0
        else:
            delta_h_prime_deg = diff + 360.0

    delta_h_prime = 2.0 * math.sqrt(c1_prime * c2_prime) * math.sin(math.radians(delta_h_prime_deg / 2.0))

    l_bar_prime = (l1 + l2) / 2.0
    c_bar_prime = (c1_prime + c2_prime) / 2.0

    if c1_prime * c2_prime == 0.0:
        h_bar_prime = h1_prime + h2_prime
    else:
        diff = abs(h1_prime - h2_prime)
        if diff <= 180.0:
            h_bar_prime = (h1_prime + h2_prime) / 2.0
        elif (h1_prime + h2_prime) < 360.0:
            h_bar_prime = (h1_prime + h2_prime + 360.0) / 2.0
        else:
            h_bar_prime = (h1_prime + h2_prime - 360.0) / 2.0

    t = (
        1.0
        - 0.17 * math.cos(math.radians(h_bar_prime - 30.0))
        + 0.24 * math.cos(math.radians(2.0 * h_bar_prime))
        + 0.32 * math.cos(math.radians(3.0 * h_bar_prime + 6.0))
        - 0.20 * math.cos(math.radians(4.0 * h_bar_prime - 63.0))
    )

    delta_theta = 30.0 * math.exp(-math.pow((h_bar_prime - 275.0) / 25.0, 2.0))

    c_bar_prime7 = math.pow(c_bar_prime, 7.0)
    rc = 2.0 * math.sqrt(c_bar_prime7 / (c_bar_prime7 + pow25_7))

    l_bar_minus50_sq = math.pow(l_bar_prime - 50.0, 2.0)
    sl = 1.0 + (0.015 * l_bar_minus50_sq) / math.sqrt(20.0 + l_bar_minus50_sq)
    sc = 1.0 + 0.045 * c_bar_prime
    sh = 1.0 + 0.015 * c_bar_prime * t

    rt = -math.sin(math.radians(2.0 * delta_theta)) * rc

    vl = delta_l_prime / (k_l * sl)
    vc = delta_c_prime / (k_c * sc)
    vh = delta_h_prime / (k_h * sh)

    return math.sqrt(vl * vl + vc * vc + vh * vh + rt * vc * vh)


def pava(points: List[Tuple[float, float]]) -> List[Tuple[float, float]]:
    """
    Pool Adjacent Violators Algorithm (PAVA) for isotonic monotonic regression.
    Matches Kotlin MonotonicLinearizer.pava.
    """
    if not points:
        return []
    sorted_pts = sorted(points, key=lambda p: p[0])

    class Block:
        def __init__(self, weight: float, total: float, indices: list[int]):
            self.weight = weight
            self.total = total
            self.indices = indices

        @property
        def value(self) -> float:
            return self.total / self.weight

    blocks: list[Block] = []
    for idx, pt in enumerate(sorted_pts):
        b = Block(weight=1.0, total=pt[1], indices=[idx])
        while blocks and blocks[-1].value > b.value:
            prev = blocks.pop()
            b.weight += prev.weight
            b.total += prev.total
            b.indices = prev.indices + b.indices
        blocks.append(b)

    out_y = [0.0] * len(sorted_pts)
    for b in blocks:
        v = b.value
        for i in b.indices:
            out_y[i] = v

    return [(sorted_pts[i][0], out_y[i]) for i in range(len(sorted_pts))]


class MonotonePiecewiseLinear:
    """
    Monotone piecewise linear interpolator with endpoint clamping.
    Matches Kotlin MonotonePiecewiseLinear.
    """
    def __init__(self, knots: List[Tuple[float, float]]):
        if len(knots) < 2:
            raise ValueError(f"Requires at least 2 knots (got {len(knots)})")
        for i in range(len(knots) - 1):
            if knots[i][0] >= knots[i + 1][0]:
                raise ValueError("Knots x must be strictly increasing")
            if knots[i][1] > knots[i + 1][1]:
                raise ValueError("Knots y must be non-decreasing")
        self.knots = knots

    def interpolate(self, x: float) -> float:
        if x <= self.knots[0][0]:
            return self.knots[0][1]
        if x >= self.knots[-1][0]:
            return self.knots[-1][1]

        low = 0
        high = len(self.knots) - 1
        while low <= high:
            mid = (low + high) // 2
            if self.knots[mid][0] <= x:
                if mid == len(self.knots) - 1 or self.knots[mid + 1][0] > x:
                    x0, y0 = self.knots[mid]
                    x1, y1 = self.knots[mid + 1]
                    t = (x - x0) / (x1 - x0)
                    return y0 + t * (y1 - y0)
                low = mid + 1
            else:
                high = mid - 1
        return self.knots[-1][1]


def fit_linearization(
    observed_grays: List[Rgb],
    reference_reflectances: List[float],
) -> Tuple[MonotonePiecewiseLinear, MonotonePiecewiseLinear, MonotonePiecewiseLinear]:
    """
    Fits per-channel monotonic linearization curves mapping raw observed RGB to linear reflectance.
    """
    r_pts = [(g.r, ref) for g, ref in zip(observed_grays, reference_reflectances)]
    g_pts = [(g.g, ref) for g, ref in zip(observed_grays, reference_reflectances)]
    b_pts = [(g.b, ref) for g, ref in zip(observed_grays, reference_reflectances)]

    r_iso = pava(r_pts)
    g_iso = pava(g_pts)
    b_iso = pava(b_pts)

    return (
        MonotonePiecewiseLinear(r_iso),
        MonotonePiecewiseLinear(g_iso),
        MonotonePiecewiseLinear(b_iso),
    )


def fit_correction_matrix_3x3(
    linearized_patches: List[Rgb],
    reference_xyz: List[Xyz],
) -> np.ndarray:
    """
    Fits 3x3 linear color correction matrix M via least squares:
    target_xyz = linear_rgb @ M, where M has shape (3, 3).
    Matches Kotlin Linear3x3CorrectionModel.
    """
    a = np.array([[p.r, p.g, p.b] for p in linearized_patches], dtype=np.float64)
    b = np.array([[p.x, p.y, p.z] for p in reference_xyz], dtype=np.float64)
    m, _, _, _ = np.linalg.lstsq(a, b, rcond=None)
    return m


def apply_correction_3x3(linear_rgb: Rgb, matrix_3x3: np.ndarray) -> Xyz:
    v = np.array([linear_rgb.r, linear_rgb.g, linear_rgb.b], dtype=np.float64)
    res = v @ matrix_3x3
    return Xyz(
        x=float(max(0.0, res[0])),
        y=float(max(0.0, res[1])),
        z=float(max(0.0, res[2])),
    )
