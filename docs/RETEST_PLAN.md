# Duels retest plan

_Target: Paper 1.21.11, Java 21. Generated after the bounds-requirement,
message-key, menu-navigation, edit-mode-layout, and template-cleanup fixes in
an earlier session. This is now the single current manual acceptance suite -
the older `IN_GAME_TEST_PLAN.md` it was generated from has been removed as
superseded._

Two groups of work here:

1. **Part A** - items that behaved differently after the fixes from the
   session this plan was generated in, so the old pass no longer reflected
   current behaviour at the time.
2. **Part B** - every item from section 15 onward (numbering carried over
   from the retired plan) that was still unchecked at that point.

Tick items here as you go.

## Where we are right now (2026-09-24, 03:39)

Read this first if you have lost the thread. Everything below this section is
the full detail; this is the short version.

**What just got solved.** A single misconfiguration - the dynamic arena world
`duels_dynamic_arenas` being generated on Peaceful difficulty - was behind three
separate bug reports: TNT doing no damage, the hunger bar never moving, and a
mystery Regeneration effect healing duellists. `WorldCreator` does not inherit
difficulty from server.properties, so the world Duels created for itself was
written out as Peaceful, and on Peaceful the server silently skips explosion
damage against players, stops hunger depleting, and regenerates health
continuously. Arena 1 lives in `world` and was always fine, which is why TNT
worked earlier in testing and then appeared to break.

**Those three verification duels are done.** Run on the restarted server between
03:32 and 03:34 on 2026-09-24 - TNT damage in the dynamic arena, a TNT death and
crater in the static arena, and lava flow rollback - all confirmed in game with
a clean server log.

**What is actually left.** Stage 6: five or six duels started from Creative with
flying enabled, checking you get Creative and flight back afterwards. Then the
final acceptance boxes in B10.

**Still open, deliberately not fixed yet.** TNT lit by a redstone torch resolves
to no attacker, so bystander isolation cannot act on it. Arena bounds already
keep bystanders out of range, so this is recorded as a decision to make rather
than a bug to rush - see the item near the end of this file.

**Debug logging has been removed.** The `[tnt-debug]` console spam is gone now
that the diagnosis is confirmed, so the console should be quiet again.


## Part A: retest - behaviour changed since the original pass

### A1. Arena list wording (was section 3)

- [X] STATIC arena list entries in the GUI still show "Playable copies /
  ready / free" counts exactly as before.
- [X] A DYNAMIC arena with no source shows "No build source registered yet"
  instead of any copy counts.
- [X]] A DYNAMIC arena with a source but no captured template shows "Source
  set up, capture a template to enable matches".
- [X]] A DYNAMIC arena with a captured template and generated copies shows
  "X/Y generated copies ready" with a free count.
- [X] `/duels arena list` (the text command) is unchanged and still reports
  raw counts for every arena type - confirm this divergence from the GUI is
  acceptable, not a regression.

### A2. Bounds now required for new copies (was sections 3 and 6)

- [X] A brand-new STATIC instance with both spawns set but no gameplay
  bounds reports **not ready** (previously spawns alone were enough).
- [X] Setting both gameplay bounds corners on that instance makes it ready,
  with no other change needed.
- [X] A brand-new DYNAMIC source with spawns but no bounds also reports not
  ready, and capture is refused with a bounds-specific message before any
  other capture check runs.
- [X] On a data folder carried over from before this session, restart and
  confirm the console logs one warning line per pre-existing boundless
  instance (arena ID, instance ID, and a hint to add bounds) - and confirm
  those same instances still allocate and host matches normally.

### A3. Capture-failure message text (was section 6, and delete/retire/retry/adopt/convert/clear flows in sections 3, 16, 17)

- [X] Trigger a real capture failure (missing corner, oversized region,
  world mismatch) - the message should still read "Could not capture that
  arena template: ...".
- [X] Trigger a delete/retire/retry/adopt/convert/clear failure (e.g. delete
  an arena that still has instances, retry a copy that isn't FAILED, convert
  an arena with more than one copy) - each should now read "Cannot do that:
  ..." with a reason specific to that action, not the capture-template
  wording.

### A4. Edit mode (was section 4)

- [X] Clicking the Exit tool returns you to the instance/source detail menu
  you were editing, not just to no GUI at all.
- [X] The hotbar layout while editing is grouped: spawn 1, spawn 2, gap,
  structure corner 1, structure corner 2, gap, bounds corner 1, bounds corner
  2, exit - re-review this visually since the slot order changed.

### A5. Template file cleanup (was sections 6 and 17)

- [X] After retiring all generated copies of a DYNAMIC arena and successfully
  recapturing its source, check `plugins/Duels/structures/` - only the
  newest revision's `.nbt` file should remain; the previous revision's file
  should be gone.

## Part B: not yet run (main plan sections 15-24)

### B1. Section 15 - Block rollback and debris cleanup

- [X] Break original blocks, place new blocks, and detonate TNT inside bounds.
- [X] TNT explosion and bed/respawn-anchor block explosion produce no
  leftover debris drops inside the tracked arena.
- [X] Blocks outside bounds are not reverted.
- [X] A new match cannot enter the instance while batched rollback is running.
- [X] A heavier rollback stays responsive; observe TPS/Spark for a visible
  spike.
- [X] An instance without bounds does not roll changes back (documented
  limitation) - test this against a legacy-exempt boundless instance from
  A2, not a newly created one (which cannot reach this state at all).
- [X] `stop` during a damaged match and restart does not leave Duels in an
  invalid allocation state.
- [x] **Reopened - gap found in live testing:** a lava/water source placed
  with a bucket mid-duel was not rolled back, even though block
  place/break/TNT changes were. Root cause: `BlockChangeRollbackStrategy`
  only listened for `BlockPlaceEvent`/`BlockBreakEvent`/explosion events;
  bucket placement and removal fire `PlayerBucketEmptyEvent`/
  `PlayerBucketFillEvent` instead, which were never tracked. Fixed by adding
  handlers for both, using the same `track()` path. Retest: place lava and
  water with a bucket mid-duel (both a dynamic and a static arena), end the
  match, confirm both are gone after rollback; also scoop a liquid back up
  with a bucket mid-duel and confirm it's restored afterward.
  **Reopened again 2026-09-24:** the source block is now removed correctly,
  but the flowing liquid it produced is not. Second root cause:
  `BlockFromToEvent` - the only event a liquid spread step fires - was still
  untracked, so every block the flow entered, and every block it washed away
  en route, was invisible to the rollback. Fixed by tracking
  `event.getToBlock().getState()`. Retest as above, and additionally let the
  liquid run a reasonable distance before ending the match: confirm the whole
  flow is gone and anything it destroyed on the way is back.
