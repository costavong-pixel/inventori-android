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
