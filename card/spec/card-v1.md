# NeoGold Calibration Card v1 Specification

| Field | Value |
|---|---|
| Document | Card v1 Physical Specification |
| Version | 1.0.0-draft |
| Status | DRAFT — Awaiting P02 Review Gate |
| Date | 2026-10-17 |
| Authors | Kitalon Labs / NeoGold Project |
| Machine-readable companion | `card/spec/card-v1.json` |
| License | Creative Commons Attribution 4.0 International (CC BY 4.0) |

---

## 1. Physical Dimensions and Ergonomics

### 1.1 Card Dimensions
- **Width:** 120.0 mm
- **Height:** 76.0 mm
- **Corner radius:** 3.0 mm (die-cut rounded corners to avoid scratching infant skin)
- **Bleed allowance:** 3.0 mm on all exterior edges for printing (overall artwork bounding box: 126.0 × 82.0 mm)
- **Safe margin:** 3.0 mm keepout from trimmed card edge to any active visual element (markers, patches, text)

### 1.2 Clinical & Field Ergonomics (Newborn Chest & Smartphone Frame)
- **Chest sizing:** A healthy full-term or late preterm infant (gestational age $\ge 35$ weeks) has a chest width between 85 mm and 120 mm. A 120 × 76 mm card rests comfortably across or along the sternum when placed flat on the infant's chest during supine examination.
- **Working distance:** 15 cm to 25 cm (typical handheld smartphone capture distance).
- **Camera field of view (FOV):** At 20 cm distance with a standard smartphone main lens (approx. 24–28 mm full-frame equivalent, horizontal FOV ~65°–75°), the camera field spans approximately 240–280 mm wide. The 120 mm card occupies **43% to 50% of the image frame width**, leaving ample border for the infant's surrounding skin, framing guides, and hand grip without occluding corner markers.
- **Scale factor:** At exactly **10.0 pixels per millimetre**, the 120 × 76 mm card maps directly to the **1200 × 760 px** canonical perspective-corrected image specified in `docs/4_System_Architecture.md` Section 5.2.

### 1.3 Material and Finish
- **Substrate:** 300 to 400 $\mu\text{m}$ thick bright-white matte synthetic stock (e.g., Teslin or Polypropylene synthetic paper) or heavy coated card stock ($350\text{ g/m}^2$). Synthetic paper is preferred for moisture resistance and tear resistance in humid clinic environments.
- **Lamination:** Double-sided **dead matte** thermal lamination film (approx. 30–50 $\mu\text{m}$ per side). 
  - **Zero specular reflection:** Glossy or satin laminations produce specular glare spots that fail the in-app quality gate (`GLARE` reason code).
  - **Wipeable & hygienic:** The matte laminate allows cleaning with 70% isopropyl alcohol wipes between infants without ink bleeding.
- **Distribution sleeve:** Each card is packaged in an opaque, sunlight-protective paper/foil sleeve to prevent UV-induced pigment fading (`TC-CARD-08`).

---

## 2. Punched Skin Window

### 2.1 Geometry and Position
- **Shape:** Square window with rounded corners.
- **Dimensions:** 24.0 mm × 24.0 mm.
- **Corner radius:** 2.0 mm die-cut.
- **Position:** 
  - $X = 36.0\text{ mm}$ (from left edge)
  - $Y = 26.0\text{ mm}$ (from top edge)
- **Manufacturing:** The window is **physically punched out (die-cut)** through both the card stock and the laminate, creating an open aperture.

### 2.2 Clinical Rationale
When the card is placed against the infant's sternum, the newborn's skin protrudes slightly through the 24 × 24 mm punched window. This ensures:
1. The infant's skin is illuminated by the **exact same incident ambient light field** as the surrounding color calibration patches.
2. The skin is co-planar with the reference patches, eliminating depth-of-field differences and perspective distortions.
3. No transparent plastic or glass covers the skin, avoiding Fresnel reflections, color tinting, or skin blanching.

