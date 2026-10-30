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
5. **Verify + ship**: end-to-end in a running client, perf numbers on a big world, README + docs/HANDOFF.md,
   build jar, copy `build/libs/savepoint-*.jar` to the Prism mods folder (replace older savepoint jars only),
   check no mixin/key/command clashes with the other mods there.

## Progress
(update this list at the end of every chunk)
- [x] 0  - [x] 1  - [x] 2  - [ ] 3  - [ ] 4  - [ ] 5

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
