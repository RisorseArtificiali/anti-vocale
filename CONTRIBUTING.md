# Contributing to Anti-Vocale

Thank you for wanting to help. This project is developed with heavy AI assistance and human ownership (see the README's "How this project is built"), and AI-assisted contributions are welcome here. What we ask is that AI extends your understanding of the change, never replaces it.

## For every contribution

- **Disclose AI use.** If a coding agent wrote or heavily shaped the code, tests, or issue text, say so in the PR or issue. There is no penalty for it; this repo is built the same way.
- **Understand what you submit.** You must be able to explain every line and answer questions about it in review. "The agent wrote it and the tests pass" is the definition of a contribution we cannot accept.
- **Test what you submit.** `./gradlew :app:testFdroidDebugUnitTest` must pass. Anything that changes app behavior needs verification on a real device, described in the PR. Anything that touches native inference (models, JNI, providers) needs a release-build check too: R8 strips differently from debug.
- **No agent-opened contributions without a driving human.** Pull requests and issues opened by autonomous agents without a person who takes responsibility for them will be closed. The review cost of a plausible-looking contribution is hours; we have to spend it only when a human vouches for the work.

The refusal criterion, borrowed from the wider 2025-26 policy discussion, is the *extractive contribution*: work that is cheap to produce and expensive to review. We are a small project; we will reject it whether the cheapness came from a human or a model.

## Ways to contribute that need no code

- **Report real-device results.** The single most valuable contribution: this app's quality surface is a matrix of devices, backends, models, and languages, and published WER numbers almost never match real phones. An issue that says "model X, phone Y, language Z, here is what happened" moves the project more than a PR.
- **Propose or produce community models.** See [the community catalog path](docs/external-models.md#what-import-is-for-and-what-it-does-not-promise): a validated model can reach every user without an app release. The repo ships a [conversion skill](.claude/skills/community-model-conversion/SKILL.md) for coding agents, and a self-hosted catalog index is supported natively.
- **Translations.** The UI and the user guide; open an issue first so efforts do not collide.

## Practical notes

- Build: see [docs/BUILD.md](docs/BUILD.md). Two flavors (playStore, fdroid); `assembleDebug` alone is ambiguous.
- Development happens on `main`; there are no feature-branch PRs from the maintainer side, so rebase onto the latest `main` before opening yours.
