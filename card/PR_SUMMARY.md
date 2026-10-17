# NeoGold Card v1 — Print Partner Specification Summary

**Project:** NeoGold Open-Source Neonatal Jaundice Screening Tool  
**Document:** Technical Print & Die-Cut Specification Sheet (One-Page Summary)  
**Date:** October 2026 | **Version:** 1.0.0  
**License:** CC BY 4.0 (Card Design) | Kitalon Labs / NeoGold Project  

---

### 1. Physical Dimensions & Finishing
- **Finished Trim Size:** **120.0 mm × 76.0 mm** (landscape orientation).
- **Corner Radius:** **3.0 mm** die-cut rounded corners on all four external corners (prevents scratching newborn skin).
- **Bleed Allowance:** **3.0 mm** on all outer edges (total printed sheet size: 126.0 mm × 82.0 mm).
- **Safe Margin:** **3.0 mm** keepout from the trim line to any active element.
- **Punched Skin Window (Aperture):**
  - **Size:** **24.0 mm × 24.0 mm** square aperture with **2.0 mm** rounded corners.
  - **Position:** Top-left corner of window is at **$X = 36.0\text{ mm}$, $Y = 26.0\text{ mm}$** from trimmed card edges.
  - **Process:** Must be physically punched out (die-cut completely through card and lamination). The aperture must be completely open and free of burrs or hanging shreds.

### 2. Substrate & Matte Lamination
- **Stock:** High-grade bright-white matte synthetic stock (polypropylene / Teslin, 300–350 $\mu\text{m}$) or coated paperboard ($350\text{ g/m}^2$). Synthetic stock is strongly preferred for tear and moisture resistance.
- **Finished Thickness:** **350 to 420 $\mu\text{m}$** (including double-sided lamination).
- **Lamination:** **Double-sided DEAD MATTE thermal lamination film** ($30\text{ to }50\ \mu\text{m}$ per side).
  - **CRITICAL:** Strictly **zero gloss / zero sheen** ($60^\circ$ gloss meter reading **$< 5\text{ GU}$**). Glossy or satin laminations produce camera glare spots that invalidate newborn screening photos.
  - **Cleanability:** Must withstand repeated wipe-down with hospital-grade 70% isopropyl alcohol disinfectant wipes without ink dissolution or delamination.

### 3. Printing, Resolution & Content
- **Print Technology:** Calibrated 4-color or 6-color digital press or pigment inkjet, minimum **1200 dpi** effective resolution.
- **Color Targets (23 Patches, $9.0\text{ mm} \times 9.0\text{ mm}$ each):**
  - **Neutral Gray Ramp (7 steps):** Lightness $L^* = 90, 77, 64, 51, 39, 28, 18$ ($a^* = 0, b^* = 0$). Must be completely neutral (Chroma $C^* \le 3.0$).
  - **Skin & Yellow Tones (9 steps):** Targeted newborn skin tones spanning light to dark ($L^* = 72, 56, 40$) with varying yellowness ($b^* = 12\text{ to }32$).
  - **Saturated Color References (3 steps):** Yellow (bilirubin axis), Red, and Blue.
  - **Duplicate Diagnostic Patches (4 patches):** Duplicate gray and skin patches placed diagonally in opposite corners (`G2`/`G2D`, `G4`/`G4D`, `K5`/`K5D`, `K8`/`K8D`).
- **Corner Fiducial Markers (4 positions):**
  - Size: **$16.0\text{ mm} \times 16.0\text{ mm}$** square black-and-white fiducial markers (ArUco 4x4, IDs 0, 1, 2, 3) placed $3.0\text{ mm}$ from corners.
  - Ink Density: Deep solid black ($K = 100\%$, optical density $D > 1.6$). Edge sharpness within $\pm 0.05\text{ mm}$ (no dot gain blurring the modules).
- **Calibration QR Code:**
  - Size: **$32.5\text{ mm} \times 32.5\text{ mm}$** ($65 \times 65$ modules, $0.50\text{ mm/module}$), surrounded by a **$2.0\text{ mm}$ white quiet zone** (overall clear area: $36.5\text{ mm} \times 36.5\text{ mm}$).
  - Position: Centered at **$X = 81.25\text{ mm}$, $Y = 38.0\text{ mm}$**.
- **Human-Readable Text Line:**
  - Located at $X = 63.0\text{ mm}$, $Y = 57.5\text{ mm}$ (width 36.5 mm, height 3.0 mm), 6 pt monospaced font: `<batch_id>  EXP <YYYY-MM>`.

### 4. Quality Control & Batch Acceptance Tolerances (`TC-CARD-10`)
Sample cards will be tested with a calibrated benchtop reflectance spectrophotometer under standard CIE illuminant D65 and $2^\circ$ standard observer:
1. **Nominal Target Color Difference:** Mean $\Delta E_{00} \le 3.0$ for gray and skin patches; $\le 5.0$ for saturated patches.
2. **Duplicate Pair Uniformity on Single Card:** $\Delta E_{00} \le 1.5$ between duplicate pairs on opposite corners.
3. **Inter-Card Print Run Consistency:** Maximum deviation $\Delta E_{00} \le 1.5$ across all sampled cards for any patch across the entire print run.
4. **Registration & Die-Cut Accuracy:** All elements and apertures positioned within $\mathbf{\pm 0.3\text{ mm}}$ of design coordinates.

### 5. Packaging & Handling
- Each finished card must be packed in an opaque, light-blocking paper or foil sleeve to protect sensitive pigments against ambient ultraviolet (UV) light degradation during transit and storage.
- Storage condition: Clean, dry, room temperature ($15^\circ\text{C}$ to $30^\circ\text{C}$), non-condensing.
