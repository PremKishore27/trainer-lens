# How Trainer Lens calculates IVs

This page explains exactly what the IV checker does with the numbers it reads from your screen.
All formulas are the ones the game itself uses (from the game master data); the code lives in
[`Cpm.kt`](../app/src/main/java/dev/trainerlens/Cpm.kt), [`IvSolver.kt`](../app/src/main/java/dev/trainerlens/IvSolver.kt)
and [`Pvp.kt`](../app/src/main/java/dev/trainerlens/Pvp.kt).

## 1. What goes in

| Input | Where it comes from |
|---|---|
| **Species** (and form) | Pokémon name, or the "*NAME* CANDY" line if it has a nickname |
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
| | | | 41 – 44.5 | 11,000 |
| | | | 45 – 48.5 | 12,000 |
| | | | 49 – 49.5 | 15,000 |

Special Pokémon pay a different price, so the app also accepts these costs:

| Pokémon | Cost | Example (level 19–20.5) |
|---|---|---|
| Normal | × 1 | 2,500 |
| **Shadow** | × 1.2 | 3,000 |
| **Purified** | × 0.9 | 2,250 |
| **Lucky** | × 0.5 | 1,250 |

When a cost could mean several things (a shadow's 3,000 looks like a normal Pokémon's 3,000 at level 21), the app tries **all** matching levels.
Costs above level 40 are less well documented, so neighbouring buckets overlap by half a level to be safe.

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

## 5. Regional forms

The database has separate base stats for Alolan, Galarian, Hisuian and other forms, but the screen only shows
"Vulpix". The app tries every form of that Pokémon (normal form first). If more than one form fits, the card says
**"Also fits: Vulpix (Alola)"**.

## 6. PvP league ranks

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

## 7. Known limits

- **Mega-evolved** Pokémon and **best-buddy boosted** CP (+1 level) can give "no match".
- Low-level Pokémon often fit many spreads, so you get a range.
- Text recognition can misread a digit; the card updates every second, so look at it for a moment.
- English (and partly German) game language only.