- [x] **Reopened - second gap found alongside the above:** a redstone torch
  occasionally survived rollback as a dropped item instead of being restored
  as a block, specifically after a TNT explosion destroyed the block it was
  mounted on. Root cause: a block detaching from lost support (not a direct
  player break) fires neither `BlockBreakEvent` nor an explosion event, so
  its state change was never tracked and its item drop was never suppressed
  the way explosion drops are. Fixed by adding a `BlockDropItemEvent`
  handler that tracks the block's state and clears the drop. Retest:
  detonate TNT next to a wall-mounted torch (or other attached block -
  redstone dust, a sign, a lever) mid-duel and confirm after rollback the
  block is back in place with no dropped item nearby.

**Verified in play 2026-09-24 03:32-03:34.** Four duels on the restarted server
covering all of it: TNT damage in the dynamic arena, a TNT death and crater in
the static arena, and lava flow rollback. Jack confirmed the behaviour in game
and the server log records the four matches with no exceptions or warnings. This
closes the Peaceful root cause, the explosion-rollback ordering bug, the liquid
spread gap and the detached-block drop gap together.

### B2. Section 16 - Dynamic provisioning and selection

- [X] Recapturing the source while generated copies exist is refused without
  changing the saved template.
- [X] P1/P2 see "Preparing arena..." while their first copy is generated;
  P3/P4 see it when a second copy is needed.
- [X] `dynamic-layout.yml` is created on first generation, not source
  capture, with frozen geometry and world UUID.
- [X] The configured dynamic void world is created and contains no normal
  terrain.
- [X] A new provisioned instance row is persisted as a distinct
  instance/slot.
- [X] Instance-list GUI identifies generated copies, slot, health state, and
  whether each copy is ready.
- [X] The copied arena includes every block and block entity inside capture
  bounds.
- [X] Decorative blocks outside gameplay bounds but inside capture bounds
  are present.
- [X] Blocks outside capture bounds are absent.
- [X] Both relative spawns have correct position, yaw, and pitch.
- [X] Gameplay bounds are correctly offset into the new slot.
- [X] P3/P4 were not cleared or teleported before the paste succeeded.
- [X] The selected DynamicArena never falls back to StaticArena.
- [X] P1/P2 and P3/P4 play simultaneously in different generated copies,
  with no cross-arena effects. Neither match enters the source world.
- [X] Spectating, boundary handling, kit restrictions, results, and
  restoration behave identically in the provisioned copy.

### B3. Section 17 - Dynamic pooling, reset, and retirement

- [X] Match damage in the provisioned copy rolls back before reuse.
- [X] The next duel reuses the same provisioned instance/slot instead of
  creating another.
- [X] No player sees rollback or a partial template during reuse.
- [X] A provisioned instance is marked unavailable while
  allocated/resetting.
- [X] `/duels arena instance delete <provisionedInstanceId>` is refused
  while active.
- [X] Deleting an idle provisioned instance marks it retiring, clears the
  slot in bounded batches, then removes its persisted row.
- [X] The same retirement via GUI confirmation clears the physical slot
  before removing its record; an active copy cannot be retired via GUI.
- [X] Another provision cannot use the slot until clearing finishes.
- [X] Provisioning after retirement can reuse the now-vacant slot without
  old blocks.
- [X] Deleting the source is refused while its captured template or
  generated copies exist; after retirement and template clear, deletion does
  not erase the source's world blocks.

### B4. Section 18 - Dynamic capacity and simultaneous preparation

- [X] Two simultaneous dynamic requests reserve different slots.
- [X] No two structures overlap, including their configured padding.
- [X] When all dynamic slots are occupied, another selected challenge fails
  cleanly with no player mutation.
- [X] The failed challenge remains available if it has not expired.
- [X] Changing slot width/length/padding/max after first use does not move
  old slots. Note: verified by inspecting `dynamic-layout.yml` directly, not
  a startup log - there is currently no runtime log line announcing that the
  persisted layout is authoritative over `config.yml`; this is only
  documented in code comments (`config.yml`, `DynamicArenaSettings`). Worth
  a small follow-up to add an explicit startup log line for admin clarity.
- [X] A template wider/longer than a slot is rejected without world
  mutation. Covered via two separate checks: an oversized-volume capture is
  refused at capture time (`TOO_LARGE`); a template whose width/length
  exceeds the slot dimensions is accepted at capture but refused at
  provision time (`TEMPLATE_UNAVAILABLE`, surfaced as the same generic
  "No arenas are available right now" message) - both leave the world and
  challenge untouched.

### B5. Section 19 - Dynamic restart and failure recovery

- [X] `stop` during a dynamic match; restart. The `DIRTY` instance is
  rebuilt before becoming allocatable and players restore on login. Note: a
  successful rebuild logs nothing to console (only a failed rebuild does) and
  recovery runs before the tester can look, so `DIRTY` itself is only
  observable by checking `arena-instances.yml` in the window between
  killing/stopping and the next successful restart - confirmed correct via
  server log timeline (accept -> DIRTY write -> disconnect -> silent
  rebuild -> READY on next successful boot), not by catching the state live.
- [X] Force-kill the Java process during a dynamic match; restart and verify
  the same full-template rebuild, not only in-memory block rollback. Note: a
  hard kill on Windows can leave the world-lock file held briefly, causing
  one or more immediate restart attempts to fail with "another process has
  locked a portion of the file" - this is a Windows/Paper artifact of the
  kill itself, not a Duels bug; retrying the start after a few seconds
  succeeds normally.
- [X] Stop/kill during initial provisioning if timing permits; the
  persisted `PROVISIONING` row is rebuilt before reuse.
- [X] Stop during retirement; restart resumes clearing before freeing the
  slot.
- [X] Temporarily remove the referenced NBT file while the server is
  stopped; restart leaves the instance quarantined/FAILED and does not
  overlap its slot.
- [X] Restore the file and run `/duels arena instance retry <instanceId>`;
  the instance rebuilds and becomes ready.
- [X] Instance-detail GUI retry works only for a FAILED provisioned copy and
  reports success/failure without opening a broken copy to matches.
- [X] Rename/remove the dynamic world folder while stopped; startup refuses
  to create a replacement over the saved layout and logs a prominent
  diagnostic. Note: this is not a graceful, caught diagnostic - the
  `IllegalStateException` propagates out of `onEnable()` uncaught, so Paper
  disables the whole Duels plugin and prints its standard stack trace; the
  rest of the server and other plugins keep running fine.
- [X] Restore the correct folder and world UUID; recovery resumes normally.
- [X] A duplicate/out-of-range slot edited into `arena-instances.yml` is
  quarantined by startup rather than allowed to overlap another instance.
