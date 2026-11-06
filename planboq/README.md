# PlanBOQ

Android app that turns floor plans (DXF, vector PDF, photos) into clean vector
drawings — with scale calibration, a vector editor and PDF/DXF export. Phase 1
MVP per the concept doc; tagging and BOQ generation come in Phases 2-3.

```
planboq/
├── android/    Android Studio project (Kotlin 2.1, Compose, minSdk 26, targetSdk 36)
├── server/     Optional FastAPI processing server for Oracle ARM (Docker)
└── docs/       Play Store checklist, privacy policy template, architecture
```

## Build the app

1. Install **Android Studio** (Ladybug 2024.2 or newer; it bundles the JDK).
2. Open the `android/` folder and let Gradle sync (first sync downloads
   dependencies; the Gradle wrapper 8.14 is included).
3. Run unit tests: `./gradlew test` or via the IDE.
4. Run on an emulator or device (Play → Run).

> The Gradle wrapper jar + scripts are included; if your IDE complains, it can
> repair it, or run `gradle wrapper --gradle-version 8.14` with any local Gradle.

## What works in this MVP

| Flow | How |
|---|---|
| DXF import | ASCII DXF R12-R2018: LINE / LWPOLYLINE / POLYLINE / CIRCLE / ARC, `$INSUNITS` honoured |
| Vector PDF import | page 1 vector paths (m/l/c/re operators), curves flattened |
| Photo capture | **CamScanner-style auto page detection** (largest convex quad) with an adjustable confirm box; perspective warp → illumination flatten → CLAHE → adaptive threshold → **text-blob removal** → **deskew** → LSD (Hough fallback) → collinear merge + endpoint weld → wall-corner joining |
| Rooms | **Automatic room detection** from wall bands with door/window gaps bridged; polygons + live m² labels. **Room editing**: rename, type, exclude from BOQ, delete (from the Rooms list or by tapping a room) |
| Review | Corrected-image underlay (25% opacity) under the vectors, toggleable; "compare lines" check before BOQ |
| Scale | tap 2 points, enter real length → verify with a second dimension (2% tolerance) |
| Editor | pan/zoom, select, move endpoints/whole lines, add lines, delete, layers with kinds and **per-layer wall thickness**, undo, quick **Door/Window tagging** tools |
| BOQ | **Bill of Quantities**: wall centerline extraction (double-line walls counted once), opening families with sizes and deductions, brickwork/plaster/paint/flooring/ceiling/skirting, manual structural lines; MEASURED/ASSUMED/MANUAL tags; **expandable "how calculated" details**; category subtotals, contingency, grand total |
| Rates | **Shared rate library** (material + labour + overhead build-up, or direct rate) applied by item code across projects; per-item overrides |
| Deliverables | Plan PDF with **title block** (client/site/floor/prepared/checked/revision/date), **room names + areas printed on the plan**, colour legend, scale bar; BOQ XLSX with **Cover / Room Schedule / BOQ / Assumptions** sheets, PDF with item codes, basis-of-measurement & assumptions, readiness statement and **signature lines** |
| Readiness & data collection | **BOQ readiness score** with blocking/assumption/optional gaps; guided **Complete data** screen: resolve scale/rooms/walls, confirm assumptions, add structural data, fill missing rates, project info; exports blocked only on true blockers |
| Structural estimates | Footings, columns, beams/slab and steel computed from collected data (ASSUMED, formulas shown) — never silently invented |
| Standards | Generic metric, **India CPWD style**, **UK BS style**, **US imperial** (units and item wording; INR/GBP/USD defaults) |
| Projects | Rename, duplicate, delete; **Combined BOQ** across projects (e.g. Ground + First floors) |
| Cloud sync | Optional device-to-device backup via the processing server with a user-held sync code (requires the updated server deployment) |
| Billing | Play Billing 9: `planboq_pro_monthly` / `planboq_pro_yearly`, free = 3 conversions/month |
| Server (optional) | pre-configured default `https://api.corecivil.engineer`; paste the API key in Settings → **Test connection** (probes `/api/v1/health`, then validates the key against the export endpoint). Device processing always remains the fallback |

## Deploy the server (optional)

See [server/README-DEPLOY.md](server/README-DEPLOY.md) — Docker on your Oracle
ARM instance behind Caddy, one API key in app Settings. Without it the app is
fully functional on-device.

## Before you publish

Read [docs/PLAY_STORE_LAUNCH_CHECKLIST.md](docs/PLAY_STORE_LAUNCH_CHECKLIST.md)
— developer account, closed-testing requirement for new personal accounts,
subscription product setup, listing assets, and the data-safety form crib in
[docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md).

## Disclaimer (also enforced in-app)

Traced geometry is an estimate. Every export carries the label
"Verify before construction." Do not use unverified outputs for construction.
