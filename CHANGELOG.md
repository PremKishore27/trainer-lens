# Changelog

Newest first. The latest build is always on the [Releases](https://github.com/PremKishore27/trainer-lens/releases) page.

## Unreleased → next release

### Added
- **Redesigned overlay:** one fixed-size, frosted-glass window with an IV | Bag switch. It never changes size, and you can drag it anywhere.
- **Size button (A− / A+)** to switch between a larger and a smaller window; your choice is remembered.
- **Guided hints with drawings:** *Check a Pokémon* / *Wrong screen* on the IV tab and *Go to your Item bag* on the Bag tab tell you where to go.
- **Appraisal names the Pokémon:** for renamed Pokémon, regional forms and anything not identified, the *"This … was caught on …"* line on the appraisal screen is read automatically (also *Alolan Exeggutor* style names).
- Bag memory keeps items seen only once (flagged *Seen once*), scans faster while the Items list is on screen, and knows more items (Rocket Radar, Mysterious Component, Sinnoh evolution items).
- **Copy log** now includes the window geometry and what was read, for easier bug reports.
- Community files: contributing guide, code of conduct, security policy, issue and pull request templates.

### Fixed
- IV not showing for the second and later Pokémon (the window covered the HP line).
- Bag forgetting items scrolled past quickly.
- A Pokémon's *163 / 163 HP* line was mistaken for the bag fill.
- Sluggishness: frame bitmaps are reused instead of allocated on every scan; calmer scan rates.

## 0.2.20
- Nickname handling from real phone dumps, candy / type / size clues, level arc, exact stardust table, tests for every Pokémon form.

## Earlier
- Bag advisor, IV checker v2 with appraisal bars, signed APK releases, setup screen. See the [release list](https://github.com/PremKishore27/trainer-lens/releases).
