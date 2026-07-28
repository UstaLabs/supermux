# Codex handoff — finish the Play Console "App content" declarations

**Goal:** complete the remaining Google Play Console *App content* declarations for the
supermux Android app so it leaves "draft" and can be promoted to closed testing. A previous
Claude session did most of the release (app created, own-key signing, AAB uploaded to internal
testing, store listing pushed via API) and 4 of 9 declarations. The Play SPA fought its
automation; you're taking over the last ones.

## Environment (already set up — reuse it)
- **Chrome is running with CDP** on `http://127.0.0.1:9222`, showing a **logged-in** Play
  Console (on Chrome-Remote-Desktop display `:20`). Don't relaunch it.
- Node: `/tmp/node-v22.16.0-linux-x64/bin/node`  ·  puppeteer-core: `NODE_PATH=/tmp/node_modules`
- Connect: `puppeteer.connect({browserURL:'http://127.0.0.1:9222'})`, then
  `page = (await browser.pages()).find(p => p.url().includes('/app/4974903422030584766'))`.
- Desktop screenshot to *see* the screen: `DISPLAY=:20 ffmpeg -f x11grab -video_size 3840x2560 -i :20 -vframes 1 -update 1 -y /tmp/s.png` then view `/tmp/s.png`. Or `page.screenshot()`.

## App
- Package `dev.supermux.android` · appId `4974903422030584766` · dev `8521985817907745173`
- App content overview URL:
  `https://play.google.com/console/u/0/developers/8521985817907745173/app/4974903422030584766/app-content/overview`

## Done already (don't redo): Ads=No, Government apps=No, Financial features=doesn't provide, Health=doesn't have.

## STATUS UPDATE (2026-07-11): News, Target audience, and App access are now DONE too.
Only **3 declarations remain** in "Need attention": **Content ratings**, **Data safety**, and
**Advertising ID**. Answer for the new one — **Advertising ID → "No, my app doesn't use
advertising ID"** (supermux has no ads and no analytics SDKs; no `AD_ID` permission). Then just
do Content ratings + Data safety below. Chrome was relaunched with `--disable-gpu` — don't kill it.

## Remaining declarations + EXACT answers
1. **App access** (slug `testing-credentials`) — a dialog may still be open, pre-filled: Name
   "Demo broker pairing", the reviewer instructions, full-access box ticked. It just needs
   **Add** then **Save**; "Add" was throwing a transient *"changes couldn't be saved"* — retry.
   Answer = "Yes, some functionality restricted"; instructions must include this **static URL**
   (textarea max **500 chars**):
   `https://applereview.ustalabs.com/pair?t=DND4mXy2a7ZUk2Tq0Q-9Aue1IcZSvrqOa14xiVC75YY`
2. **News apps** → **No** (not a news app).
3. **Target audience** → age **18 and over** only; "could it appeal to children?" → **No**.
4. **Content rating** → start questionnaire; email = the account's; category **"Utility,
   Productivity, Communication or Other"**; every content question (violence/sexual/language/
   drugs/etc.) → **No**; users can interact/communicate → **No**; shares location → **No**;
   digital purchases → **No**; uncontrolled web browser → **No**. Result should be Everyone/PEGI 3.
5. **Data safety** → "collect or share user data?" → **No** (the developer runs no server; all
   data goes only to the user's *own* self-hosted broker); data encrypted in transit → **Yes**;
   users can request deletion → **Yes**.

Full reasoning for #4/#5 is in `apps/android/play-store/DATA-SAFETY-AND-RATING.md`.

## Hard-won technical notes (these WILL save you time)
- **Clicking Angular Material controls:** DOM `.click()` does NOT reliably check mat-checkbox /
  mat-radio (labels are detached from inputs). Use a real click:
  `const h = await page.evaluateHandle(()=> someElfoundByText.closest('mat-checkbox')||...); await h.asElement().click();`
- **Direct declaration URLs bounce** to the account page after enough scripted navigation.
  Instead go to `.../app-content/overview` (loads reliably) and click the **"Start declaration"**
  buttons; each opens a declaration. Fill it, Save, return to overview, repeat.
- **Multi-step forms:** choose option → **Next** (some) → **Save**. E.g. Financial/Health had a
  step-2 "Documentation" page with only a Save.
- **"Save" only STAGES** the declaration (into Publishing overview) — nothing publishes until the
  release is sent for review — so it is safe to fill them all.
- A human is watching on the CRD screen; you can also just guide them if a form truly resists.

## After all 9 are green
Tell Ahmet. The closed-testing promotion itself is done via the Play Developer API by the
original session (service account at `~/supermux-upload-key-BACKUP/play-service-account.json`).
Your job is just to get the 5 App-content declarations green in the browser.
