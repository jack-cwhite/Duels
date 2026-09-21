# Duels complete in-game test plan

_Target: Paper 1.21.11, Java 21. Covers all currently implemented V1 and Phase
4B behaviour. Last updated: 2026-09-21._

This is the single manual acceptance suite to run before planning the next
development phase. It includes command behaviour, every current GUI, static and
dynamic arenas, match lifecycle, recovery, statistics, permissions, and failure
handling.

Follow the sections from top to bottom. Review each GUI when the corresponding
feature first appears; there is no separate "open every menu" detour. Keep the
server running between sections unless a step specifically says to restart.

Arena type is chosen before setup. A STATIC arena has hand-built playable copies;
a DYNAMIC arena has one non-playable build source and generated playable copies.
Both types may coexist on the same server. The new code still needs this complete
real-Paper pass before Phase 4B sign-off.

`docs/V1_TEST_PLAN.md` remains the historical V1 sign-off. You do not need to
run it separately when you run this document.

## How to record results

Tick each item as it passes. For a failure, record:

All checks are open for this build. Previous V1 passes are historical, not
sign-off for the new arena flow.

```text
Test:
Result: FAIL / BLOCKED
Steps performed:
Expected:
Actual:
Console error/warning:
Screenshot or video:
Reproducible: always / sometimes / once
```

Use a full `stop` and server start whenever this plan says restart. Do not use
`/reload`, PlugMan, or another hot-reload tool.

## Test environment and players

Recommended:

- Paper 1.21.11 and Java 21.
- Two operator accounts for normal setup and combat tests.
- Four clients for allocation/concurrency tests; six is ideal for the pooled
  dynamic-instance test.
- Spark installed only for timing/TPS observation if desired. It is not a Duels
  dependency.
- Back up `plugins/Duels/` and the test worlds before destructive recovery and
  corrupt-file tests.

Use these names in the checklist:

- `P1`, `P2`: first duel.
- `P3`, `P4`: concurrent duel and spectators.
- `P5`, `P6`: optional third concurrent request.

Create these resources and note their real IDs:

```text
Arena S: StaticArena (STATIC), two hand-built playable copies
Arena D: DynamicArena (DYNAMIC), one build source, then generated copies
Kit 1: Sword (weapon, armor, food)
Kit 2: Archer (bow, arrows, distinct armor)
Kit 3: Destruction (pickaxe, blocks, TNT, flint and steel)
```

An **arena** is the named match choice. A **playable copy** hosts a duel.
StaticArena's copies are built and registered manually. DynamicArena's single
source is a build/reference space, never a duel location. Capture that source;
Duels generates playable copies inside its dynamic world as matches need them.

## 1. Build, install, and startup

```powershell
cd C:\Users\jncwh\Development\JCore
mvn clean install
cd C:\Users\jncwh\Development\Duels
mvn clean package
```

- [ ] JCore and Duels builds finish without test failures.
- [ ] Install the newly built shaded Duels jar; do not install JCore as a
  separate server plugin.
- [ ] Duels enables with no exception or severe log entry.
- [ ] `plugins/Duels/` contains the normal configuration files.
- [ ] On a clean static-only data folder there is no `dynamic-layout.yml`,
  `structures/` directory, or `duels_dynamic_arenas` world yet.
- [ ] `/duel` shows player help.
- [ ] `/duels` opens the admin menu for an operator.
- [ ] A non-operator can use `/duel` but cannot use `/duels` or admin
  subcommands.
- [ ] `/ds` and `/fight` aliases behave like `/duels` and `/duel`.
- [ ] Player/admin help text lists the current commands and syntax, including
  selected-arena challenges and dynamic template/provisioning operations.
- [ ] Tab completion and incorrect-usage messages are sensible for the main
  command branches.

## 2. Admin entry and arena-type choice

Review each menu at normal GUI scale and at one smaller/larger client GUI scale.

