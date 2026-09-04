# Context for Next Task

> **Maintenance tip**: Keep this file updated as you make progress — update the tip commit hash,
> move completed work into the "Recently Completed" section, and revise the "Pending" section.
> This file is the primary handoff document between sessions.
>
> **Next step guidance**: Only write a "Next step" when there is something actionable *after* a PR
> merges. Don't write "get PR reviewed/merged" — this doc lives in the repo and is read post-merge,
> so those entries are immediately stale. Use `—` or omit the field when nothing remains.

## Current State (as of 2026-09-04)

### Working Branch
`development-261` — no Snowflake feature work in flight. Tip: `56d3a84cad`.

### Recently Completed Work

All four items previously listed as pending have merged into `development-261`.

**PR #37 — project SDK ordering race and symlink resolution** (merged 2026-09-02)

1. `setProjectSdk()` ran in `execute()` *before* `addBspFetchedJdks()` registered the SDK in the JDK
   table, so `findJdk()` returned null and the project SDK was never set; `cleanUpInvalidJdks()` then
   removed the old one, leaving "No SDK". Moved to `postprocessingSubtask()`. The `defaultJdkName`
   overwrite is now guarded to full sync only, since partial sync sees a subset of targets.
2. `resolveJavaHome()` now tries `toRealPath()` before falling through to wrapper resolution.
   `ide_java_home_override` paths that are symlinks (e.g. `/nix/var/nix/profiles/default`) failed
   `isValidSdkHome()` because `normalize()` doesn't resolve symlinks, and the fallback then scanned
   the Bazel execroot and picked the wrong JDK.

**PR #36 — partial sync destroying the target cache** (merged 2026-08-26)

1. `TargetPersistenceLayerSyncHook` routes `PartialProjectSync` through a new `mergePartial()` path
   that updates the target cache additively, instead of `saveAll()` → `TargetsCacheStorage.reset()`,
   which wiped every target in the project and repopulated with only the partial-sync scope.
   Root cause: 251's `TargetUtilsSyncHook` called `getOrFetchResolvedWorkspace()` with the default
   `SecondPhaseSync` scope; the upstream `TargetPersistenceLayerSyncHook` that replaced it in 261
   uses `environment.workspace` blindly.
2. `TargetsCacheStorage.addTargets()` now uses `xxh3_64`, matching `reset()` and `addFileToTarget()`.
   The `xxh3_128` mismatch meant `getTargetsForPath()` could never resolve labels added via
   `addTargets()`, breaking gutter icons and file→target lookups after partial sync.
3. `BazelFileEventListener` forces a Bazel query for `ExternalCreate` events (files submitted by
   external plugins such as Snowjet) even when the file appears to sit in an existing module's source
   root. This is a workaround for the resource-root bug below, not a fix for it.

**PR #35 — file event batch guard** (merged 2026-08-18)

A `git pull` can deliver dozens of `Create`/`ExternalCreate` events at once, triggering an expensive
inverse-sources query that chokes IntelliJ. New-file events are counted with a short-circuiting
sequence (`asSequence().filter().drop(5).any()`); over the limit, processing is skipped and the
Resync notification is shown. `NEW_FILE_EVENTS_LIMIT = 5`, declared next to `PROCESSING_DELAY`.
The check now lives in `processEventsForProject`, not `addNewFilesToBothModels` as the PR body says.

**PR #34 — shard module elimination** (merged 2026-08-20)

`java_incremental_library` splits a target into one umbrella plus N shard sub-targets; importing
shards as separate IntelliJ modules caused red code and bloated the module list. Shard-tagged targets
are filtered before the workspace/non-workspace partition, shard deps are dropped from umbrella
dependency lists, `resolveShardFolkDependencies` was removed, and `UnsyncedTargetUpdater` skips
shard-tagged targets the same way it skips `no-ide`.

**Next step**: —

### Pending / In-Progress Work

**Overly broad resource roots** — open, root cause not investigated.

During full sync, targets with a `resources = [...]` attribute get resource roots that are far too
broad: `GlobalServices/src/test/java` ends up registered as a Resources folder for a single target's
module, so `getModulesForFile()` returns that module for *any* file under the whole tree. The
downstream effect is `bazelQueryIsRequired = false` for new files in those directories — the file
looks like it belongs to a module that doesn't own it. PR #36 item 3 works around the symptom for
`ExternalCreate` events only. Suspect the resource-directory computation in the JVM sync path picks a
parent directory instead of the specific resource path.

**PR #39 — sync metrics topics** (open, authored by `sfc-gh-daiwang`)

Adds `PartialSyncResultListener` and `FullSyncResultListener` message-bus topics for Snowjet to
record sync metrics. Review posted 2026-09-04 (`#issuecomment-5545927174`); the author revised in
`3b4939b473` the same day and took most of it.

