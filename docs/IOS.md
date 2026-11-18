# Trainer Lens on iPhone and iPad

A first version. It works on **screenshots** (iOS doesn't allow a window floating over another app), and it is installed by **sideloading** an unsigned file, because it isn't on the App Store.

> **Status:** built and checked by GitHub's automatic tests (the IV maths, Pokémon data, text parsing and bag rules are tested against the same game data as Android), but **not yet tried on a real iPhone**. If something doesn't read right, please [open an issue](https://github.com/PremKishore27/trainer-lens/issues/new/choose).

## What you need
- iPhone or iPad with iOS 17 or newer
- A computer (Mac or Windows) **or** a sideloading app that works on the phone
- A free Apple ID (a paid developer account is not needed)

## Install (once)
1. Download **[TrainerLens-iOS-unsigned.ipa](https://github.com/PremKishore27/trainer-lens/releases/latest/download/TrainerLens-iOS-unsigned.ipa)**.
2. Sign and install it with one of these (each has its own guide; pick one):
   - **[Sideloadly](https://sideloadly.io)** (Mac / Windows): drag the `.ipa` in, enter your Apple ID, press Start.
   - **[AltStore](https://altstore.io)** or **[SideStore](https://sidestore.io)**: open the `.ipa` with it and install.
3. On the iPhone: **Settings → General → VPN & Device Management → your Apple ID → Trust**. (iOS 16+: also turn on **Developer Mode** in Settings → Privacy & Security if asked.)
4. Open **Trainer Lens**.

**Re-signing:** apps signed with a free Apple ID stop opening after **7 days**. AltStore / SideStore can refresh them automatically; with Sideloadly just install the same file again.

## Use it
1. In Pokémon GO open a Pokémon from your list and take a screenshot (side button + volume up).
2. For exact IVs: ☰ → **Appraise**, tap until the three bars show, and take a second screenshot.
3. In Trainer Lens open the **IV** tab, tap **Choose Pokémon screenshots** and pick both. You get the IV %, Attack / Defense / HP, level and Great / Ultra / Master rank.
4. **Bag:** Poké Ball → **Items**, scroll slowly through the whole list taking screenshots, then pick them all in the **Bag** tab. Items to throw away are **red pills**, items to keep are **green pills**.

## Privacy
Pictures are read **on your iPhone** and are not saved or uploaded. The only network use is downloading the public Pokémon stats list from pogoapi.net (a copy is also built in, so it works offline).

## Differences from Android
- No floating window and no live reading; you choose screenshots instead.
- The level-arc reading is not used (Android reads the dot on the level arc); the stardust cost and the appraisal narrow the level instead.
- Everything else (IV solving, PvP ranks, nicknames via candy / types / the "caught on" line, bag rules) is the same code logic ported to Swift.