- [ ] `/duels` → Arenas → Create Arena opens a two-choice type screen.
- [ ] STATIC and DYNAMIC choices explain the different setup paths before asking
  for a name; Cancel/Back changes nothing.
- [ ] Create one arena of each type through the GUI. The list labels their
  types correctly, and both can coexist on the server.
- [ ] The Back button is alone on the bottom row in the bundled layouts.
- [ ] Record a visual note for each screen as you reach it in the sections below.

## 3. Arena templates and manual instances

```text
# If you created StaticArena through the GUI in section 2, reuse its ID.
# Otherwise: /duels arena create StaticArena STATIC
/duels arena instance create <staticArenaId>
/duels arena instance create <staticArenaId>
/duels arena list
```

- [ ] Arena list command and GUI show the same name, ID, type, playable-copy
  count, ready count, free count, and enabled state.
- [ ] A new arena starts in STATIC mode. Its two hand-built copies require only
  both spawns to host a duel; structure capture is not required.
- [ ] STATIC detail offers playable copies and one-copy conversion, but no
  structure corners, template, generated-copy, or capture controls.
- [ ] A STATIC arena with two copies refuses conversion without losing either.
- [ ] An existing one-copy STATIC arena may be converted with its confirmation
  button or `/duels arena convert <arenaId> <copyId>`; spawns, bounds and blocks
  remain, but that copy becomes a non-playable source until capture. Test this
  on a disposable arena, not on your two-copy StaticArena.
- [ ] A new instance is not ready before both spawns are set.
- [ ] Set both spawns using `/duels arena instance setspawn <instanceId> <1|2>`.
- [ ] Set both spawns through the instance GUI; left click sets and right click
  teleports without modifying the spawn.
- [ ] Right-clicking an unset spawn gives a clear message.
- [ ] `/duels arena instance list <arenaId>` labels each copy's origin; the
  STATIC GUI lists only its hand-built playable copies.
- [ ] Rename through command and GUI; restart and confirm persistence.
- [ ] Toggle enabled through command and GUI; disabled arenas receive no new
  matches, and re-enabling restores availability.
- [ ] Arena and instance delete confirmations cancel and confirm correctly.
- [ ] An arena cannot be deleted while any instance references it.
- [ ] Creating a resource after deleting another uses a higher ID rather than
  reusing the deleted ID.

## 4. Edit mode, bounds, and capture tools

```text
/duels arena instance editmode <instanceId>
```

- [ ] The real hotbar/offhand is saved and replaced with spawn, gameplay bounds,
  and Exit tools; STATIC copies have no structure-corner tools.
- [ ] Spawn 1/2 tools set on left click and teleport on right click.
- [ ] Gameplay bounds corner 1/2 tools set and teleport correctly.
- [ ] Aqua particles outline gameplay bounds and follow updated corners.
- [ ] Inventory opening/editing, item drop, inventory drag, number keys, and
  offhand swap cannot lose or duplicate the edit tools.
- [ ] Death in edit mode does not drop tools; respawn restores the tools.
- [ ] Disconnecting or using Exit restores the original hotbar/offhand exactly.
- [ ] Console cannot use player-only location/edit commands.

## 5. Kits and kit editor

```text
/duels kit create Sword
/duels kit create Archer
/duels kit create Destruction
/duels kit list
```

- [ ] Command-created kit snapshots storage, armor, and offhand contents.
- [ ] An empty kit can be populated through the GUI editor.
- [ ] Correct helmet/chest/legs/boots types are accepted in their slots.
- [ ] Wrong armor types are rejected through normal click, shift-click, number
  key, drag, and offhand-swap paths.
- [ ] Saving persists every item, amount, enchantment, name, lore, damage, and
  offhand/armor slot after reopening and after restart.
- [ ] Closing without Save discards changes.
- [ ] Editing never mutates the administrator's real inventory.
- [ ] Q/drop cannot create copied kit items in the world.
- [ ] Rename works through command and GUI.
- [ ] Icon selection from the held item works through command and GUI.
- [ ] A custom icon retains material/enchant-glint while configured menu
  name/lore are still applied.