The revision cut the upstream-file delta from +141/−19 to **+37/−5**: the metrics helpers moved to a
new Snowflake-owned `PartialSyncMetrics.kt` in the same package, and `processEventQueue` now wraps the
single call site so `applyAllChanges` keeps its body. `AddFileToModuleAction.kt` grew instead
(+75/−22), which is the right place for it to grow. Correctness fixes landed too: publish moved after
`SyncStatusService.finishSync()` and wrapped in `runCatching`; the manual path now distinguishes
`query_unavailable` / `query_no_target` / `query_ran_aspect` / `query_found_synced` instead of
reporting success for all four; the racy `isSyncInProgress` re-read is gone, replaced by collapsing
`query_failed` and `query_sync_running` into one `query_unavailable` value; default outcome is now
`"unknown"`.

Outstanding at time of writing: the `runCatching` wrappers have no `onFailure`, so a subscriber that
always throws yields zero metrics and zero log output (`getOrLogException` is the platform idiom);
there is no `OUTCOME_CANCELLED`, so routine cancellations land in the `"unknown"` bucket and destroy
its value as a "a code path forgot to set this" signal; two likely ktlint failures (import ordering in
`ProjectSyncTask.kt`, `argument-list-wrapping` in `BazelFileEventListener.kt`); and the
`PartialSyncResultListener` KDoc still justifies string-typed fields by "not changing publish() call
sites in upstream files", which the refactor made untrue. Still no tests, no schema version, and no
correlation id linking a partial-sync batch to the full sync that follows it.

Note for anyone aggregating this data: `PartialSyncResult` is published per *batch*, not per user
action — `processEventQueue` runs in a `do/while` over `FileEventQueueController` batches with a fresh
holder each time, and events from separate `processEventsForProject` calls can share a batch.

### Decisions / Closed Without Merging

**PR #38 — libraries reachable only through exported deps** (closed 2026-09-03, not merged).

Unresolved imports from exports-only `java_library` shims (e.g. `spring-beans` reached through
`//GlobalServices:spring-beans`) are fixed by setting `import_depth: 1` in the project view, not by
changing the mapper. `AspectBazelProjectMapper.createProject` builds its library set from the
depth-limited frontier while `createLibrary` copies the target's full `depsList` into
`Library.dependencies`, so at `import_depth: 0` the shim is the frontier library and the maven jar it
`exports` is referenced but never defined. `LibraryGraph.toDependencyId` then emits an unprefixed
module name and the workspace model drops the unmatched dependency with no log line.

Two notes if this resurfaces: the bug class is displaced rather than eliminated — whatever sits at
the new frontier has deps one hop past it that dangle identically, and `java_incremental_library`
generates a `<name>.deps` exports-only shim per target. And a warning when a `LibraryItem.dependencies`
entry matches neither a library nor a module would make the next occurrence visible instead of silent.

### Repo Context
- This is a Snowflake fork of the JetBrains Bazel IntelliJ plugin (`hirschgarten`)
- Snowflake repo: `github.com/Snowflake-Labs/hirschgarten`
- Development branch for IntelliJ 261 builds: `development-261`
- PRs are filed against `development-261` (not `main`)
- Git remote for pushing PRs: `snowflake` (points to `github.com/Snowflake-Labs/hirschgarten`)
- Use your own `sfc-gh-*` GitHub account when creating PRs

### Fork Topology (check before editing)

Rebase cost is not uniform across files, and upstream has restructured its module layout — files this
fork keeps under `plugin-bazel/src/main/kotlin/…` often live upstream under `intellij.bazel.core/src/…`
or `intellij.bazel.backend/src/…`, so a rebase involves a rename on top of the content drift. Check
before deciding how invasive an edit can be:

```bash
git ls-tree -r --name-only jetbrains/main | grep '/<FileName>.kt$'   # empty ⇒ Snowflake-only
git log --oneline --since=<date> jetbrains/main -- <upstream/path>   # churn
```

Verified as of `jetbrains/main` @ `1e418e3c76` (2026-08-20):

| File | Upstream path | Churn since 2025-06 |
|---|---|---|
| `BazelFileEventListener.kt` | `intellij.bazel.core/src/…` | 12 commits — hottest file we edit |
| `ProjectSyncTask.kt` | `intellij.bazel.backend/src/…` | 1 commit |
| `SimplifiedFileEvent.kt`, `SyncStatusService.kt` | exist upstream | — |
| `AddFileToModuleAction.kt`, `ModuleAssignmentUtils.kt`, `UnsyncedTargetUpdater.kt` | **Snowflake-only** | edit freely |

Prefer new Snowflake-owned files in the same package over appending declarations to upstream files,
and prefer wrapping a call site over re-indenting an upstream function body.
