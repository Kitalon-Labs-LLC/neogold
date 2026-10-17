"""Tests for card v1 physical layout, patches, and sampling geometry."""

import json
from pathlib import Path


def load_spec():
    spec_path = Path("card/spec/card-v1.json")
    with open(spec_path, "r", encoding="utf-8") as f:
        return json.load(f)


def test_card_dimensions_and_scaling():
    spec = load_spec()
    card = spec["card"]
    assert card["width_mm"] == 120.0
    assert card["height_mm"] == 76.0
    assert card["corner_radius_mm"] == 3.0

    canon = spec["canonical_image"]
    assert canon["width_px"] == 1200
    assert canon["height_px"] == 760
    assert canon["px_per_mm"] == 10
    assert canon["patch_inner_fraction"] == 0.6
    assert canon["window_inner_fraction"] == 0.7


def test_patches_composition_and_counts():
    spec = load_spec()
    patches = spec["patches"]
    assert len(patches) == 23

    # Group counts
    grays = [p for p in patches if p["group"] == "GRAY"]
    skins = [p for p in patches if p["group"] == "SKIN"]
    sats = [p for p in patches if p["group"] == "SATURATED"]

    # 7 unique grays + 2 duplicates = 9
    assert len(grays) == 9
    # 9 unique skin tones + 2 duplicates = 11
    assert len(skins) == 11
    # 3 saturated references
    assert len(sats) == 3


def test_gray_ramp_monotonicity():
    spec = load_spec()
    patches = {p["id"]: p for p in spec["patches"]}
    gray_ids = ["G1", "G2", "G3", "G4", "G5", "G6", "G7"]

    # Ensure at least 6 steps (here 7 steps)
    assert len(gray_ids) >= 6

    # Strictly decreasing lightness
    lightness = [patches[gid]["lab_d65"][0] for gid in gray_ids]
    for i in range(len(lightness) - 1):
        assert lightness[i] > lightness[i + 1], f"{gray_ids[i]} ({lightness[i]}) <= {gray_ids[i+1]} ({lightness[i+1]})"

    # All grays must have zero chroma in nominal targets
    for gid in gray_ids:
        a = patches[gid]["lab_d65"][1]
        b = patches[gid]["lab_d65"][2]
        assert a == 0.0 and b == 0.0, f"{gid} chroma not zero: a={a}, b={b}"


def test_duplicate_patches():
    spec = load_spec()
    patches = {p["id"]: p for p in spec["patches"]}

    dups = {
        "G2D": "G2",
        "G4D": "G4",
        "K5D": "K5",
        "K8D": "K8",
    }
    for dup_id, orig_id in dups.items():
        assert patches[dup_id]["duplicate_of"] == orig_id
        # CIELAB targets must be identical
        assert patches[dup_id]["lab_d65"] == patches[orig_id]["lab_d65"]

        # Opposites: one in top row (y <= 15), one in bottom row (y >= 50)
        y_dup = patches[dup_id]["rect_mm"]["y"]
        y_orig = patches[orig_id]["rect_mm"]["y"]
        assert (y_orig < 20 and y_dup > 50) or (y_dup < 20 and y_orig > 50), (
            f"Duplicate pair {dup_id}/{orig_id} not in opposite rows: y1={y_orig}, y2={y_dup}"
        )


def _rects_overlap(r1: dict, r2: dict) -> bool:
    # Overlap test with small epsilon
    eps = 0.01
    return not (
        r1["x"] + r1["w"] <= r2["x"] + eps
        or r2["x"] + r2["w"] <= r1["x"] + eps
        or r1["y"] + r1["h"] <= r2["y"] + eps
        or r2["y"] + r2["h"] <= r1["y"] + eps
    )


def test_no_patch_overlaps():
    spec = load_spec()
    patches = spec["patches"]
    for i in range(len(patches)):
        for j in range(i + 1, len(patches)):
            p1 = patches[i]
            p2 = patches[j]
            assert not _rects_overlap(p1["rect_mm"], p2["rect_mm"]), (
                f"Patches overlap: {p1['id']} and {p2['id']}"
            )


def test_no_overlap_with_window_or_markers():
    spec = load_spec()
    win_rect = spec["window"]["rect_mm"]
    markers = spec["markers"]["placements"]
    qr_clr = spec["qr"]["clear_rect_mm"]

    for p in spec["patches"]:
        pr = p["rect_mm"]
        assert not _rects_overlap(pr, win_rect), f"Patch {p['id']} overlaps skin window"
        assert not _rects_overlap(pr, qr_clr), f"Patch {p['id']} overlaps QR clear area"
        for m in markers:
            assert not _rects_overlap(pr, m["rect_mm"]), f"Patch {p['id']} overlaps marker {m['id']}"


def test_sampling_rectangles():
    spec = load_spec()
    canon = spec["canonical_image"]
    p_frac = canon["patch_inner_fraction"]
    w_frac = canon["window_inner_fraction"]

    # Check patch sampling rects
    for p in spec["patches"]:
        r = p["rect_px"]
        s = p["sample_px"]
        expected_w = int(round(r["w"] * p_frac))
        expected_h = int(round(r["h"] * p_frac))
        assert s["w"] == expected_w
        assert s["h"] == expected_h
        # Must be strictly within patch rect
        assert s["x"] >= r["x"]
        assert s["y"] >= r["y"]
        assert s["x"] + s["w"] <= r["x"] + r["w"]

    # Check window sampling rect
    wr = spec["window"]["rect_px"]
    ws = spec["window"]["sample_px"]
    assert ws["w"] == int(round(wr["w"] * w_frac))
    assert ws["h"] == int(round(wr["h"] * w_frac))
    assert ws["x"] >= wr["x"]
    assert ws["y"] >= wr["y"]
    assert ws["x"] + ws["w"] <= wr["x"] + wr["w"]


def test_markers_and_fiducials():
    spec = load_spec()
    m_info = spec["markers"]
    assert len(m_info["placements"]) == 4
    corners = {m["corner"]: m for m in m_info["placements"]}
    assert set(corners.keys()) == {"TL", "TR", "BR", "BL"}

    # IDs match 0, 1, 2, 3
    assert corners["TL"]["id"] == 0
    assert corners["TR"]["id"] == 1
    assert corners["BR"]["id"] == 2
    assert corners["BL"]["id"] == 3

    # Bitmap sanity check
    aruco = m_info["families"]["ARUCO_4X4"]
    assert aruco["modules_with_border"] == 6
    for i in range(4):
        rows = aruco["bitmaps"][str(i)]
        assert len(rows) == 6
        assert all(len(r) == 6 for r in rows)
        # Outermost border must be black ('0')
        assert rows[0] == "000000"
        assert rows[5] == "000000"
        assert all(r[0] == '0' and r[5] == '0' for r in rows)