- [ ] `/duels kit <id>` and `/duels kit edit <id>` open the expected screens.
- [ ] Allowed-kits GUI and `/duels arena allowkit <arenaId> <kitId>` agree.
- [ ] Delete confirmation cancel/confirm works and IDs remain monotonic.

## 6. Dynamic source setup and template capture

Build DynamicArena's single source with blocks/block entities such as
chests, signs, doors, water, and redstone. Configure both spawns and gameplay
bounds. Make the structure capture box larger than gameplay bounds so it also
contains decorative walls.

```text
# If you created DynamicArena through the GUI in section 2, reuse its ID.
# Otherwise: /duels arena create DynamicArena DYNAMIC
/duels arena source create <dynamicArenaId>
/duels arena instance editmode <sourceInstanceId>
# Set both structure corners in-world; GUI buttons also work.
/duels arena template capture <sourceInstanceId>
/duels arena template info <dynamicArenaId>
```

- [ ] DYNAMIC detail offers one Build Source, generated copies and template;
  it never offers hand-built playable-copy creation.
- [ ] A second source and `/duels arena instance create <dynamicArenaId>` are
  refused cleanly. The source never appears in the generated-copy list or
  ready/free playable counts.
- [ ] Source edit mode includes structure-corner tools, unlike STATIC edit mode.
- [ ] Structure corner tools set/teleport correctly. Orange particles show the
  capture box independently of aqua gameplay bounds.
- [ ] Source GUI structure-corner buttons set from where you stand and right
  click teleports. Draft carries between GUI and edit tools; disconnect or
  selecting another source clears it.
- [ ] Capture is refused until both spawns, both gameplay bounds, and both
  structure corners exist.
- [ ] Capture is refused if the capture box excludes either spawn or either
  gameplay-bounds corner.
- [ ] Capture is refused across different worlds.
- [ ] GUI capture uses the temporary two-corner draft, reports the same errors
  as the command, and shows the captured revision/size in arena detail.
- [ ] Oversized capture is refused according to `max-template-volume`.
- [ ] Successful capture reports revision and dimensions.
- [ ] The captured NBT dimensions include both selected corner blocks. For a
  box spanning 32, 33, and 48 blocks, the saved size reports 32x33x48 (not
  31x32x47); a generated copy includes the far corner's blocks.
- [ ] `plugins/Duels/structures/arena-<id>-r<revision>.nbt` exists.
- [ ] `/duels arena template info` reports the same revision/size.
- [ ] Source capture is allowed before any generated copy exists. Recapture
  after a generated copy exists is refused without replacing the saved revision.
- [ ] Type changes after setup are refused; no hybrid manual/generated arena
  can be created through the GUI or commands.

## 7. Challenges and arena selection

- [ ] Self-challenge is refused.
- [ ] `/duel P2` and `/duel challenge P2` both create an Any Arena challenge.
- [ ] `/duel challenge P2 <staticArenaId>` names the selected arena to both players.
- [ ] `/duel select P2` opens an arena-choice GUI with Any and enabled arenas;
  clicking one sends the matching challenge. Disabled arenas do not appear.
- [ ] A selected challenge never silently falls back to another arena.
- [ ] A nonexistent or disabled selected arena is refused.
- [ ] A second challenge for the same pair is refused.
- [ ] One player may hold challenges involving different players.
- [ ] `/duel accept <player>` and `/duel deny <player>` choose a specific
  challenge; no-argument forms choose the latest applicable challenge.
- [ ] Denial informs both participants.
- [ ] Challenge expiry uses `duel-request-expiry-time`; value `0` never expires.
- [ ] Either player disconnecting removes their challenges and informs online peers.
- [ ] If no selected static instance is free, acceptance fails cleanly and the
  unexpired challenge remains usable after capacity is freed.
