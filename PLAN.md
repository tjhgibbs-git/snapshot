# Savepoint: plan

Fabric mod for Minecraft 26.3. Take a savepoint of a singleplayer world, playtest, revert in one click.
Repo layout, versions and build setup copy `tjhgibbs-git/tree-generator-fabric` (reference checkout:
`/tmp/claude-1000/ref/tree-generator-fabric` if still present, else `gh repo clone tjhgibbs-git/tree-generator-fabric`).
UI is reviewed in-game with `tjhgibbs-git/block-scraper` (`~/claude-projects-wsl/block-scraper`).
Mod id `savepoint`, command `/savepoint` (NOT `/snapshot`: WorldEdit owns that). Branch: `26.3`.
Java 25, Loom `net.fabricmc.fabric-loom`, JDK at `~/.local/jdk/jdk-25.0.5+8`.

## Decisions (confirmed with the user)
- Scope: singleplayer + LAN host only (integrated server). No dedicated-server support.
- Revert = automatic world reload: save, disconnect, restore files, reopen the same world. Not in-place.
- Revert always restores the player too (playerdata is part of the snapshot).
- Disk: auto-prune oldest *unpinned* savepoints when the store exceeds a budget (default 10 GB), never
  pruning pinned ones nor the newest 3. Budget, keep-newest and everything below must be easy to
  change in a settings screen with plain-English descriptions and a live "using X of Y GB" readout.
- Full backup (independent zip of the whole world) mode setting: **Off / Once per session (default) /
  Every savepoint**. After creating a savepoint, if no backup exists for this session (mode != Every),
  offer in chat a clickable "[Save full backup]". Plus a manual button. Keep last N zips (default 3).
  Zips are built from a savepoint manifest on a background thread (consistent, no gameplay impact).
- Cooldown (default 10 s, configurable, 0 = off) between savepoint creations and between reverts.
- Safety: every revert first takes an automatic "before revert" undo savepoint (flagged auto, prunable,
  but the latest is always kept).
- Quick keys: one for "Quick savepoint" (overwrites the single `quick` slot) and one for "Quick revert";
  and a key that opens the main screen. Pick defaults nothing in the pack uses (check Axiom/WorldEdit/etc.).

## Storage design
Store at `<gameDir>/savepoints/<worldFolderName>/`:
- `blobs/ab/abcdef...` content-addressed (SHA-256) opaque bytes; chunk payloads and whole small files.
- `manifests/<id>.json` per savepoint: name, note, created, pinned, auto, dimension+pos of player,
  and entries: world-relative path -> either whole-file blob hash, or for `*.mca` a table of
  chunk index -> {timestamp, hash, size, compression byte}. `.mcc` oversized chunk files = whole-file blobs.
- Everything under the world folder is captured except `session.lock`, so it is layout-agnostic
  (26.x uses `dimensions/<ns>/<dim>/region|entities|poi`).
- Snapshot: server `saveAllChunks(flush)` first. Chunks whose region-header timestamp+length match the
  previous manifest and are older than (now - 2 s) reuse the old hash without reading. Others are read
  raw (no recompression) and hashed. Hash I/O may run off-thread, but file reads happen while saving is
  disabled / on a quiesced state so nothing is torn.
- Revert: world is closed. Diff target manifest vs current files (current manifest taken as the undo
  point), rewrite only regions that differ using our own .mca writer, restore other files, delete
  files that did not exist at snapshot time. Verify blob hash on every read; refuse corrupt blobs.
- GC: delete blobs unreferenced by any manifest after delete/prune.
- Also store a thumbnail PNG per savepoint (client screenshot), shown in the UI.

## Chunks (each ends with a commit; later chunks start from a fresh context reading this file)
0. **Scaffold**: gradle (copy reference), fabric.mod.json, empty entrypoints, block-scraper wiring,
   `./gradlew build` green, .gitignore, README stub. Rename nothing from sylvan except by deliberate port.
1. **Core store** (`core/`, pure Java, no net.minecraft): mca reader/writer, blob store, manifests,
   snapshot/diff/restore/prune/GC, zip backup builder. JUnit tests on synthetic worlds AND a copy of a real
   world (`.../instances/26.3/minecraft/saves/Mods Demo World`; copy it, never touch the original).
   Tests: round-trip byte-for-byte restore, dedup (2nd identical snapshot adds ~0 bytes), corruption
   detection, prune rules, perf budget on a larger synthetic world.
