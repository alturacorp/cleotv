# Cleo TV

A home screen for Fire TV (Fire OS) in Cleo's style (formerly Lume): warm dark palette, the ✦ star, a pill nav (Home · Apps · Cleo), a hero where Cleo suggests tonight's picks, now playing with controls, favourites, an Apps grid, a full-screen Cleo conversation and an ambient mode.

The UI is one file, `app/src/main/assets/web/index.html`. The Android part is a thin shell that hosts it and gives it a bridge (`window.IoleTV`) for apps, icons, media and network calls.

## Naming

- The name people see is one constant, `BRAND` at the top of the script in `index.html`, plus `app_name` in `res/values/strings.xml` and the banner/icon.
- Everything internal uses the codename **iole** and never changes with a rename: package `app.iole.tv`, storage keys `iole_tv_*`, bridge `IoleTV` / `window.iole`.
- The package changed from the test build (`app.lume.tv`), so uninstall the test build once. Settings start fresh.

## Build it with GitHub Actions

1. Push this folder to the `main` branch of a GitHub repository (public, if you want the one-link install below).
2. "Build Cleo TV" runs on every push in the **Actions** tab (about 5 minutes).
3. Each build publishes `cleo-tv.apk` as the latest release:
   `https://github.com/<you>/<repo>/releases/latest/download/cleo-tv.apk`

For a private repo, download the APK from the run's artifacts and install with `adb install -r cleo-tv.apk`.

## Install on the Fire TV

1. Settings → My Fire TV → About → select the device name 7 times to unlock Developer options.
2. Install **Downloader** from the Appstore, then Developer options → Install unknown apps → Downloader → On.
3. In Downloader, enter the release link above and install. Updates install over the top.

## Getting to Cleo

Fire OS always sends the Home button to Amazon's home screen and won't let another app replace it. Cleo works around that with **Settings → Home screen → Make Home open Cleo** (off by default). It uses an accessibility service (`HomeRedirectService`) that notices when Amazon's home comes to the front and opens Cleo on top. It reads only the package and screen name of the window in front, never screen content or typing, and nothing leaves the TV. It skips Amazon Kids and Amazon's settings/profile screens, so nobody gets stuck. **Open Fire TV home for a minute** (Cleo tile menu, or the same setting) pauses it so you can reach Amazon's settings.

Other ways in: "Alexa, open Cleo", a remapped remote button, or start-on-boot (below). Back never exits Cleo.

App tiles use each app's own TV banner. Apps without one get a card drawn on the TV from their icon: a gradient in the app's colour with a white version of its symbol, the way Apple TV does it. No logos are bundled with Cleo. The browser preview shows letter tiles instead.

## Optional adb setup

```
adb connect <fire-tv-ip>
# Now playing card and media controls:
adb shell cmd notification allow_listener app.iole.tv/app.iole.tv.MediaListener
# Open Cleo after boot on Fire OS 8:
adb shell appops set app.iole.tv SYSTEM_ALERT_WINDOW allow
# If Fire TV's Accessibility menu doesn't list Cleo, turn on the Home redirect from a computer:
adb shell settings put secure enabled_accessibility_services app.iole.tv/app.iole.tv.HomeRedirectService
adb shell settings put secure accessibility_enabled 1
```

Settings → Display & motion → **Performance**: Auto switches to a lighter mode (fewer stars, no shimmer, slower ambient frames) on sticks with under about 1.5 GB of memory. The home screen also stops animating after 8 seconds without a button press.

## How the TV talks to the Worker

The TV posts to `https://iole.graggle9901.workers.dev/lume` (changeable under Settings → Cleo server) in the same shape as the phone app:

```json
{ "messages": [{"role": "system", "content": "Cleo TV"}, {"role": "user", "content": "[Context from Cleo TV, for this reply]\n…TV prompt…\n[End of context]\n\n…"}], "uid": "tv-…", "client": "tv" }
```

The Worker requires a system message first but doesn't pass it to the model (Cleo Core is server-owned), so the TV's
instructions travel as labelled context at the start of the latest user message.

