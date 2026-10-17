"""Tests for minimal QR encoder (card/generator/qrenc.py)."""

import pytest
import sys
from pathlib import Path

GEN_DIR = Path(__file__).resolve().parent.parent / "generator"
if str(GEN_DIR) not in sys.path:
    sys.path.insert(0, str(GEN_DIR))

import qrenc


def test_byte_capacity_monotonic():
    """Verify byte capacity increases with version."""
    for ecl in ["L", "M", "Q", "H"]:
        prev_cap = 0
        for ver in range(1, 41):
            cap = qrenc.byte_capacity(ver, ecl)
            assert cap > prev_cap, f"Version {ver}-{ecl} capacity {cap} <= prev {prev_cap}"
            prev_cap = cap


def test_qr_v12_m_capacity():
    """Verify QR Version 12-M capacity matches ISO/IEC 18004."""
    # Version 12-M has exactly 287 bytes capacity in byte mode
    cap = qrenc.byte_capacity(12, "M")
    assert cap == 287, f"Expected 287 bytes for v12-M, got {cap}"
    assert qrenc.symbol_size(12) == 65, "Expected 65 modules for v12"


def test_smallest_version_selection():
    """Verify version selection picks smallest fitting version."""
    assert qrenc.smallest_version(10, "M") == 1
    assert qrenc.smallest_version(239, "M") <= 12
    assert qrenc.smallest_version(287, "M") == 12
    assert qrenc.smallest_version(288, "M") > 12


def test_encode_binary_structure():
    """Verify generated symbol geometry, modules, and finder patterns."""
    data = b"Hello NeoGold"  # 13 bytes, fits in v1-M (capacity 14)
    code = qrenc.encode_binary(data, ecl="M", version=1)
    assert code.version == 1
    assert code.size == 21
    assert len(code.modules) == 21
    assert all(len(row) == 21 for row in code.modules)

    # Top-left finder center must be dark
    assert code.modules[3][3] is True
    # Top-right finder center
    assert code.modules[3][17] is True
    # Bottom-left finder center
    assert code.modules[17][3] is True


def test_encode_v12_payload():
    """Verify encoding 239-byte card batch payload into Version 12-M."""
    payload = b"\x01\x02\x03\x04" * 59 + b"\xaa\xbb\xcc"  # 239 bytes
    code = qrenc.encode_binary(payload, ecl="M", version=12)
    assert code.version == 12
    assert code.size == 65
    assert len(code.modules) == 65
    assert all(len(row) == 65 for row in code.modules)


def test_encode_overflow_raises():
    """Verify encoding raises ValueError when payload exceeds capacity."""
    too_large = b"\x00" * 300
    with pytest.raises(ValueError, match="do not fit"):
        qrenc.encode_binary(too_large, ecl="M", version=12)