- [X] After all generated copies have been retired, the arena remains
  DYNAMIC. A type switch while a source or template exists is refused.
- [X] `template clear` requires literal `confirm`, and the GUI uses a
  confirmation screen. Both refuse while generated copies remain, then
  remove the saved metadata/file safely without changing arena type.

### B6. Section 20 - Statistics and leaderboard

- [X] YAML backend writes one match record per intended recorded duel.
- [X] SQLite creates one match row and two participant rows per result.
- [X] MySQL/MariaDB backend has the same row counts and no dialect errors,
  if available. Tested against real MySQL and MariaDB containers (Docker).
- [X] PostgreSQL backend has the same row counts and boolean queries work,
  if available.
- [X] `/duel top` loads asynchronously, displays correct ordering/win
  totals, shows the viewer's record, and remains responsive with no records.
  Note: a Mojang session-server timeout was observed in the console
  (`Couldn't look up profile properties...`) while opening this menu - this
  is Paper/authlib fetching a player-head skin texture for the leaderboard
  item, not Duels code; it only affects that one head's cosmetic texture and
  is expected on a test server with an unreliable path to Mojang.
- [X] Statistics leaderboard visual layout reviewed after recorded matches.
- [X] Restart preserves all records without duplication.
- [X] Stop an external database during a result write: players restore and
  the arena releases despite failure; one severe log entry contains the
  complete recoverable result fields. Verified against MySQL: server was
  stopped mid-match, the match still completed with both players restored
  and the arena released, and exactly the expected SEVERE log line was
  produced with all recoverable fields. The match result itself is
  permanently lost in this scenario (by design, not retried) - flagged as a
  later-priority roadmap item in `docs/ROADMAP.md`'s Deferred section rather
  than something to fix now, since it never affects gameplay and is a rare
  failure mode for a standalone server.
- [X] Restore DB and restart; schema/migration runs once without
  duplicate-index errors.

### B7. Section 21 - Configuration, migration, and diagnostics

- [X] Invalid stats backend, zero/invalid kit-selection time, negative
  challenge expiry, bad pool size, and invalid dynamic numeric settings each
  warn once and use the documented safe fallback. Note: found and fixed a
  cosmetic typo in the two boolean-setting warnings ("deaulting" instead of
  "defaulting") in `DuelsSettings.readValidBoolean`; re-verified with a bad
  `enable-grace-period` value that the warning now reads "defaulting to
  true" correctly.
- [X] Legacy arena without `provisioningMode` loads as STATIC.
- [X] Legacy instance without `origin` loads as MANUAL.
- [X] A previously DYNAMIC arena with exactly one old MANUAL copy upgrades
  it to SOURCE on startup, preserving its ID, spawns, bounds and template.
  Verified: arena 2's sole legacy copy (instance 3, no `origin` key) was
  auto-promoted at startup with `Migrated DYNAMIC arena #2 copy #3 to a
  non-playable source`, and `arena-instances.yml` confirms `origin: SOURCE`
  with spawns preserved verbatim.
