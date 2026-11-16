# Contributing to Trainer Lens

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/PremKishore27/trainer-lens/issues/new/choose) and include:

1. The app version (shown in the **Copy log** text, e.g. `Trainer Lens 0.2.25`), your phone model and Android version.
2. What you did, what you expected, and what happened. A screenshot helps.
3. The text from **Copy log** (IV tab or Bag tab) taken on the screen that went wrong.

> 🔒 **Privacy:** the log lists the text read from your screen. On a Pokémon page this includes the
> *"This … was caught on <date> … <place>"* line. Delete that line (and anything else personal) before you post it.

## Suggesting a feature

Open an issue with the *Feature request* template. Say what you were trying to do, not only the solution you have in mind.

## Building and testing

You need JDK 17 and the Android SDK (or just open the folder in Android Studio).

```bash
gradle testReleaseUnitTest   # all unit tests (every Pokémon form through the IV solver, overlay layout, hints)
gradle assembleDebug         # builds the APK
```

GitHub Actions runs the same on every push and pull request. A pull request needs a green build.

## Code guidelines

- **Keep logic out of views.** Anything that decides *what* to show lives in plain Kotlin (`CardModel.kt`, `BagMemory.kt`, `Layout.kt`, the parsers) and has a unit test. Views only draw it.
- **The overlay has a fixed size.** Sizes are dp constants in `Metrics` (`Layout.kt`); tests check that every part fits and that the window stays between the CP and HP lines. If you add something to a card, add it to the numbers and the test.
- **Read-only.** The app must never tap, swipe or send input to the game, or talk to the game's servers. Pull requests that do will not be merged.
- **No game artwork.** Draw simple icons yourself; don't add Pokémon sprites, logos or other trademarked images.
- **Real data, not guesses.** For text-recognition fixes, add a test built from a real *Copy log* dump (remove personal places first) – see `RealDumpTest.kt`.
- Keep user-facing text short and in plain language.

## Pull requests

1. Fork, create a branch, make your change with tests.
2. Run the tests locally if you can; otherwise let the CI run show them.
3. Describe what changed and why. Use the pull request template.
4. Be kind in reviews – see the [Code of Conduct](CODE_OF_CONDUCT.md).
