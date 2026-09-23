# 0016. Projects live in an app-owned workspace reached through a single storage gateway

- **Status:** Proposed
- **Date:** 2026-09-23
- **Deciders:** Code On The Go team

## Context

The app targets API 28 and holds `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`,
`WRITE_EXTERNAL_STORAGE` and `requestLegacyExternalStorage="true"`. Those four declarations are the
only remaining entries in `config/target-sdk-baseline.json` (see `docs/TARGET_SDK_MIGRATION.md`):
they are what stops the target from moving past API 29, and they are also the app's widest
privilege by a large margin — unrestricted read/write over the whole shared volume.

They are not gratuitous. An IDE is genuinely unlike an ordinary app: it opens whole source trees,
runs Gradle over them, and must keep working when a project was cloned by another tool. The current
design serves that need in the bluntest way available:

- `Environment.PROJECTS_DIR` is `<shared storage>/CodeOnTheGoProjects`, created eagerly at startup;
- projects may also be opened from anywhere via `ACTION_OPEN_DOCUMENT_TREE`, whose result is
  converted straight back to a `java.io.File` path (`FileUtil.convertUriToFilePath`,
  `BaseFragment`), so the SAF grant is used as a path picker rather than as an access capability;
- developer switches and the documentation database are sentinel *files* on shared storage
  (`WebServer`'s four `Download/...` paths, `DocumentationRequestInterceptor`'s disable sentinel);
- `idelog.txt` is written to the storage root.

A repository scan found 19 direct uses of `Environment.getExternalStorageDirectory()` /
`FileUtil.getExternalStorageDir()` across `:app` and `:common`. That is the real problem: the
dependency on broad storage is not one decision in one place, it is spread across feature code, so
no incremental fix can be verified and any fix can be silently undone by the next feature.

Raising the target without addressing this does not merely lose a permission — on API 30+ the
legacy flag is ignored, `MANAGE_EXTERNAL_STORAGE` becomes a special-access grant the user must
approve on a system screen (and a distribution-policy question), and code that assumes a writable
`/storage/emulated/0/CodeOnTheGoProjects` fails at runtime rather than at compile time.

## Decision

Adopt a **workspace-first storage model with a single gateway**, in this order:

1. **The workspace is app-owned.** The default project root moves to app-specific storage, which
   needs no permission at any API level and survives the scoped-storage transition unchanged.
   Projects the user does not explicitly place elsewhere live there.
2. **Outside locations are capabilities, not paths.** A project opened from elsewhere is reached
   through its persisted SAF tree grant (`takePersistableUriPermission`), accessed as a `DocumentFile`
   / `ContentResolver` handle. Converting a picked tree back into a `File` path is the anti-pattern
   this ADR exists to end.
3. **One gateway.** All shared-storage access goes through a single `:common` component. Feature
   code must not call `Environment.getExternalStorageDirectory()` directly. This is what makes the
   migration reviewable: the gateway's surface is the exhaustive list of what still needs porting.
4. **Developer switches stop being files on shared storage.** *(Done for the `WebServer`
   sentinels: see `DeveloperOverrides`.)* They were world-writable files under shared `Download/`,
   so another app holding storage access could plant `CodeOnTheGo.webserver.debug` and turn on
   verbose request logging - request lines and rendered HTML - in someone's release build. They now
   live in the app's own files directory and resolve to unreachable paths in a release build. The
   documentation disable sentinel was moved the same way: `DocumentationRequestInterceptor` now
   takes its sentinel path as a constructor argument, resolved next to the documentation database
   in app-private storage, so a file planted under shared `Download/` has no effect. `FeatureFlags`
   was the third and worst instance and is now fixed the same way, via `FeatureFlagSource`: six
   switches were read from the *public* Downloads directory with no debug gating at all, so any app
   with storage access could plant `CodeOnTheGo.exp` to expose unfinished surfaces and raise the
   minimum free space from 4 GB to 6 GB (enough to stop the app starting), `S153.txt` to re-enable
   the x86 configuration `SplashActivity` deliberately exits on, or `CodeOnTheGo.a2s2` to switch
   StrictMode off in a release build. `FeatureFlags.initialize` now requires a `FeatureFlagSource`,
   so a call site cannot reintroduce a shared-storage read by omission.
5. **Existing projects stay where they are, reached through a SAF grant.** Decided with the product
   owner over two alternatives: copying every discovered project into the workspace on first run,
   and an explicit migration screen. Auto-copying was rejected because it silently doubles disk use
   on a device that may not have the space — exactly the population this app serves — and because a
   half-finished copy of someone's work is a worse failure than an extra tap. The cost accepted in
   exchange is that building a pre-existing project requires an explicit import step; that cost is
   visible and recoverable, whereas a failed bulk copy is neither.
6. **Explicit import/export at the boundary.** Getting a project in or out of the workspace is a
   deliberate, visible action rather than a side effect of where a file happens to sit.
7. **`TARGET_SDK` rises last.** Each step above keeps the target SDK ratchet green; the version bump
   is the final commit of the sequence, not the trigger for it.

Enforcement is mechanical, matching the approach already used for the target SDK:
`scripts/shared_storage_ratchet.py` records today's 19 call sites in
`config/shared-storage-baseline.json` — 19 at the time of writing, 10 today — and fails CI when a new one appears — or when a baselined one
disappears without being removed from the baseline. The debt can only shrink.

## Consequences

**Positive.** The permission set shrinks to something defensible; the API 30 blocker becomes
tractable work with a visible burn-down instead of an unbounded refactor; storage behaviour stops
depending on a device's FUSE quirks; the gateway gives a single place to add quota, cleanup and the
Environment Doctor's storage diagnostics.

**Negative / costs.** SAF is slower than direct file I/O and its `DocumentFile` API is awkward for
the recursive, high-fanout access a build performs — the workspace-first default exists precisely so
the slow path is the exception. Gradle and the embedded Termux toolchain need real filesystem paths,
so anything they touch must be inside the workspace, which makes "build a project that lives on a
SAF tree" mean "materialise or import it first". Existing users have projects on shared storage and
need a migration that cannot silently lose work.

**Explicitly deferred.** This ADR does not move `Environment.DEFAULT_ROOT` (`/data/data/.../files`,
the Termux prefix); that is already app-private. It does not change the plugin sandbox, and it does
not itself raise the target SDK.

## Alternatives considered

**Keep `MANAGE_EXTERNAL_STORAGE` and raise the target anyway.** Cheapest, and it is what most
on-device IDEs do. Rejected as the default: it keeps the app's widest privilege permanently, ties
distribution to a policy exemption, and leaves users unable to reduce the grant. The permission may
still be retained as an *optional* power-user mode once the workspace path works without it.

**Full SAF, no app-owned workspace.** Purest scoped-storage answer, and rejected as impractical:
Gradle, the JDK and Termux all need real paths, so an IDE built entirely on `DocumentFile` would
have to copy trees in and out for every build.

**`MediaStore`.** Wrong model — source trees are not media collections.

**Document the debt and move on.** What the improvement roadmap already did. Rejected because
without a ratchet the 19 call sites grow, and each new one makes the eventual migration larger.
