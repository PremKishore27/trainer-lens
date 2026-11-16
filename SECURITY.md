# Security policy

## Supported versions

Only the [latest release](https://github.com/PremKishore27/trainer-lens/releases/latest) is supported. Please update before reporting.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.
Use GitHub's private reporting instead: **Security → Report a vulnerability** on this repository.
Include what you found, how to reproduce it, and which version you tested. You will get an answer as soon as the maintainer can; this is a hobby project, so please allow a few days.

## What the app does and does not do

- It reads the screen on the phone and keeps nothing: no screenshots are saved or uploaded.
- Its only network request downloads public Pokémon stats from `pogoapi.net`.
- It never taps, swipes or sends input to other apps.

## Verifying a download

Releases are built by [GitHub Actions](.github/workflows/build-apk.yml) from the source in this repository and signed with the project's release key.
Only download the APK from this repository's **Releases** page.