2. **Server integration + commands**: config (`config/savepoint.properties`), cooldown, create/list/
   rename/pin/delete/backup commands, background execution, chat messages with clickable actions, the
   full-backup offer. Gametest where feasible.
3. **Revert flow**: server stop -> restore -> reopen world from the client (`/savepoint revert`, undo
   point first, progress screen, errors leave the world untouched). Verify with block-scraper that loot
   chests re-seal, killed mobs return, inventory/position restore.
4. **UI**: main screen (list with thumbnails, size, age, pin; Create / Revert / Delete / Backup buttons;
   confirm on revert; store usage bar), settings screen, HUD toast "Savepoint saved", keybinds.
   Port visual style/widgets from the Sylvan client package. Review with block-scraper at several sizes.
4b. Start of chunk 4 also: make `fullBackupKeep` a PER-WORLD setting (stored with the world's store, e.g.
   `<store>/settings.properties`, overriding the global default of 3); the settings screen edits the value
   for the current world.
5. **Functionality review + make it feel GREAT**: use the mod like a real playtester in a running client,
   end to end, and fix everything clumsy. Checklist: first-run experience and discoverability (does the
   player know the keys/commands?); time from key press to savepoint saved and to back in the world after
   revert (measure; trim anything avoidable, e.g. reopen path, progress screen flicker, thumbnail cost);
   feedback on every action (toasts/sounds/chat wording, cooldown messages); accidental-click safety (confirm
   on revert, undo path obvious); error paths (disk full, corrupt blob, cancelled revert, locked file on
   Windows, LAN players); large-world behaviour (server pause on first savepoint); store size honesty in the
   UI; settings clarity; UI at several window sizes/GUI scales/languages; clash check with all mods in the
   Prism mods folder; run a multi-cycle soak (create/mutate/revert x20, check store growth is ~0 and the
   world is byte-identical to the savepoint each time). Run /code-review on the diff. Fix, commit.
6. **Ship**: README + docs/HANDOFF.md, build jar, copy `fabric/build/libs/savepoint-*.jar` to the Prism
   mods folder (replace older savepoint jars only), final sanity launch.

## Progress
(update this list at the end of every chunk)
- [x] 0  - [x] 1  - [x] 2  - [x] 3  - [x] 4  - [x] 5 (review/polish)  - [ ] 6 (ship)

## Notes for next chunk
- Build: `export JAVA_HOME=~/.local/jdk/jdk-25.0.5+8 PATH=$JAVA_HOME/bin:$PATH; ./gradlew build` (~45 s warm).
  The wrapper needs JAVA_HOME/PATH set in a fresh shell; ~/.gradle/gradle.properties alone is not enough.
- Layout: `core` (java-library, release 21, JUnit 5, `perf`-tagged tests only with -Pperf), `fabric` (Loom,
  split client source set, `gametest` source set with placeholder `ScaffoldGameTests`, core jar-in-jar).
  Package roots: `io.github.tjhgibbs.savepoint.{core,mod,client,gametest}`.
- Block-scraper wiring is live: `./gradlew :fabric:runBlockScraper` exists (only configured, not launched).
  `fabric/build.gradle` comment refers to a not-yet-existing `client.SavepointScreen` class.
- `-PcoreOnly` or env `SAVEPOINT_CORE_ONLY=1` builds core alone.
- `unzip` is not installed; use `jar tf` to inspect jars. No Sylvan code was ported.
- Gametests were not run (placeholder only); `./gradlew build` does not run them.

## Notes for next chunk (from chunk 1: core store)
All in `io.github.tjhgibbs.savepoint.core` (pure Java, release 21). Tests: `./gradlew -PcoreOnly :core:test` (add `-Pperf` for the ~1 GB test).
Real-world test copies `.../saves/Mods Demo World` and is skipped if absent.
- `SavepointStore(Path root[, Options])`; root = `<gameDir>/savepoints/<worldFolder>`. `Options.defaults().withClock(..).withIoThreads(n)`.
  - `Manifest create(Path worldDir, SavepointMeta meta[, OperationControl])`; `SavepointMeta.of(Kind, name).withNote/withAuto/withPinned/withPlayer(PlayerPos)/withThumbnail(byte[] png)`
    (UNDO kind defaults auto=true). Creating a QUICK replaces older unpinned QUICK savepoints.
  - `List<Manifest> list()` (newest first, lock-free), `Optional<Manifest> get(id)`, `unreadableManifests()`
  - `rename(id,name)`, `setNote(id,note)`, `pin(id,bool)`, `setThumbnail(id,png)`, `readThumbnail(id)` -> all return/handle `Manifest`
  - `long delete(id)` (frees unreferenced blobs, returns bytes), `GcResult gc()`, `List<Manifest> prune(new PrunePolicy(maxBytes, keepNewest))` (maxBytes<=0 = unlimited; never pinned / newest N / newest UNDO)
  - `RestoreReport restore(Path worldDir, id[, OperationControl])` (world must be closed; stages+verifies then moves; rolls back on failure; throws `CorruptBlobException`/`SavepointNotFoundException`/`OperationCancelledException`)
  - `VerifyReport verify(id, ctl)`, `StoreUsage usage()` (blobBytes/manifestBytes/total()), `Map<String,Long> uniqueBytes()`, `long uniqueBytes(id)`
  - `buildFullBackup(id, Path zipFile, String rootFolderName, ctl)`; `ZipBackup.fileName(manifest)` (`savepoint-<id>.zip`), `ZipBackup.retain(dir, keepN)`
