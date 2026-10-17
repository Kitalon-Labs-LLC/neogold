"""Minimal QR Code encoder (byte mode only), standard library only.

Purpose: render a QR of the exact density a card batch payload needs, so the
card layout can be reviewed and printed at the right module size, and so the
capacity claims in card/spec/qr-formats.md can be checked by a test.

The algorithm follows ISO/IEC 18004 (versions 1 to 40, error correction levels
L, M, Q, H, automatic mask selection). Output was cross-checked once against
OpenCV's QR decoder (a scratch-only tool, not a project dependency); see
card/tests/test_qr.py for the in-repo structural checks.

No network access, no third-party imports.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Sequence

# Index 0 of each row is padding. Rows: L, M, Q, H. Columns: version 1..40.
_ECC_CODEWORDS_PER_BLOCK = {
    "L": (-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    "M": (-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
    "Q": (-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    "H": (-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
}
_NUM_ERROR_CORRECTION_BLOCKS = {
    "L": (-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
    "M": (-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
    "Q": (-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
    "H": (-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
}
_FORMAT_BITS = {"L": 1, "M": 0, "Q": 3, "H": 2}


def _raw_data_modules(ver: int) -> int:
    result = (16 * ver + 128) * ver + 64
    if ver >= 2:
        numalign = ver // 7 + 2
        result -= (25 * numalign - 10) * numalign - 55
        if ver >= 7:
            result -= 36
    return result


def data_codewords(ver: int, ecl: str) -> int:
    """Number of data codewords (bytes) in a symbol of this version and level."""
    return (_raw_data_modules(ver) // 8
            - _ECC_CODEWORDS_PER_BLOCK[ecl][ver] * _NUM_ERROR_CORRECTION_BLOCKS[ecl][ver])


def _count_bits(ver: int) -> int:
    return 8 if ver <= 9 else 16


def byte_capacity(ver: int, ecl: str) -> int:
    """Maximum number of payload bytes in byte mode (mode indicator and count included)."""
    return (data_codewords(ver, ecl) * 8 - 4 - _count_bits(ver)) // 8


def symbol_size(ver: int) -> int:
    """Modules per side."""
    return ver * 4 + 17


def smallest_version(length: int, ecl: str) -> int:
    for ver in range(1, 41):
        if length <= byte_capacity(ver, ecl):
            return ver
    raise ValueError("payload too long for any QR version at level " + ecl)


def _gf_multiply(x: int, y: int) -> int:
    z = 0
    for i in reversed(range(8)):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree: int) -> List[int]:
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _gf_multiply(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _gf_multiply(root, 0x02)
    return result


def _rs_remainder(data: Sequence[int], divisor: Sequence[int]) -> List[int]:
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _gf_multiply(coef, factor)
    return result


def _bit(x: int, i: int) -> bool:
    return ((x >> i) & 1) != 0


@dataclass
class QrCode:
    version: int
    ecl: str
    mask: int
    size: int
    modules: List[List[bool]]  # modules[y][x], True is dark

    def matrix_rows(self) -> List[str]:
        return ["".join("1" if m else "0" for m in row) for row in self.modules]


def _alignment_positions(ver: int) -> List[int]:
    if ver == 1:
        return []
    numalign = ver // 7 + 2
    step = 26 if ver == 32 else (ver * 4 + numalign * 2 + 1) // (numalign * 2 - 2) * 2
    size = ver * 4 + 17
    result = [size - 7 - i * step for i in range(numalign - 1)] + [6]
    return list(reversed(result))


class _Builder:
    def __init__(self, ver: int, ecl: str, codewords: Sequence[int]):
        self.ver, self.ecl = ver, ecl
        self.size = ver * 4 + 17
        self.modules = [[False] * self.size for _ in range(self.size)]
        self.isfn = [[False] * self.size for _ in range(self.size)]
        self._draw_function_patterns()
        self._draw_codewords(codewords)

    def _set_fn(self, x: int, y: int, dark: bool) -> None:
        self.modules[y][x] = dark
        self.isfn[y][x] = True

    def _draw_function_patterns(self) -> None:
        size = self.size
        for i in range(size):
            self._set_fn(6, i, i % 2 == 0)
            self._set_fn(i, 6, i % 2 == 0)
        self._finder(3, 3)
        self._finder(size - 4, 3)
        self._finder(3, size - 4)
        pos = _alignment_positions(self.ver)
        n = len(pos)
        for i in range(n):
            for j in range(n):
                if (i == 0 and j == 0) or (i == 0 and j == n - 1) or (i == n - 1 and j == 0):
                    continue
                self._alignment(pos[i], pos[j])
        self._draw_format(0)  # reserve; redrawn after the mask is chosen
        self._draw_version()

    def _finder(self, x: int, y: int) -> None:
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                dist = max(abs(dx), abs(dy))
                xx, yy = x + dx, y + dy
                if 0 <= xx < self.size and 0 <= yy < self.size:
                    self._set_fn(xx, yy, dist not in (2, 4))

    def _alignment(self, x: int, y: int) -> None:
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                self._set_fn(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)

    def _draw_format(self, mask: int) -> None:
        data = _FORMAT_BITS[self.ecl] << 3 | mask
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (data << 10 | rem) ^ 0x5412
        size = self.size
        for i in range(0, 6):
            self._set_fn(8, i, _bit(bits, i))
        self._set_fn(8, 7, _bit(bits, 6))
        self._set_fn(8, 8, _bit(bits, 7))
        self._set_fn(7, 8, _bit(bits, 8))
        for i in range(9, 15):
            self._set_fn(14 - i, 8, _bit(bits, i))
        for i in range(0, 8):
            self._set_fn(size - 1 - i, 8, _bit(bits, i))
        for i in range(8, 15):
            self._set_fn(8, size - 15 + i, _bit(bits, i))
        self._set_fn(8, size - 8, True)

    def _draw_version(self) -> None:
        if self.ver < 7:
            return
        rem = self.ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = self.ver << 12 | rem
        for i in range(18):
            color = _bit(bits, i)
            a = self.size - 11 + i % 3
            b = i // 3
            self._set_fn(a, b, color)
            self._set_fn(b, a, color)

    def _draw_codewords(self, data: Sequence[int]) -> None:
        size = self.size
        i = 0
        right = size - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(size):
                for j in range(2):
                    x = right - j
                    upward = ((right + 1) & 2) == 0
                    y = (size - 1 - vert) if upward else vert
                    if not self.isfn[y][x] and i < len(data) * 8:
                        self.modules[y][x] = _bit(data[i >> 3], 7 - (i & 7))
                        i += 1
            right -= 2

    def apply_mask(self, mask: int) -> None:
        for y in range(self.size):
            for x in range(self.size):
                if self.isfn[y][x]:
                    continue
                if mask == 0:
                    inv = (x + y) % 2 == 0
                elif mask == 1:
                    inv = y % 2 == 0
                elif mask == 2:
                    inv = x % 3 == 0
                elif mask == 3:
                    inv = (x + y) % 3 == 0
                elif mask == 4:
                    inv = (x // 3 + y // 2) % 2 == 0
                elif mask == 5:
                    inv = x * y % 2 + x * y % 3 == 0
                elif mask == 6:
                    inv = (x * y % 2 + x * y % 3) % 2 == 0
                else:
                    inv = ((x + y) % 2 + x * y % 3) % 2 == 0
                if inv:
                    self.modules[y][x] = not self.modules[y][x]

    def penalty(self) -> int:
        size, m = self.size, self.modules
        score = 0
        lines = [row for row in m] + [[m[y][x] for y in range(size)] for x in range(size)]
        pat_a = [True, False, True, True, True, False, True, False, False, False, False]
        pat_b = list(reversed(pat_a))
        for line in lines:
            run = 1
            for k in range(1, size):
                if line[k] == line[k - 1]:
                    run += 1
                else:
                    if run >= 5:
                        score += 3 + (run - 5)
                    run = 1
            if run >= 5:
                score += 3 + (run - 5)
            for k in range(size - 10):
                window = line[k:k + 11]
                if window == pat_a or window == pat_b:
                    score += 40
        for y in range(size - 1):
            for x in range(size - 1):
                if m[y][x] == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                    score += 3
        dark = sum(sum(1 for v in row if v) for row in m)
        total = size * size
        k = (abs(dark * 20 - total * 10) + total - 1) // total - 1
        score += max(k, 0) * 10
        return score


def _build_codewords(data: bytes, ver: int, ecl: str) -> List[int]:
    bits: List[int] = []

    def put(value: int, length: int) -> None:
        for i in reversed(range(length)):
            bits.append((value >> i) & 1)

    put(0b0100, 4)
    put(len(data), _count_bits(ver))
    for b in data:
        put(b, 8)
    capacity_bits = data_codewords(ver, ecl) * 8
    put(0, min(4, capacity_bits - len(bits)))
    put(0, -len(bits) % 8)
    pad = 0xEC
    while len(bits) < capacity_bits:
        put(pad, 8)
        pad ^= 0xEC ^ 0x11
    dcw = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]

    num_blocks = _NUM_ERROR_CORRECTION_BLOCKS[ecl][ver]
    block_ecc_len = _ECC_CODEWORDS_PER_BLOCK[ecl][ver]
    raw_codewords = _raw_data_modules(ver) // 8
    num_short = num_blocks - raw_codewords % num_blocks
    short_len = raw_codewords // num_blocks
    blocks: List[List[int]] = []
    divisor = _rs_divisor(block_ecc_len)
    k = 0
    for i in range(num_blocks):
        dat_len = short_len - block_ecc_len + (0 if i < num_short else 1)
        dat = dcw[k:k + dat_len]
        k += dat_len
        ecc = _rs_remainder(dat, divisor)
        if i < num_short:
            dat = dat + [0]
        blocks.append(dat + ecc)
    result: List[int] = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != short_len - block_ecc_len or j >= num_short:
                result.append(blk[i])
    assert len(result) == raw_codewords
    return result


def encode_binary(data: bytes, ecl: str = "M", version: int | None = None) -> QrCode:
    """Encode raw bytes in a single byte-mode segment.

    If ``version`` is given the symbol uses exactly that version (raises if the
    payload does not fit); otherwise the smallest fitting version is used.
    """
    if ecl not in _FORMAT_BITS:
        raise ValueError("ecl must be one of L, M, Q, H")
    if version is None:
        version = smallest_version(len(data), ecl)
    elif len(data) > byte_capacity(version, ecl):
        raise ValueError(
            f"{len(data)} bytes do not fit QR version {version}-{ecl} "
            f"(capacity {byte_capacity(version, ecl)})")
    codewords = _build_codewords(data, version, ecl)
    best = None
    best_score = None
    for mask in range(8):
        b = _Builder(version, ecl, codewords)
        b.apply_mask(mask)
        b._draw_format(mask)
        score = b.penalty()
        if best_score is None or score < best_score:
            best, best_score, best_mask = b, score, mask
    return QrCode(version, ecl, best_mask, best.size, best.modules)