### 2.3 Sampling Region (Canonical Image)
- **Full window rectangle (px):** $X = 360$, $Y = 260$, $W = 240$, $H = 240\text{ px}$.
- **Inner sampling rectangle (70% inner linear region):**
  - Margin: 15% on each side ($36\text{ px}$).
  - $X = 396$, $Y = 296$, $W = 168$, $H = 168\text{ px}$ ($16.8 \times 16.8\text{ mm}$ on the skin).
  - Trimming: Inner 70% eliminates card edge shadow, skin-card contact boundary artifacts, and adhesive bleed.

---

## 3. Corner Fiducial Markers

Four square fiducial markers are located at the card corners for robust automated detection, perspective transformation, and corner identification.

### 3.1 Marker Placements and IDs
All markers are $16.0 \times 16.0\text{ mm}$, positioned $3.0\text{ mm}$ from the card edges (3.0 mm safe margin):

| Corner | ID | Rect ($X, Y$) mm | Rect ($W, H$) mm | Canonical Pixels ($X, Y, W, H$) |
|---|---|---|---|---|
| **Top-Left (TL)** | 0 | (3.0, 3.0) | (16.0, 16.0) | (30, 30, 160, 160) |
| **Top-Right (TR)** | 1 | (101.0, 3.0) | (16.0, 16.0) | (1010, 30, 160, 160) |
| **Bottom-Right (BR)** | 2 | (101.0, 57.0) | (16.0, 16.0) | (1010, 570, 160, 160) |
| **Bottom-Left (BL)** | 3 | (3.0, 57.0) | (16.0, 16.0) | (30, 570, 160, 160) |

### 3.2 Marker Family Options and Size Impact

Both marker options occupy the exact same physical footprint ($16.0 \times 16.0\text{ mm}$) and positions:

| Feature | ArUco 4x4 (DICT_4X4_50) | AprilTag 36h11 (tag36h11) |
|---|---|---|
| **Internal grid** | $4 \times 4$ bits | $6 \times 6$ bits |
| **Border bits** | 1 black border module | 1 black border + white quiet zone |
| **Total modules per side** | 6 modules ($6 \times 6$) | 8 modules ($8 \times 8$) |
| **Physical module width** | **$2.67\text{ mm}$** ($16\text{ mm} / 6$) | **$2.00\text{ mm}$** ($16\text{ mm} / 8$) |
| **Pixel size at 20 cm ($1080\text{p}$)** | Approx. $13.3\text{ px/module}$ | Approx. $10.0\text{ px/module}$ |
| **Detection robustness** | Larger modules; easier to resolve under slight motion blur on low-end cameras. | Higher Hamming distance (11 vs 3); lower false positive rate, slightly finer modules. |
| **C/C++ Library size** | OpenCV `objdetect` module (~1.8–3.5 MB depending on strip). | Standalone C library (`apriltag`) ~150–250 KB compiled. |
| **Layout impact** | **Zero impact** on card layout; both fit $16 \times 16\text{ mm}$. | **Zero impact** on card layout; both fit $16 \times 16\text{ mm}$. |

**Decision & Status:** Per ADR-003 and `docs/4_System_Architecture.md` Section 16, the card artwork v1 is committed with **ArUco 4x4 (IDs 0, 1, 2, 3)**. The final binary engine choice between OpenCV ArUco and standalone AprilTag will be measured against the 25 MB APK download budget in work package P08.

### 3.3 Quiet Zone and Keepout
- A minimum keepout zone of **$3.0\text{ mm}$** of pure white background surrounds every marker on all interior sides. No color patch, line, or text enters this zone.

---

## 4. Calibration Patch Set

The card includes **23 color patches**, each measuring **$9.0 \times 9.0\text{ mm}$**, arranged around the central skin window and QR code.