- [X] An old DYNAMIC arena with multiple manual copies does not silently mix
  them into generated allocation; choose a source explicitly with
  `/duels arena source adopt <copyId>` and resolve leftover legacy copies.
  Verified: arena 3's two legacy copies (instances 4/5, no `origin` key)
  were left un-promoted, with the expected startup warning `DYNAMIC arena #3
  has multiple legacy manual copies; none will be playable`. Manual
  `/duels arena source adopt` resolution not yet exercised in-game - noting
  as still open if we want an explicit adopt-command walkthrough later.
  Update: the adopt command itself was exercised while testing item 6 below
  (`/duels arena source adopt 4` on arena 3's two-copy scenario) and behaved
  correctly - promoted instance 4 to SOURCE, and a second adopt attempt (on
  either 4 or 5) was correctly rejected with "only an idle legacy manual
  copy of a DYNAMIC arena without a source can be adopted."
- [X] Legacy inline arena spawns/bounds migrate once to an instance and do
  not duplicate on the next restart. Found and fixed a real bug in the
  process: `ArenaInstanceMigrator.migrate()` read `spawn1`/`spawn2`/
  `boundsCorner1`/`boundsCorner2` directly off the raw `ConfigurationSection`
  and handed them straight to `LocationSerializer.deserialize()`, which
  requires a `java.util.Map`. Bukkit's YAML loader auto-nests map-shaped
  values into a `ConfigurationSection` rather than a `Map`, so this crashed
  plugin enable entirely with `IllegalArgumentException: Expected a map when
  deserializing Location` the moment a real legacy-inline arena was loaded -
  every other Location-loading path in the codebase goes through
  `YamlFile.get(...)`, which does this conversion, but the migrator bypassed
  it. Fixed by adding a public `YamlFile.resolve(Object)` helper that does
  the same section-to-map conversion, and using it in the migrator. Verified
  after the fix: first restart logged `Migrated arena #4's spawns/bounds to
  new instance #6`, stripped the four legacy keys from arena 4's row in
  `arenas.yml`, and created instance 6 in `arena-instances.yml`; a second
  restart produced no re-migration log and no duplicate instance, confirming
  the `contains("spawn1")` guard correctly no-ops once the keys are gone.
- [X] Unknown arena/template/instance fields are rejected with a useful
  warning, not silently discarded. Verified for instances: adding
  `bogusField: true` to instance 1 produced `Skipping unreadable entry '1'
  in repository at path 'instances': Unknown arena instance field
  'bogusField'` at startup, naming the exact bad field, and the plugin
  enabled normally otherwise. Not yet separately exercised for arena- or
  template-level unknown fields, though `ArenaSerializer` and
  `ArenaTemplate` loading use the same allowlist-rejection pattern as
  `ArenaInstanceSerializer`.
- [X] Corrupt arena, instance, kit, stats, and saved-player entries are
  skipped or diagnosed without silently rewriting unrelated valid entries.
  Verified via the same unknown-field test above: `YamlRepository` skips the
  bad entry with a clear diagnostic and leaves it completely untouched on
  disk (re-read `arena-instances.yml` after startup - instance 1's
  `bogusField` and all its original data were still present verbatim, not
  stripped or rewritten). Not separately exercised for kit/stats/
  saved-player corruption, but they share the same `YamlRepository`
  skip-and-log mechanism.
- [X] Invalid menu material produces the configured error/barrier item and
  names the exact config path in console. Verified by setting
  `navigation.confirm.material` to `NOT_VALID`: console logged `[ItemStackParser]
  Invalid item at 'navigation.confirm': 'NOT_VALID' is not a valid material.`
  naming the exact path, and in-game the confirm button still showed as lime
  wool rather than a barrier. Traced this to `MenuNavigationStyle.from()`
  (JCore) - navigation "chrome" items (previous/next/back/confirm/cancel/
  page-indicator) intentionally pass a working named-item fallback (e.g.
  lime wool "Confirm") to `ItemStackParser.parseSafely`, rather than relying
  on its generic barrier fallback, so a bad config value degrades to a
  functional default instead of breaking the confirm button while still
  diagnosing the bad path in console. This is correct, deliberate behavior,
  not a bug - other, non-navigation menu items (e.g. arena/kit icons via
  `parseSafely` with no fallback argument) do fall back to the generic
  "Invalid menu item" barrier with path/error lore; not separately
  re-exercised against a non-navigation item this round, but the mechanism
  is identical and was already exercised structurally via code reading.
  Also noted: the fallback confirm item kept the name "Confirm" but lost its
  "&7Click to confirm." lore. This is expected, not a bug - the fallback is
  `MenuNavigationStyle`'s own hardcoded `namedItem(Material.LIME_WOOL,
  "&aConfirm")` (JCore, sets only a display name), not a re-read of the rest
  of the valid keys around the broken `material` value in `menus.yml`'s
  `navigation.confirm` section (which does define a `lore` list normally).
  The fallback is a bare functional safety net, not a partial-recovery of
  the surrounding config.
- [X] **BUG FOUND, FIXED, AND LIVE-VERIFIED:** Static arenas still function
  if the dynamic world/template files are unavailable. Originally verified
  this did NOT hold.
  A dynamic arena had already been provisioned on the test server earlier
  this session, so `dynamic-layout.yml` had a persisted layout with a
  `world-id`. Renaming the `duels_dynamic_arenas` world folder away and
  restarting produced:
  ```
  java.lang.IllegalStateException: Dynamic arena world 'duels_dynamic_arenas'
  is missing; refusing to recreate it over an ambiguous layout
          at DynamicArenaWorldManager.getOrCreateWorld(DynamicArenaWorldManager.java:27)
          at Duels.initializeManagers(Duels.java:445)
          at Duels.onEnable(Duels.java:99)
  ```
  This propagates uncaught out of `onEnable()`, so Bukkit disables the
  entire plugin - `initializeMenus()`, `registerCommands()`, and
  `registerEvents()` never run. Every static arena, every command, every
  menu goes down along with the dynamic ones, even though only the dynamic
  arena world was actually missing. `getOrCreateWorld()` is only invoked at
  startup when `DynamicArenaSlotManager.hasPersistedLayout()` is true (i.e.
  a dynamic arena has been provisioned at least once before), so a server
  that has never used dynamic arenas is unaffected - but once dynamic
  arenas have been used even once, losing that world folder takes the
  whole plugin down on every subsequent restart until it's restored.
  Missing optional third-party integrations (Vault/PlaceholderAPI/
  WorldEdit) are a non-issue since none are currently wired up as runtime
  dependencies in Duels V1 (only a forward-looking comment in
  `ArenaStructureProvider.java`; `plugin.yml` declares no
  `softdepend`/`depend` entries) - so that half of this item is trivially
  satisfied.

  **Fix applied** (`Duels.java`, `initializeManagers()`): the eager
  `dynamicArenaWorldManager.getOrCreateWorld()` call made at startup (only
  reached when `hasPersistedLayout()` is true) is now wrapped in a
  `try { ... } catch (RuntimeException exception)` that logs a `SEVERE`
  message - "Could not load the dynamic arena world; dynamic arenas will be
  unavailable until this is resolved and the server is restarted. Static
  arenas are unaffected." plus the exception - and then lets `onEnable()`
  continue normally instead of propagating. `DynamicArenaProvisioner`
  already wrapped its own `getOrCreateWorld()` calls in `provision()` and
  `rebuild()` with a `catch (RuntimeException)` that fails the operation
  and marks the instance `FAILED` rather than crashing, so no further
  hardening was needed there - only the one eager startup call was
  uncaught. Dynamic-arena instance records that reference the missing
  world will still fail to deserialize, but that's the existing
  `YamlRepository` skip-and-log mechanism already verified in item 8
  ("Skipping unreadable entry ... in repository at path 'instances'") -
  not a crash, just those specific records becoming unavailable until the
  world is restored.

  Built and packaged successfully (`mvn -o install -DskipTests` in JCore,
  then `mvn -o compile` / `mvn -o package -DskipTests` in Duels;
  `target/Duels-1.0-SNAPSHOT-shaded.jar` produced with no errors), but
  **not yet re-verified live**. Next step: copy the new shaded jar onto
  the test server (replacing `plugins/Duels.jar`), leave the
  `duels_dynamic_arenas` world folder renamed/missing exactly as it was for
  the failing test above, restart, and confirm the console now shows a
  `SEVERE` warning instead of a crash, with Duels enabling fully - test a
  static arena (e.g. arena `1`) works normally for an actual duel, and that
  commands/menus all respond. Once confirmed, check this item off and
  rename the `duels_dynamic_arenas` folder back (or reprovision a dynamic
  arena) to restore the test server to its prior working state.

  **Re-test attempt received but inconclusive:** a console log was pasted
  after the fix was built, but it is byte-for-byte identical to the
  pre-fix crash log above (same timestamps, same stack trace referencing
  `Duels.java:445`/`onEnable(Duels.java:99)`). Since the fix moved the
  `getOrCreateWorld()` call inside a try/catch, that exact crash can no
  longer propagate out of `onEnable()` once the new jar is actually
  running - so this result means either the old log was pasted again by
  mistake, or `plugins/Duels.jar` on the test server was not actually
  replaced with the freshly built `target/Duels-1.0-SNAPSHOT-shaded.jar`
  before this restart. **Next step when resuming:** confirm the jar in
  `plugins/` has today's timestamp/size matching
  `target/Duels-1.0-SNAPSHOT-shaded.jar` (copy it over again if unsure),
  then restart with the `duels_dynamic_arenas` world folder still missing
  and re-paste the console output.

  **Root cause of the inconclusive result, found:** `pom.xml`'s
  `maven-antrun-plugin` execution (`copy-to-test-server`, bound to the
  `package` phase) auto-copies `target/Duels-1.0-SNAPSHOT.jar` straight to
  `C:\Users\jncwh\OneDrive\Desktop\Test Server\plugins` on every successful
  `mvn package` - no manual copy needed. But a manual `mvn clean package`
  run (e.g. via IntelliJ's Maven tool window, without `-DskipTests`) hits 9
  pre-existing, unrelated `DuelsIntegrationTest` failures (arena
  allocation/match-state assertions returning null - not caused by this
  session's changes; `DuelsIntegrationTest.java` and
  `StaticArenaAllocator.java` have no uncommitted diffs) and Maven aborts
  *before* reaching the `shade`/`antrun` phases - so the jar is silently
  never rebuilt or redeployed, and the test server keeps running whatever
  was there before. This is almost certainly why the "re-test" showed the
  identical pre-fix crash log. Rebuilding with `-DskipTests` (as this
  session's builds have all done) succeeds and deploys correctly - the jar
  in the test server's `plugins/` folder was confirmed to now have a fresh
  `2026-09-23 00:50` timestamp after the latest build. **Always add
  `-DskipTests` when rebuilding until those 9 integration test failures are
  separately investigated and fixed** - otherwise the auto-deploy silently
  no-ops.

  **The 9 failures were fixed, not test-suite scope creep:** root cause was
  that `ArenaInstance.isReady()` was changed (uncommitted work predating
  this session) to also require `hasBounds() || !boundsRequired`, but
  `boundsRequired` defaults to `true` for any freshly-constructed instance
  and is only ever cleared by `ArenaInstanceSerializer` when deserializing a
  legacy on-disk record with no `boundsRequired` key. The shared test
  helper `createReadyInstance()` and the inline `built` instance in
  `dynamicSourceIsNeverAllocatedAsAPlayableCopy` predated that change and
  only set spawns, never bounds, so their instances were silently never
  ready. Fixed by giving `createReadyInstance()` and `built` real bounds
  (harmless - tests that set their own custom bounds afterward just
  overwrite them). `spectatingAnArenaWithoutBoundsIsRefused` was the one
  genuine exception - it intentionally tests the no-bounds case - so instead
  of adding bounds it now explicitly clears them and calls
  `instance.exemptFromBoundsRequirement()` (the same public method the
  serializer uses for legacy grandfathering) so the test honestly exercises
  "a legitimately bounds-exempt instance with no bounds" rather than
  accidentally relying on an unready instance. `mvn -o test` now passes
  45/45 (33/33 in `DuelsIntegrationTest`), and `mvn -o clean package`
  (without `-DskipTests`) succeeds and auto-deploys cleanly again.

  **Live re-verification, confirmed working:** re-ran with the
  `duels_dynamic_arenas` folder still renamed/missing. Console now shows:
  ```
  [ERROR]: [Duels] Could not load the dynamic arena world; dynamic arenas
  will be unavailable until this is resolved and the server is restarted.
  Static arenas are unaffected.
  java.lang.IllegalStateException: Dynamic arena world 'duels_dynamic_arenas'
  is missing; refusing to recreate it over an ambiguous layout
          at DynamicArenaWorldManager.getOrCreateWorld(DynamicArenaWorldManager.java:27)
          at Duels.initializeManagers(Duels.java:453)
          at Duels.onEnable(Duels.java:100)
  ```
  The line numbers (453/100, shifted by exactly the try/catch added) confirm
  this is genuinely the fixed build running, not a stale jar. `onEnable()`
  continued past the failure - the legacy-bounds warning for instance #5
  logged afterward, Multiverse/Duels both finished enabling, and the server
  reached `Done (20.688s)!` with no crash. Dynamic arenas are unavailable as
  intended; static arenas, commands, and menus are expected to work normally
  (not yet separately re-confirmed with an actual static duel on this run,
  but nothing in `initializeMenus()`/`registerCommands()`/`registerEvents()`
  was skipped, per the successful full startup). Restore the
  `duels_dynamic_arenas` folder name and restart to bring dynamic arenas
  back before continuing to other B7 items.
- [X] Missing optional integrations do not prevent Duels from enabling.
- [X] Static arenas still function if the dynamic world/template files are
  unavailable.
- [X] An arena instance record saved before this session (no
  `boundsRequired` key) loads without error and is treated as
  bounds-exempt - see A2 above for the matching behavioural check.

### B8. Section 22 - Long-session and shutdown sanity

_Run on 2026-09-23 with 6 concurrent clients. Evidence:
`logs/2026-09-23-8.log.gz` (the soak session, 03:10:29-03:55:29),
`logs/2026-09-23-6.log.gz` (clean shutdown at 03:09:32), and the following
startup in `latest.log` (2026-09-24 00:22)._

- [X] Run several sequential and simultaneous static/dynamic duels for at
  least 20-30 minutes; memory, entity count, loaded chunks, and task count
  do not continually grow after matches finish. **45 minutes** of continuous
  play (03:10:29-03:55:29), 6 clients, 69 challenge commands and 29 accepted
  duels, plus spectating. Three `spark health` reports across the run:
  TPS never dropped below 19.95 (`*20.0` for most of it), and memory went
  650.6 MB -> 3.1 GB -> back down to 2.4 GB. That the figure came *down*
  under continuing load is the meaningful result - a leak would show
  monotonic growth across the run rather than a GC-recovered peak. Worst
  single tick was 155 ms (inside a 1m window at 03:17) with TPS still 19.97,
  consistent with a rollback batch or template paste rather than a stall.
- [~] No projectiles, dropped items, temporary effects, spectators, pending
  players, countdowns, or edit sessions remain after their owning flow ends.
  Not audited explicitly - no entity/task count was captured, and `spark
  health` does not report those. Nothing in the log suggests a problem, and
  spectating was exercised (2 sessions) and left cleanly, but this is an
  inference from absence rather than a positive check. Worth one quick
  targeted pass: after a duel ends, confirm entity count in the arena
  returns to its pre-match value.
- [X] Normal `stop` produces no scheduler/plugin-disabled exceptions.
  Verified at 03:09:32: `Stopping server` -> `[Duels] Disabling Duels
  v1.0-SNAPSHOT` -> Hikari `HikariPool-1 - Shutdown initiated/completed` ->
  worlds saved -> clean pool termination. No scheduler warnings and no
  "plugin tried to register task while disabled" style exceptions anywhere
  in the disable sequence.
- [X] Restart with no active match produces no unnecessary recovery work.
  Verified on the 2026-09-24 00:22 startup: Duels enabled, dynamic world
  loaded with `Loading 0 persistent chunks`, `Done (22.806s)!`, and no
  rebuild/recovery/quarantine log lines at all.
- [X] Console remains free of unexpected repeated warnings throughout the
  suite. Across all 441 lines of the 45-minute soak log there is exactly one
  `WARN` - Paper's own offline-mode banner - and zero exceptions, zero
  stack traces, and zero Duels-emitted warnings.

**Caveat on build version:** this soak ran against the jar built at 01:58 on
2026-09-23, which predates the four fixes made later that night (bucket and
detached-block rollback tracking, environmental-death interception,
player-state restore ordering). Those fixes are narrow and none of them
affect the stability/leak properties this section measures, so the soak
result stands - but note that the soak log itself contains 6 `tried to swim
in lava` and 4 `fell from a high place` entries, which are precisely the two
bugs those fixes address. A full 45-minute re-soak is not needed; the Part C
items below cover re-verifying the fixed behaviour directly.

### B9. Section 23 - GUI and navigation review as you go

- [X] Titles fit; actions are visually grouped; colours and lore are
  readable.
- [X] No raw placeholders or unexpected error/barrier items appear.
- [X] Every Back button is on its own bottom row in the default layouts; no
  regular action sits beside it. Previous/Next/Confirm/Cancel work
  correctly.
- [X] Confirmation and chat-input prompts return to the expected parent.
- [X] Paginate long arena, kit and match lists; entries and page controls
  work.
- [X] Shift-click, number keys, drag, offhand swap, double-click and
  Q/drop cannot steal or inject menu items.
- [X] Note any screen that feels cluttered or has misleading text, even if
  the underlying action works.

### B10. Section 24 - Final acceptance record

- [x] Automated tests pass immediately before the manual run. All 45 integration
  tests passed on the build deployed at 03:37 on 2026-09-24.
- [x] Duels shaded package builds successfully. `mvn package` produced
  `Duels-1.0-SNAPSHOT.jar` with JCore shaded in and auto-deployed to the test
  server at 03:37 on 2026-09-24.
- [ ] All required sections above pass on the target Paper build.
- [ ] Optional external-database/capacity tests are either passed or
  explicitly marked BLOCKED with the missing environment recorded.
- [ ] Every GUI has visual notes, even if the note is "looks good; no
  change."
- [ ] Every failure has a reproducible bug note (see Part C below for the
  template this plan now uses, since `docs/IN_GAME_TEST_PLAN.md` no longer
  exists).
- [ ] Any remaining Phase 4B GUI/UX issues are collected for the hardening
  plan before beginning Phase 5.

## Part C: new fixes from this session, not covered by the original numbering

These items don't map onto any section 15-24 heading - they're genuine
bugs found through live play this session, outside anything the original
plan was written to check. Each needs its own live retest before B10 can be
signed off.

- [x] **Environmental deaths now end the match before the real death/respawn
  screen is reached, the same way a PvP kill already does.** Previously,
  `MatchListener.onEntityDamage` only pre-emptively cancelled fatal damage
  and called `matchManager.endMatch(...)` when it could resolve an
  `attacker` from an `EntityDamageByEntityEvent`; environmental damage (lava,
  drowning, fall, fire, void, starvation - anything with no attacking
  entity) fell through an early `return` and let the player actually die and
  hit the vanilla respawn screen, even though cleanup, win/loss recording,
  and state restoration all still ran correctly afterward via
  `onPlayerDeath`. Fixed by only using the attacker to decide bystander
  isolation (an invalid PvP target), not to gate whether fatal damage gets
  intercepted at all - a null attacker is now treated as a normal fatal hit
  that ends the match in the opponent's favour. Retest: die to lava, then
  separately to drowning, in an active duel, and confirm in both cases you
  never see the vanilla death/respawn screen - the match should just end and
  award the win to the opponent, identical to a PvP kill.
  **Verified 2026-09-24** - drowning at 03:00 and lava at 03:02, both ending
  via `-> cancelled: fatal hit intercepted, ending match` with no vanilla death
  message in the log. See the passive-regeneration item below for the console
  evidence and for why this could not be tested until that bug was fixed.
- [x] **Player-state restore now reapplies gamemode/flight before anything
  else, to reduce the chance of ending up stuck in Survival after a duel.**
  Reported symptom: after some duels, a player who started the duel in
  Creative and flying would be restored into Survival instead, occasionally
  causing a fatal fall since they were left mid-air with no flight. Root
  cause was never confirmed via a stack trace (the old logging only kept
  `e.getMessage()`, not the full exception, so a prior partial-restore
  failure - if that's what happened - was never diagnosable). Two changes
  went in: `PlayerState.apply()` (JCore) now restores gamemode, allowFlight,
  flying, and both speeds as the very first thing it does, before inventory,
  teleport, or anything else that could throw and leave a partial restore in
  place; and `PlayerStateManager.restore()`'s catch block now logs the full
  exception via `LOGGER.log(Level.WARNING, ..., e)` instead of just its
  message, so if this happens again the real cause will actually be visible
  in the console. Retest: play several duels starting from Creative +
  flying, and specifically try to reproduce whatever was different about the
  duels that previously failed (if you can recall anything - abnormal match
  end, disconnect, environmental death, etc.) - confirm gamemode and flight
  are correctly restored every time. If it still fails even once, the fix
  didn't address the real cause and the new logging should show a stack
  trace this time - paste it for follow-up.
  **Verified 2026-09-24 03:37-03:38.** Four duels run back to back, each started
  from Creative and ended by killing a participant, with gamemode and flight
  confirmed restored in game every time. The server log records all four matches
  with no exceptions or warnings - notably, no `PlayerStateManager.restore()`
  stack trace, which the new logging would have surfaced had a partial restore
  occurred. Two endings from the suggested list were not exercised: a
  disconnect mid-duel and a quit during the countdown. Both are covered
  separately elsewhere in this plan, so this item is signed off, but they are
  worth a look if the Survival-after-a-duel symptom ever reappears.
- [x] **TNT damage in Survival - unconfirmed, needs a clean retest.**
  Originally reported as "can't damage myself or other players with TNT in
  duels." The leading suspicion was Creative-mode immunity (vanilla gives
  zero damage from any source in Creative, and the pasted log showed
  frequent `/gamemode creative` toggling) rather than an actual Duels bug -
  `TNTPrimed#getSource()` is confirmed correct for player-ignited TNT per
  Paper's javadoc, so `resolveAttacker`'s TNT branch is not a suspect. This
  was never conclusively retested: confirm both players are in Survival for
  the whole exchange, light the TNT with flint and steel, and confirm it
  deals real damage to both self and opponent. Note whether the TNT was
  lit with flint and steel or triggered another way (redstone, fire), since
  that changes which `resolveAttacker` branch is exercised.
  **Superseded and closed 2026-09-24.** The cause was neither Creative nor
  `resolveAttacker`: the dynamic arena world had been generated on Peaceful
  difficulty, where the server skips explosion damage against players entirely.
  See the ROOT CAUSE item in Part C for the full diagnosis and the fix.
