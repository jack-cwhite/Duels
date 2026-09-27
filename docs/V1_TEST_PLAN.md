# Duels foundation sign-off test plan

Consolidated, sequential in-game test suite for the original standalone foundation
(Phases 0-4, 3B and 11). This is the historical foundation sign-off record; the
public V1 scope was later expanded in `docs/V1_COMPLETION_PLAN.md`.
`docs/IN_GAME_SUITE.md` contains the dynamic-provisioning and containment acceptance
pass; `docs/TESTING.md` remains the clean-install release matrix.

Run on a clean Paper 1.21.11 server, Java 21. Two clients cover the main flow;
three for interference checks; four for concurrent-arena/spectator checks. Use
a full server restart (not a plugin reload) wherever a step says "restart" -
hot reloads do not reproduce a clean startup reliably.

Tick off each `[ ]` as it passes. Note any failure with enough detail to file
as a bug (steps, expected vs actual, console output).

---

## 0. Build and install

```powershell
cd C:\Users\jncwh\Development\JCore
mvn clean install
cd C:\Users\jncwh\Development\Duels
mvn clean package
Copy-Item .\target\Duels-1.0-SNAPSHOT.jar 'C:\path\to\paper\plugins\Duels.jar' -Force
```

- [ ] Duels enables without an exception.
- [ ] `plugins/Duels/` contains `config.yml`, `database.yml`, `messages.yml`, `menus.yml`.
- [ ] `/duel` shows player help; `/duels` opens the admin menu for an operator.
- [ ] A non-operator can use `/duel` but not `/duels`.

## 1. Arena setup (template + instance)

```text
/duels arena create TestArena
/duels arena instance create <arenaId>
/duels arena instance setspawn <instanceId> 1
/duels arena instance setspawn <instanceId> 2
/duels arena instance create <arenaId>
/duels arena instance setspawn <secondInstanceId> 1
/duels arena instance setspawn <secondInstanceId> 2
/duels arena list
```

- [ ] `/duels arena list` shows instance count, how many are ready, how many
      are free right now, and whether the arena is enabled - no single
      template-level "Ready: Yes/No".
- [ ] Arena menu entry shows the same figures.
- [ ] Left click on a spawn item updates that spawn; right click teleports to
      it without changing it; right-clicking an unset spawn says so.
- [ ] Rename (GUI and `/duels arena rename`) persists after a full restart.
- [ ] Toggle enabled/disabled (GUI and `/duels arena toggle`) works both ways.
- [ ] Delete confirmation cancel/confirm both work.
- [ ] `/duels arena delete <arenaId>` is rejected while instances are still
      registered; deleting the instances first lets the arena delete succeed.
- [ ] Create/delete a temporary arena, create another - new ID is higher than
      the deleted one.

## 2. Arena bounds and boundary policy

- [ ] `/duels arena instance bounds <instanceId> 1` / `2`, the instance menu's
      two Bounds Corner items (left click = set to current location, right
      click = teleport to it or say unset), and the edit-mode wand
      (`/duels arena instance editmode <instanceId>`) all set the same bounds
      and agree with each other.
- [ ] Edit-mode shows live particles outlining the bounds while active.
- [ ] Both bounds paths are rejected while the instance is hosting a match,
      and succeed again once it ends.
- [ ] The arena's Out Of Bounds GUI item and `/duels arena boundary <id> <mode> [grace]`
      produce the same result. Left click cycles SOFT_RETURN -> FORFEIT ->
      WARNING and back around; right click prompts for grace period in chat,
      rejecting non-numeric input without changing anything.
- [ ] In a live duel, walking out of bounds behaves per the configured mode:
      WARNING just warns, SOFT_RETURN pushes back, FORFEIT ends the match for
      the leaver after the grace period elapses. Re-entering bounds before the
      grace period ends cancels the pending forfeit.

## 3. Kit setup and editor safety

```text
/duels kit create Sword
/duels kit list
```

- [ ] Command-created kit copies storage, armor, and offhand contents.
- [ ] A newly created empty kit can be filled through the item editor.
- [ ] Armor slots reject the wrong equipment type; number-key/offhand swaps
      cannot place invalid armor.
- [ ] Save/close/reopen preserves every slot; closing without saving discards
      changes; the editor never mutates the admin's real inventory.