### 4.1 Patch Set Composition
1. **Neutral gray ramp (7 steps):** $L^*$ values from 90 down to 18 ($a^* = 0, b^* = 0$). Used for camera response linearization and tone curve modeling (`G1` to `G7`).
2. **Skin and yellow tones (9 steps):** Spanning light to dark skin tones ($L^* = 72, 56, 40$) across low, medium, and high yellowness ($b^* = 12$ to $32$). Targeted specifically for newborn jaundice estimation (`K1` to `K9`).
3. **Saturated chromatic references (3 steps):** High-chroma Yellow (`X1`, bilirubin hue axis), Red (`X2`), and Blue (`X3`, negative $b^*$ axis) to anchor the color gamut boundary.
4. **Duplicate patches in opposite corners (4 patches):**
   - Gray duplicates: `G2D` (duplicate of `G2`, $L^*=77$) and `G4D` (duplicate of `G4$, $L^*=51$).
   - Skin duplicates: `K5D` (duplicate of `K5`, medium skin) and `K8D` (duplicate of `K8`, dark skin).
   - Diagonally separated to detect uneven illumination, shadow gradients, and smartphone local tone mapping (HDR).

### 4.2 Comprehensive Patch Table

All coordinates are in millimetres on the physical card. The nominal CIELAB targets refer to standard illuminant **D65** and the **CIE $2^\circ$ standard observer**.

| Index | Patch ID | Group | Description | Nominal $L^*$ | Nominal $a^*$ | Nominal $b^*$ | $X$ (mm) | $Y$ (mm) | Canonical Px ($X, Y, W, H$) | Inner 60% Sample Px ($X, Y, W, H$) |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | `G1` | GRAY | Gray ramp step 1 (lightest) | 90.0 | 0.0 | 0.0 | 45.0 | 3.0 | (450, 30, 90, 90) | (468, 48, 54, 54) |
| 1 | `G2` | GRAY | Gray ramp step 2 | 77.0 | 0.0 | 0.0 | 34.5 | 3.0 | (345, 30, 90, 90) | (363, 48, 54, 54) |
| 2 | `G3` | GRAY | Gray ramp step 3 | 64.0 | 0.0 | 0.0 | 55.5 | 3.0 | (555, 30, 90, 90) | (573, 48, 54, 54) |
| 3 | `G4` | GRAY | Gray ramp step 4 (mid-gray reference) | 51.0 | 0.0 | 0.0 | 24.0 | 3.0 | (240, 30, 90, 90) | (258, 48, 54, 54) |
| 4 | `G5` | GRAY | Gray ramp step 5 | 39.0 | 0.0 | 0.0 | 66.0 | 3.0 | (660, 30, 90, 90) | (678, 48, 54, 54) |
| 5 | `G6` | GRAY | Gray ramp step 6 | 28.0 | 0.0 | 0.0 | 45.0 | 64.0 | (450, 640, 90, 90) | (468, 658, 54, 54) |
| 6 | `G7` | GRAY | Gray ramp step 7 (darkest) | 18.0 | 0.0 | 0.0 | 55.5 | 64.0 | (555, 640, 90, 90) | (573, 658, 54, 54) |
| 7 | `K1` | SKIN | Light skin, low yellowness | 72.0 | 10.0 | 12.0 | 24.0 | 23.0 | (240, 230, 90, 90) | (258, 248, 54, 54) |
| 8 | `K2` | SKIN | Light skin, mid yellowness | 72.0 | 8.0 | 20.0 | 104.5 | 23.0 | (1045, 230, 90, 90) | (1063, 248, 54, 54) |
| 9 | `K3` | SKIN | Light skin, high yellowness | 72.0 | 5.0 | 30.0 | 6.5 | 23.0 | (65, 230, 90, 90) | (83, 248, 54, 54) |
| 10 | `K4` | SKIN | Medium skin, low yellowness | 56.0 | 12.0 | 14.0 | 24.0 | 33.5 | (240, 335, 90, 90) | (258, 353, 54, 54) |
| 11 | `K5` | SKIN | Medium skin, mid yellowness | 56.0 | 10.0 | 22.0 | 87.0 | 3.0 | (870, 30, 90, 90) | (888, 48, 54, 54) |
| 12 | `K6` | SKIN | Medium skin, high yellowness | 56.0 | 6.0 | 32.0 | 6.5 | 33.5 | (65, 335, 90, 90) | (83, 353, 54, 54) |
| 13 | `K7` | SKIN | Dark skin, low yellowness | 40.0 | 12.0 | 12.0 | 24.0 | 44.0 | (240, 440, 90, 90) | (258, 458, 54, 54) |
| 14 | `K8` | SKIN | Dark skin, mid yellowness | 40.0 | 9.0 | 20.0 | 76.5 | 3.0 | (765, 30, 90, 90) | (783, 48, 54, 54) |
| 15 | `K9` | SKIN | Dark skin, high yellowness | 40.0 | 6.0 | 27.0 | 6.5 | 44.0 | (65, 440, 90, 90) | (83, 458, 54, 54) |
| 16 | `X1` | SATURATED | Saturated yellow (bilirubin axis) | 80.0 | 2.0 | 70.0 | 104.5 | 33.5 | (1045, 335, 90, 90) | (1063, 353, 54, 54) |
| 17 | `X2` | SATURATED | Saturated red | 45.0 | 52.0 | 35.0 | 66.0 | 64.0 | (660, 640, 90, 90) | (678, 658, 54, 54) |
| 18 | `X3` | SATURATED | Saturated blue (negative $b^*$ axis) | 35.0 | 8.0 | -42.0 | 104.5 | 44.0 | (1045, 440, 90, 90) | (1063, 458, 54, 54) |
| 19 | `G2D` | GRAY | Duplicate of G2 ($L^*=77$) | 77.0 | 0.0 | 0.0 | 76.5 | 64.0 | (765, 640, 90, 90) | (783, 658, 54, 54) |
| 20 | `G4D` | GRAY | Duplicate of G4 ($L^*=51$) | 51.0 | 0.0 | 0.0 | 87.0 | 64.0 | (870, 640, 90, 90) | (888, 658, 54, 54) |
| 21 | `K5D` | SKIN | Duplicate of K5 (med skin, mid yellow) | 56.0 | 10.0 | 22.0 | 24.0 | 64.0 | (240, 640, 90, 90) | (258, 658, 54, 54) |
| 22 | `K8D` | SKIN | Duplicate of K8 (dark skin, mid yellow) | 40.0 | 9.0 | 20.0 | 34.5 | 64.0 | (345, 640, 90, 90) | (363, 658, 54, 54) |

### 4.3 Sampling Geometry in Canonical Image
- Patch size in canonical image: $90 \times 90\text{ px}$.
- Inner 60% linear region: $54 \times 54\text{ px}$ centered in each patch ($18\text{ px}$ margin on all 4 sides).
- **Trimming algorithm:** 
  1. The vision engine extracts the $54 \times 54\text{ px}$ inner region.
  2. The brightest 10% and darkest 10% of pixel values are discarded (trimmed mean).
  3. The mean of the remaining 80% represents the measured RGB value. This completely rejects edge bleed, minor surface scratches, and specular micro-highlights.

---

## 5. Calibration QR Code

### 5.1 QR Position and Geometry
- **Center coordinates:** $X = 81.25\text{ mm}$, $Y = 38.0\text{ mm}$.
- **Version:** QR Code **Version 12** ($65 \times 65$ modules).
- **Error Correction Level:** **M** (approx. 15% recovery capacity).
- **Encoding Mode:** Byte Mode (8-bit binary).
- **Module size:** **$0.50\text{ mm} \times 0.50\text{ mm}$**.
- **Symbol dimensions:** $32.5\text{ mm} \times 32.5\text{ mm}$ ($65 \text{ modules} \times 0.5\text{ mm}$).
- **Quiet zone:** 4 modules ($2.0\text{ mm}$) on all four sides.
- **Clear keepout area:** $36.5\text{ mm} \times 36.5\text{ mm}$ centered at $(81.25, 38.0)\text{ mm}$.

### 5.2 Human-Readable Batch Text
Directly below the QR code, a human-readable text line is printed:
- **Position:** $X = 63.0\text{ mm}$, $Y = 57.5\text{ mm}$, width $36.5\text{ mm}$, height $3.0\text{ mm}$.
- **Font:** OCR-B or monospaced sans-serif, 6 pt, black ink.
- **Format:** `<batch_id>  EXP <YYYY-MM>` (e.g., `NG-2026-001  EXP 2028-10`).
- **Purpose:** Allows field workers to visually check card batch identity and expiry without needing a phone.

---

## 6. Manufacturing, Print, and Batch Acceptance Tolerances

The production of NeoGold cards follows the centralized batch-verification strategy defined in PRD Section 9a. Because every card batch carries its **actual measured spectrophotometer readings** in the signed QR code, slight global variations between print batches do not introduce calibration error—the phone corrects against the *measured* values, not theoretical values. However, print consistency within a batch is strictly enforced.

### 6.1 Batch Acceptance Criteria (Tested by `TC-CARD-10`)
Before a batch is released and signed with `neogold-sign`, sample cards are drawn across the print run:
- **Sample size:** At least 5 cards, or 2% of the batch (whichever is larger), sampled evenly across the print run.
- **Measurement tool:** Benchtop or handheld reflectance spectrophotometer (45°/0° or d/8° sphere geometry, specular component excluded, calibrated with a white ceramic reference tile).
- **Color difference metric:** **CIE Delta E 2000 ($\Delta E_{00}$)** under D65 illuminant, $2^\circ$ standard observer.

| Test / Check | Threshold | Rationale |
|---|---|---|
| **Nominal Color Target ($\Delta E_{00}$)** | Gray ramp: $\le 3.0$<br/>Skin/yellow: $\le 3.0$<br/>Saturated references: $\le 5.0$ | Ensures patches stay within the trained dynamic range of the estimation engine. |
| **Duplicate Pair Uniformity** | $\le 1.5\ \Delta E_{00}$ between duplicate pairs (`G2`/`G2D`, `G4`/`G4D`, `K5`/`K5D`, `K8`/`K8D`) on the same card. | Essential for detecting smartphone local tone mapping and camera non-uniformity. |
| **Inter-Card Batch Spread** | $\le 1.5\ \Delta E_{00}$ maximum deviation across all sampled cards for any patch. | Guarantees that the batch average measured values in the QR represent all cards in the batch. |
| **Neutrality of Gray Ramp** | Chroma $C^* = \sqrt{a^{*2} + b^{*2}} \le 3.0$ on all gray steps (`G1`–`G7`). | Prevents tinting of the linearization ramp. |

### 6.2 Physical & Print Tolerances
- **Registration tolerance:** Patch and marker placement within **$\pm 0.3\text{ mm}$** of nominal coordinates.
- **Die-cut tolerance:** Exterior card border and punched window within **$\pm 0.3\text{ mm}$**.
- **Marker edge sharpness:** No ink bleed exceeding $0.05\text{ mm}$ at marker boundaries.
- **Lamination haze:** Dead matte finish, 60° gloss meter reading **$< 5\text{ GU}$** (Gloss Units).

---

## 7. Canonical Warped Image Reference

When the card is detected by the Android `:engine:vision` module:
1. Four marker centers are identified.
2. Perspective homography warps the card surface to an exact **$1200 \times 760\text{ px}$** canvas ($10.0\text{ px/mm}$).
3. The coordinate system origin $(0, 0)$ is at the top-left corner of the trimmed card.

```
(0,0) +-------------------------------------------------------------+ (1200,0)
      | [M0: TL]   G4  G2  G1  G3  G5  K8  K5              [M1: TR] |
      |                                                             |
      |   K3      +--------------------+      +-----------------+   |
      |   K6      |   PUNCHED SKIN     |      |  CALIBRATION QR |   |
      |   K9      |      WINDOW        |      |     CODE        |   |
      |           | (360,260,240,240)  |      | (650,217,325,325|   |
      |   K1      +--------------------+      +-----------------+   |
      |   K4                                    BATCH TEXT LINE     |
      |   K7      K5D K8D G6  G7  X2  G2D G4D                       |
      | [M3: BL]                                           [M2: BR] |
(0,760)+-------------------------------------------------------------+ (1200,760)
```
