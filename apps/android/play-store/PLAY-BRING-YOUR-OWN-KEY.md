# Play Store with YOUR signing key (so GitHub APK == Play install)

**Goal:** Play delivers APKs signed with **your `CN=Supermux, O=UstaLabs` key** (SHA `630d4f…`),
the *same* key your GitHub/sideload APK uses — so a user can move between channels without
uninstalling. This means opting **out** of Google's auto-generated signing key and providing
your own ("bring your own key" via the PEPK tool).

> This supersedes the "let Google generate the key" step in `RELEASE-CHECKLIST.md §C`. Everything
> else in that checklist (listing copy, Data Safety, content rating, screenshots) still applies.

---

## The key facts
- **App signing key** (Google uses it to sign what users download) → set this to **CN=Supermux**.
- **Upload key** (you sign uploads with) → use the **same CN=Supermux key** (simplest). Then the
  AABs you build locally are accepted, and delivered APKs match your GitHub APK signature.
- ⚠️ **Critical:** when you bring your own app-signing key, **Google cannot recover it if you lose
  it** — you'd never be able to update the app again. The key lives at
  `apps/android/upload-keystore.jks` and `~/supermux-upload-key-BACKUP/`. **Put a copy in a
  password manager / offline backup now.** (Passwords: `~/supermux-upload-key-BACKUP/CREDENTIALS.txt`.)

## Steps (Play Console — you drive; I run the one command)
1. **Create the app** (Play Console → Create app): name `supermux`, English (US), App, Free.
2. Go to **Test and release → App integrity → Play app signing**. Before your first release you're
   offered signing options — choose **"Use a different key… / Export and upload a key from a Java
   keystore"** (the PEPK path, *not* "Let Google create").
3. Google shows a **PEPK tool** download + a per-app **encryption key** (a `.pem` file or a hex
   string). **Copy that to me** (or download pepk.jar and run the command yourself):
   ```bash
   java -jar pepk.jar \
     --keystore=apps/android/upload-keystore.jks \
     --alias=upload \
     --output=supermux-app-signing-key.zip \
     --include-cert \
     --encryption-key-path=<the .pem Google gave you>
     # older Console variant instead of the last line: --encryptionkey=<hex string>
   ```
   It'll prompt for the store + key passwords (in `CREDENTIALS.txt`).
4. **Upload `supermux-app-signing-key.zip`** in the Console → this registers CN=Supermux as the
   **app signing key**.
5. When asked about an **upload key**, choose to **use the same key** (upload the cert / skip a
   separate upload key). Now your CN=Supermux-signed AABs upload cleanly.
6. **Upload the AAB** I build (signed with CN=Supermux). Confirm the signing summary in the Console
   shows SHA-256 `63:0D:4F:40:…:C1:B1` for both app-signing and upload.

## After signing is set
- Finish the listing (copy in `STORE-LISTING.md`), **Data Safety** + **content rating**
  (`DATA-SAFETY-AND-RATING.md`), **privacy policy URL** → `https://supermux.dev/privacy` (live),
  screenshots (`assets/screenshots/`).
- New **personal** account → **closed test: 12 testers × 14 days** before production
  (see `RELEASE-CHECKLIST.md §D/§H`).

## What I still owe you
- A **signed AAB** (CN=Supermux key) — building it once the box is free of the other active Gradle
  build (release builds must run solo or they OOM). Version: TBD — your tree has an uncommitted
  `0.9.6 / vc30` bump owned by another session; tell me the versionCode to lock for the Play AAB.
- I can run the **PEPK** command the moment you paste the Console's encryption key.