- [ ] Q/drop cannot drop copied kit items into the world.
- [ ] Rename (GUI and `/duels kit rename`), set icon from held item (GUI and
      `/duels kit icon`), and `/duels kit <id> edit` deep-linking into the
      item editor all work.
- [ ] Custom icon keeps its material/glow while menu name/lore still apply.
- [ ] Delete confirmation cancel/confirm both work; a new kit's ID is higher
      than a deleted one's.

## 4. Challenge rules

With `PlayerOne`, `PlayerTwo`, `PlayerThree`:

```text
PlayerOne: /duel PlayerOne          (rejected - self challenge)
PlayerOne: /duel PlayerTwo
PlayerThree: /duel PlayerOne
PlayerTwo: /duel deny
PlayerOne: /duel PlayerTwo
PlayerTwo: /duel accept
```

- [ ] Self-challenges rejected; a player can hold several requests with
      different people at once; a second request for an existing pair is
      rejected.
- [ ] `/duel accept <player>` / `/duel deny <player>` pick a specific request;
      no-argument form picks the latest.
- [ ] Deny informs both players.
- [ ] Challenge expires after `duel-request-expiry-time`; `0` never expires.
- [ ] Disconnecting either participant clears pending challenges and informs
      the other player.
- [ ] Accepting with no free arena/instance reports "no arena available"
      cleanly, and the challenge is not silently consumed by the failed
      attempt (retrying after freeing an instance works).

## 5. Match start, kit selection, bare-fist

Set `kit-selection-time: 10`, restart.

- [ ] Both players teleported to different (or the two spawns of the same)
      instance; become Survival, stop flying, lose temporary effects/fire/
      fall distance, start at full health/food.
- [ ] Original inventory, armor, offhand, gamemode, location, XP, health,
      effects, flight state are all absent during the duel.
- [ ] Players can move during selection but cannot damage each other.
- [ ] Left click selects a kit; right click previews; Back returns to
      selector; `/duel kit` reopens it after manual close.
- [ ] No selection made -> first allowed kit applied when the timer ends.
- [ ] Selection menu closes when combat starts; stale clicks can't change the
      chosen kit.
- [ ] Snapshot check: start a duel with a long selection timer, edit/delete an
      available kit from another admin account mid-selection - the existing
      duel keeps the old kit, the next duel sees the updated list.
- [ ] With every kit disallowed on an arena: no selector opens, both players
      are told there are no kits, countdown still runs, combat starts with
      empty inventory/armor/offhand.

## 6. Damage and winner matrix

Run separately: melee lethal, arrow/projectile lethal, primed TNT, lingering
potion cloud, evoker fangs, fire tick, fall damage, lava, drowning, void,
`/kill <loser>` mid-duel, disconnect during selection, disconnect during
combat.

- [ ] For every completed combat duel, the opponent wins exactly once and both
      online players are restored exactly once.

With a third player present:

- [ ] Third-player melee/projectiles/TNT/lingering effects cannot damage a
      duelist, and a duelist cannot damage the third player.
- [ ] A third party's tamed pet cannot damage a duelist, and a duelist's pet
      cannot damage the third party.

## 7. Arena reset (block rollback)

Set bounds on the test instance first; leave one corner outside as a control.

- [ ] Build a small structure inside bounds, use a kit with TNT (or a
      breaking tool), start a duel, break/place/detonate during combat, end
      the duel (kill or `/kill`).
- [ ] Within a second or two, broken/exploded blocks return and placed blocks
      disappear, with no visible tick stutter (`/tps` or Spark during a
      heavier version with several TNT charges).
- [ ] No item drops left on the floor from the explosion - check both TNT
      (entity explosion) and a bed/respawn anchor in the Overworld (block
      explosion); both should leave zero drops.
- [ ] Starting a new duel into the same instance immediately after finds the
      structure already restored, not mid-repair.
- [ ] Damage just outside the bounds control corner during a duel is **not**
      reverted.
- [ ] Damage the structure, then `stop` the server mid-match instead of ending
      normally - restart is clean, no bad state left behind.
- [ ] Repeat block damage against an instance with no bounds configured -
      changes are simply never reverted (expected limitation, not a bug).

## 8. Advancements during a duel

- [ ] Start a duel with a kit that trivially earns advancement progress (take
      damage, shoot a bow, kill the opponent) - no toast, no chat broadcast,
      no repeating spam during or after.
