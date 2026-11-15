# Trainer Lens 🔍

[![Build APK](https://github.com/PremKishore27/trainer-lens/actions/workflows/build-apk.yml/badge.svg)](https://github.com/PremKishore27/trainer-lens/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/PremKishore27/trainer-lens?label=latest&color=2ea043)](https://github.com/PremKishore27/trainer-lens/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/PremKishore27/trainer-lens/total?color=blue)](https://github.com/PremKishore27/trainer-lens/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)

**A screen-reading helper overlay for Pokémon GO on Android.**
It watches your screen with on-device text recognition and shows a small card with useful info on top of the game:
how to clean up your **item bag**, and the **IVs and PvP rank** of any Pokémon you open.

> 🛡️ **Read-only by design.** Trainer Lens never taps, swipes, injects input, or talks to the game's servers.
> It only *looks* at the screen – like a friend looking over your shoulder.

### ⬇️ [Download the latest APK](https://github.com/PremKishore27/trainer-lens/releases/latest/download/TrainerLens.apk)

---

## Features

| | Feature | What it shows |
|---|---|---|
| 🖐️ | **Movable glass overlay** | A 🔍 bubble you can drag anywhere. Tap it for one fixed-size, frosted-glass window with **IV** and **Bag** tabs – it never resizes, whichever Pokémon you open. Drag it by its top bar; the bubble and the window each remember their own spot. |
| 🎒 | **Bag advisor** | Remembers your items for the whole session while you scroll. Shows free slots, what you **can remove** (over your keep limit) and what to **keep** – even when the bag isn't full. When it is full, it tells you exactly what to discard to free the slots you want. |
| 📊 | **IV checker** | Open any Pokémon with the IV tab selected. **Appraise it for exact IVs** – the app measures the appraisal bars like Poké Genie. The card shows **IV %**, appraisal **stars**, the **Attack / Defense / HP** spread, estimated **level**, and **Little / Great / Ultra League rank**. |
| 🌍 | **Nicknames & regional forms** | Finds the species from its candy label, types, size and EVOLVE button – never from the name alone – so renamed Pokémon and Alolan / Galarian / Hisuian forms work. Shows **"Not it?"** buttons when two species fit. |
| ⚡ | **Fast swiping** | A new Pokémon is shown from the first frame it's read (marked ⏳) and confirmed on the next; heavy work runs in the background. The 📋 button shows how long OCR and analysis took on your phone. |
| 🎯 | **Level arc** | Reads the white dot on the level arc (like Poké Genie) and calibrates itself to your phone. |
| ✅ | **Tested on every Pokémon** | Each build checks ~76,000 cases across all 1,168 forms against the game data. |
| ✨ | **Shadow / purified / lucky** | Understands their different stardust costs. |
| 🔄 | **Always up to date** | Base stats are loaded from the [pogoapi.net](https://pogoapi.net) community database, so new Pokémon work without an app update. |

### Example IV card

```
Kingdra                         nickname
 ( 91% )  ATK ▬▬▬▬▬▬▬ 15
 ( 41/45) DEF ▬▬▬▬▬▬  13
          HP  ▬▬▬▬▬▬  13
[ Lv 39 ] [ CP 2578 ] [ HP 152 ]
Little —          Great  —
Ultra  CP too high  Master —
Excellent ★★★ · exact
```

The ring and bars are green for a great Pokémon (top-100 PvP rank or ≥ 91 %), amber for decent, red for weak.
League rows: ✓ = top 100 of 4,096 spreads, ? = maybe (depends on the exact IVs), ✗ = not good, *CP too high* = already past that league's cap.
The overlay hides itself from its own screen reading (its area is blacked out before text recognition), so it can't misread its own numbers.
How the numbers are calculated (formulas, tables, worked example): **[docs/IV-CALCULATIONS.md](docs/IV-CALCULATIONS.md)**.

---

## Install

Trainer Lens isn't on the Play Store, so Android shows a few extra safety prompts. **They're all expected.**

1. **Download** [`TrainerLens.apk`](https://github.com/PremKishore27/trainer-lens/releases/latest/download/TrainerLens.apk) on your phone and open it.
   If asked, allow your browser / Files app to **install unknown apps**.
2. **Google Play Protect** may offer to scan the app → tap **Install without scanning** (or let it scan).
3. Open Trainer Lens and tap the steps **1 → 5**:

   | Step | What to do | Why it's needed |
   |---|---|---|
   | 1 | Allow notifications | Android requires a notification while the screen is being read |
   | 2 | **Allow restricted settings** – opens Trainer Lens's *App info* page. Tap **⋮ (three dots, top right)** → **Allow restricted settings** → confirm with PIN/fingerprint | Android 13+ blocks step 3 for apps not installed from the Play Store. Without this, *Display over other apps* is greyed out |
   | 3 | Allow display over other apps | Shows the 🔍 bubble on top of the game |
   | 4 | Don't restrict battery *(recommended)* | Stops Android from closing the overlay |
   | 5 | **▶ Start session** → choose **Entire screen** | Lets it read the screen |

   > ⚠️ **Android:** the *Allow restricted settings* option is **only** in the ⋮ menu of the app's own page:
   > *Settings → Apps → Trainer Lens → ⋮ → Allow restricted settings*. If the option isn't there yet, tap step 3 once (Android blocks it and *then* shows the option), and come back.

4. Switch to Pokémon GO. **Drag the 🔍 bubble** anywhere on the screen; **tap it** to open the panel with the **🎒 Bag** and **📊 IV** tabs.

**Updating:** install the new APK over the old one – permissions and settings stay.
<sub>Installed a build older than v0.2.4? Uninstall it once first; it was signed with a different key.</sub>

Tested on: Motorola Signature (Android 17). Requires Android 8.0 or newer.

---

## Using it

### The overlay
- **Drag** the 🔍 bubble anywhere. **Tap** it to open/close the panel.
- **🎒 Bag** and **📊 IV** tabs: info shows only for the tab you pick. **■ Stop** ends the session.

### 🎒 Bag advisor
1. In Pokémon GO open **Items**. Tap 🔍 → **🎒 Bag** (tap **Reset** to start fresh).
2. Scroll **slowly** through the whole list. The session **remembers** every item it reads (*remembered: N item types*) until you tap **Reset** or **Stop** – even with the panel closed.
   A count is only accepted when it's read the same on two frames in a row (the list isn't moving), and rows cut off at the screen edge are ignored, so scrolling doesn't create wrong numbers.
3. Read the panel:
   - Items are sorted by **rarity**: **Common** (Potion, Super Potion, Nanab, Revive, Poké Ball, Hyper Potion, Razz, Great Ball, Pinap) are suggested first,
     then **Uncommon** (Max Potion, Max Revive, Ultra Ball, Silver Pinap) – only their surplus. **Rare** items are never suggested.
   - **Bag full?** It lists exactly what to discard to get 30 free slots, common items first. If the surplus isn't enough, it goes
     down to half the keep limit of *common* items and marks those *(below keep limit)*.
   - **Can remove** – items above their keep limit (e.g. 84 Poké Balls with a limit of 50 → remove 34).
   - **Keep** – everything else, plus protected items (incense, lures, passes, incubators, camera, gifts, evolution items, Star Piece, Lucky Egg, Golden Razz, Rare Candy, TMs) that are never suggested.
   - **Free more (+10)** – the app aims to keep 30 slots empty; each tap raises the target by 10. It only matters when the bag is nearly full.

**"remembered: 0 item types"?** Stop on a part of the list, tap **📋 Copy screen text** in the Bag tab and paste it into a
[GitHub issue](https://github.com/PremKishore27/trainer-lens/issues) – it shows exactly what the app read.

Keep limits and rarity are in [`Rules.kt`](app/src/main/java/dev/trainerlens/Rules.kt) – earlier items in the list are suggested first.

### 📊 IV checker
Tap 🔍 → **📊 IV**, then open a Pokémon. For the **exact** result, appraise it in-game (☰ → Appraise) and keep the bars on screen for a second.
The card shows:
- **IV % out of 100** – 15/15/15 = 100 %, 12/13/13 = 38/45 = **84 %** – with the spread and a rating (Perfect / Excellent / Good / Decent / Weak).
- **Level**, CP and HP.
- **One PvP verdict** – ✅ good for Great / Ultra / Little League (top 100 spread), ❌ not good, or ❌ CP already too high.

Without an appraisal it works from CP + HP + stardust alone: exact when only one spread fits, otherwise a **range**
like `84–96% (5 possible)` and *"🤔 Maybe good"* if only some of them rank well. Low-level Pokémon often give a range.
League ranks compare all 4,096 spreads of that species (#1 = best possible) – see [the calculations](docs/IV-CALCULATIONS.md#6-pvp-league-ranks-and-the-verdict-line).

---

## Privacy & permissions

- 📵 Screen images are processed **on the phone** by Google ML Kit and immediately discarded. Nothing is recorded, saved or uploaded.
- 🌐 The only internet request is downloading Pokémon base stats from `pogoapi.net` (no data about you is sent).
- 🔒 No accounts, no ads, no analytics, no trackers.

## Limitations

- English game language (German labels partly supported).
- Mega-evolved Pokémon may show "no match".
- Text recognition can misread a digit – give the card a second to settle.
- Stickers and scrapbook pages aren't counted in the bag advisor yet.

## FAQ

**Is this allowed?** It's an unofficial tool. It doesn't modify, automate or connect to the game, but any third-party tool is used at your own risk.
**Does it recognise the Pokémon from its picture?** No – the text on the page (candy, types, size, EVOLVE) is exact, while no on-device model separates 1,000+ Pokémon forms reliably. See [the calculations](docs/IV-CALCULATIONS.md#5-which-pokémon-is-it-nicknames-and-regional-forms).
**It says "Can't tell which Pokémon this is".** Tap **📋 Copy screen text** in the IV tab and paste it into a [GitHub issue](https://github.com/PremKishore27/trainer-lens/issues) – it shows every line that was read and where.
**Why does it need screen recording?** That's the only way an Android app can see what another app shows. The recording never leaves your phone.
**The bubble disappeared.** Android may stop the screen capture after a while – open Trainer Lens and tap **▶ Start session** again. Step 4 (battery) helps.
**"Display over other apps" is greyed out.** Do step 2: *App info → ⋮ → Allow restricted settings*.
**"No IV spread fits".** Wait a second (OCR may have misread), or it's a Mega / buddy-boosted Pokémon.

---

## Build from source

```bash
git clone https://github.com/PremKishore27/trainer-lens.git
cd trainer-lens
gradle assembleDebug        # needs JDK 17 + Android SDK, or open the folder in Android Studio
```

Every push builds an APK on GitHub Actions; pushes to `main` publish a new [Release](https://github.com/PremKishore27/trainer-lens/releases) with the `.apk` attached.

<details>
<summary>Project structure</summary>

| File | Role |
|---|---|
| `CaptureService.kt` | Screen capture, OCR loop, the fixed-size draggable glass window (bubble ⇄ panel) |
| `Ui.kt` · `IvCardView.kt` · `CardModel.kt` | Overlay look: colours, ring/bar views, the IV card, and its content as plain testable data |
| `BagFeature.kt` · `ItemParser.kt` · `Recommender.kt` · `Rules.kt` | Bag advisor |
| `IvFeature.kt` · `SummaryParser.kt` · `IvAnalyzer.kt` · `IvSolver.kt` | IV checker |
| `Cpm.kt` | CP multiplier and stardust tables, CP/HP formulas |
| `Pvp.kt` | League rank tables |
| `Species.kt` | Base stats from pogoapi.net, forms |
| `MainActivity.kt` | Setup screen with the 5 steps |

</details>

## Roadmap

- [x] Bag advisor
- [x] IV checker with PvP ranks
- [ ] Read the in-game appraisal for exact IVs
- [ ] Editable keep limits inside the app
- [ ] Stickers & more languages

---

<sub>Trainer Lens is a fan-made project and is not affiliated with, endorsed or sponsored by Niantic, Scopely, Nintendo, Creatures, GAME FREAK or The Pokémon Company. Pokémon and Pokémon GO are trademarks of their respective owners.</sub>
