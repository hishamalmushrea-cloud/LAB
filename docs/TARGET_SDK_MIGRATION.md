# Target SDK migration

The app targets API 28. That is deliberate — an on-device IDE depends on file, install and service
behaviour that later platform versions restrict — but it is also a liability: every release ships
with a decade of opted-out behaviour changes, and the compatibility shims that hold it together are
invisible until someone raises the number and the build breaks.

`scripts/target_sdk_readiness.py` replaces that invisibility with a ratchet.

## How the ratchet works

The tool derives the blocker inventory mechanically from `app/src/main/AndroidManifest.xml`, the app
sources and `BuildConfig.TARGET_SDK`. The blockers that the project currently accepts are recorded
in `config/target-sdk-baseline.json`. The phase-one CI job then enforces two rules:

* a blocker that is **not** in the baseline fails the build — the debt cannot grow silently;
* a baselined blocker that no longer occurs also fails the build, with a `RESOLVED:` message — a
  fix must be locked in by deleting its entry, so the list only ever shrinks.

```bash
python3 scripts/target_sdk_readiness.py --report   # the current inventory
python3 scripts/target_sdk_readiness.py            # the CI ratchet check
python3 scripts/target_sdk_readiness.py --write-baseline   # only with reviewer agreement
```

## What is detected

| id | API | Why it blocks the migration |
|---|---:|---|
| `cleartext-traffic` | 28 | `usesCleartextTraffic="true"` widens the network policy app-wide instead of scoping the loopback build server in `network_security_config`. |
| `legacy-external-storage` | 29 | `requestLegacyExternalStorage` is ignored from API 30; project files need scoped storage, SAF tree grants or an app-private workspace. |
| `manage-external-storage` | 30 | `MANAGE_EXTERNAL_STORAGE` becomes a special access grant with a distribution policy attached. |
| `broad-read-external-storage` | 33 | `READ_EXTERNAL_STORAGE` stops being granted; it needs `maxSdkVersion` plus the granular media permissions. |
| `missing-exported` | 31 | A component with an intent filter must declare `android:exported`, or installation fails. |
| `missing-foreground-service-type` | 34 | A service that calls `startForeground` without a declared type throws `MissingForegroundServiceTypeException`. |
| `mutable-pending-intent` | 31 | `PendingIntent` without `FLAG_IMMUTABLE`/`FLAG_MUTABLE` throws. |
| `unflagged-receiver` | 34 | `registerReceiver` for a non-protected broadcast must pass `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`. |

Detection is intentionally conservative: `registerReceiver(null, filter)` (a sticky-intent read),
`ContextCompat.registerReceiver` and any call whose flags appear in the following formatted argument
list are not reported, so the ratchet does not push authors toward noise-suppressing rewrites.

## Fixed so far

* `GradleBuildService` now builds its notification `PendingIntent` with `FLAG_IMMUTABLE`. The launch
  intent carries no extras for the shade to fill in, so immutability is the correct choice.
* `IDEApplication` registers its Direct Boot unlock receiver through
  `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`; `ACTION_USER_UNLOCKED` is a
  protected system broadcast and must never be exported.
* `GradleBuildService` declares `android:foregroundServiceType="specialUse"` with the matching
  `FOREGROUND_SERVICE_SPECIAL_USE` permission and a subtype property. None of the predefined API 34
  types describes an on-device compiler; `specialUse` is the honest classification and states the
  reason in the manifest rather than mislabelling the build as `dataSync`.
* Cleartext is no longer permitted app-wide. `android:usesCleartextTraffic="true"` is gone and the
  network security configuration denies cleartext in its base config, allowing it only for
  loopback and the two emulator host aliases. A repository-wide search confirmed the loopback
  preview server is the only cleartext peer, so the HTTPS-only asset contract is now enforced at
  the transport layer too.

That takes the inventory from seven blockers to three, all of them at API 28 today.

## The remaining three

All three are the same decision wearing three hats: `legacy-external-storage`,
`manage-external-storage` and `broad-read-external-storage` describe an IDE that manipulates whole
source trees through unrestricted filesystem access. Fixing them needs a product change, not a
manifest edit:

* an app-private workspace as the default project location, with explicit import/export;
* SAF tree grants for projects the user deliberately keeps outside that workspace;
* a migration path for projects already living on shared storage.

This is the work that gates API 30, and it should be designed before it is coded. Raising
`TARGET_SDK` is the last commit of the sequence, not the first, and every step must keep the
ratchet green.