- [ ] While arena preparation is pending, repeated accept/challenge attempts do
  not place either player in two matches or consume the challenge twice.
- [ ] Players remain at their original locations with their inventories intact
  until arena preparation succeeds.

## 8. Match preparation and kit selection

Set `kit-selection-time: 10` for convenience and restart.

- [ ] Both players reach the two different instance spawns.
- [ ] Both become Survival and have normalized flight, velocity, fire, freeze,
  health, hunger, effects, and equipment state.
- [ ] Their original location, inventory, armor, offhand, XP, health, effects,
  gamemode, and flight state are absent during the match but preserved for restore.
- [ ] Players can move during kit selection but cannot damage each other.
- [ ] Kit selector opens only when allowed kits exist.
- [ ] Kit selector and kit preview visual layout reviewed during a match.
- [ ] Left click selects; right click previews; Back returns; `/duel kit` reopens.
- [ ] No choice applies the first allowed kit when selection ends.
- [ ] Selector closes when selection ends; stale clicks cannot change the kit.
- [ ] Grace countdown prevents damage, then combat starts exactly once.
- [ ] With grace disabled, combat starts directly after kit selection.
- [ ] With every kit disallowed, no selector opens, both players receive the
  bare-fist message, and combat still begins with empty equipment.
- [ ] Edit/delete a kit from another admin while players are selecting: the
  current match keeps its snapshot and the next match sees the new configuration.

## 9. Combat isolation and result matrix

Run wins using melee, arrow, TNT, splash potion, lingering potion cloud, evoker
fangs, fire tick, fall, lava, drowning, void, and `/kill`.

- [ ] Every terminal event produces one winner and one result only.
- [ ] Both online players restore exactly once.
- [ ] Disconnect during selection awards/ends cleanly and restores the opponent.
- [ ] Disconnect during combat awards/ends cleanly and restores the opponent.
- [ ] P3 melee/projectiles/TNT/potions cannot damage either duelist.
- [ ] A duelist cannot damage P3.
- [ ] Third-party tamed pets cannot damage duelists and duelists' pets cannot
  damage third parties.
- [ ] Duel opponents can affect each other only during `IN_PROGRESS`, not
  selection or grace.
- [ ] Death drops from duel inventory are cleared and cannot be collected.

## 10. Boundary policies

- [ ] Command and GUI both configure `WARNING`, `SOFT_RETURN`, and `FORFEIT`.
- [ ] GUI left click cycles modes and right click requests grace seconds.
- [ ] Invalid chat input changes nothing and provides useful feedback.
- [ ] `WARNING`: leaving bounds warns but does not move or forfeit the player.
- [ ] `SOFT_RETURN`: leaving bounds returns the player safely.
- [ ] `FORFEIT`: leaving for the whole grace period loses the duel.
- [ ] Re-entering before the grace expires cancels a pending forfeit.
- [ ] Boundary mode changes during a running match take effect consistently.
- [ ] A reversed pair of bounds corners behaves exactly like normally ordered corners.
- [ ] Bounds editing is refused while that physical instance is allocated and
  succeeds after reset/release.

## 11. Spectators

- [ ] `/duel spectate` reports no matches when none exist.
- [ ] Live-duels spectator menu visual layout reviewed with a match running.
- [ ] With enough live matches, spectator-menu pagination works.
- [ ] With a match, `/duel spectate` lists it and clicking joins it.
- [ ] `/duel spectate <player>` joins that player's match directly.
- [ ] Spectator state is saved; gamemode becomes Spectator and combat cannot be influenced.
- [ ] Spectator is confined to arena bounds regardless of combatant boundary mode.
- [ ] Camera-lock may target either combatant but not unrelated entities/players.
- [ ] `/duel leave` restores exact original state/location.
- [ ] A match with no bounds cannot be spectated.
- [ ] Combatants cannot spectate; duplicate spectating is refused.
- [ ] Match end auto-ejects and restores all spectators.
- [ ] Two spectators operate independently.
- [ ] Disconnect/reconnect restores a spectator, including when the watched
  match ends while the spectator is offline.