- `Manifest` (record: id,name,note,createdMillis,pinned,auto,kind,player,thumbnail,entries; `Kind` MANUAL/QUICK/UNDO/SESSION; `PlayerPos(dimension,x,y,z)`; `logicalBytes()`), JSON via `toJson()/fromJson()`.
- `OperationControl` (progress(phase,done,total) bytes; isCancelled()) is called on the calling thread; ops run on the calling thread
  (internal short-lived IO workers only). list/get/usage are safe from any thread while a create runs; other ops serialise on one lock.
- Chunk-reuse shortcut rule differs slightly from the plan (stricter, correct): reuse needs same ts+len+compression AND chunk ts older than
  (previous savepoint creation time - 2 s). Same rule applies in restore diffing. Caller must have saved/flushed the world before create.
- `session.lock` is never captured, restored or deleted; a store nested inside the world dir is skipped automatically.
- Malformed `.mca` falls back to whole-file blob; `.mcc` files are ordinary whole-file blobs, external-chunk stubs stay in the chunk table.
- Timings (this box, WSL): ~937 MB synthetic world: create 4.3 s, unchanged re-create 0.1 s, restore of 1 region 0.12 s, zip 3.5 s, verify 0.8 s.
  Real Mods Demo World (22 MB on disk, 2 MB payload): create 0.4 s, restore 0.03 s.
- Fabric module not rebuilt in this chunk (only core touched).

## Notes for next chunk (from chunk 2: server integration + commands)
Fabric main package `io.github.tjhgibbs.savepoint.mod`. Core additions: `core.Settings` (record + key table `Settings.specs()` with key/description/valueHint/get/set,
`with(key,value)` throws IllegalArgumentException with user-presentable message), `core.Cooldown`, `core.IdResolver`. Tests in core `ServiceLogicTest`.
- Config: `SavepointConfig.get()/set(Settings)/setInMemory/load/save`, file `config/savepoint.properties`. Keys: storeBudgetGb=10 (0=unlimited), keepNewest=3,
  cooldownSeconds=10 (0=off), fullBackupMode=OFF|SESSION|EVERY (SESSION), fullBackupKeep=3. UI should build rows from `Settings.specs()` (description + valueHint).
- `SavepointService.current()` (null on dedicated/not started). Created on SERVER_STARTED, closed on SERVER_STOPPING. Store root `<gameDir>/savepoints/<worldFolder>/`, zips in `backups/`.
  All player-taking methods: server thread only. `createManual(p,name)`, `createQuick(p)` (cooldown, chat line with [Revert][Pin], prune, backup offer, pushes list),
  `createUndoPoint(p)` throws IOException (no cooldown/chat; p may be null; chunk 3 must abort revert on failure), `checkCooldown(p)`/`markCooldown()`/`cooldownRemaining()`
  (shared timer: chunk 3 must call both for revert), `revert(p, token)` -> `RevertHandler` (set with `SavepointService.setRevertHandler`, default prints "not implemented"),
  `list/info/rename/pin/delete/status/showConfig/setConfig/backup/sendList`, `resolve(p, token)`, `store()`, `worldDir()`, `server()`, `mayUse(p)`.
  Mutations are refused with a chat message while a full backup zip is being written (the store lock would otherwise block the server thread).
- Create runs ON the server thread: `server.saveEverything(true,true,true)` (players + level data + chunks, flushed) then `store.create`, so no unload/autosave can tear a region. Progress -> action bar.
- Commands (`/savepoint`, requires singleplayer owner or gamemaster): create [name], quick, list, info/revert/pin/unpin/delete <id>, rename <id> <name>, backup, status, config [key value].
  `<id>` = full id, 4-hex short id, `latest`, name, or unique name prefix (quote names with spaces). Chat actions run these commands, so chunk 3 only needs `/savepoint revert <id>` to work.
