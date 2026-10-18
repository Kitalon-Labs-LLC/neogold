"""Spike Benchmark: OpenCV ArUco vs AprilTag Marker Detection.

Evaluates:
- Detection rate across 3 scales (0.75x, 1.00x, 1.25x) and 5 tilt angles (0°, 10°, 20°, 30°, 45°)
- Execution time per frame (ms)
- Rotation robustness (0°, 15°, 45°, 90°, 180°)
"""

import json
import math
import time
from pathlib import Path
import cv2
import numpy as np

def load_spec():
    spec_path = Path("card/spec/card-v1.json")
    with open(spec_path, "r", encoding="utf-8") as f:
        return json.load(f)

def render_canonical_card(spec, marker_family="ARUCO_4X4"):
    """Render canonical 1200x760 px card image."""
    w = 1200
    h = 760
    # White background
    img = np.ones((h, w, 3), dtype=np.uint8) * 255

    # Safe margin guide
    cv2.rectangle(img, (30, 30), (1170, 730), (240, 240, 240), 1)

    # 1. Markers
    markers_info = spec["markers"]
    family_data = markers_info["families"][marker_family]
    bitmaps = family_data["bitmaps"]
    grid_n = family_data["modules_with_border"]

    for m in markers_info["placements"]:
        mid = str(m["id"])
        rect = m["rect_px"]
        mx, my, mw, mh = rect["x"], rect["y"], rect["w"], rect["h"]
        mod_size = mw / grid_n
        bitmap_rows = bitmaps[mid]
        # White background for marker
        cv2.rectangle(img, (mx, my), (mx + mw, my + mh), (255, 255, 255), -1)
        for r_idx, row_str in enumerate(bitmap_rows):
            for c_idx, ch in enumerate(row_str):
                if ch == '0': # Black module
                    rx1 = int(round(mx + c_idx * mod_size))
                    ry1 = int(round(my + r_idx * mod_size))
                    rx2 = int(round(mx + (c_idx + 1) * mod_size))
                    ry2 = int(round(my + (r_idx + 1) * mod_size))
                    cv2.rectangle(img, (rx1, ry1), (rx2, ry2), (0, 0, 0), -1)

    # 2. Skin Window
    win_rect = spec["window"]["rect_px"]
    wx, wy, ww, wh = win_rect["x"], win_rect["y"], win_rect["w"], win_rect["h"]
    # Simulated skin tone (L*=60, a*=14, b*=20 in sRGB ~ [184, 133, 114] BGR)
    cv2.rectangle(img, (wx, wy), (wx + ww, wy + wh), (114, 133, 184), -1)

    # 3. Color Patches
    for p in spec["patches"]:
        prect = p["rect_px"]
        px, py, pw, ph = prect["x"], prect["y"], prect["w"], prect["h"]
        lab = p["lab_d65"]
        # Convert lab to sRGB
        lab_pixel = np.uint8([[[lab[0] * 255.0 / 100.0, lab[1] + 128.0, lab[2] + 128.0]]])
        bgr = cv2.cvtColor(lab_pixel, cv2.COLOR_Lab2BGR)[0][0]
        cv2.rectangle(img, (px, py), (px + pw, py + ph), (int(bgr[0]), int(bgr[1]), int(bgr[2])), -1)

    # 4. QR placeholder
    qr_rect = spec["qr"]["clear_rect_px"]
    qx, qy, qw, qh = qr_rect["x"], qr_rect["y"], qr_rect["w"], qr_rect["h"]
    cv2.rectangle(img, (qx, qy), (qx + qw, qy + qh), (255, 255, 255), -1)
    # Simple QR finder patterns simulation
    cv2.rectangle(img, (qx + 20, qy + 20), (qx + qw - 20, qy + qh - 20), (0, 0, 0), 2)

    return img

