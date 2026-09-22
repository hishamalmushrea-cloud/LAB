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

## Fixed while introducing the ratchet

* `GradleBuildService` now builds its notification `PendingIntent` with `FLAG_IMMUTABLE`. The launch
  intent carries no extras for the shade to fill in, so immutability is the correct choice.
* `IDEApplication` registers its Direct Boot unlock receiver through
  `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED`; `ACTION_USER_UNLOCKED` is a
  protected system broadcast and must never be exported.

Both are safe at API 28 and remove two of the seven detected blockers outright.

## The remaining five

The rest are product decisions rather than mechanical fixes, and each needs its own change:

1. **Storage model** (`legacy-external-storage`, `manage-external-storage`,
   `broad-read-external-storage`) — the largest item. It needs an app-private workspace with
   explicit import/export plus SAF tree grants for user-chosen project directories, because an IDE
   legitimately manipulates whole source trees. Expect this to be the work that gates API 30.
2. **`missing-foreground-service-type`** — `GradleBuildService` needs a declared type (`dataSync`
   is the closest fit for a long-running build) and its matching permission before API 34.
3. **`cleartext-traffic`** — the loopback preview server is the only cleartext consumer; scope it in
   `network_security_config` and drop the app-wide attribute.

Order of work: fix the two cheap items above (done), then the foreground service type, then
cleartext scoping, then the storage model. Raising `TARGET_SDK` should be the last commit of the
sequence, not the first, and each step must keep the ratchet green.
