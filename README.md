# Inventori for Android

Inventori is a local-first inventory app for home and insurance records, moving and packing, and small-business stock. All three modes use the same on-device inventory.

## V1

- Onboarding for Home Inventory, Moving / Packing, or Small Business
- Nested property, business, room, storage area, shelf, and box/bin locations
- Unique items with available, checked-out, and returned states
- Quantity items with plus/minus and direct quantity editing
- Item photo, category, brand, model, serial number, quantity, purchase price, user-entered replacement estimate, notes, receipt, appraisal/document, and timestamps
- Camera and gallery photo selection; document picker for receipts and appraisals
- Search by name, brand, category, model, serial number, and full location path
- Location-organized PDF export with available item photos and values
- Offline SQLite storage; no account or network connection required

## AI preview

The V1 AI adapter is a deterministic mock that can produce one or multiple draft items. Every draft opens a confirmation screen where items can be edited, removed, or added before saving. Manual item entry is always available. No AI API key or external AI service is included. A production AI provider should be called through a secured backend, never with a credential bundled in the APK or this public repository.

## Build

Requires JDK 17 and the Android SDK for API 35.

    ./gradlew assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk

Run local JVM tests with ./gradlew testDebugUnitTest. Android emulator checks run in GitHub Actions with ./gradlew connectedDebugAndroidTest. Successful workflow runs publish the debug APK as the inventori-debug-apk artifact.

## Data and insurance record

Inventory data and copied attachments stay in this app's private local storage. There is no login, cloud sync, or backup service. Uninstalling the app or clearing its storage removes those local records; export a PDF and keep separate copies of important documents.

The PDF is a basic user-generated inventory record. Replacement values are entered by the user; Inventori does not appraise items, guarantee an insurance claim, or connect to an insurance company.

## Security

Do not commit API keys, passwords, signing keys, keystores, backend credentials, or local configuration. The repository .gitignore excludes common local secret and signing files. Debug builds use Android's standard debug signing and are not release-signed.


## V2

V2 adds:
- Safe Android status/navigation bar insets so system buttons no longer cover Inventori controls.
- One persistent workspace selector instead of three large Home / Moving / Business buttons.
- Optional opt-in GPS evidence metadata for future Home Inventory photos. Inventori never requires GPS for normal use.
- Searchable QR, barcode, NFC, RFID/EPC, and other inventory identifiers.
- Compatibility with Bluetooth barcode and UHF RFID scanners that operate as keyboard/HID input.
- Identifier and optional GPS metadata in item details and PDF exports.

Dedicated vendor-specific UHF RFID reader SDK integration is intentionally deferred. V2 stores RFID/EPC identifiers without locking Inventori to one hardware brand.


## V2.1 workspaces

Inventori now uses a real hierarchy:

`Account (future) → Workspace → Location → Item`

Each workspace has its own type (Home Inventory, Moving / Packing, or Small Business), its own locations, and its own items. A customer can create multiple workspaces of any type and switch between them from the Inventori header.

The V2 local database is migrated in place. Existing items and locations are preserved in one imported workspace, which adopts the user's previously selected mode on first V2.1 launch. Locations are not artificially limited by the local data model.


## V2.2 real AI recognition

V2.2 adds a server-side vision path and successful-scan metering.

- Android never contains the OpenAI API key.
- A configured HTTPS backend analyzes one photo for one or many inventory items.
- The user still reviews every detected item before saving.
- One successful photo analysis counts as one scan even when multiple items are returned.
- Failed provider requests and zero-item detections do not consume a scan.
- The app displays the last known scans remaining and estimated provider cost for a successful request.
- With no backend URL configured, the deterministic local preview remains available.

See `backend/README.md` for deployment and build instructions.
