"""NeoGold Card v1 SVG and Print-Ready PDF Generator.

Reads card geometry, patch targets, and fiducials from card/spec/card-v1.json
and renders:
1. card/generator/out/card-v1.svg
2. card/generator/out/card-v1.pdf

Standard library only, no external dependencies, no network access.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import sys
from pathlib import Path

# Add generator directory to path for qrenc
GEN_DIR = Path(__file__).resolve().parent
if str(GEN_DIR) not in sys.path:
    sys.path.insert(0, str(GEN_DIR))

import qrenc


def lab_to_srgb(L: float, a: float, b: float) -> tuple[int, int, int, str]:
    """Convert CIELAB (D65/2°) to sRGB integer components [0, 255] and hex string."""
    Xn, Yn, Zn = 0.95047, 1.00000, 1.08883
    fy = (L + 16.0) / 116.0
    fx = fy + a / 500.0
    fz = fy - b / 200.0

    def finv(t: float) -> float:
        return t**3 if t > (6.0 / 29.0) else (3.0 * (6.0 / 29.0) ** 2 * (t - 4.0 / 29.0))

    X = Xn * finv(fx)
    Y = Yn * finv(fy)
    Z = Zn * finv(fz)

    # sRGB D65 matrix
    r_l = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z
    g_l = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z
    b_l = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z

    def gamma(c: float) -> float:
        c = max(0.0, min(1.0, c))
        return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1.0 / 2.4)) - 0.055

    r = int(round(gamma(r_l) * 255.0))
    g = int(round(gamma(g_l) * 255.0))
    b_val = int(round(gamma(b_l) * 255.0))
    hex_str = f"#{r:02x}{g:02x}{b_val:02x}"
    return r, g, b_val, hex_str


def get_qr_matrix(spec: dict) -> list[list[bool]]:
    """Generate QR code module matrix for the card.
    Uses the valid card batch payload test vector if available,
    otherwise encodes a valid placeholder payload.
    """
    test_vector = Path("card/spec/testvectors/card_batch_valid.json")
    if test_vector.exists():
        try:
            with open(test_vector, "r", encoding="utf-8") as f:
                data = json.load(f)
                payload_bytes = bytes.fromhex(data["payload_hex"])
                code = qrenc.encode_binary(payload_bytes, ecl="M", version=12)
                return code.modules
        except Exception:
            pass

    # Fallback placeholder payload (Version 12-M)
    dummy_payload = b"\x01\x01\x09\xaf\x0c\x8a" + b"TEST-CARD-KEY-01" + b"NG-2026-001" + (b"\x00" * 200)
    code = qrenc.encode_binary(dummy_payload, ecl="M", version=12)
    return code.modules


def render_svg(spec: dict, qr_modules: list[list[bool]], out_path: Path) -> None:
    """Render the card layout as an SVG document."""
    card = spec["card"]
    w_mm = card["width_mm"]
    h_mm = card["height_mm"]
    r_mm = card["corner_radius_mm"]

    lines = []
    lines.append('<?xml version="1.0" encoding="UTF-8"?>')
    lines.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w_mm}mm" height="{h_mm}mm" viewBox="0 0 {w_mm} {h_mm}">')
    lines.append('  <title>NeoGold Calibration Card v1</title>')
    lines.append('  <desc>Precision neonatal color calibration chart with 23 patches, ArUco corner fiducials, and central skin window.</desc>')
    lines.append('  <defs>')
    lines.append('    <style>')
    lines.append('      .label { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; font-size: 2.0px; font-weight: bold; }')
    lines.append('      .text-line { font-family: "Courier New", Courier, monospace; font-size: 2.4px; font-weight: bold; fill: #000000; }')
    lines.append('      .sublabel { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; font-size: 1.4px; fill: #666666; }')
    lines.append('      .window-guide { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; font-size: 1.8px; font-weight: 600; fill: #c62828; text-anchor: middle; }')
    lines.append('    </style>')
    lines.append('  </defs>')

    # 1. Card Base Background
    lines.append(f'  <!-- Card Base -->')
    lines.append(f'  <rect x="0" y="0" width="{w_mm}" height="{h_mm}" rx="{r_mm}" ry="{r_mm}" fill="#ffffff" stroke="#bbbbbb" stroke-width="0.3"/>')

    # Safe margin guideline (subtle)
    sm = card.get("safe_margin_mm", 3.0)
    lines.append(f'  <!-- Safe Margin Guide (3mm) -->')
    lines.append(f'  <rect x="{sm}" y="{sm}" width="{w_mm - 2*sm}" height="{h_mm - 2*sm}" fill="none" stroke="#e0e0e0" stroke-width="0.1" stroke-dasharray="0.8,0.8"/>')

    # 2. Corner Fiducial Markers (ArUco 4x4)
    markers_info = spec["markers"]
    active_family = markers_info.get("artwork_family", "ARUCO_4X4")
    family_data = markers_info["families"][active_family]
    bitmaps = family_data["bitmaps"]
    grid_n = family_data["modules_with_border"]  # 6 for ArUco 4x4

    lines.append(f'  <!-- Corner Fiducial Markers ({active_family}) -->')
    for m in markers_info["placements"]:
        mid = str(m["id"])
        rect = m["rect_mm"]
        mx, my, mw, mh = rect["x"], rect["y"], rect["w"], rect["h"]
        mod_size = mw / grid_n
        bitmap_rows = bitmaps[mid]

        lines.append(f'  <g id="marker_{mid}_{m["corner"]}">')
        # Background quiet box
        lines.append(f'    <rect x="{mx}" y="{my}" width="{mw}" height="{mh}" fill="#ffffff"/>')
        # Draw modules from bitmap
        for r_idx, row_str in enumerate(bitmap_rows):
            for c_idx, ch in enumerate(row_str):
                # '0' is black, '1' is white
                if ch == '0':
                    mod_x = mx + c_idx * mod_size
                    mod_y = my + r_idx * mod_size
                    lines.append(f'    <rect x="{mod_x:.3f}" y="{mod_y:.3f}" width="{mod_size:.3f}" height="{mod_size:.3f}" fill="#000000"/>')
        lines.append(f'  </g>')

    # 3. Punched Skin Window
    win = spec["window"]
    w_rect = win["rect_mm"]
    wx, wy, ww, wh = w_rect["x"], w_rect["y"], w_rect["w"], w_rect["h"]
    wr = win.get("corner_radius_mm", 2.0)
    lines.append(f'  <!-- Punched Skin Window (Aperture) -->')
    lines.append(f'  <g id="skin_window">')
    # Neutral background fill to show aperture
    lines.append(f'    <rect x="{wx}" y="{wy}" width="{ww}" height="{wh}" rx="{wr}" ry="{wr}" fill="#f8fafc" stroke="#dc2626" stroke-width="0.35" stroke-dasharray="1.2,1.2"/>')
    # Sampling guide (70% inner region)
    sp_frac = spec["canonical_image"].get("window_inner_fraction", 0.7)
    sww = ww * sp_frac
    swh = wh * sp_frac
    swx = wx + (ww - sww) / 2
    swy = wy + (wh - swh) / 2
    lines.append(f'    <rect x="{swx:.2f}" y="{swy:.2f}" width="{sww:.2f}" height="{swh:.2f}" fill="none" stroke="#fca5a5" stroke-width="0.15" stroke-dasharray="0.8,0.8"/>')
    lines.append(f'    <text x="{wx + ww/2:.2f}" y="{wy + wh/2 - 2:.2f}" class="window-guide">SKIN WINDOW</text>')
    lines.append(f'    <text x="{wx + ww/2:.2f}" y="{wy + wh/2 + 1.5:.2f}" class="window-guide" font-size="1.4px">(PUNCHED OUT)</text>')
    lines.append(f'    <text x="{wx + ww/2:.2f}" y="{wy + wh/2 + 4.5:.2f}" class="sublabel" text-anchor="middle">24 × 24 mm</text>')
    lines.append(f'  </g>')

    # 4. Color Patches (23 patches)
    lines.append(f'  <!-- Color Calibration Patches (23 Patches) -->')
    for p in spec["patches"]:
        pid = p["id"]
        prect = p["rect_mm"]
        px, py, pw, ph = prect["x"], prect["y"], prect["w"], prect["h"]
        lab = p["lab_d65"]
        r_val, g_val, b_val, hex_color = lab_to_srgb(lab[0], lab[1], lab[2])

        # Text color contrast
        luminance = (0.299 * r_val + 0.587 * g_val + 0.114 * b_val) / 255.0
        text_color = "#ffffff" if luminance < 0.45 else "#000000"

        lines.append(f'  <g id="patch_{pid}">')
        lines.append(f'    <rect x="{px}" y="{py}" width="{pw}" height="{ph}" fill="{hex_color}" stroke="#333333" stroke-width="0.1"/>')
        # Inner 60% sampling box guideline (subtle indicator)
        ip_frac = spec["canonical_image"].get("patch_inner_fraction", 0.6)
        ipw = pw * ip_frac
        iph = ph * ip_frac
        ipx = px + (pw - ipw) / 2
        ipy = py + (ph - iph) / 2
        lines.append(f'    <rect x="{ipx:.2f}" y="{ipy:.2f}" width="{ipw:.2f}" height="{iph:.2f}" fill="none" stroke="{text_color}" stroke-width="0.1" opacity="0.25"/>')
        # Patch label text
        lines.append(f'    <text x="{px + pw/2:.2f}" y="{py + ph/2 + 0.7:.2f}" class="label" fill="{text_color}" text-anchor="middle">{pid}</text>')
        lines.append(f'  </g>')

    # 5. Calibration QR Code
    qr_info = spec["qr"]
    qr_rect = qr_info["symbol_rect_mm"]
    qx, qy, qw, qh = qr_rect["x"], qr_rect["y"], qr_rect["w"], qr_rect["h"]
    clr_rect = qr_info["clear_rect_mm"]
    cx, cy, cw, ch = clr_rect["x"], clr_rect["y"], clr_rect["w"], clr_rect["h"]

    lines.append(f'  <!-- Calibration QR Code (Version 12-M, 65x65 modules) -->')
    lines.append(f'  <g id="calibration_qr">')
    # Quiet zone background
    lines.append(f'    <rect x="{cx:.3f}" y="{cy:.3f}" width="{cw:.3f}" height="{ch:.3f}" fill="#ffffff"/>')

    # Draw QR modules
    num_mods = len(qr_modules)
    mod_size = qw / num_mods
    for r_idx in range(num_mods):
        for c_idx in range(num_mods):
            if qr_modules[r_idx][c_idx]:
                mx = qx + c_idx * mod_size
                my = qy + r_idx * mod_size
                lines.append(f'    <rect x="{mx:.3f}" y="{my:.3f}" width="{mod_size:.3f}" height="{mod_size:.3f}" fill="#000000"/>')
    lines.append(f'  </g>')

    # 6. Human-Readable Text Line
    t_line = spec["text_line"]
    tx = t_line["rect_mm"]["x"]
    ty = t_line["rect_mm"]["y"]
    tw = t_line["rect_mm"]["w"]
    th = t_line["rect_mm"]["h"]
    sample_text = t_line.get("placeholder", "NG-2026-001  EXP 2028-10")
    lines.append(f'  <!-- Human-Readable Batch ID & Expiry -->')
    lines.append(f'  <text x="{tx + tw/2:.2f}" y="{ty + th/2 + 0.9:.2f}" class="text-line" text-anchor="middle">{sample_text}</text>')

    # 7. Card Branding / Metadata
    lines.append(f'  <!-- NeoGold Branding -->')
    lines.append(f'  <text x="60.0" y="73.5" class="sublabel" text-anchor="middle" font-weight="500">NeoGold Card v1 • Calibration Reference • CC BY 4.0</text>')

    lines.append('</svg>')

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Generated SVG: {out_path} ({out_path.stat().st_size} bytes)")


class PdfBuilder:
    """Minimal PDF 1.4 vector generator (stdlib only)."""

    def __init__(self, width_pt: float, height_pt: float):
        self.width_pt = width_pt
        self.height_pt = height_pt
        self.objects: list[bytes] = []
        self.stream_ops: list[str] = []

    def _add_object(self, data: bytes) -> int:
        self.objects.append(data)
        return len(self.objects)

    def set_fill_rgb(self, r: float, g: float, b: float) -> None:
        self.stream_ops.append(f"{r:.4f} {g:.4f} {b:.4f} rg")

    def set_stroke_rgb(self, r: float, g: float, b: float) -> None:
        self.stream_ops.append(f"{r:.4f} {g:.4f} {b:.4f} RG")

    def set_line_width(self, w_pt: float) -> None:
        self.stream_ops.append(f"{w_pt:.4f} w")

    def set_dash(self, array: list[float], phase: float = 0.0) -> None:
        if not array:
            self.stream_ops.append("[] 0 d")
        else:
            arr_str = " ".join(f"{x:.2f}" for x in array)
            self.stream_ops.append(f"[{arr_str}] {phase:.2f} d")

    def rect(self, x_pt: float, y_top_pt: float, w_pt: float, h_pt: float, fill: bool = True, stroke: bool = False) -> None:
        # PDF origin is bottom-left
        y_bottom_pt = self.height_pt - (y_top_pt + h_pt)
        op = "B" if (fill and stroke) else ("f" if fill else "s")
        self.stream_ops.append(f"{x_pt:.3f} {y_bottom_pt:.3f} {w_pt:.3f} {h_pt:.3f} re {op}")

    def text(self, text_str: str, x_pt: float, y_top_pt: float, font_size_pt: float, center: bool = False) -> None:
        y_bottom_pt = self.height_pt - y_top_pt
        # Escape parenthesis
        escaped = text_str.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        self.stream_ops.append("BT")
        self.stream_ops.append(f"/F1 {font_size_pt:.2f} Tf")
        if center:
            # Approximate Helvetica character width ~0.55 * font_size
            est_width = len(text_str) * font_size_pt * 0.52
            x_pt = x_pt - est_width / 2.0
        self.stream_ops.append(f"{x_pt:.3f} {y_bottom_pt:.3f} Td")
        self.stream_ops.append(f"({escaped}) Tj")
        self.stream_ops.append("ET")

    def build(self) -> bytes:
        content_stream = "\n".join(self.stream_ops).encode("latin1")
        # Obj 1: Catalog
        # Obj 2: Pages
        # Obj 3: Page
        # Obj 4: Content Stream
        # Obj 5: Font F1
        cat = b"<< /Type /Catalog /Pages 2 0 R >>"
        pages = b"<< /Type /Pages /Kids [ 3 0 R ] /Count 1 >>"
        page = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 {self.width_pt:.2f} {self.height_pt:.2f} ] "
            f"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
        ).encode("latin1")
        c_stream = f"<< /Length {len(content_stream)} >>\nstream\n".encode("latin1") + content_stream + b"\nendstream"
        font = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

        objs = [cat, pages, page, c_stream, font]

        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        for i, obj_data in enumerate(objs, 1):
            offsets.append(len(out))
            out.extend(f"{i} 0 obj\n".encode("latin1"))
            out.extend(obj_data)
            out.extend(b"\nendobj\n")

        xref_pos = len(out)
        out.extend(f"xref\n0 {len(objs) + 1}\n".encode("latin1"))
        out.extend(b"0000000000 65535 f \n")
        for off in offsets:
            out.extend(f"{off:010d} 00000 n \n".encode("latin1"))

        trailer = (
            f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_pos}\n%%EOF\n"
        ).encode("latin1")
        out.extend(trailer)
        return bytes(out)


def render_pdf(spec: dict, qr_modules: list[list[bool]], out_path: Path) -> None:
    """Render the card layout as a print-ready vector PDF document."""
    PT_PER_MM = 72.0 / 25.4
    card = spec["card"]
    w_mm = card["width_mm"]
    h_mm = card["height_mm"]
    w_pt = w_mm * PT_PER_MM
    h_pt = h_mm * PT_PER_MM

    pdf = PdfBuilder(w_pt, h_pt)

    # 1. Base card outline & background
    pdf.set_fill_rgb(1.0, 1.0, 1.0)
    pdf.set_stroke_rgb(0.7, 0.7, 0.7)
    pdf.set_line_width(0.3 * PT_PER_MM)
    pdf.rect(0, 0, w_pt, h_pt, fill=True, stroke=True)

    # Safe margin
    sm = card.get("safe_margin_mm", 3.0) * PT_PER_MM
    pdf.set_dash([1.0, 1.0])
    pdf.set_stroke_rgb(0.85, 0.85, 0.85)
    pdf.rect(sm, sm, w_pt - 2 * sm, h_pt - 2 * sm, fill=False, stroke=True)
    pdf.set_dash([])

    # 2. Fiducial Markers
    markers_info = spec["markers"]
    active_family = markers_info.get("artwork_family", "ARUCO_4X4")
    family_data = markers_info["families"][active_family]
    bitmaps = family_data["bitmaps"]
    grid_n = family_data["modules_with_border"]

    for m in markers_info["placements"]:
        mid = str(m["id"])
        rect = m["rect_mm"]
        mx = rect["x"] * PT_PER_MM
        my = rect["y"] * PT_PER_MM
        mw = rect["w"] * PT_PER_MM
        mh = rect["h"] * PT_PER_MM
        mod_size = mw / grid_n
        bitmap_rows = bitmaps[mid]

        for r_idx, row_str in enumerate(bitmap_rows):
            for c_idx, ch in enumerate(row_str):
                if ch == '0':
                    pdf.set_fill_rgb(0.0, 0.0, 0.0)
                    pdf.rect(mx + c_idx * mod_size, my + r_idx * mod_size, mod_size, mod_size, fill=True, stroke=False)

    # 3. Punched Skin Window
    win = spec["window"]
    w_rect = win["rect_mm"]
    wx = w_rect["x"] * PT_PER_MM
    wy = w_rect["y"] * PT_PER_MM
    ww = w_rect["w"] * PT_PER_MM
    wh = w_rect["h"] * PT_PER_MM

    # Aperture preview
    pdf.set_fill_rgb(0.96, 0.98, 1.0)
    pdf.set_stroke_rgb(0.85, 0.2, 0.2)
    pdf.set_line_width(0.3 * PT_PER_MM)
    pdf.set_dash([2.0, 2.0])
    pdf.rect(wx, wy, ww, wh, fill=True, stroke=True)
    pdf.set_dash([])

    pdf.set_fill_rgb(0.8, 0.1, 0.1)
    pdf.text("SKIN WINDOW (PUNCHED)", wx + ww / 2, wy + wh / 2 - 2 * PT_PER_MM, 5.0, center=True)
    pdf.set_fill_rgb(0.4, 0.4, 0.4)
    pdf.text("24 x 24 mm", wx + ww / 2, wy + wh / 2 + 5 * PT_PER_MM, 4.0, center=True)

    # 4. Color Patches
    for p in spec["patches"]:
        pid = p["id"]
        prect = p["rect_mm"]
        px = prect["x"] * PT_PER_MM
        py = prect["y"] * PT_PER_MM
        pw = prect["w"] * PT_PER_MM
        ph = prect["h"] * PT_PER_MM
        lab = p["lab_d65"]
        r_val, g_val, b_val, _ = lab_to_srgb(lab[0], lab[1], lab[2])

        pdf.set_fill_rgb(r_val / 255.0, g_val / 255.0, b_val / 255.0)
        pdf.set_stroke_rgb(0.2, 0.2, 0.2)
        pdf.set_line_width(0.1 * PT_PER_MM)
        pdf.rect(px, py, pw, ph, fill=True, stroke=True)

        luminance = (0.299 * r_val + 0.587 * g_val + 0.114 * b_val) / 255.0
        if luminance < 0.45:
            pdf.set_fill_rgb(1.0, 1.0, 1.0)
        else:
            pdf.set_fill_rgb(0.0, 0.0, 0.0)
        pdf.text(pid, px + pw / 2, py + ph / 2 + 1.5 * PT_PER_MM, 4.5, center=True)

    # 5. QR Code
    qr_info = spec["qr"]
    qr_rect = qr_info["symbol_rect_mm"]
    qx = qr_rect["x"] * PT_PER_MM
    qy = qr_rect["y"] * PT_PER_MM
    qw = qr_rect["w"] * PT_PER_MM
    qh = qr_rect["h"] * PT_PER_MM

    clr_rect = qr_info["clear_rect_mm"]
    cx = clr_rect["x"] * PT_PER_MM
    cy = clr_rect["y"] * PT_PER_MM
    cw = clr_rect["w"] * PT_PER_MM
    ch = clr_rect["h"] * PT_PER_MM

    pdf.set_fill_rgb(1.0, 1.0, 1.0)
    pdf.rect(cx, cy, cw, ch, fill=True, stroke=False)

    num_mods = len(qr_modules)
    mod_size = qw / num_mods
    pdf.set_fill_rgb(0.0, 0.0, 0.0)
    for r_idx in range(num_mods):
        for c_idx in range(num_mods):
            if qr_modules[r_idx][c_idx]:
                mx = qx + c_idx * mod_size
                my = qy + r_idx * mod_size
                pdf.rect(mx, my, mod_size, mod_size, fill=True, stroke=False)

    # 6. Text line
    t_line = spec["text_line"]
    tx = t_line["rect_mm"]["x"] * PT_PER_MM
    ty = t_line["rect_mm"]["y"] * PT_PER_MM
    tw = t_line["rect_mm"]["w"] * PT_PER_MM
    sample_text = t_line.get("placeholder", "NG-2026-001  EXP 2028-10")
    pdf.set_fill_rgb(0.0, 0.0, 0.0)
    pdf.text(sample_text, tx + tw / 2, ty + 2.0 * PT_PER_MM, 6.0, center=True)

    # 7. Card footer
    pdf.set_fill_rgb(0.4, 0.4, 0.4)
    pdf.text("NeoGold Card v1 - Calibration Reference - CC BY 4.0", w_pt / 2, h_pt - 2.5 * PT_PER_MM, 4.0, center=True)

    pdf_bytes = pdf.build()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(pdf_bytes)
    print(f"Generated PDF: {out_path} ({len(pdf_bytes)} bytes)")


def main():
    parser = argparse.ArgumentParser(description="Render NeoGold Card v1 to SVG and print-ready PDF.")
    parser.add_argument("--spec", type=Path, default=Path("card/spec/card-v1.json"), help="Path to card-v1.json specification")
    parser.add_argument("--out-dir", type=Path, default=Path("card/generator/out"), help="Output directory")
    args = parser.parse_args()

    if not args.spec.exists():
        print(f"Error: Spec file not found: {args.spec}", file=sys.stderr)
        sys.exit(1)

    with open(args.spec, "r", encoding="utf-8") as f:
        spec = json.load(f)

    print("Generating calibration QR code matrix...")
    qr_matrix = get_qr_matrix(spec)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    svg_path = args.out_dir / "card-v1.svg"
    pdf_path = args.out_dir / "card-v1.pdf"

    print("Rendering SVG...")
    render_svg(spec, qr_matrix, svg_path)

    print("Rendering PDF...")
    render_pdf(spec, qr_matrix, pdf_path)

    print("All renders completed successfully.")


if __name__ == "__main__":
    main()