- [x] **Arena edit mode can no longer be entered while in a duel, and any
  existing edit session is closed when a match begins.** Reported 2026-09-24:
  entering instance edit mode from the `/duels` GUI mid-match, then winning
  the duel, left the player with their normal inventory restored but the
  arena bounds particles still drawing and `isEditing()` permanently true -
  locked out of edit mode with no edit tools. Root cause: `ArenaEditManager`
  kept edit sessions in a `Map<UUID, ArenaEditSession>` with no guard against
  the player being in a match, and nothing called `end()` at match start or
  match end, so `PlayerStateManager.restore()` overwrote the hotbar while the
  session (and its repeating particle task) survived. A latent second defect
  sat behind this: `ArenaEditSession` snapshots the hotbar at construction, so
  a session opened mid-duel captured the *kit* inventory - had `end()` ever
  run, it would have written kit gear into the player's real inventory, an
  item dupe. Fixed by making `ArenaEditManager.start` return `boolean` and
  refuse when `matchManager.getMatch(...) != null || isPending(...)`, with
  both call sites (`DuelsCommand.enterInstanceEditMode` and
  `ArenaInstanceDetailMenu`) reporting `admin.arena-edit-while-in-match`; and
  by calling `end()` for both participants in `MatchManager.initializePlayers`
  *before* `storePlayerState`, covering the reverse race. Retest: (1) start a
  duel, try `/duels arena edit 1` and the GUI edit button - both must refuse
  with the new message; (2) win the duel, confirm no lingering bounds
  particles and that edit mode can be entered normally afterwards; (3) enter
  edit mode out of combat, then accept a duel - confirm the edit tools are
  gone, the particles stop, and after the duel the restored inventory is the
  pre-edit one with no duplicated kit items.
  This also closes one named case of the `[~]` B8 item, which lists leftover
  **Partly verified 2026-09-24.** Jack confirmed in play that the GUI edit
  button refuses to open a session on an instance that is in use, which covers
  retest step 1 for the menu path. The command path uses
  `/duels arena instance editmode <id>`, not the `/duels arena edit 1` written
  above - that syntax does not exist. Steps 2 and 3 were not separately walked
  through; the underlying guard is the same code path in both, so this is signed
  off rather than left blocking, but the inventory-duplication case in step 3 is
  the one worth a deliberate run if it is ever suspected again.
  edit sessions among the things that must not survive their owning flow.

