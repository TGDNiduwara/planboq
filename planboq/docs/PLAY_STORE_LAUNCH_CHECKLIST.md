# PlanBOQ — Google Play launch checklist

Work through this in order. Items marked **[you]** need your action in the
Play Console / browser; everything else ships with this repo.

## A. Developer account

- [ ] **[you]** Create a Google Play developer account: https://play.google.com/console
      - One-time registration fee (USD 25).
      - Personal accounts created after Nov 2023: identity verification, and
        **12 testers for 14 days in closed testing** before production access
        (Google tightened this from 20/14 in 2025 — check the current number
        in the Console when you get there). Organisational accounts (D-U-N-S)
        skip the tester requirement.
- [ ] **[you]** Complete identity verification and set up payments profile.

## B. App identity (already set in this repo)

| Item | Value |
|---|---|
| App name | PlanBOQ |
| Package / applicationId | `com.planboq.app` |
| minSdk | 26 (Android 8.0) |
| targetSdk | 36 |
| versionCode / versionName | 1 / 0.1.0 |

The applicationId can never change after first upload. If you ever rename the
app, change the display name only.

## C. Products & monetisation

- [ ] **[you]** Play Console -> Monetise -> Products -> Subscriptions -> create
      subscription `planboq_pro` with two base plans/offers:
      - `planboq_pro_monthly` (auto-renew monthly)
      - `planboq_pro_yearly` (auto-renew yearly)
      The app queries product IDs `planboq_pro_monthly` / `planboq_pro_yearly`
      (`BillingManager.ALL_PRODUCTS`). Create the subscription ID and base-plan
      IDs to match, or edit the constants to match what you create.
- [ ] **[you]** Set prices per target country.
- [ ] **[you]** Note Play's service fee: 15% for the first USD 1M/year via the
      small-developer programme (requires enrolling), otherwise 30%. Verify
      current rates before launch.

## D. Build & upload

- [ ] Open `android/` in Android Studio (Ladybug or newer), let it sync.
- [ ] Run unit tests: `./gradlew test` (or the IDE gutter arrows).
- [ ] Run on emulator/device: import a DXF, trace a photo, calibrate scale, export.
- [ ] Generate signed AAB: Build -> Generate Signed App Bundle (create an
      upload keystore; keep it and its passwords safe — losing it means a new
      app listing key reset via Play support).
- [ ] **[you]** Play Console -> Create app -> upload the AAB to **Closed testing**
      first (satisfies the tester requirement AND catches device issues).
- [ ] **[you]** After 14 days of closed testing (personal accounts): promote to
      Production.

## E. Store listing assets (prepare before upload)

- App name (30 chars): `PlanBOQ - Floor Plans & BOQ`
- Short description (80 chars): suggest
  `Turn floor plans into clean vector drawings. DXF, PDF and photo tracing.`
- Full description: lead with the converter; mention the disclaimer; no
  quantity promises in v1.
- **[you]** App icon 512x512 (use `res/drawable/ic_launcher_foreground` motif).
- **[you]** 2-8 phone screenshots (editor, corner-tap, export screens are good).
- **[you]** Feature graphic 1024x500.

## F. Policy pages

- [ ] **[you]** Host `docs/PRIVACY_POLICY.md` at a public URL (GitHub Pages is
      fine) and paste the link into the Console.
- [ ] **[you]** Data safety form (see the answers cribbed in PRIVACY_POLICY.md).
- [ ] Content rating questionnaire -> Everyone.
- [ ] Target audience: adults (working professionals); no ads in v1.
- [ ] App access: all functionality available without credentials.

## G. Submissions gotchas (2026)

- Billing Library must be 8.0+ for new submissions (this repo uses 9.1.0).
- targetSdk must be at least the Play-mandated level for the year (repo uses
  36; check the Console banner for the current minimum).
- Photo-trace outputs MUST keep the "verify before construction" disclaimer —
  removing it invites 1-star reviews and refund demands.
- New personal accounts get extra scrutiny on first production release; expect
  a policy review delay of a few days.

## H. Post-launch validation plan (from the concept doc)

1. Post in civil engineer / contractor groups: what do they use today?
2. Watch installs -> activations -> conversions in the Console.
3. STOP RULE: if after ~3 months fewer than ~10 regular users or zero paying,
   stop or pivot before investing in Phase 2/3.
