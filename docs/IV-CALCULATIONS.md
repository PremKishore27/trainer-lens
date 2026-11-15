# How Trainer Lens calculates IVs

This page explains exactly what the IV checker does with the numbers it reads from your screen.
All formulas are the ones the game itself uses (from the game master data); the code lives in
[`Cpm.kt`](../app/src/main/java/dev/trainerlens/Cpm.kt), [`IvSolver.kt`](../app/src/main/java/dev/trainerlens/IvSolver.kt)
and [`Pvp.kt`](../app/src/main/java/dev/trainerlens/Pvp.kt).

## 1. What goes in

| Input | Where it comes from |
|---|---|
| **Species** (and form) | the name line, the "*NAME* CANDY" line (same for the whole evolution family – works for nicknames) and the type line ("GRASS / DRAGON") |
| **CP** | "CP 510" at the top of the page |
| **Max HP** | "73 / 73 HP" |
| **Stardust cost** | number next to the **POWER UP** button |
| **Base stats** (Attack, Defense, Stamina) | [pogoapi.net `pokemon_stats.json`](https://pogoapi.net/api/v1/pokemon_stats.json), downloaded once per session, held in memory only |
| **CP multiplier (CPM)** per level | [pogoapi.net `cp_multiplier.json`](https://pogoapi.net/api/v1/cp_multiplier.json) for levels 1–45, built into the app; 45.5–51 add the game's fixed 0.0025 per half level |

## 2. The game's formulas

Every Pokémon has three hidden **IVs** – Attack, Defense and HP (Stamina) – each **0 to 15**,
and a **level** from 1 to 51 in half steps. Each level has a CP multiplier `CPM` (0.094 at level 1, 0.7317 at 30, 0.7903 at 40, 0.8403 at 50).

```
Attack  = (BaseAttack  + IV_Attack ) × CPM
Defense = (BaseDefense + IV_Defense) × CPM
Stamina = (BaseStamina + IV_HP     ) × CPM

HP = max(10, floor(Stamina))
CP = max(10, floor( (BaseAttack + IV_Attack)
                    × √(BaseDefense + IV_Defense)
                    × √(BaseStamina + IV_HP)
                    × CPM² / 10 ))
```

**IV percentage** is simply the share of the 45 possible points:

```
IV % = (IV_Attack + IV_Defense + IV_HP) / 45 × 100
```

So **12 / 12 / 12 → 36 / 45 = 80.0 %**, and 15 / 15 / 15 → 100 % (a "hundo").

**Appraisal stars** (what the game's team leader shows) depend only on the total:

| Total | IV % | Stars |
|---|---|---|
| 45 | 100 % | ★★★ (red background) |
| 37–44 | 82.2–97.8 % | ★★★ |
| 30–36 | 66.7–80.0 % | ★★☆ |
| 23–29 | 51.1–64.4 % | ★☆☆ |
| 0–22 | 0–48.9 % | ☆☆☆ |

## 3. Using stardust to find the level

The level isn't shown anywhere, but the **power-up stardust cost** depends on it:

| Level | Dust | | Level | Dust |
|---|---|---|---|---|
| 1 – 2.5 | 200 | | 21 – 22.5 | 3,000 |
| 3 – 4.5 | 400 | | 23 – 24.5 | 3,500 |
| 5 – 6.5 | 600 | | 25 – 26.5 | 4,000 |
| 7 – 8.5 | 800 | | 27 – 28.5 | 4,500 |
| 9 – 10.5 | 1,000 | | 29 – 30.5 | 5,000 |
| 11 – 12.5 | 1,300 | | 31 – 32.5 | 6,000 |
| 13 – 14.5 | 1,600 | | 33 – 34.5 | 7,000 |
| 15 – 16.5 | 1,900 | | 35 – 36.5 | 8,000 |
| 17 – 18.5 | 2,200 | | 37 – 38.5 | 9,000 |
| 19 – 20.5 | 2,500 | | 39 – 40.5 | 10,000 |
| | | | 41 – 42.5 | 11,000 |
| | | | 43 – 44.5 | 12,000 |
| | | | 45 – 46.5 | 13,000 |
| | | | 47 – 48.5 | 14,000 |
| | | | 49 – 49.5 | 15,000 |

Special Pokémon pay a different price, so the app also accepts these costs:

| Pokémon | Cost | Example (level 19–20.5) |
|---|---|---|
| Normal | × 1 | 2,500 |
| **Shadow** | × 1.2 | 3,000 |
| **Purified** | × 0.9 | 2,250 |
| **Lucky** | × 0.5 | 1,250 |

When a cost could mean several things (a shadow's 3,000 looks like a normal Pokémon's 3,000 at level 21), the app tries **all** matching levels.
All costs come from the game data ([pogoapi.net `pokemon_powerup_requirements.json`](https://pogoapi.net/api/v1/pokemon_powerup_requirements.json)) and are checked by the unit tests.

## 4. Solving

For every possible level (from the dust table) and every one of the **16 × 16 × 16 = 4,096** IV spreads,
the app recalculates HP and CP with the formulas above and keeps the spreads that give **exactly** the HP and CP on screen.

- **One spread left** → exact result, e.g. `80.0 % ★★☆ · Atk 12 · Def 12 · HP 12 · Lv 20`.
- **Several left** → a range, e.g. `64–91 % · 13 possible spreads · Lv 19.5–20.5`, with the best and worst spread.
  Appraising the Pokémon in-game, or powering it up and reading it again, narrows this down.
- **None** → something on screen was misread, or it's a Mega / buddy-boosted / special case.

**Worked example – Pikachu** (base 112 / 96 / 111), 12 / 12 / 12 at level 20 (CPM 0.59740001):

```
HP = floor((111 + 12) × 0.5974)                         = floor(73.48)  = 73
CP = floor(124 × √108 × √123 × 0.5974² / 10)
   = floor(124 × 10.392 × 11.091 × 0.35689 / 10)        = floor(510.05) = 510
```

On screen: **CP 510, HP 73, dust 2,500**. 2,500 means level 19–20.5, and **13** of the 4,096 spreads produce
exactly CP 510 / HP 73 in that range (from 13/15/13 at level 19.5 down to 15/3/11 at 20.5), so the card shows **64–91 %**.
That's normal for low-level Pokémon – the higher the level, the fewer spreads fit.

**Check against known values:** a 15/15/15 Mewtwo (base 300 / 182 / 214) gives **CP 4178 at level 40** and **4724 at level 50**, matching the game.

## 4b. Exact IVs from the in-game appraisal (Poké Genie style)

CP + HP + stardust often leave several possible spreads. The game's **appraisal** shows the exact values as three bars,
so when you appraise (☰ → Appraise) the app measures them from the screenshot:

1. Text recognition finds the **Attack**, **Defense** and **HP** labels.
2. Just below each label it scans pixel rows for the bar: the longest run of non-white pixels (small white dividers between the
   three bar segments are bridged).
3. It measures how far the **coloured fill** (orange, or red when full) reaches:
   `IV = round(fill width / bar width × 15)`.
4. The three values must come out the same on **two frames in a row**.
5. Cross-check: the spread must also reproduce the CP and HP. If a bar was misjudged by one point, the closest spread
   that fits CP/HP is used. The card then says **✔ from appraisal** and shows the exact level.

**Example – Kingdra** (base 194 / 194 / 181), appraisal 15 / 13 / 13:
CP 2578 and HP 152 fit at **level 39** (CPM 0.78463697) → **91 % · Excellent ★★★ · Lv 39**.

**Example – Empoleon** (base 210 / 186 / 197), appraisal 14 / 14 / 13 = 41 / 45 → **91 % · Excellent ★★★**
(at level 38 that's CP 2785, HP 163).

### Steady readings
- A Pokémon's CP/HP/dust only count when they're read **identically on two frames** (1 second apart), so a Pokémon's
  animation passing over the CP doesn't produce a wrong number.
- Common OCR look-alikes are corrected (O→0, l/I→1, S→5, B→8, Z→2).
- CP is only read from the top third of the screen.
- If the stardust number was misread and nothing fits, the app retries with every level.

### The level arc (Poké Genie / GoIV idea)
On a Pokémon's page a white dot sits on a half-circle above it. Its position is

```
arc fraction = (CPM(level) − CPM(1)) / (CPM(maxLevel) − CPM(1))
maxLevel     = min(trainer level + 10, 50)    (the highest level you can power it to)
```

Every phone draws the arc slightly differently, so Trainer Lens **calibrates itself** instead of using fixed numbers:

1. **Finding the dot:** pure-white pixels in the top half are *eroded* (only pixels whose whole neighbourhood is white
   survive), which removes the thin arc line and the CP text and leaves the filled dot. Round blobs of the right size are kept.
2. **Arc geometry:** the dots of different Pokémon all lie on one circle centred horizontally on the screen.
   After ≥ 4 well-spread dots, a RANSAC + least-squares circle fit gives its centre height and radius (saved per screen size).
3. **maxLevel:** the first time an appraisal gives a Pokémon's exact level, maxLevel is solved from the formula above.
4. From then on the dot's angle gives a level estimate, used as a **soft hint**: spreads within ±¾ level of it are
   preferred, and if none fit, the arc is ignored. So the arc can only narrow a range, never make a result wrong.

The card's last line shows the arc's state: *learning (n dots)*, *appraise 1 Pokémon to finish*, *arc ✓* or *arc ✓ used*.

## 5. Which Pokémon is it? (nicknames and regional forms)

The name line shows the **nickname**, so the app never relies on it alone. It collects *clues* from the page, over
several frames, and combines them:

| Clue | How it's read | What it tells |
|---|---|---|
| **Name** | a line that is exactly a Pokémon name | the species (when not renamed) |
| **Candy label** | "245 DRATINI CANDY", also "DRATINI" + "CANDY" on two lines, a wrong letter ("DRATIN1"), "CANDY XL" | the whole **evolution family** (built from [`pokemon_evolutions.json`](https://pogoapi.net/api/v1/pokemon_evolutions.json)) |
| **Types** | "GRASS / DRAGON", read above the POWER UP button | the **form** – Alolan Exeggutor is Grass/Dragon, the normal one Grass/Psychic |
| **Weight & height** | "210.00kg", "2.20m" compared with each species' average ([`pokemon_height_weight_scale.json`](https://pogoapi.net/api/v1/pokemon_height_weight_scale.json)) | the **stage** inside a family (Dratini 1.8 m, Dragonair 4.0 m, Dragonite 2.2 m / 210 kg). Soft score only – sizes vary, XXS to XXL |
| **EVOLVE button** | the candy number next to it (25 = Dratini, 100 = Dragonair) and whether a button exists at all | the **stage**; final evolutions have no button |

Then:

1. Candidates = the family (if the name isn't a Pokémon, it's a nickname) filtered by the types, ordered by the size and EVOLVE evidence.
2. Every candidate goes through the IV solver; the first that fits CP / HP / stardust wins. With an appraisal, the one matching the bars wins.
3. **If nothing fits**, the search widens to every Pokémon with those types, best size/evolve evidence first, and the card says *(best guess)*.
   This is only tried when at least one real clue (types, size, or EVOLVE) was read – otherwise the answer would be a coin flip, and the card says it can't tell.
4. If other species also fit, the card shows **"Not it?"** buttons – tap one to switch.

**Why not recognise the Pokémon from its picture?** There's no ready-made on-device model that tells Pokémon species apart, and the
in-game 3D models (all forms, shinies, costumes, poses, lighting) differ from any artwork you could ship. Training one would mean
a big dataset, 1,000+ classes and a larger app – and it would still be less reliable than the text on the page, which is exact.
If the clues above ever fail on your phone, 📋 *Copy screen text* shows what was read, so the reading can be fixed.

### Steady across frames (swiping)
Identity is just **CP + HP**. Everything else is *collected* over frames of the same Pokémon (the most-seen candy family, types,
stardust, the EVOLVE button on any frame), so a line missing or misread in one frame no longer changes the answer.
- A page with new CP/HP is shown **from its first frame** (marked ⏳) if some species fits it; the second frame confirms it.
- A reading that fits **no** IV spread (an animation over a digit) is ignored unless it's seen twice, and a *best guess*
  (species searched from types / size) also needs two frames. If a misread that happened to fit flips back to the real
  reading, the previous Pokémon is restored with its appraisal.
- Size and EVOLVE readings use the most-seen value, so one OCR slip can't reorder a family.
- Scan speed: ~100 ms between scans while a Pokémon page is changing, ~300 ms once it's settled, ~500 ms when no Pokémon page is on screen.

## 5b. Automated checks

Every build runs unit tests against snapshots of the game data:
- CP multipliers and stardust costs equal the game master values for every level.
- **Every Pokémon and form (1,168)** × 5 IV spreads × 13 levels × normal/shadow/purified/lucky stardust
  (≈ 76,000 cases): the true spread is always among the results, and with the appraisal it's exact, at the right level and form.
- **Renamed Pokémon:** all 1,168 forms are found again from candy + types + size + EVOLVE button; a split candy label, a wrong letter, a count in front of the label.
- **Frames:** flickering clues, swiping to the next Pokémon, a misread CP, and the "tap to switch" choice are simulated with real on-screen positions.
- Alolan Exeggutor is told apart by type; OCR look-alikes (`CP 2S78`) are fixed.

## 6. PvP league ranks and the verdict line

For each league the app computes, for all 4,096 spreads, the **highest level (up to 50) that stays at or below the CP cap**:

| League | CP cap |
|---|---|
| Little Cup | 500 |
| Great League | 1,500 |
| Ultra League | 2,500 |

At that level it calculates the **stat product**:

```
StatProduct = Attack × Defense × floor(Stamina)      (all with that level's CPM)
```

The spreads are sorted by stat product; **rank #1** is the best possible for that species in that league, and the
percentage is the spread's stat product compared to rank #1. Low Attack and high Defense/HP usually rank best, which is
why a 0/15/15 can beat a 15/15/15 in Great League.

The rank only compares spreads **within the same species** – it doesn't say whether the species itself is good in PvP.
Master League has no cap, so there the plain IV % is what matters.

**The card's one-line verdict:**

| Verdict | Rule |
|---|---|
| ✅ Good for *Great/Ultra/Little* League (#n) | rank ≤ 100 of 4,096 **and** its CP is still at or below that league's cap |
| 🤔 Maybe good | only some of the possible spreads are in the top 100 – appraise to be sure |
| ✅ Good IVs for Master League | every possible spread is ≥ 42 / 45 (93 %) and CP > 2,500 |
| ❌ CP too high for … League | the spread would rank top 100, but the Pokémon is already powered past the cap (levels can't go down) |
| ❌ Not good for PvP | none of the above |

It judges the **IVs only**, not whether the species itself is strong in PvP.

## 7. Known limits

- **Mega-evolved** Pokémon can give "no match". Best-buddy boosted Pokémon (up to level 51) are included.
- Low-level Pokémon often fit many spreads, so you get a range.
- Text recognition can misread a digit; the card updates every second, so look at it for a moment.
- English (and partly German) game language only.
