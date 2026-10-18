"""Generate Vision Test Fixtures for P08 Quality Gate.

Generates:
1. testdata/bad/ (All 10 quality failure modes)
   - bad_01_no_card.png (NO_CARD)
   - bad_02_card_angle.png (CARD_ANGLE)
   - bad_03_nonuniform_patches.png (NONUNIFORM_PATCHES)
   - bad_04_blur.png (BLUR)
   - bad_05_glare.png (GLARE)
   - bad_06_underexposed.png (UNDEREXPOSED)
   - bad_07_overexposed.png (OVEREXPOSED)
   - bad_08_uneven_light.png (UNEVEN_LIGHT)
   - bad_09_high_residual.png (HIGH_RESIDUAL)
   - bad_10_window_blue_cloth.png (WINDOW_NOT_SKIN)
   - bad_11_window_green_cloth.png (WINDOW_NOT_SKIN)
   - bad_12_window_empty_hole.png (WINDOW_NOT_SKIN)
   - bad_13_window_high_contrast.png (WINDOW_NOT_SKIN)
   - manifest.json and manifest.sha256

2. testdata/golden/vision/ (Valid captures that must pass quality gate >= 95%)
   - golden_01_nominal_scale1.0.png
   - golden_02_scale0.85.png
   - golden_03_scale1.15.png
   - golden_04_rot015.png
   - golden_05_rot090.png
   - golden_06_rot180.png
   - golden_07_rot270.png
   - golden_08_tilt10.png
   - golden_09_skin_jaundice.png
   - golden_10_nominal_aruco.png
   - manifest.json and manifest.sha256
"""

import hashlib
import json
import math
import os
from pathlib import Path
import cv2
import numpy as np

def load_spec():
    spec_path = Path("card/spec/card-v1.json")
    with open(spec_path, "r", encoding="utf-8") as f:
        return json.load(f)