def create_perspective_frame(card_img, scale=1.0, tilt_deg=0.0, rot_deg=0.0, frame_size=(1600, 1200)):
    """Render the card transformed by scale, 3D tilt, and in-plane rotation into a frame."""
    fw, fh = frame_size
    cw, ch = card_img.shape[1], card_img.shape[0]

    # Destination frame (neutral background)
    frame = np.ones((fh, fw, 3), dtype=np.uint8) * 230

    # 3D transformation
    rad_tilt = math.radians(tilt_deg)
    rad_rot = math.radians(rot_deg)

    # Card corners in card coordinate system centered at (0,0)
    corners_3d = np.array([
        [-cw / 2, -ch / 2, 0],
        [ cw / 2, -ch / 2, 0],
        [ cw / 2,  ch / 2, 0],
        [-cw / 2,  ch / 2, 0],
    ], dtype=np.float32)

    # Scale
    corners_3d[:, :2] *= scale

    # Rotate around X-axis (tilt/pitch)
    cos_t, sin_t = math.cos(rad_tilt), math.sin(rad_tilt)
    rot_x = np.array([
        [1,      0,       0],
        [0,  cos_t,  -sin_t],
        [0,  sin_t,   cos_t]
    ], dtype=np.float32)

    # Rotate around Z-axis (in-plane rotation)
    cos_r, sin_r = math.cos(rad_rot), math.sin(rad_rot)
    rot_z = np.array([
        [ cos_r, -sin_r, 0],
        [ sin_r,  cos_r, 0],
        [     0,      0, 1]
    ], dtype=np.float32)

    # Combined rotation
    R = rot_z @ rot_x
    rotated = (R @ corners_3d.T).T

    # Camera perspective projection (virtual camera at distance d)
    d = 1800.0 # focal distance
    proj_corners = []
    for pt in rotated:
        z = pt[2] + d
        x = (pt[0] * d) / z + fw / 2
        y = (pt[1] * d) / z + fh / 2
        proj_corners.append([x, y])
    proj_corners = np.array(proj_corners, dtype=np.float32)

    # Source corners on card
    src_corners = np.array([
        [0, 0],
        [cw, 0],
        [cw, ch],
        [0, ch]
    ], dtype=np.float32)

    H = cv2.getPerspectiveTransform(src_corners, proj_corners)
    warped_card = cv2.warpPerspective(card_img, H, (fw, fh), borderValue=(230, 230, 230))

    # Mask to place warped card onto frame
    mask = cv2.warpPerspective(np.ones((ch, cw), dtype=np.uint8) * 255, H, (fw, fh), borderValue=0)
    frame[mask > 128] = warped_card[mask > 128]
    return frame

def benchmark_variant(dict_type, marker_family, spec, scales, angles, rotations, num_trials=10):
    """Benchmark marker detection for a given dictionary across scales, angles, rotations."""
    card_img = render_canonical_card(spec, marker_family)
    dictionary = cv2.aruco.getPredefinedDictionary(dict_type)
    params = cv2.aruco.DetectorParameters()
    detector = cv2.aruco.ArucoDetector(dictionary, params)

    results = []

    for s in scales:
        for a in angles:
            for r in rotations:
                frame = create_perspective_frame(card_img, scale=s, tilt_deg=a, rot_deg=r)
                gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

                # Warmup
                detector.detectMarkers(gray)

                # Time multiple trials
                times = []
                detected_counts = []
                detected_ids = []

                for _ in range(num_trials):
                    t0 = time.perf_counter()
                    corners, ids, rejected = detector.detectMarkers(gray)
                    t1 = time.perf_counter()
                    times.append((t1 - t0) * 1000.0)

                detected_count = len(ids) if ids is not None else 0
                found_all_4 = False
                if ids is not None and len(ids) >= 4:
                    id_set = set(ids.flatten().tolist())
                    if {0, 1, 2, 3}.issubset(id_set):
                        found_all_4 = True

                median_time = np.median(times)
                mean_time = np.mean(times)

                results.append({
                    "scale": s,
                    "tilt_deg": a,
                    "rot_deg": r,
                    "detected_count": detected_count,
                    "all_4_detected": found_all_4,
                    "median_time_ms": median_time,
                    "mean_time_ms": mean_time
                })

    return results