- [ ] `/advancement`/advancements screen afterward confirms nothing from the
      duel was granted.
- [ ] Outside a duel, a real advancement still triggers its toast/broadcast
      normally.

## 9. Spectator mode

```text
/duel spectate <player>
/duel spectate
/duel leave
```

With a live duel and a third player:

- [ ] `/duel spectate <player>` starts spectating; gamemode becomes Spectator,
      no combat interference.
- [ ] `/duel spectate` with no target opens a menu of running duels; clicking
      one spectates it; reports clearly if nothing is running.
- [ ] Spectator cannot leave the arena's bounds regardless of its boundary
      mode - always held in, never warned/allowed to forfeit.
- [ ] `/duel leave` restores exact previous location, inventory, gamemode.
- [ ] Spectating an arena with no bounds set is refused with a clear message.
- [ ] A combatant cannot spectate their own match; spectating a match you're
      already spectating is rejected.
- [ ] When the watched match ends, the spectator is auto-ejected, told it
      ended, and restored the same way `/duel leave` would.
- [ ] Two spectators on the same duel work independently - one leaving doesn't
      affect the other.
- [ ] Disconnect while spectating, then reconnect - restored to original
      state, not stuck in Spectator.
- [ ] Disconnect while spectating, and the match ends during the outage -
      reconnect still restores cleanly.

## 10. Arena locking and concurrency

During an active match:

- [ ] GUI and `/duels arena instance setspawn|bounds|delete <instanceId>` are
      rejected with "currently hosting a match"; rename, enable/disable,
      allowed-kit changes at the arena level are also rejected. All succeed
      again once the match ends.

With two ready instances on one arena and four players:

- [ ] Two duels started around the same time land in different instances.
- [ ] A third duel started while both are occupied reports no arena
      available.
- [ ] Ending one duel frees its instance; the next duel started reuses it.

## 11. State recovery and shutdown

Give each player a distinctive inventory, armor, XP, effects, health,
location, gamemode before a duel. Verify exact restoration after:

- [ ] A normal win.
- [ ] Environmental death.
- [ ] Disconnect and reconnect.
- [ ] `stop` mid-match, then restart.
- [ ] Forced process termination mid-match, then restart and login -
      `playerstates.yml` keeps the snapshot until that player rejoins and is
      restored, then removes it.

## 12. Stats backends and durable failure handling

Complete at least three duels with known winners per backend; restart after
changing `stats-storage`/`database.yml`.

- [ ] **YAML**: `stats.yml` gets one match object per completed combat duel;
      selection-stage disconnects don't count.
- [ ] **SQLite**: `SELECT * FROM duels_matches / duels_match_participants;`
      shows one match row and two participant rows per duel.
- [ ] **MySQL/MariaDB/PostgreSQL**: same row counts; `/duel top` loads wins/
      losses without SQL errors (including Postgres boolean queries).
- [ ] Stop the external database mid-session, complete a duel - the match
      still ends cleanly for both players (restoration, arena release) even
      though the stats write fails; the server log shows one SEVERE line with
      every match-result field (arena, both players, winner, kits, timestamp)
      so the outcome can be recovered manually, not just a bare exception.
- [ ] Restore the database and restart - reconnects and migrates once,
      without duplicate-index errors.

## 13. Configuration and failure behavior

- [ ] Invalid `stats-storage`, `kit-selection-time: 0`/`invalid`, negative
      `duel-request-expiry-time` each produce exactly one clear startup
      warning and the documented fallback (30s / 15s / SQL).
- [ ] An arena entry with an unknown field (e.g. `spawn3`) is skipped with a
      warning at startup, not silently rewritten.
- [ ] An invalid/out-of-range `database.yml` `pool-size` warns and falls back
      to the default rather than failing startup.
- [ ] A corrupt arena/kit/saved-player YAML entry is skipped with a warning,
      not a plugin-disabling crash.
- [ ] A missing/mistyped menu material shows the invalid-item barrier in the
      GUI and the console names the config path.

## 14. Release acceptance

Ship v1 only when:

- [ ] `mvn clean package` passes for JCore and Duels with no test failures.
- [ ] Every section above has been run and passed on a real Paper server.
- [ ] The console has no unexpected exceptions or repeated connection-pool
      warnings across the whole session.
- [ ] A Spark profile during several simultaneous duels shows no database
      work happening on the server thread.