- [ ] Repeat direct and menu spectating for a dynamically provisioned arena.

## 12. Advancement suppression

- [ ] During a duel, perform actions that would grant advancement criteria;
  no toast, chat broadcast, or repeat spam occurs.
- [ ] The advancement screen/command confirms duel-earned criteria were not granted.
- [ ] The same advancement can be earned normally outside a duel.

## 13. Player-state restoration

Before each case give both players distinctive inventories, armor, offhand,
XP, effects, health, location, gamemode, flight settings, and movement speeds.

- [ ] Restore after a normal win.
- [ ] Restore after environmental death.
- [ ] A dead player respawns at the correct restored location/state.
- [ ] Restore after disconnect/reconnect.
- [ ] Restore after `stop` during a match and restart/login.
- [ ] Restore after forcibly terminating the Java process mid-match and restart/login.
- [ ] `playerstates.yml` retains an offline player's snapshot until successful
  login restore, then removes it.
- [ ] No duelled kit item, effect, flight flag, or altered attribute leaks into
  the restored state.

## 14. Static allocation and concurrency

With two ready instances of StaticArena and at least four players:

- [ ] Two simultaneous duels use different instance IDs/locations.
- [ ] A third request while both are allocated reports no available arena.
- [ ] Ending one duel does not expose that instance until its reset completes.
- [ ] The next duel reuses the released instance.
- [ ] Disabling the arena prevents new allocation without disrupting a running match.
- [ ] Spawn/bounds/delete operations on an allocated instance are refused and
  work again after release.
- [ ] Arena-level rename, boundary, and allowed-kit behaviour matches the
  intended live/snapshot semantics rather than corrupting the running match.

## 15. Block rollback and debris cleanup

Use Destruction kit in an arena with bounds. Leave blocks immediately outside
the bounds as controls.

- [ ] Break original blocks, place new blocks, and detonate TNT inside bounds.
- [ ] After match end, originals return and placed blocks disappear.
- [ ] TNT explosion and bed/respawn-anchor block explosion produce no leftover
  debris drops inside the tracked arena.
- [ ] Blocks outside bounds are not reverted.
- [ ] A new match cannot enter the instance while batched rollback is running.
- [ ] A heavier rollback stays responsive; observe TPS/Spark for a visible spike.
- [ ] An instance without bounds does not roll changes back (documented limitation).
- [ ] `stop` during a damaged match and restart does not leave Duels in an
  invalid allocation state.

## 16. Dynamic provisioning and selection

Use four players. Start P1 vs P2 in a generated DynamicArena copy, then:

```text
P3: /duel challenge P4 <dynamicArenaId>
P4: /duel accept P3
```

- [ ] Recapturing the source while generated copies exist is refused without changing
  the saved template.

- [ ] P1/P2 see “Preparing arena…” while their first copy is generated; P3/P4
  see it when a second copy is needed.
- [ ] `dynamic-layout.yml` is created on first generation, not source capture,
  with frozen geometry and world UUID.
- [ ] The configured dynamic void world is created and contains no normal terrain.
- [ ] A new provisioned instance row is persisted as a distinct instance/slot.
- [ ] Instance-list GUI identifies generated copies, slot, health state, and
  whether each copy is ready.
- [ ] The copied arena includes every block and block entity inside capture bounds.
- [ ] Decorative blocks outside gameplay bounds but inside capture bounds are present.
- [ ] Blocks outside capture bounds are absent.
- [ ] Both relative spawns have correct position, yaw, and pitch.
- [ ] Gameplay bounds are correctly offset into the new slot.
- [ ] P3/P4 were not cleared or teleported before the paste succeeded.
- [ ] The selected DynamicArena never falls back to StaticArena.
- [ ] P1/P2 and P3/P4 play simultaneously in different generated copies, with
  no cross-arena effects. Neither match enters the source world.