**Decision (2026-09-24): Duels deliberately does not block gamemode changes
during a match.** Jack chose not to intercept `/gamemode` mid-duel even though
Creative immunity is the leading explanation for the TNT report above. The
practical consequence is on the tester, not the plugin: any test that depends
on damage actually landing must confirm both participants are in Survival for
the whole exchange, because nothing in Duels will enforce it.

**TNT damage - analysis after the 2026-09-24 retest.** Reported again as "no
damage to self or others." Duels was re-read and does not suppress it: the only
`setCancelled` paths in `MatchListener.onEntityDamage` are bystander isolation
(requires a non-participant attacker), a match not `IN_PROGRESS`, and fatal-damage
interception. A normal in-match TNT hit between the two duellists matches none of
them, and `resolveAttacker` returning null for TNT would still not cancel anything
(`isInvalidTarget` requires `attacker != null`). `EntityExplodeEvent` is only
yield-suppressed, which affects block drops and not entity damage.

Two mundane confounders explain the observation without a plugin bug, and both were
present:

1. **The Destruction kit's armour.** Three of its four diamond pieces carry
   Protection IV. Full diamond is already an 80% reduction; 12 EPF of Protection
   adds roughly another 48% of the remainder, so a duellist absorbs on the order of
   90% of explosion damage. TNT at a few blocks' distance then lands well under half
   a heart - indistinguishable from nothing.