def main():
    spec = load_spec()

    scales = [0.75, 1.00, 1.25]
    angles = [0.0, 10.0, 20.0, 30.0, 45.0]
    rotations = [0.0] # Primary grid is 3 scales x 5 angles

    print("Running Benchmark for Variant A: OpenCV ArUco (DICT_4X4_50)...")
    res_aruco = benchmark_variant(cv2.aruco.DICT_4X4_50, "ARUCO_4X4", spec, scales, angles, rotations)

    print("Running Benchmark for Variant B: AprilTag (DICT_APRILTAG_36h11)...")
    res_april = benchmark_variant(cv2.aruco.DICT_APRILTAG_36h11, "APRILTAG_36H11", spec, scales, angles, rotations)

    # Print summary table
    print("\n" + "="*85)
    print(f"{'Scale':<7} | {'Angle':<7} | {'ArUco Detected':<15} | {'ArUco Time (ms)':<16} | {'AprilTag Det':<13} | {'AprilTag Time':<13}")
    print("="*85)

    for i in range(len(res_aruco)):
        ra = res_aruco[i]
        rb = res_april[i]
        s_str = f"{ra['scale']:.2f}x"
        a_str = f"{ra['tilt_deg']:.0f}°"
        da_str = f"{ra['detected_count']}/4 ({'PASS' if ra['all_4_detected'] else 'FAIL'})"
        ta_str = f"{ra['median_time_ms']:.2f} ms"
        db_str = f"{rb['detected_count']}/4 ({'PASS' if rb['all_4_detected'] else 'FAIL'})"
        tb_str = f"{rb['median_time_ms']:.2f} ms"
        print(f"{s_str:<7} | {a_str:<7} | {da_str:<15} | {ta_str:<16} | {db_str:<13} | {tb_str:<13}")

    print("="*85)

    # Summary statistics
    aruco_pass_count = sum(1 for r in res_aruco if r["all_4_detected"])
    april_pass_count = sum(1 for r in res_april if r["all_4_detected"])
    total_cases = len(res_aruco)

    aruco_mean_time = np.mean([r["median_time_ms"] for r in res_aruco])
    april_mean_time = np.mean([r["median_time_ms"] for r in res_april])

    print(f"\nSummary:")
    print(f"ArUco 4x4   : {aruco_pass_count}/{total_cases} detected ({aruco_pass_count/total_cases*100:.1f}%), Avg Time: {aruco_mean_time:.2f} ms")
    print(f"AprilTag 36h: {april_pass_count}/{total_cases} detected ({april_pass_count/total_cases*100:.1f}%), Avg Time: {april_mean_time:.2f} ms")

    # In-plane rotation stress test
    print("\n" + "="*85)
    print("In-plane Rotation Stress Test (Scale 1.0x, Tilt 10°):")
    rot_tests = [0.0, 15.0, 45.0, 90.0, 180.0]
    res_aruco_rot = benchmark_variant(cv2.aruco.DICT_4X4_50, "ARUCO_4X4", spec, [1.0], [10.0], rot_tests)
    res_april_rot = benchmark_variant(cv2.aruco.DICT_APRILTAG_36h11, "APRILTAG_36H11", spec, [1.0], [10.0], rot_tests)
    for i in range(len(rot_tests)):
        ra = res_aruco_rot[i]
        rb = res_april_rot[i]
        print(f"Rotation {ra['rot_deg']:>3.0f}°: ArUco: {ra['detected_count']}/4 ({ra['median_time_ms']:.2f} ms) | AprilTag: {rb['detected_count']}/4 ({rb['median_time_ms']:.2f} ms)")

if __name__ == "__main__":
    main()