- [ ] Spectating, boundary handling, kit restrictions, results, and restoration
  behave identically in the provisioned copy.

## 17. Dynamic pooling, reset, and retirement

End a duel in a generated copy, then start another selected duel.

- [ ] Match damage in the provisioned copy rolls back before reuse.
- [ ] The next duel reuses the same provisioned instance/slot instead of creating another.
- [ ] No player sees rollback or a partial template during reuse.
- [ ] A provisioned instance is marked unavailable while allocated/resetting.
- [ ] `/duels arena instance delete <provisionedInstanceId>` is refused while active.
- [ ] Deleting an idle provisioned instance marks it retiring, clears the slot
  in bounded batches, then removes its persisted row.
- [ ] The same retirement via GUI confirmation clears the physical slot before
  removing its record; an active copy cannot be retired via GUI.
- [ ] Another provision cannot use the slot until clearing finishes.
- [ ] Provisioning after retirement can reuse the now-vacant slot without old blocks.
- [ ] Deleting the source is refused while its captured template or generated
  copies exist; after retirement and template clear, deletion does not erase
  the source's world blocks.

## 18. Dynamic capacity and simultaneous preparation

Run this on a disposable copy of the plugin data. Set a small `max-slots` before
first dynamic use; once `dynamic-layout.yml` exists, its saved geometry correctly
wins over later config edits.

- [ ] Two simultaneous dynamic requests reserve different slots.
- [ ] No two structures overlap, including their configured padding.
- [ ] When all dynamic slots are occupied, another selected
  challenge fails cleanly with no player mutation.
- [ ] The failed challenge remains available if it has not expired.
- [ ] Changing slot width/length/padding/max after first use does not move old
  slots; startup explains that persisted layout is authoritative.
- [ ] A template wider/longer than a slot is rejected without world mutation.

## 19. Dynamic restart and failure recovery

Back up the test data before these tests.

- [ ] `stop` during a dynamic match; restart. The `DIRTY` instance is rebuilt
  before becoming allocatable and players restore on login.
- [ ] Force-kill the Java process during a dynamic match; restart and verify the
  same full-template rebuild, not only in-memory block rollback.
- [ ] Stop/kill during initial provisioning if timing permits; the persisted
  `PROVISIONING` row is rebuilt before reuse.
- [ ] Stop during retirement; restart resumes clearing before freeing the slot.
- [ ] Temporarily remove the referenced NBT file while the server is stopped;
  restart leaves the instance quarantined/FAILED and does not overlap its slot.
- [ ] Restore the file and run `/duels arena instance retry <instanceId>`; the
  instance rebuilds and becomes ready.
- [ ] Instance-detail GUI retry works only for a FAILED provisioned copy and
  reports success/failure without opening a broken copy to matches.
- [ ] Rename/remove the dynamic world folder while stopped; startup refuses to
  create a replacement over the saved layout and logs a prominent diagnostic.
- [ ] Restore the correct folder and world UUID; recovery resumes normally.
- [ ] A duplicate/out-of-range slot edited into `arena-instances.yml` is
  quarantined by startup rather than allowed to overlap another instance.
- [ ] After all generated copies have been retired, the arena remains DYNAMIC.
  A type switch while a source or template exists is refused.
- [ ] `template clear` requires literal `confirm`, and the GUI uses a
  confirmation screen. Both refuse while generated copies remain, then remove
  the saved metadata/file safely without changing arena type.

## 20. Statistics and leaderboard

Complete at least three known results per backend. Selection/grace disconnects
should be checked separately from completed combat results.

- [ ] YAML backend writes one match record per intended recorded duel.
- [ ] SQLite creates one match row and two participant rows per result.
- [ ] MySQL/MariaDB backend has the same row counts and no dialect errors, if available.
- [ ] PostgreSQL backend has the same row counts and boolean queries work, if available.
- [ ] `/duel top` loads asynchronously, displays correct ordering/win totals,
  shows the viewer's record, and remains responsive with no records.