- Payloads (`SavepointPayloads`, ids `savepoint:<name>`): S2C `ListPayload(entries, usedBytes, budgetBytes, cooldownRemaining, backupRunning, backupDoneThisSession, settingValues in Settings.specs() order)`
  with `Entry(id, shortId, name, kind, createdMillis, pinned, auto, logicalBytes, uniqueBytes, dimension)`; C2S `RequestListPayload`, `CreatePayload(name, quick)`, `DeletePayload(id)`,
  `PinPayload(id,pinned)`, `RenamePayload(id,name)`, `BackupPayload`, `ConfigSetPayload(key,value)`. Every mutation re-sends the list to the requester. No thumbnails yet (chunk 4 adds a payload; `store.setThumbnail`).
- Lang keys: `savepoint.msg.*` in en_us.json. Client side has NO listener for ListPayload yet (chunk 4 registers `ClientPlayNetworking`).
- GameTest: `SavepointGameTests` via `./gradlew :fabric:runGameTest` (works here, dedicated test server; uses `SavepointService.startForTesting`). Not covered: commands/chat/networking in a live client.
- Open: first create of a very large world blocks the server thread for seconds; kind SESSION unused (no automatic session savepoint yet).

## Notes for next chunk (from chunk 3: revert flow)
Verified LIVE with block-scraper on a flat creative world (typed `/savepoint ...` in the real chat): loot chest re-sealed (LootTable back), killed villager back,
placed/broken blocks reverted, inventory (7 diamonds), player position, and world time (day 6000 vs 18000) all restored; `revert undo` round-trips; a corrupted
blob made the restore fail, world reopened untouched with a red chat line + toast; Cancel on the progress screen works; cooldown message shows after a create.
Timings (tiny flat world, llvmpipe, idle-tick): command -> server stopped ~0.6-0.9 s, restore 60 ms, reopen -> rejoined ~3.3-4.3 s, total ~4.0-5.3 s.
- Flow: `/savepoint revert <id|latest|undo|name>` -> `SavepointService.revert` -> `RevertFlow.begin` (mod pkg, server thread; owner-only, cooldown check, `createUndoPoint`
  (abort on failure), re-check target not pruned, `markCooldown`, chat line, S2C `BeginRevertPayload(levelId, worldDir, storeRoot, targetId, targetName, undoId)`) ->
  client `RevertCoordinator` (src/client, tick-driven state machine DISCONNECT -> RESTORE (worker thread, `RevertProgressScreen`) -> REOPEN (`openWorld(levelId)`) -> AWAIT_JOIN ->
  chat `Reverted to "X". Undo with /savepoint revert undo [Undo]` + toast). Restore failure/cancel: world is reopened anyway, red chat line + toast; if the reopen itself fails an
  `AlertScreen` explains. `RevertCoordinator.isActive()` lets UI disable buttons. Chunk 4 UI must trigger a revert by sending the command `/savepoint revert <fullId>` (or a
  C2S payload that calls `svc.revert(p, id)`); never call the coordinator directly.
- `undo` alias lives in `core.IdResolver` (newest `Kind.UNDO`). `SavepointService.storeRoot()` added; the cooldown timer is now STATIC so it survives the server restart.
- Dev knob: `-Dsavepoint.debugRestoreDelayMs=N` slows restore progress callbacks (to inspect the progress screen). Start the client with `JAVA_TOOL_OPTIONS=...`.
- block-scraper quirks (not our bug): `block-scraper command` keeps the ORIGINAL integrated server, so after a revert it hits a dead server; use typed chat
  (`key slash`, `type ...`, `key enter`) instead. `command` has no player entity so `/savepoint` is "unknown" there; `execute as @p run savepoint` also fails
  ("Incorrect argument"), typed chat works. `time query daytime` no longer exists (use `time query day`). Start with `--idle-tick` or ticks (and so the coordinator) stall.
  A new throwaway world may be named `New World (1)`; its store is `<gameDir>/savepoints/<that name>`.
- Fixed a chunk-2 cosmetic: `/savepoint list` showed `[[id]]`.
- Not verified: LAN-hosted worlds (other players are simply disconnected by the server stop; untested), a real world with mods/large size (restore of big worlds is
  progress-bar territory but untested), the progress bar fill (screenshot only showed the bar at 0 because the delay hack gives few callbacks), reopen when the world
  open flow shows its own warning screens (onCancel routes to an AlertScreen). The world-open `onCancel` path is untested. Restore needs the world closed: if a
  previous server thread were still holding region files (not seen on Linux) the restore would fail cleanly and show the error.
