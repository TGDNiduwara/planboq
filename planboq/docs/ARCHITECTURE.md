# PlanBOQ architecture (Phase 1 MVP)

```
                ┌──────────────────────────── Android app (Kotlin, Compose) ───────────────────────────┐
                │                                                                                      │
 DXF ──────► DxfParser ──┐                                                                             │
 Vector PDF ──► VectorPdfExtractor ──►  PlanDocument (internal geometry, JSON on disk)  ──► Editor     │
 Photo ──► PlanImageProcessor ──┘             │                                        │   │           │
                │  (OpenCV: warp→clean→LSD/Hough→LinePostProcessor)                    │   └─► Export   │
                │                                                                      │        PDF/DXF │
                │  Settings (DataStore) ── server URL/key (optional)                   │                │
                └──────────────────────────────┬───────────────────────────────────────┴────────────────┘
                                               │ PNG of cleaned plan (only when configured)
                                               ▼
                        ┌── Oracle ARM server: FastAPI + OpenCV (+ ezdxf/reportlab exports) ──┐
                        │  POST /api/v1/process/image  → segments (same px space as device)    │
                        │  POST /api/v1/export/{dxf,pdf}                                       │
                        └──────────────────────────────────────────────────────────────────────┘
```

## Core rule

One internal format — `PlanDocument` (`data/geometry/Geometry.kt`). Every
importer converts INTO it; every exporter reads FROM it. Phase 2 tagging and
Phase 3 BOQ generation attach to the same model (layers already carry a
`LayerKind` category; quantities will key off that enum).

Coordinates are plan units:
- DXF import: real-world millimetres (converted from `$INSUNITS`), scale 1:1.
- Vector PDF import: millimetres on paper; user calibrates (e.g. 1:100).
- Photo trace: pixels of the corrected image; user calibrates with two taps
  and a known length, then verifies with a second dimension (`CalibrateScaleUseCase`).

`ScaleState.realMmPerPlanUnit` converts plan units to real mm everywhere.

## Processing strategy (device-first, server-optional)

The photo pipeline runs fully on-device (OpenCV 4.11 AAR). If an API key is
set in Settings, the *cleaned binary image* (not the photo) is uploaded to the
processing server, so server and device results share one pixel space and the
editor is identical either way. Any server failure falls back to device
results (the UI reports which path was used) — the app works offline on site.

- **Default server URL**: `https://api.corecivil.engineer` is baked into
  `SettingsRepository.DEFAULT_SERVER_URL`; it stays editable in Settings.
- **API key**: entered manually in Settings (kept in DataStore, never in the
  APK). The Settings screen has a **Test connection** button that probes
  `GET /api/v1/health` and then validates the key against
  `POST /api/v1/export/dxf` with an empty plan — no extra server route needed.
- **Public-launch follow-up**: manual keys don't scale to Play users. Before
  launch, replace with per-device tokens issued by the server after verifying
  the Play subscription (server endpoint + Play Developer API), or have the
  app call the API key-free with Play purchase-token validation.

## Smart capture and clean trace (v2)

- `PageDetector` finds the photographed sheet as the largest convex quad
  (Canny → dilate → contours → approxPolyDP, min-area-rect fallback). The
  Confirm-page screen pre-places the 4 handles; the user only nudges them.
- Trace pipeline (`PlanImageProcessor`): warp → illumination flatten (divide by
  blurred) → adaptive threshold → **small text-blob masking** → **deskew**
  (Hough angle median) → LSD/Hough → collinear merge → **wall-corner joining**
  (`WallJoiner`). The corrected image is saved as the review underlay.
- Review mode in the editor shows the corrected image at 25% opacity beneath
  the vectors; the user confirms before generating the BOQ.

## Rooms

`RoomDetector` rasterises **wall bands** (centreline + measured thickness), not
the two thin face lines, bridges collinear door/window gaps with virtual wall
segments, seals T-junction gaps with a morphological close, then flood-fills
the enclosed regions. Labels/areas render live in the editor; the detector
re-runs automatically once the scale is calibrated (photo/PDF imports).

## BOQ (v2)

`boq/` package:
- `WallCenterlines` pairs parallel wall faces (40–350 mm apart) into
  centreline + measured thickness — double-line walls are never double-counted.
  The drawing itself is untouched.
- `QuantityEngine` computes (generic metric): brickwork volume, plaster/paint
  (both faces, opening deductions), flooring/ceiling from rooms, skirting,
  and MANUAL structural lines (footings/columns/beams/steel). Every item is
  tagged MEASURED / ASSUMED / MANUAL.