- [ ] Statistics leaderboard visual layout reviewed after recorded matches.
- [ ] Restart preserves all records without duplication.
- [ ] Stop an external database during a result write: players restore and the
  arena releases despite failure; one severe log entry contains the complete
  recoverable result fields.
- [ ] Restore DB and restart; schema/migration runs once without duplicate-index errors.

## 21. Configuration, migration, and diagnostics

Perform malformed-config tests one at a time and restore the file after each.

- [ ] Invalid stats backend, zero/invalid kit-selection time, negative challenge
  expiry, bad pool size, and invalid dynamic numeric settings each warn once
  and use the documented safe fallback.
- [ ] Legacy arena without `provisioningMode` loads as STATIC.
- [ ] Legacy instance without `origin` loads as MANUAL.
- [ ] A previously DYNAMIC arena with exactly one old MANUAL copy upgrades it
  to SOURCE on startup, preserving its ID, spawns, bounds and template.
- [ ] An old DYNAMIC arena with multiple manual copies does not silently mix
  them into generated allocation; choose a source explicitly with
  `/duels arena source adopt <copyId>` and resolve leftover legacy copies.
- [ ] Legacy inline arena spawns/bounds migrate once to an instance and do not
  duplicate on the next restart.
- [ ] Unknown arena/template/instance fields are rejected with a useful warning,
  not silently discarded.
- [ ] Corrupt arena, instance, kit, stats, and saved-player entries are skipped
  or diagnosed without silently rewriting unrelated valid entries.
- [ ] Invalid menu material produces the configured error/barrier item and names
  the exact config path in console.
- [ ] Missing optional integrations do not prevent Duels from enabling.
- [ ] Static arenas still function if the dynamic world/template files are unavailable.

## 22. Long-session and shutdown sanity

- [ ] Run several sequential and simultaneous static/dynamic duels for at least
  20–30 minutes; memory, entity count, loaded chunks, and task count do not
  continually grow after matches finish.
- [ ] No projectiles, dropped items, temporary effects, spectators, pending
  players, countdowns, or edit sessions remain after their owning flow ends.
- [ ] Normal `stop` produces no scheduler/plugin-disabled exceptions.
- [ ] Restart with no active match produces no unnecessary recovery work.
- [ ] Console remains free of unexpected repeated warnings throughout the suite.

## 23. GUI and navigation review as you go

Check each screen when it appears above, rather than opening it out of sequence:
admin entry/type choice → STATIC list/detail/copy/edit → kit menus → DYNAMIC
source/capture/generated detail → player challenge/kit preview → spectator and
leaderboard menus.

- [ ] Titles fit; actions are visually grouped; colours and lore are readable.
- [ ] No raw placeholders or unexpected error/barrier items appear.
- [ ] Every Back button is on its own bottom row in the default layouts; no
  regular action sits beside it. Previous/Next/Confirm/Cancel work correctly.
- [ ] Confirmation and chat-input prompts return to the expected parent.
- [ ] Paginate long arena, kit and match lists; entries and page controls work.
- [ ] Shift-click, number keys, drag, offhand swap, double-click and Q/drop
  cannot steal or inject menu items.
- [ ] Note any screen that feels cluttered or has misleading text, even if the
  underlying action works.

## 24. Final acceptance record

- [ ] Automated tests pass immediately before the manual run.
- [ ] Duels shaded package builds successfully.
- [ ] All required sections above pass on the target Paper build.
- [ ] Optional external-database/capacity tests are either passed or explicitly
  marked BLOCKED with the missing environment recorded.
- [ ] Every GUI has visual notes, even if the note is “looks good; no change.”
- [ ] Every failure has a reproducible bug note using the template at the top.
- [ ] Any remaining Phase 4B GUI/UX issues are collected for the hardening
  plan before beginning Phase 5.