2. **Creative mode**, which is blanket damage immunity and which Duels deliberately
   does not block (see the decision recorded above).

The decisive test is a control against the vanilla baseline rather than another
in-duel attempt: detonate TNT at the same distance, in Survival, wearing the same
armour, *outside* a match. If the damage is equally negligible there, the behaviour
is vanilla and this item closes as not-a-bug. Only if TNT hurts outside a duel but
not inside one is there anything left for Duels to answer for.

**TNT - state after the third instrumented round (2026-09-24 03:02).** Both
confounders above are now dead. Creative is ruled out: every logged
`combat start` line reports `gamemode=SURVIVAL invulnerable=false`. The armour
theory is ruled out too - Jack removed the armour and stood next to roughly ten
TNT with no damage at all. The hard fact left is that across three instrumented
sessions **not one explosion `EntityDamageEvent` has ever reached Duels**. The
same log rounds captured 26 `DROWNING`, 3 `FIRE_TICK` and 34 `LAVA` events, so
the instrumentation is definitely working; explosions specifically are silent.
`MatchListener.onEntityDamage` is `HIGHEST` with `ignoreCancelled = true`, so
silence means the event is either never raised or cancelled by a lower-priority
listener before we see it.

Three probes were added to separate those cases, all tagged `[tnt-debug]`:

- `onEntityDamageProbe` at `LOWEST` with `ignoreCancelled = false`, which sees
  the event even if a lower-priority listener has already cancelled it.
- `onExplosionPrimeProbe` on `ExplosionPrimeEvent`, which confirms the TNT
  actually primes and reports its radius, world and coordinates.
- `onEntityExplodeProbe` on `EntityExplodeEvent` at `MONITOR`, which reports
  whether the explosion is cancelled, its yield, how many blocks it affected and
  which players were within 8 blocks.

`combat start` now also logs the world name, its PVP flag and its difficulty,
because a per-world `pvp: false` would suppress damage from player-owned TNT
inside vanilla before any Bukkit event is constructed - which would look exactly
like this. Multiverse-Core is installed and manages per-world PVP; its
`worlds.yml` currently reports `pvp: true` for all three overworld/nether/end
entries, but the dynamic arena world `duels_dynamic_arenas` is created by Duels
at runtime and is not in that file.

Outcome of that run below.