- `BoqScreen` edits quantities/rates, merges on recalculation (keeps rates and
  manual edits), and exports `.xlsx` (built-in OOXML writer) or PDF.
- Openings are counted by clustering tagged DOOR/WINDOW elements so a door
  leaf plus its arc, or the glazing lines of one window, count once.

## Measurement editing, rates and combined BOQs (v3)

- `Room.excluded` removes a room's floor area from quantities while it stays on
  the drawing; `Layer.thicknessMm` overrides the wall thickness used by
  `WallCenterlines` (and therefore by BOQ and room detection);
  `Segment.openingTypeId` links an opening to an `OpeningType` family.
- The editor's Door/Window tools draw tagged opening segments; the BOQ engine
  counts typed openings one-to-one and clusters untyped ones (leaf + arc, or
  glazing lines) so each physical opening counts once.
- `RateEntry` stores a material + labour + overhead build-up or a direct rate;
  `RateRepository` persists it globally (`files/rates.json`). `BoqRates` applies
  library rates to items whose project rate is still zero; manual rates win.
- `CombinedBoq` sums item quantities by code across projects (Ground/First
  floors) and takes rates from the first project that has one.
- Deliverables: `PdfExporter` prints a title block, room names/areas, a colour
  legend and scale bar; `BoqExporter` prints a project header, room schedule,
  category subtotals, contingency and grand total.

## Readiness, guided setup, standards, sync (v4)

- `BoqReadiness` scores how submittable a BOQ is: BLOCKING gaps (uncalibrated
  scale, no rooms, no walls) cannot be accepted; IMPORTANT assumptions
  (storey/slab/thickness defaults, missing rates, structural data) can be
  reviewed; OPTIONAL gaps (project info, default opening types) are advisory.
  Accepted gap ids persist in `BoqDocument.acceptedGaps` and still print in the
  assumptions annex.
- `BoqSetupScreen` is the data-collection workflow: plan blockers with direct
  actions (calibrate / detect rooms), assumption confirmation, opening
  families, structural data, rate gaps and project information. Saving marks
  reviewed assumptions, rebuilds items and re-evaluates readiness.
- `Standards` maps a `MeasurementStandard` to output units, default currency,
  brickwork basis (volume vs area) and item wording; `QuantityEngine` converts
  one metric measurement into the selected standard (metric m²/m³/m/kg or
  imperial sf/cy/ft/ton).
- `StructuralData` feeds structural estimates (footing/column/beam/slab
  volumes and steel at kg/m³), all tagged ASSUMED with the formula in the
  item detail. Merge rules only keep a MANUAL line when it has content, so
  engine placeholders never block new estimates.
- Deliverables: `XlsxWriter` is multi-sheet (Cover, Room Schedule, BOQ,
  Assumptions); `BoqExporter.exportPdf` adds item codes, a basis/assumptions
  section, readiness statement and Prepared/Checked/Approved lines.
- Cloud sync: `server/main.py` exposes `/api/v1/sync/push|list|project|delete`
  keyed by SHA-256 of a user-held sync code plus the API key;
  `SyncRepository` backs up/restores plan JSON, BOQ JSON and the review image,
  with newer-updatedAt winning.

## Monetisation

- Free: 3 conversions/month (`FreeTier.MONTHLY_LIMIT`), watermarked exports.
- Pro: auto-renewing subscription via Play Billing 9 (`planboq_pro_monthly` /
  `planboq_pro_yearly`), no watermark, unlimited conversions.
- Entitlement = active purchase from `queryPurchasesAsync` OR the debug-only
  override switch (hidden in release builds — tests the paywall without Play
  Console).

## Known limitations (documented, deliberate)

- DXF: ASCII files, LINE/LWPOLYLINE/POLYLINE/CIRCLE/ARC only; bulges read as
  chords; no INSERT/block expansion yet (common in survey files — later).
- PDF: page 1 only, vector paths only (text and raster ignored).
- LSD vs Hough: LSD is used when the OpenCV build exposes it; otherwise
  HoughLinesP. Either way the user cleans up in the editor — semi-automatic
  by design, never presented as fully automatic.
- Exports (PDF and DXF) always include the scale bar and the provenance
  disclaimer; photo-traced documents always carry
  "Traced from image. Verify before construction."

## Phase 2+ hooks

- `LayerKind` (EXTERNAL_WALL, DOOR, WINDOW, ...) is the tagging target: the
  editor's layer sheet already assigns kinds; Phase 2 adds per-kind settings
  (heights, thicknesses) and runs lengths/areas/counts off `PlanDocument`.
- Server has export endpoints (`export/dxf|pdf`) mirroring the device writers
  for bulk/CLI use (ezdxf).