- Replies are read from `choices[0].message.content` (or `content[0].text`).
- `403` with `{"error":"lume_disabled"}` shows "A support agent is handling this conversation."
- Quick replies come from a second call with `task: "suggestions"`.
- Tonight's picks come from one call per part of the day, cached on the TV. It sends the names of installed streaming apps, the day and the temperature.
- The TV registers a random device token once with `/auth/register` (no fingerprint) and sends it as `X-Device-Token`, like cleo.ac. An unknown token makes it register again and retry once.

## First run and Settings

- **First-time setup** runs once: welcome, "How things work" (the same points as the web app's consent screen, adapted for a shared TV; agreeing is required before Cleo chats or suggests picks), your name, favourites, town/ambient/clock, and how to get back to Cleo. It resumes where it stopped and can be run again from Settings, Profile.
- **Settings** has Profile, Home screen, Ambient, Cleo (tone on the TV), Weather (town, °C/°F), Display & motion (reduce motion, larger text), Privacy (what leaves the TV and where it goes) and About. Hold OK on any setting to see what it does.
- **Settings lock**: an optional 4-digit PIN for Settings (and so for restoring hidden apps). It's stored on the TV only and asked again each time Cleo is reopened.
- **Reset this TV** (About) clears everything stored under `iole_tv_*` and runs setup again.

## Ambient mode

Starts after a few idle minutes (Settings, Ambient; "Preview ambient now" shows it straight away).

- **Scenes**, picked automatically from the time, weather and whether music is playing, or fixed in Settings:
  - **Night sky**: about 75 real bright stars and the main constellations, placed for your town and the current time, with the Moon (position and phase) when it's up. Faint background stars are decorative.
  - **Aurora**: slow ribbons in Cleo's colours; breathes faster while music plays (it follows playback, not the actual beat).
  - **Weather**: rain, storm, snow, fog, cloud or sun from Open-Meteo.
  - **Morning**: a slow sunrise; also shows at a set time (only if the TV is on with Cleo open).
- **Remote**: ◀ ▶ change scene, ▲ shows what you're looking at, OK asks Cleo about it, anything else wakes the TV.
- **Late at night** (after 11pm): darker scenes, an optional breathing guide, and a fade to near-black after a set time. Cleo can't switch the TV off.
- **Seasonal touches**: Halloween, Bonfire Night fireworks, December snow with a countdown, New Year's Eve countdown and New Year fireworks.
- **Daily note**: one light line a day from Cleo, written from the date, season and weather only.
- **Burn-in protection**: the clock and text move slightly every minute.
- Rendering runs at half resolution, capped at 30 fps, and stops completely outside ambient mode. Reduce motion draws a still frame every 30 seconds instead.

## Profiles, Continue Watching and the journal

- **Who's watching**: add profiles under Settings → Profile. Each one gets its own favourites, chats, picks and Continue Watching. Cleo asks who's watching at start-up (and after 3+ hours away); the round initial at the top right switches. **Guest** saves nothing.
- **Kids profiles** show only the apps a grown-up allows (☰ on any app → Allow for …). They have no Cleo chat and no AI picks. Leaving one needs the Settings PIN, so creating one asks for a PIN first. At **bedtime** (default 7:30pm), Cleo pauses playback with the sleep timer, comes to the front and keeps apps closed until 6am.
- **Continue Watching** is built from media sessions longer than 10 minutes (music apps are skipped). Artwork is saved in the app's private storage and pruned after 7 days. Not every app publishes a session; Netflix, Prime Video, Disney+ and YouTube usually do.
- **How was it?** appears after you finish something. Ratings send only titles to shape picks.
- **Watch journal** (off by default) keeps finished titles and ratings on the TV for a year view.

## Search, posters and title cards

Needs `cleo-catalog.js` in the Worker (see that file's header). It's free: Stremio's public Cinemeta catalogue (no key, no account) supplies titles, posters, backgrounds and title logos, and the TV loads artwork straight from images.metahub.space. Title cards offer *Open <app>* for the app a pick came from, and *Ask Cleo* for where else to watch.

- **Tonight's picks** get a background and the title's logo, and OK opens a title card with an *Open <app>* button for the app Cleo suggested.
- **Search** (top bar) finds installed apps as you type, plus films and shows. Kids profiles don't show Search.

## Send to TV

On the phone or computer, go to cleo.ac → Settings → Cleo ID. You can send words to the TV's chat, a **link** (the TV asks before opening it; YouTube links open in the YouTube app), or a **photo**. Photos are shrunk on the phone and passed through the Worker, which deletes them once every TV has them (after a day at most). On the TV they open full screen (◀ ▶, OK for a slideshow, ☰ to remove), stay for 30 days, and show in the **Memories** ambient scene. While linked, the TV checks for new things every 6 seconds (every 2.5 s in chat), and not while another app is in front.

## Control Center, reminders and weather

- **Control Center**: press ☰ anywhere that isn't an app or card, or select the clock. It has the profile, sleep timer, photos, Search, Settings, now playing, a 5-day forecast and reminders. It's frosted glass except in Lighter mode.
- **Reminders**: in chat, say *"remind me at 8 to call Mum"* or *"remind me in 20 minutes to check the oven"*. They're handled on the TV (nothing is sent) and kept through restarts. When one is due it shows as a small card over whatever is playing; the break reminder uses the same card. The card needs "display over other apps" (`adb shell appops set app.iole.tv SYSTEM_ALERT_WINDOW allow`, the same step as start-on-boot). Without it you get a plain toast.
- **Weather** comes from Open-Meteo. If that fails, Cleo uses MET Norway (only the town's coordinates are sent).

## Smart cards

When a reply is about one film or show, a word's meaning, or what's playing, Cleo adds a card under it. Title cards use the catalogue and come with a *Show …* chip that opens the title card. Word cards come from the Free Dictionary API (only the word is sent). The model asks for a card by ending its reply with a tag like `[card:title|Glass Onion (2022)]`, `[card:word|petrichor]` or `[card:nowplaying]`. The TV removes the tag and fetches the data itself. The card schema (v1) is written at the top of the smart-cards section in `index.html`, ready for the web app to share. Ambient mode shows a **word of the day** from a list built into the TV (Settings → Ambient).

## Updates

Cleo checks the GitHub repo that built it for a newer release (a few times a day, and from Settings → About → Updates). When one exists it asks *Update available?*, downloads `cleo-tv.apk` itself and hands it to Fire TV's installer. The first time, Fire TV shows *Install unknown apps*: turn it on for Cleo, press Back and choose *Install again*. Updates only work when the repo is public, and they install over the top only while the signing key stays the same (the shared key in `/signing`, or your own secrets, but don't switch between them). Only the request for the latest version goes to GitHub.

## "What's on screen?"

Ask Cleo about what's playing: *who's in this?*, *how many episodes are left?*, *is it worth finishing?* Cleo looks the title up in the catalogue (cast, summary and, for series, the episode list) and works out which episode you're on from the session's title or episode name. When you ask, the title and how far in you are go with that message. Chat suggests these questions when you open it while something's playing. It depends on the app publishing a media session with a title; most big apps do.

## Watch (YouTube in Cleo)

A **Watch** tab between Apps and Search. YouTube videos play inside Cleo in YouTube's official embedded player
(IFrame Player API, `controls=0`); Cleo's own title card, control panel and Up next are drawn around the video,
never on top of it, as YouTube's [Required Minimum Functionality](https://developers.google.com/youtube/terms/required-minimum-functionality) asks.

- **Rows:** Continue (videos you stopped part-way, per profile), From channels you follow, Popular on YouTube (UK).
- **Search:** type words, a video link, or `@channel` to find a channel and follow it.
- **Player:** OK plays or pauses, ◀ ▶ skip 10 seconds, any other button shrinks the picture and shows Cleo's
  panel below it (progress, ±10 s, speed, captions, Up next, Ask Cleo). After 5 seconds it grows back.
  At the end, Up next offers the next video from the row you came from. Back goes one step out.
- **Remote media buttons** (play/pause, rewind, fast-forward) control the player.
- **Links from your phone** (Send to TV) offer *Play in Cleo* or *Open in the YouTube app*.
- **Chat:** "play the trailer for Dune", "play lo-fi beats on YouTube".
- **Lights follow what you watch** and the Phone link's *Do not disturb during films* include Cleo's player.
  Messages that arrive during a video appear below the picture, never over it; a menu closes the player first.
- Kids profiles don't see Watch yet. Guest keeps no follows or resume points.
- The screen stays on while a video plays (`keepAwake`), and leaving Cleo pauses the video.

Search and rows go through the Worker's `/yt/` routes (`cleo-youtube.js`), which hold the free YouTube Data API key
(secret `YOUTUBE_API_KEY`), cache results in KV, and only answer registered Cleo devices. Quota: 10,000 units a day;
a search costs 101 and is capped at 40 per device and 80 overall a day; rows cost 1–3 units and are cached for hours.
Playing a video needs no key, so links from your phone play even before the key is set up.

## Cleo Home (smart home)

Settings → **Smart home**, then everything appears in Control Center (☰) and in **Your devices**.

- **Home Assistant** (recommended): enter its address on the TV (use the IP, like `http://192.168.1.20:8123`), then send a long-lived token from cleo.ac → Settings → Cleo ID → Smart home. Cleo reads rooms from your HA areas.
- **Philips Hue**: Settings → Smart home → Philips Hue → press the bridge button → Pair.
- **Shelly**: add each plug's IP address on the TV.
- **Tuya** (Calex, Smart Life, and other Tuya-based lights and plugs): paste a Tuya cloud project's Access ID and Secret on cleo.ac. The guide is on that page. The lights must be in the **Smart Life** app, not the Calex app, because Tuya only links its own apps. Requests are signed on the TV with the secret (HMAC-SHA256). Tuya's free plan needs renewing every few months on iot.tuya.com.
- **SmartThings, Govee, LIFX**: paste their keys on cleo.ac. They go through those companies' own servers.

Keys typed on cleo.ac are encrypted in the browser for this TV (the TV publishes its own public key when it's linked to Cleo ID), so Cleo's server can't read them. They're stored only on the TV.

- **Chat**: *turn off the lamp*, *dim the bedroom lights to 30%*, *set the heating to 21*, *turn on movie night*, *turn everything off*. These are handled on the TV and never sent to Cleo. Locks and alarms always ask on screen first.
- **Lights that follow what you watch**: in Your devices, ☰ on a light → *Dim for films*, then turn on *Lights follow what you watch*. They dim when a film or episode plays, come up a bit when you pause, and go back afterwards.
- **Doorbell** (Home Assistant): choose the doorbell entity and a camera. When it rings, a card with a snapshot appears over whatever is playing (watched natively every 2 s on your network). Optionally it also pauses playback.
- **Kids' bedtime**: ☰ on a light → *Turn off at kids' bedtime*.
- **Ambient**: optional status line (unlocked doors, heating, lights left on).
- Cleo now allows plain `http://` only for addresses on your home network (needed for Home Assistant, Hue and Shelly).

## Phone link (calls and messages on the TV)

Set up on cleo.ac → Settings → Cleo ID → **Phone link**. On the TV: Settings → **Phone link**.

| | What reaches the TV | How |
|---|---|---|
| **Android** (`cleo-phone.apk` from the releases) | Calls (decline from the TV), texts, WhatsApp, Telegram, Signal and more, with quick replies | The app reads notifications and encrypts them for the TV |
| **iPhone** | iMessages and texts | A Shortcuts *Message* automation. cleo.ac shows the exact steps and your token. Not encrypted (Shortcuts can't), not kept. |
| **Mac** (`cleo-mac-helper.py`) | iMessages and texts that reach the Mac | Reads Messages' database (needs Full Disk Access), encrypted for the TV |

- Cards appear over whatever is playing. They show only "Message from Mum" unless you turn on *Show message text*, and they never appear on a kids profile.
- *Pause for calls* is on by default. *Do not disturb during films* holds messages until you pause; calls still come through.
- The last few appear in Control Center. On Android, choose one there to send a quick reply through the original app.
- **iPhone calls**: iPhones only share calls with Bluetooth accessories that ask for them (ANCS), which needs Android 12 on the TV. Settings → Phone link → *iPhone calls over Bluetooth* checks your stick. Fire OS 8 sticks are Android 11, so expect "Not on this stick".
- A "Watching TV" Focus on the iPhone isn't possible: nothing outside the phone can switch Focus. The TV's own *Do not disturb during films* does the job instead.

## Viewing info (VIS)

Cleo's own version of "Viewing Information Services", built the private way. It's **off** until you turn it on (in setup or **Settings → Viewing info**), separately for each profile, and never on kids profiles or Guest.

- **Records:** the apps you open, what you watch (the title each app reports, how long, whether you finished), your "How was it?" ratings, and genres looked up in the free catalogue. **Never** what's on screen.
- **Uses:** a few words of taste in Cleo's picks (genres, loved and finished titles; never times or a history), favourites ordered by what you use at this time of day (*Favourites follow your habits*), an Apps sort *Your habits*, and "You usually watch something around now" on Home.
- **What Cleo knows** (Settings → Viewing info) lists everything in plain words. You can delete single items, pause, or delete everything.
- **Sync, end to end:** the main profile, when the TV is linked to Cleo ID, is shared with cleo.ac through one encrypted vault per account (`vis/get`, `vis/put` in `cleo-id.js`). The data is sealed with a random AES-256 vault key, and that key is sealed for each device's public key (the TV's Android key pair, and a WebCrypto key pair in each browser). Cleo's server stores only ciphertext. A browser gets access the next time the TV syncs after the browser has published its key. Deletions sync too (tombstones, plus a "cleared at" time for delete-everything).
- **On cleo.ac:** Settings → Cleo ID → Viewing info → **Show** decrypts it in the browser. You can delete items, export JSON or delete everything. With *Use it in chats here* ticked, a few words of taste go with chat messages, and you get a "carry on with…" note.
- Never used for ads, never shared or sold.

## Cleo ID

- **Link this TV**: in setup choose "Set up with my phone", or later Settings, Profile, Cleo ID. The TV shows a QR code and a 6-character code; scan it (it opens `cleo.ac/?link=CODE`) or type it in the web app under Settings, Cleo ID, Link a TV, then confirm with Face ID, a fingerprint or the phone's PIN.
- Once linked, the TV takes your Cleo ID name, tone and weather town, and whatever you send from **Type on your TV** in the web app appears in the TV's Cleo conversation (the TV checks every few seconds while the Cleo screen is open).
- The TV holds its own device token; the server only stores its hash. Remove the TV from the web app or with Settings, Profile, Cleo ID, Unlink this TV.
- Needs `cleo-id.js` added to the Worker (see that file's header). The QR code is drawn with qrcode-generator by Kazuhiko Arase (MIT, `assets/web/qrcode.js`).

## Voice, sounds and timers

- **Read replies aloud**: Cleo's replies are spoken with the Fire TV's built-in voice (Settings, Cleo, or the toggle in the Cleo screen). Speaking speed: slower, normal or faster. Nothing extra leaves the TV.
- **Interface sounds**: soft synthesised ticks and a chime when Cleo replies; off in Settings, Display & motion (also silent with Reduce motion).
- **Sleep timer**: from the moon button on the Now playing card, Settings, Home screen, or by asking Cleo ("turn off in 45 minutes", "sleep timer for an hour", "cancel the sleep timer"). When it ends, Cleo pauses what's playing (needs now playing access), comes back to the front and fades into a dim ambient screen. Cleo can't switch the TV off.
- **Break reminder** (off by default): a small message over whatever you're watching after 1½, 2 or 3 hours away in other apps, only while something is playing. It never pauses anything.

## Using it

- **D-pad** moves, **OK** opens, **hold OK** or **☰** shows options (favourite, move, hide, app info, new picks).
- **Back** returns to Home; from Cleo or a menu it closes it.
- Ambient mode starts after 5 minutes idle; change it in Settings.

## Signing

Builds use the shared key in `signing/` so updates install cleanly. To use your own, add repo secrets `LUME_KEYSTORE_BASE64`, `LUME_KEYSTORE_PASSWORD`, `LUME_KEY_ALIAS` and `LUME_KEY_PASSWORD`.

## Not supported

Fire TV Stick 4K Select (2025) and other Vega OS devices can't install APKs.

Fonts: Lora and Figtree, SIL Open Font License (see `assets/web/fonts`).