- Open: the integrated server's own final save on stop is harmless (restore happens after). "Before revert" undo points are all named identically; chunk 4 UI could
  show the target name/time in the list. After a revert the old `SavepointService` is gone and a new one is created on rejoin (list payloads must be re-requested).

## Notes for next chunk (from chunk 4: UI)
Client package `io.github.tjhgibbs.savepoint.client`: `SavepointScreen` (list + detail panel + create row + usage bar; arrows/PgUp/PgDn/Home/End/Del),
`SavepointSettingsScreen` (rows from `Settings.specs()`, step sliders, debounced ConfigSet sends, per-world heading), `RevertConfirmScreen`
(quick-revert key pressed again confirms; Shift+F8 skips the dialog), `PromptScreen` (rename), `ClientState` (last ListPayload + local cooldown clock),
`Thumbs` (lazy thumbnail fetch, DynamicTextures released on screen close), `SceneShots` (frame grabbed when a screen opens / F7, shrunk to a 16:9 PNG
by `PngEncoder`, uploaded on CreatedPayload; undo points get it written straight into the store by `RevertCoordinator`), `UsageBar`, `ClientFmt`, `Names`.
Keys: K open, F7 quick save, F8 quick revert (checked against Axiom/WorldEdit/Sylvan/Capella/Stacksmith). "Savepoints" button top-left of the pause menu.
- Verified with block-scraper: 854x480, 1280x720 (scale 3), 1920x1080 at scale 2 and 4, de_de fallback; create/quick/pin/revert via the UI; settings slider
  reaches the server and the config file. Revert via UI: total ~4.1 s (stop 0.9 s, restore 58 ms, reopen 3.1 s).
- block-scraper disables toasts, so toasts (saved / cooldown / unavailable) cannot be seen in shots. `reload`/`restart` need JAVA_HOME exported.
- Fixed during review: Create/Quick buttons clipped "Create (9s)"; action bar stuck on "Saving savepoint... 0%"; backup offer repeated after every save
  (now once per world per game session); pinned quick showed "replaced by the next quick save"; detail thumbnail hid the text at large GUI scales.
- Open for chunk 5: at GUI scale 3 / 720p the detail panel has no room for a thumbnail and drops the last line ("Deleting frees"); settings changes from
  the GUI also print a "Set key to value." chat line.

## Notes for next chunk (from chunk 5: review + polish)
- BIG FIX: 26.3 does not pad a region file's last sector, so `RegionFile.open` rejected every live region and they were stored as whole-file
  blobs (no chunk dedup; ~950 KB per revert cycle on a tiny flat world). Now only the chunk's real bytes must be inside the file. Test
  `unpaddedRegionIsReadAsChunks`. The Mods Demo World regions happen to be padded, which is why RealWorldTest never caught it.
- Live soak (block-scraper, 10x place block + Shift+F8): every block reverted, store growth ~35 KB/cycle (chunks the game re-saved),
  revert totals 2.5-3.7 s (stop ~0.4-0.9 s, restore ~20 ms, reopen ~2-3 s = vanilla world load). Core `SoakTest`: 20 cycles byte-identical,
  repeated playtest adds only manifest bytes.
- UX: one-time chat hint naming K/F7/F8 on a world with no savepoints; revert confirm warns when the world is open to LAN; GUI setting changes
  no longer print a chat line (the command still does).
- /code-review high fixes: `SavepointStore.uniqueBytes()` is now lock-free (lists no longer wait behind a zip; the service's stale cache is gone);
  thumbnail upload rechecks on the worker; `settings()` cached (invalidated by `setWorldSetting` / a new global Settings); lowering
  fullBackupKeep prunes zips immediately; undo labels no longer nest ("Before revert -> Before revert -> X") and cut surrogate-safe;
  global config comment for fullBackupKeep reworded; `WorldSettings.clear` canonicalises the key.
- Not done / not testable here: disk-full and Windows locked-file paths (no Windows box), LAN with a second player, very large world first-create
  pause (first savepoint must hash everything; later ones only changed chunks), toasts (block-scraper hides them). Review finding left as-is:
  WorldSettings/SavepointConfig duplicate a small properties load/apply loop.
- `./gradlew build` now also runs the gametests (`runGameTest`).
