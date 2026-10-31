# Inventori Android APK signing and non-destructive upgrades

Inventori's historical GitHub Actions debug builds were signed by ephemeral
runner-generated debug keys. Android rejects an update signed with a different
certificate, even when the package ID is unchanged. Uninstalling removes
Inventori's local SQLite database and photos.

## Stable signing requirement

Do not ship another APK until GitHub Actions has the following repository secrets:

- `INVENTORI_SIGNING_KEYSTORE_BASE64`: base64-encoded, long-lived, private PKCS12 keystore.
- `INVENTORI_SIGNING_STORE_PASSWORD`: password for that keystore.

The workflow decodes the keystore only in the temporary runner and uses alias
`inventori-release`. Both debug APK Gradle tasks and emulator checks are
configured to sign consistently. The workflow refuses to release a debug APK
without those secrets and checks the APK's certificate SHA-256 before upload.

**Pinned certificate fingerprint:**
`31:41:FB:62:79:7C:BB:3F:24:10:E9:E3:61:2B:54:3F:41:E9:20:F7:2C:C4:7D:E2:CC:C7:2B:3A:73:9A:23:51`

**Never commit the private keystore, password, or base64 contents to Git.**
Keep a protected offline copy of the keystore and its password. The fingerprint
is public, and may be committed.

## Windows PowerShell setup

Run from an administrator-owned Windows machine with GitHub CLI installed and
authenticated with permission to manage Actions secrets for
`costavong-pixel/inventori-android`:

```powershell
gh auth login
$repo = "costavong-pixel/inventori-android"
$keystore = "C:\\private\\Inventori-stable-signing.p12"
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
$encoded | gh secret set INVENTORI_SIGNING_KEYSTORE_BASE64 -R $repo
$pass = Read-Host "Enter signing keystore password"
$pass | gh secret set INVENTORI_SIGNING_STORE_PASSWORD -R $repo
```

Do not paste the secret values into issues, PRs, logs, or chat. The workflow
will fail visibly until they are installed.

## First installation from stable signing

Existing Build 10 and earlier APKs were signed with **different** keys.
Without the old private key, Android cannot update them in place.
Consequently, **one controlled migration may be necessary**:

1. Make a backup in the installed app using **BACK UP INVENTORY (ZIP)**.
2. Download and validate the ZIP, then save a second copy elsewhere.
3. Confirm the new stable-signed APK has passed all tests and carries the pinned certificate.
4. Only then replace the old app and restore the backup. Uninstalling an old
   debug-signed version deletes its local data.

All subsequent APKs signed with the **same stable keystore** and package ID
should install as ordinary updates without wiping local app data.

## Required CI release gates

- Backend tests, Android unit tests and emulator instrumentation must all pass.
- APK signing certificate must match the pinned SHA-256 fingerprint.
- APK upload is disabled if any checks fail.
- ZIP backup and restore must pass on Android using a disposable inventory fixture.

## Compromise / loss

If this private key is lost and no backup exists, another incompatible
Android signing migration may be required. Treat the keystore as a long-term
release credential, not a disposable debug key.
