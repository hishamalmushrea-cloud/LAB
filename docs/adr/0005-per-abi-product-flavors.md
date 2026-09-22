# 0005. Ship per-ABI product flavors (v7/v8), not a universal APK

- **Status:** Proposed
- **Date:** 2026-06-18
- **Updated:** 2026-09-21
- **Deciders:** Code On The Go team

## Context

The app bundles a large on-device Android/Termux toolchain. Its SDK and bootstrap payloads are architecture-specific, so a universal APK carrying both `armeabi-v7a` and `arm64-v8a` copies would be prohibitively large. Code On The Go is distributed primarily as a **direct APK download** from the App Dev for All website, not exclusively through Google Play, so it cannot rely on Play's automatic ABI splitting to slim every distribution channel.

Earlier versions of this ADR also cited an in-core llama.cpp AAR. AI and llama.cpp were subsequently extracted from the core repository into the plugin ecosystem; they are no longer part of this decision or the core build graph.

## Decision

Define two **product flavors** on an `abi` dimension — `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) — **centrally** in `composite-builds/build-logic` (`conf/AndroidModuleConf.kt`), applied to every Android module except `:plugin-api`.

Consequences for the build:
- Build tasks are flavor-qualified: `assembleV8Debug`, `assembleV7Release`, etc. **There is no flavorless `assembleDebug`.**
- Architecture-specific SDK/bootstrap assets live under `assets/release/v7/` and `assets/release/v8/`; common assets live under `assets/release/common/`.
- `:app:assembleV8Assets` and `:app:assembleV7Assets` produce the corresponding toolchain bundles.

## Consequences

**Positive**
- Each APK ships only one ABI's native libraries and toolchain payloads, reducing download size.
- Explicit, centralized control over per-ABI bundling.

**Negative / costs**
- No flavorless variant; every build/test/release task is doubled into V7/V8.
- Larger build matrix and more release artifacts to produce and track.
- Co-published per-ABI APKs need distinct `versionCode`s so an arm64 device will not treat the v7 APK as a downgrade.
- A recurring onboarding gotcha: contributors must use `assembleV8Debug` (etc.), not `assembleDebug`.

## Alternatives considered

- **Universal (fat) APK** — rejected because of the duplicated native and toolchain payloads.
- **Android App Bundle ABI splits** — rejected because primary distribution is direct APK download, so Play-side splitting cannot serve every channel.
- **Gradle ABI splits (`splits { abi { } }`)** — rejected because the build must select different prebuilt SDK/bootstrap inputs per ABI, not merely slice native libraries from one common build. Product-flavor source sets provide that binding explicitly.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — Build & Module Configuration.
- [CLAUDE.md](../../CLAUDE.md) — build task commands.