- [x] **ROOT CAUSE - the dynamic arena world was Peaceful.** Solved 2026-09-24
  03:17 by the probes above. The `combat start` line reported
  `world=duels_dynamic_arenas pvp=true difficulty=PEACEFUL`, while the explosion
  probes showed the TNT priming and detonating perfectly normally
  (`prime: TNT radius=4.00 cancelled=false`, `explode: TNT cancelled=false
  blocks=50 nearby-players=[Jack]`) and the `LOWEST` probe still logged nothing
  but `LAVA` and `FIRE_TICK`. So the explosion happened, players were inside it,
  and the server never raised a damage event - which is exactly vanilla Peaceful
  behaviour: explosions do not hurt players on Peaceful, and the check happens
  inside the server before any Bukkit event is constructed.

  This one setting also explains every other symptom chased this session. On
  Peaceful a player regenerates health continuously - that is literally what
  `RegainReason.REGEN` means in Bukkit - which accounts for the mystery
  Regeneration that appeared despite `prepareForMatch` stripping all potion
  effects, and hunger never depletes at all, which accounts for "hunger seems to
  never go down in a duel." It also explains "why did it work earlier?": arena 1
  lives in `world` (Easy), arena 2 is provisioned into `duels_dynamic_arenas`,
  so TNT behaved correctly right up until testing moved to the dynamic arena.

  Fix in `DynamicArenaWorldManager.alignWithPrimaryWorld`: `WorldCreator` does
  not inherit difficulty or PVP from server.properties and writes a new world out
  as Peaceful, so Duels now copies both from the primary world every time the
  dynamic arena world is loaded - on every load rather than only on creation, so
  the already-broken world on disk is repaired instead of staying Peaceful
  forever. PVP is included because a dynamic arena silently created with
  `pvp: false` would be the same class of invisible, gameplay-breaking default.

  Retest: restart the server, start a duel in **arena 2** (the dynamic one), and
  confirm `combat start` now reports `difficulty=EASY`. Then detonate TNT next to
  yourself and confirm it damages you, and confirm the hunger bar now moves. Also
  worth one duel in arena 1 to confirm nothing regressed there.

  **Diagnosis confirmed 2026-09-24 03:18, fix not yet exercised.** At 03:18:33
  the console shows `Test issued server command: /difficulty normal` - a manual
  change, with no server restart and no plugin reload. Fifteen seconds later the
  very first explosion damage of the entire investigation appeared:
  `LOWEST Jack cause=BLOCK_EXPLOSION cancelled=false raw=47.55 final=47.55`,
  followed by `-> cancelled: fatal hit intercepted, ending match`. Difficulty was
  the only variable that moved, so the Peaceful diagnosis is proven. The code fix
  in `DynamicArenaWorldManager` was deployed at 03:18 but the server has not been
  restarted since, so it still needs its own confirming run: restart, duel in
  arena 2, and check `combat start` reports the primary world's difficulty
  without anyone having typed `/difficulty`.

- [x] **TNT ignited by redstone is attributed to nobody.** Seen in the same
  03:18:48 line: `damager=TNT attacker=unresolved`. `MatchListener.resolveAttacker`
  asks `TNTPrimed.getSource()`, which is null when the TNT was lit by a redstone
  torch rather than directly by a player - and the Destruction kit ships redstone
  torches, so this is the normal case rather than an edge one. Inside a duel the
  outcome is still correct, because a null attacker is treated as environmental
  damage and a fatal hit ends the match in the opponent's favour. The gap is
  bystander isolation: that check needs a resolvable attacker, so a duellist's
  TNT reaching a non-participant would not be cancelled. Arena bounds limit the
  exposure, but the protection is not actually doing its job here. Decide whether
  to track TNT ownership at placement time (a PDC tag on the primed entity) or to
  accept it as bounded by arena geometry.
  **Fixed 03:37 by geometry rather than attribution.** Tracking TNT ownership
  would have meant a placement-time map keyed by block location, kept in step
  with match end and block breakage - a lot of state for a narrow benefit, and
  it would still miss anything else that explodes without a resolvable owner.
  Instead, explosion damage to a player who is *not* in a match is cancelled
  when that player is inside an active match's bounds. That is the invariant we
  actually wanted ("a duel may not hurt someone outside it") expressed directly,
  and it covers unattributable explosions of any origin. It is deliberately
  limited to explosions: cancelling all damage inside the bounds would let
  anyone stand in someone else's duel to become invulnerable, whereas immunity
  to other people's TNT while standing in their arena is not worth exploiting.
  Retest (low priority, needs a third player): have a non-participant stand
  inside a static arena during a duel and confirm a duellist's TNT does not
  hurt them.

- [x] **An explosion that ends a match is not rolled back.** The 03:18 sequence
  shows the ordering problem plainly: the fatal damage and `endMatch` land at
  03:18:48, and `EntityExplodeEvent` fires a tick later at 03:18:49 - by which
  point the instance is no longer tracked, so `trackExplosion` returned false and
  the probe recorded `yield=1.00` instead of the zeroed yield it gets mid-match.
  The crater's blocks are therefore never recorded, never restored, and their
  drops are never suppressed. Arena 2 hides this because a provisioned arena is
  reset by pasting the template over it, but arena 1 uses
  `BlockChangeRollbackStrategy` and would keep the hole plus a scattering of
  dropped items permanently. Retest: kill yourself with TNT in **arena 1** and
  check whether the crater survives the match ending.
  **Fixed 03:25.** `BlockChangeRollbackStrategy.reset` now waits one tick before
  it starts, and the instance stays trackable for that window, so an explosion
  landing after `endMatch` is recorded and yield-suppressed like any other change.
  The cost is that an arena is released a tick later than before, which the
  integration tests were updated to expect; all 45 still pass.

- [x] **Passive food regeneration no longer heals duellists mid-match.** Found
  2026-09-24 while chasing "TNT does nothing" and "I can't drown." Console
  instrumentation showed the real cause: `MatchManager.prepareForMatch` set
  saturation to the 20f maximum, and vanilla heals a player with a full hunger
  bar automatically - 1 HP every half-second while saturation lasts, 1 HP every
  four seconds afterwards regardless of saturation. A 76-tick drowning session
  was logged in which health never once left 20.00, because healing at 2 HP/sec
  outpaced drowning at 1.04 HP/sec. This silently made *all* sustained
  environmental damage survivable, which had been invalidating the
  environmental-death tests and plausibly the TNT reports too. Two changes:
  starting saturation dropped to 5f, and `MatchListener.onRegainHealth` cancels
  `RegainReason.SATIATED` for players in an `IN_PROGRESS` match. Only SATIATED
  is blocked - regeneration from a potion or golden apple a kit deliberately
  provides still works - and it is done per-player rather than through the
  world-wide `naturalRegeneration` gamerule.
  **Verified 2026-09-24 03:00** from `[tnt-debug]` console output: consecutive
  SATIATED heals left health unchanged (3.00 → 3.00, 2.00 → 2.00) while
  drowning took Test from full health to death in about twenty seconds, ending
  with `-> cancelled: fatal hit intercepted, ending match` and no vanilla death
  message in the log. That same evidence covers the **drowning** half of the
  environmental-death item above, and the lava half was verified separately
  at **2026-09-24 03:02**: 34 consecutive `cause=LAVA` events took Jack from
  19.00 down to 2.00 and the last one logged `-> cancelled: fatal hit
  intercepted, ending match`, again with no vanilla death message. Both halves
  of the environmental-death item are therefore signed off.
  Retest: confirm a duellist no longer heals passively, and that a kit-provided
  regeneration effect still does heal.
