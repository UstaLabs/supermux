# Data Safety form + Content Rating — recommended answers

These are **recommended** answers based on what the app's code actually does. The Data Safety
declaration is legally yours to make — read each line and confirm it's true before submitting.
Reasoning is given so you can defend each answer if Google asks.

---

## A. Data Safety  (Play Console → *App content → Data safety*)

**Key fact that drives every answer:** the app has **no developer backend**. Everything you
type, say, or attach is sent **only to the supermux broker that the user runs on their own
hardware**. UstaLabs operates no server and receives no user data. The one third party
involved is **Google FCM**, used solely to deliver push notifications.

### Recommended primary declaration
> **"No, this app does not collect or share any of the required user data types."**

Rationale: "Collection" under Google's policy means transferring data off the device **to the
developer or a third party acting on the developer's behalf**. Here, data goes to the *user's
own* server. This is the same posture taken by self-hosted clients (Nextcloud, Home
Assistant, WireGuard). FCM push **tokens** are processed by Google to route messages, and the
notification payload is **end-to-end encrypted** by your broker.

Then complete the rest of the section:
| Question | Answer |
|---|---|
| Is all data encrypted in transit? | **Yes** (use HTTPS/WSS to your broker — the default for any public URL). |
| Do you provide a way to request data deletion? | **Yes** — uninstall / unpair removes all local data; the user controls their own server. |
| Is your app's data collection independently validated? | No |
| Does your app use the Advertising ID? | **No** |

### ⚠️ Fallback if a reviewer pushes back
If Google's review insists that device → *your-own-server* transfer counts as "collection",
re-submit declaring these types as **Collected · Not shared · Purpose: App functionality**,
and tick *"processed ephemerally"* where it fits:
- **Audio** (voice input), **Photos/videos** (attachments you pick), **Other in-app messages**
  (session text). None are shared with third parties; none are used for ads/analytics.

---

## B. Content Rating  (Play Console → *App content → Content rating*)

Start the IARC questionnaire. Recommended answers:

| Question | Answer |
|---|---|
| Category | **Utility, Productivity, Communication or Other** (not a game) |
| Violence (realistic / cartoon / fantasy) | No |
| Sexual content / nudity | No |
| Profanity / crude humor | No |
| Controlled substances (drugs, alcohol, tobacco) | No |
| Gambling (real or simulated) | No |
| Does the app let users **interact / exchange content with other users**? | **No** — a user connects only to their *own* agent sessions; there is no user-to-user social or sharing surface. |
| Does the app share the user's **current location**? | **No** |
| Does the app allow **purchases of digital goods**? | **No** |
| Does the app contain an **uncontrolled web browser**? | **No** — it has a terminal/editor scoped to the user's own machine, not a general web browser. |
| Digital content that could be a concern (user-generated)? | No |

**Expected outcome:** *Everyone* (ESRB) / *PEGI 3* / *USK 0* / rated for all ages.

---

## C. Other "App content" sections you'll be asked to fill
| Section | Answer |
|---|---|
| **Ads** | This app contains **no ads**. |
| **Target audience & content** | Age groups **18+** (developer tool); not appealing to children. |
| **News app?** | No |
| **COVID-19 / health?** | No |
| **Data safety → Government app?** | No |
| **Financial features?** | No |
| **App access** (login required) | ⚠️ **Critical — see RELEASE-CHECKLIST.md §F.** The app needs a paired broker, so you must give Google's reviewers working access or they'll reject it. |
