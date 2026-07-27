# supermux Android — Play Store release checklist

**Your path:** brand-new **Personal** developer account → **Closed testing** (12 testers ×
14 days, required by Google) → **Production** (public). The good news: everything except the
human/account steps is already prepared in this folder.

Legend: ✅ = done for you · 👤 = only you can do it · ⏳ = in progress

---

## What's already prepared (✅)
| Item | Where |
|---|---|
| Upload signing key | `apps/android/upload-keystore.jks` (+ backup in `~/supermux-upload-key-BACKUP/`) |
| Signed release **AAB** | `apps/android/build/outputs/bundle/release/android-release.aab` |
| Store listing copy | `play-store/STORE-LISTING.md` |
| App icon (512) + feature graphic | `play-store/assets/` |
| Privacy policy | `play-store/PRIVACY-POLICY.md` |
| Data Safety + content-rating answers | `play-store/DATA-SAFETY-AND-RATING.md` |
| Phone screenshots | ⏳ `play-store/assets/screenshots/` |

> To rebuild the AAB later (after a version bump): bump `versionCode`/`versionName` in
> `apps/android/build.gradle.kts`, then
> `cd apps && ANDROID_HOME=~/Android/Sdk ./gradlew :android:bundleRelease`.

---

## A. Create the developer account (👤)
1. Go to **play.google.com/console** → sign up. Choose account type **Personal**.
2. Pay the **$25 one-time** fee.
3. Complete **identity verification** (Google asks for a government ID + address; can take a
   few hours to a couple of days).
4. Accept the Developer Distribution Agreement.

## B. Create the app (👤)
1. Play Console → **Create app**.
2. App name **supermux** · Default language **English (US)** · Type **App** · **Free**.
3. Tick the Developer-Program-Policies + US-export declarations → **Create**.

## C. Turn on Play App Signing & upload the build (👤)
1. Left nav → **Test and release → Setup → App integrity** → **App signing**: let Google
   **generate and manage the app signing key** (recommended). You upload with *our* upload key.
2. Left nav → **Test and release → Testing → Closed testing** → **Create new release**.
3. When prompted about signing, accept Play App Signing. Upload
   `apps/android/build/outputs/bundle/release/android-release.aab`.
   - It's signed with the upload key — fingerprint **SHA-256 63:0D:4F:40:8A:D8:…:C1:B1**.
4. Paste the **release notes** from `STORE-LISTING.md`. Save → **Review release** → **Roll out**.

## D. Recruit the 12 testers (👤 — start this on day 1; the 14-day clock begins now)
1. In the Closed testing track → **Testers** tab → create an email list of **≥12 Google
   accounts** (friends, community, your own secondary accounts on real devices).
2. Share the **opt-in link**; each tester must tap it and **install** the app, and **stay
   opted in for 14 continuous days**.
3. Keep ≥12 opted in the whole time — if someone drops below 12, the clock effectively stalls.

## E. Complete the store listing & policies (👤 — do during the 14-day wait)
Play Console → **Grow → Store presence → Main store listing**:
- App name, **short** + **full description**, **release notes** → paste from `STORE-LISTING.md`.
- **App icon** → `play-store/assets/icon-512.png`.
- **Feature graphic** → `play-store/assets/feature-graphic.png`.
- **Phone screenshots** (2–8) → `play-store/assets/screenshots/`.
- **Category** Productivity · **Tags** · **Contact**: `support@supermux.dev`, `https://supermux.dev`.

Then **App content** (left nav):
- **Privacy policy** URL → host `PRIVACY-POLICY.md` (see §G) and paste the URL.
- **Data safety** → answers in `DATA-SAFETY-AND-RATING.md §A`.
- **Content rating** → answers in `§B`.
- **Ads** → No ads · **Target audience** → 18+ · **App access** → see §F.

## F. ⚠️ App access for reviewers (the #1 reason self-hosted apps get rejected) (👤)
The app is useless without a paired broker, so a Google reviewer who just installs it sees only
a pairing screen and may reject for "broken / no functionality." Fix it in **App content → App
access → All or some functionality is restricted**, and provide **either**:
- a **demo broker** you keep running for review (a small always-on instance with a couple of
  example sessions) + the pairing URL/QR and steps, **or**
- clear instructions: "This is a client for the self-hosted supermux broker. Install it from
  supermux.dev on your own machine, then pair via the QR shown at first launch," plus a short
  screen recording.

> I can stand up a throwaway demo broker on a public tunnel for the review if you want — say
> the word and I'll prepare the reviewer access notes to paste here.

## G. Host the privacy policy (👤, ~2 min)
Publish `PRIVACY-POLICY.md` at a public URL — e.g. `https://supermux.dev/privacy` (add a page
to the website), or any static host. Paste that URL into App content → Privacy policy.

## H. Go to production after the test (👤)
1. After **≥12 testers opted in for ≥14 days**, the Console dashboard shows **Apply for
   production access** → fill the short questionnaire about your testing.
2. Once granted: **Test and release → Production → Create release** → **promote** the same AAB
   from closed testing (no rebuild needed) → review → **Roll out to production**.
3. First production review typically takes a few days.

---

### Future updates (reference)
Every new upload needs a higher `versionCode`. GitHub Release APKs get this **automatically**
from CI (`ANDROID_VERSION_CODE_FLOOR + GITHUB_RUN_NUMBER` + tag as `versionName` — see
`.github/workflows/release.yml` `build-android`). For a manual Play Console AAB outside that
flow, pass `-PsupermuxVersionCode=… -PsupermuxVersionName=…` (or temporarily raise the
defaults in `apps/android/build.gradle.kts`), rebuild, upload to a track, promote. The
upload key never changes.