def render_canonical_card(
    spec,
    marker_family="APRILTAG_36H11",
    window_content="SKIN_NORMAL",
    omit_marker_id=None,
    duplicate_mismatch_delta=0.0,
    exposure_multiplier=1.0,
    uneven_gradient=0.0,
    glare_radius=0,
    glare_center=None,
    scramble_patches=False
):
    cw = 1200
    ch = 760
    # White card background
    card = np.ones((ch, cw, 3), dtype=np.uint8) * 255

    # 1. Fiducial Markers
    markers_info = spec["markers"]
    family_data = markers_info["families"][marker_family]
    bitmaps = family_data["bitmaps"]
    grid_n = family_data["modules_with_border"]

    for m in markers_info["placements"]:
        mid = m["id"]
        if omit_marker_id is not None and mid == omit_marker_id:
            continue
        rect = m["rect_px"]
        mx, my, mw, mh = rect["x"], rect["y"], rect["w"], rect["h"]
        mod_size = mw / grid_n
        bitmap_rows = bitmaps[str(mid)]

        for r_idx, row_str in enumerate(bitmap_rows):
            for c_idx, ch_val in enumerate(row_str):
                color = (0, 0, 0) if ch_val == '0' else (255, 255, 255)
                rx1 = int(round(mx + c_idx * mod_size))
                ry1 = int(round(my + r_idx * mod_size))
                rx2 = int(round(mx + (c_idx + 1) * mod_size))
                ry2 = int(round(my + (r_idx + 1) * mod_size))
                cv2.rectangle(card, (rx1, ry1), (rx2, ry2), color, -1)

    # 2. 23 Color Patches
    for p in spec["patches"]:
        pid = p["id"]
        prect = p["rect_px"]
        px, py, pw, ph = prect["x"], prect["y"], prect["w"], prect["h"]
        lab = list(p["lab_d65"])

        if scramble_patches and pid.startswith("K"):
            # Alter chromaticity drastically to cause HIGH_RESIDUAL
            lab[1] = -lab[1]
            lab[2] = -lab[2]

        if duplicate_mismatch_delta > 0.0 and pid in ["G2D", "G4D", "K5D", "K8D"]:
            lab[0] += duplicate_mismatch_delta

        lab_pixel = np.uint8([[[lab[0] * 255.0 / 100.0, lab[1] + 128.0, lab[2] + 128.0]]])
        bgr = cv2.cvtColor(lab_pixel, cv2.COLOR_Lab2BGR)[0][0]
        cv2.rectangle(card, (px, py), (px + pw, py + ph), (int(bgr[0]), int(bgr[1]), int(bgr[2])), -1)

    # 3. Punched Skin Window
    win_rect = spec["window"]["rect_px"]
    wx, wy, ww, wh = win_rect["x"], win_rect["y"], win_rect["w"], win_rect["h"]

    if window_content == "SKIN_NORMAL":
        # Healthy newborn skin: L*=62, a*=14, b*=20 -> BGR ~ [114, 133, 184]
        lab_pixel = np.uint8([[[62.0 * 255.0 / 100.0, 14.0 + 128.0, 20.0 + 128.0]]])
        bgr = cv2.cvtColor(lab_pixel, cv2.COLOR_Lab2BGR)[0][0]
        cv2.rectangle(card, (wx, wy), (wx + ww, wy + wh), (int(bgr[0]), int(bgr[1]), int(bgr[2])), -1)
    elif window_content == "SKIN_JAUNDICE":
        # Jaundiced newborn skin: L*=66, a*=10, b*=32
        lab_pixel = np.uint8([[[66.0 * 255.0 / 100.0, 10.0 + 128.0, 32.0 + 128.0]]])
        bgr = cv2.cvtColor(lab_pixel, cv2.COLOR_Lab2BGR)[0][0]
        cv2.rectangle(card, (wx, wy), (wx + ww, wy + wh), (int(bgr[0]), int(bgr[1]), int(bgr[2])), -1)
    elif window_content == "CLOTH_BLUE":
        cv2.rectangle(card, (wx, wy), (wx + ww, wy + wh), (200, 80, 40), -1) # BGR blue
    elif window_content == "CLOTH_GREEN":
        cv2.rectangle(card, (wx, wy), (wx + ww, wy + wh), (50, 180, 30), -1) # BGR green
    elif window_content == "EMPTY_HOLE":
        cv2.rectangle(card, (wx, wy), (wx + ww, wy + wh), (15, 15, 15), -1)
    elif window_content == "HIGH_CONTRAST":
        cv2.rectangle(card, (wx, wy), (wx + ww // 2, wy + wh), (0, 0, 0), -1)
        cv2.rectangle(card, (wx + ww // 2, wy), (wx + ww, wy + wh), (255, 255, 255), -1)

    # 4. QR keepout area
    qr_rect = spec["qr"]["clear_rect_px"]
    qx, qy, qw, qh = qr_rect["x"], qr_rect["y"], qr_rect["w"], qr_rect["h"]
    cv2.rectangle(card, (qx, qy), (qx + qw, qy + qh), (255, 255, 255), -1)
    cv2.rectangle(card, (qx + 20, qy + 20), (qx + qw - 20, qy + qh - 20), (0, 0, 0), 2)

    # 5. Apply exposure multiplier & lighting gradient
    card_float = card.astype(np.float32) * exposure_multiplier
    if uneven_gradient != 0.0:
        grad = np.linspace(1.0 - uneven_gradient / 2.0, 1.0 + uneven_gradient / 2.0, cw).reshape(1, cw, 1)
        card_float *= grad
    card = np.clip(card_float, 0, 255).astype(np.uint8)

    # 6. Apply Glare Spot
    if glare_radius > 0:
        gx, gy = glare_center if glare_center else (wx + 80, wy + 80)
        cv2.circle(card, (gx, gy), glare_radius, (255, 255, 255), -1)

    return card

def render_camera_frame(
    card_img,
    scale=1.0,
    tilt_deg=0.0,
    rot_deg=0.0,
    blur_ksize=0,
    frame_size=(1600, 1200),
    exposure_multiplier=1.0
):
    fw, fh = frame_size
    cw, ch = card_img.shape[1], card_img.shape[0]

    # Neutral background scaled to scene exposure
    bg_val = int(np.clip(230 * exposure_multiplier, 10, 255))
    frame = np.ones((fh, fw, 3), dtype=np.uint8) * bg_val

    rad_tilt = math.radians(tilt_deg)
    rad_rot = math.radians(rot_deg)

    corners_3d = np.array([
        [-cw / 2, -ch / 2, 0],
        [ cw / 2, -ch / 2, 0],
        [ cw / 2,  ch / 2, 0],
        [-cw / 2,  ch / 2, 0],
    ], dtype=np.float32)

    corners_3d[:, :2] *= scale

    # Rotate X (tilt)
    cos_t, sin_t = math.cos(rad_tilt), math.sin(rad_tilt)
    rot_x = np.array([
        [1, 0, 0],
        [0, cos_t, -sin_t],
        [0, sin_t, cos_t]
    ], dtype=np.float32)
    corners_tilted = corners_3d @ rot_x.T

    # Rotate Z (in-plane)
    cos_r, sin_r = math.cos(rad_rot), math.sin(rad_rot)
    rot_z = np.array([
        [cos_r, -sin_r, 0],
        [sin_r, cos_r, 0],
        [0, 0, 1]
    ], dtype=np.float32)
    corners_rotated = corners_tilted @ rot_z.T

    # Perspective projection
    focal = 1800.0
    pts_src = np.array([
        [0, 0],
        [cw, 0],
        [cw, ch],
        [0, ch]
    ], dtype=np.float32)

    pts_dst = []
    for pt in corners_rotated:
        z = pt[2] + focal
        px = (pt[0] * focal) / z + fw / 2.0
        py = (pt[1] * focal) / z + fh / 2.0
        pts_dst.append([px, py])
    pts_dst = np.array(pts_dst, dtype=np.float32)

    M = cv2.getPerspectiveTransform(pts_src, pts_dst)
    warped = cv2.warpPerspective(card_img, M, (fw, fh), flags=cv2.INTER_LINEAR, borderValue=(bg_val, bg_val, bg_val))

    # Mask to place warped card onto background
    mask = cv2.warpPerspective(np.ones((ch, cw), dtype=np.uint8) * 255, M, (fw, fh), flags=cv2.INTER_NEAREST)
    frame[mask > 0] = warped[mask > 0]

    if blur_ksize > 1:
        if blur_ksize % 2 == 0:
            blur_ksize += 1
        frame = cv2.GaussianBlur(frame, (blur_ksize, blur_ksize), 0)

    return frame

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def generate_bad_set(spec):
    out_dir = Path("testdata/bad")
    out_dir.mkdir(parents=True, exist_ok=True)

    items = [
        {
            "filename": "bad_01_no_card.png",
            "expected_reasons": ["NO_CARD"],
            "description": "Empty background with no card present",
            "render": lambda: np.ones((1200, 1600, 3), dtype=np.uint8) * 230
        },
        {
            "filename": "bad_02_card_angle.png",
            "expected_reasons": ["CARD_ANGLE"],
            "description": "Card tilted at 25 degrees (exceeding 20 deg threshold)",
            "render": lambda: render_camera_frame(render_canonical_card(spec), tilt_deg=25.0)
        },
        {
            "filename": "bad_03_nonuniform_patches.png",
            "expected_reasons": ["NONUNIFORM_PATCHES"],
            "description": "Opposite corner duplicate patches differ by 8 Delta E",
            "render": lambda: render_camera_frame(render_canonical_card(spec, duplicate_mismatch_delta=8.0))
        },
        {
            "filename": "bad_04_blur.png",
            "expected_reasons": ["BLUR"],
            "description": "Motion/optical blur reducing Laplacian variance below threshold",
            "render": lambda: render_camera_frame(render_canonical_card(spec), blur_ksize=17)
        },
        {
            "filename": "bad_05_glare.png",
            "expected_reasons": ["GLARE"],
            "description": "Specular glare reflection covering > 2% of window",
            "render": lambda: render_camera_frame(render_canonical_card(spec, glare_radius=55))
        },
        {
            "filename": "bad_06_underexposed.png",
            "expected_reasons": ["UNDEREXPOSED"],
            "description": "Severe underexposure with mid-gray patch G4 luminance < 40",
            "render": lambda: render_camera_frame(render_canonical_card(spec, exposure_multiplier=0.25), exposure_multiplier=0.25)
        },
        {
            "filename": "bad_07_overexposed.png",
            "expected_reasons": ["OVEREXPOSED"],
            "description": "Severe overexposure with mid-gray patch G4 luminance > 215",
            "render": lambda: render_camera_frame(render_canonical_card(spec, exposure_multiplier=3.2), exposure_multiplier=3.2)
        },
        {
            "filename": "bad_08_uneven_light.png",
            "expected_reasons": ["UNEVEN_LIGHT"],
            "description": "Mixed/uneven lighting gradient across card > 2.0 Delta L*",
            "render": lambda: render_camera_frame(render_canonical_card(spec, uneven_gradient=0.75))
        },
        {
            "filename": "bad_09_high_residual.png",
            "expected_reasons": ["HIGH_RESIDUAL"],
            "description": "Scrambled patch reflectances yielding color correction residual Delta E > 3.0",
            "render": lambda: render_camera_frame(render_canonical_card(spec, scramble_patches=True))
        },
        {
            "filename": "bad_10_window_blue_cloth.png",
            "expected_reasons": ["WINDOW_NOT_SKIN"],
            "description": "Blue cloth in skin aperture (fails skin chromaticity check)",
            "render": lambda: render_camera_frame(render_canonical_card(spec, window_content="CLOTH_BLUE"))
        },
        {
            "filename": "bad_11_window_green_cloth.png",
            "expected_reasons": ["WINDOW_NOT_SKIN"],
            "description": "Green cloth in skin aperture (fails skin chromaticity check)",
            "render": lambda: render_camera_frame(render_canonical_card(spec, window_content="CLOTH_GREEN"))
        },
        {
            "filename": "bad_12_window_empty_hole.png",
            "expected_reasons": ["WINDOW_NOT_SKIN"],
            "description": "Empty punched window hole / dark void",
            "render": lambda: render_camera_frame(render_canonical_card(spec, window_content="EMPTY_HOLE"))
        },
        {
            "filename": "bad_13_window_high_contrast.png",
            "expected_reasons": ["WINDOW_NOT_SKIN"],
            "description": "High contrast edge (cloth/skin boundary) violating gradient limit",
            "render": lambda: render_camera_frame(render_canonical_card(spec, window_content="HIGH_CONTRAST"))
        }
    ]

    manifest = {"dataset": "bad", "count": len(items), "images": []}
    sha_lines = []

    for item in items:
        fn = item["filename"]
        img = item["render"]()
        fpath = out_dir / fn
        cv2.imwrite(str(fpath), img)
        sha = compute_sha256(fpath)
        sha_lines.append(f"{sha}  {fn}\n")
        manifest["images"].append({
            "filename": fn,
            "expected_reasons": item["expected_reasons"],
            "description": item["description"],
            "sha256": sha
        })
        print(f"Generated {fn}: {item['expected_reasons']}")

    with open(out_dir / "manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    with open(out_dir / "manifest.sha256", "w", encoding="utf-8") as f:
        f.writelines(sha_lines)

    print(f"Bad set generated in {out_dir} ({len(items)} images)")

def generate_golden_set(spec):
    out_dir = Path("testdata/golden/vision")
    out_dir.mkdir(parents=True, exist_ok=True)

    items = [
        {
            "filename": "golden_01_nominal_scale1.0.png",
            "description": "Nominal face-on capture, normal skin, 1.0x scale",
            "render": lambda: render_camera_frame(render_canonical_card(spec), scale=1.0)
        },
        {
            "filename": "golden_02_scale0.85.png",
            "description": "Slightly farther distance, scale 0.85x",
            "render": lambda: render_camera_frame(render_canonical_card(spec), scale=0.85)
        },
        {
            "filename": "golden_03_scale1.15.png",
            "description": "Closer distance, scale 1.15x",
            "render": lambda: render_camera_frame(render_canonical_card(spec), scale=1.15)
        },
        {
            "filename": "golden_04_rot015.png",
            "description": "Mild phone orientation tilt, 15 degrees in-plane rotation",
            "render": lambda: render_camera_frame(render_canonical_card(spec), rot_deg=15.0)
        },
        {
            "filename": "golden_05_rot090.png",
            "description": "Landscape 90 degrees phone rotation",
            "render": lambda: render_camera_frame(render_canonical_card(spec), rot_deg=90.0)
        },
        {
            "filename": "golden_06_rot180.png",
            "description": "Inverted 180 degrees phone rotation",
            "render": lambda: render_camera_frame(render_canonical_card(spec), rot_deg=180.0)
        },
        {
            "filename": "golden_07_rot270.png",
            "description": "Landscape 270 degrees phone rotation",
            "render": lambda: render_camera_frame(render_canonical_card(spec), rot_deg=270.0)
        },
        {
            "filename": "golden_08_tilt10.png",
            "description": "Mild 3D perspective tilt 10 degrees (within 20 deg limit)",
            "render": lambda: render_camera_frame(render_canonical_card(spec), tilt_deg=10.0)
        },
        {
            "filename": "golden_09_skin_jaundice.png",
            "description": "Jaundiced newborn skin window (L*=66, a*=10, b*=32)",
            "render": lambda: render_camera_frame(render_canonical_card(spec, window_content="SKIN_JAUNDICE"))
        },
        {
            "filename": "golden_10_nominal_aruco.png",
            "description": "Nominal card rendered with ArUco 4x4 marker family",
            "render": lambda: render_camera_frame(render_canonical_card(spec, marker_family="ARUCO_4X4"))
        }
    ]

    manifest = {"dataset": "golden_vision", "count": len(items), "images": []}
    sha_lines = []

    for item in items:
        fn = item["filename"]
        img = item["render"]()
        fpath = out_dir / fn
        cv2.imwrite(str(fpath), img)
        sha = compute_sha256(fpath)
        sha_lines.append(f"{sha}  {fn}\n")
        manifest["images"].append({
            "filename": fn,
            "expected_pass": True,
            "description": item["description"],
            "sha256": sha
        })
        print(f"Generated {fn}")

    with open(out_dir / "manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    with open(out_dir / "manifest.sha256", "w", encoding="utf-8") as f:
        f.writelines(sha_lines)

    print(f"Golden set generated in {out_dir} ({len(items)} images)")

if __name__ == "__main__":
    spec = load_spec()
    print("Generating bad set...")
    generate_bad_set(spec)
    print("\nGenerating golden set...")
    generate_golden_set(spec)
    print("\nTest data generation complete!")
