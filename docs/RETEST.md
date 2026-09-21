# Focused release retest

**Status: all sections below have been run and passed.** This plan covered the
behavior changed after the first full manual pass, and its completion is what
Duels v1's "manually verified in-game" status in `ROADMAP.md` is based on. Kept
as a reference for what v1 was actually checked against, and as the starting
checklist to extend once v2 work (Phase 4B onward) needs its own manual pass.

The parts of `TESTING.md` that already passed do not need to be repeated. Use a
full server restart after installing the new jar.

## Build and install

```powershell
cd C:\Users\jncwh\Development\JCore
mvn clean install

cd C:\Users\jncwh\Development\Duels
mvn clean package
Copy-Item .\target\Duels-1.0-SNAPSHOT.jar 'C:\path\to\paper\plugins\Duels.jar' -Force
```

## Commands and navigation

1. Run `/duel kit` outside a duel. It should say that you are not in a duel.
2. Run `/duel kit` during combat. It should say kit selection has closed.
3. Run `/duel top`. It must not display a Back button when opened directly.
4. Run `/duels arena` and `/duels kit`. Back should return to the main admin menu.
5. Run `/duels arena <id>` and `/duels kit <id>`. Each should open that object's detail menu. Back should move through its list and management menu.
6. Delete an arena and a kit through their confirmation screens. Each should return to the corresponding list with the deleted entry absent.
7. Run `/duel NoSuchPlayer`. The configured red `core.player-not-found` message should be used.

## Multiple challenge requests

Use three players, A, B, and C:

```text
A: /duel B
C: /duel B
A: /duel C
B: /duel accept A
```

All three different-pair requests should be accepted by the request system.
A second request for an existing pair should be rejected. Accepting A's
request should start one match and clear other requests involving A or B.

Also verify `/duel accept <player>` and `/duel deny <player>` choose the named
request while the no-argument forms choose the latest request.

## Movement and player state

1. Give both players Speed/Slowness effects or attribute modifiers before the duel.
2. Start a duel and compare movement with a normal third player. Duelists should have standard Survival walking speed with no rubber-banding.
3. Finish the duel and verify each original speed/effect/state is restored.
4. Start another duel and run `stop` during selection and during combat in separate tests.
5. After the server restarts, both players should be restored on join and receive the state-restored message.
6. Repeat once with a plugin-only disable/reload. Online players should be restored immediately.

## Third-party effects

Use two duelists and one outsider. During selection and combat test:

- Outsider melee, arrows, splash potions, lingering potions, TNT, and a tamed pet against either duelist.
- Either duelist using those sources against the outsider.
- The two duelists using normal attacks, potions, and TNT against one another during combat.

Cross-match interference must be blocked. Effects between the two duelists
should work only after combat begins. A duelist's own TNT or harming cloud may
hurt that duelist and should award the opponent if it is lethal.

## Leaderboard profiles

Open `/duel top` repeatedly from each test account and click every entry.
There should be no Mojang session-server profile timeout warnings. Offline
players intentionally use a generic player-head texture; online players may
show their skin.

## Configuration validation

Before startup set invalid values such as:

```yaml
duel-request-expiry-time: -1
kit-selection-time: invalid
stats-storage: INVALID
```

On startup there should be exactly one warning for each invalid value, with
fallbacks of 30 seconds, 15 seconds, and SQL. Restore valid values afterward.

Add `spawn3` to an arena entry. Startup should warn that the arena contains an
unknown field and skip that arena, rather than silently rewriting the typo.

## Storage backends

Complete at least two duels per backend and verify `/duel top` after each full
restart.

### YAML

Set `stats-storage: YAML`. Verify `stats.yml` receives one record per completed
combat duel and no record for a selection-stage disconnect.

### SQLite

Set `stats-storage: SQL` and `database.yml` type to `SQLITE`:

```powershell
sqlite3 .\plugins\Duels\database.db "SELECT * FROM duels_matches ORDER BY id DESC;"
sqlite3 .\plugins\Duels\database.db "SELECT * FROM duels_match_participants ORDER BY match_id DESC;"
```

### MySQL and MariaDB

Test each type separately:

```sql
USE duels;
SHOW TABLES;
SELECT * FROM duels_matches ORDER BY id DESC;
SELECT * FROM duels_match_participants ORDER BY match_id DESC;
SELECT player_id, SUM(won) AS wins
FROM duels_match_participants GROUP BY player_id;
```

### PostgreSQL

```sql
\c duels
\dt
SELECT * FROM duels_matches ORDER BY id DESC;
SELECT * FROM duels_match_participants ORDER BY match_id DESC;
SELECT player_id, COUNT(*) FILTER (WHERE won) AS wins
FROM duels_match_participants GROUP BY player_id;
```

For every SQL backend, expect exactly one match row and two participant rows
per completed duel. Confirm `/duel top` loads wins and losses without SQL
errors, including PostgreSQL boolean queries.

## Arena instancing

Arenas are now templates; each needs at least one registered instance to host
a match.

```text
/duels arena create Colosseum
/duels arena instance create <arenaId>
/duels arena instance setspawn <instanceId> 1
/duels arena instance setspawn <instanceId> 2
/duels arena instance create <arenaId>
/duels arena instance setspawn <secondInstanceId> 1
/duels arena instance setspawn <secondInstanceId> 2
/duels arena instance list <arenaId>
```

Check:

- `/duels arena list` shows, per arena, the instance count, how many of those
  instances are ready, how many are free right now, and whether the arena
  itself is enabled. There should be no single template-level "Ready: Yes/No",
  since readiness belongs to an instance. The arena menu entries show the same
  figures. Note that `messages.yml` and `menus.yml` only gain *missing* keys on
  startup, so delete the existing `arena-list-entry` line (and the `arena-list`
  entry lore) before testing this, or the old wording will persist.
- With two ready instances on one arena, two duels started around the same
  time land in different instances, not the same one.
- A third duel started while both instances are occupied reports no arena
  available.
- Ending one of the two duels frees its instance; the next duel started reuses
  it rather than staying stuck on "no arena available".
- `/duels arena instance bounds <instanceId> 1` and `2` set a bounds corner;
  `/duels arena instance editmode <instanceId>` opens the same build-tool edit
  flow arenas used before instancing existed.
- The instance menu's two Bounds Corner items do the same job as those
  commands: left click stores your current location, right click teleports you
  to the stored corner (or says it is unset), and the item lore shows the
  stored block coordinates. Both are rejected while the instance is hosting a
  match.
- The arena menu's Out Of Bounds item shows the current mode and grace period.
  Left click steps SOFT_RETURN -> FORFEIT -> WARNING and back around; right
  click prompts for the grace period in chat, rejecting non-numeric input
  without changing anything. Both should match what
  `/duels arena boundary <id> <mode> [graceSeconds]` produces, and take effect
  on the next out-of-bounds step without a restart.
- While an instance is hosting a live match, both the admin menu and
  `/duels arena instance setspawn|bounds|delete <instanceId>` are rejected
  with the "currently hosting a match" message. They succeed again once the
  match ends.
- `/duels arena delete <arenaId>` is rejected while the arena still has
  registered instances, even if none of them are currently in a match. Delete
  the instances first, then the arena delete succeeds.

## Arena reset (block rollback)

This only applies to a match's active arena bounds - set bounds on the test
instance before starting.

1. Build a small stone structure inside the instance's bounds and leave one
   corner outside the bounds as a control.
2. Create a kit that includes TNT (or a shovel/pickaxe, for a plainer break
   test) and enable it on the test arena.
3. Start a duel using that kit. During combat, break part of the structure,
   place some blocks of your own, and detonate at least one TNT charge
   against a wall.
4. End the duel (kill either player, or `/kill` one of them).
5. Watch the structure over the next second or two. Broken/exploded blocks
   should return, and any blocks you placed should disappear, without a
   visible stutter - run `/tps` or Spark across a heavier version of this test
   (several TNT charges) to confirm the restore doesn't tank the tick rate.
   There should also be no item drops left lying on the floor from the
   explosion: explosions inside a tracked arena have their yield zeroed, so
   the debris is never created rather than being cleaned up afterwards. Check
   this for both TNT (entity explosion) and a bed or respawn anchor detonated
   in the Overworld (block explosion).
6. Start a new duel into the same instance immediately after. The structure
   should already be back to normal by the time the new players arrive, not
   mid-repair.
7. Damage something just outside the bounds control corner during a duel.
   Confirm it is *not* reverted - only changes inside the bounds are tracked.
8. Damage the structure, then `stop` the server mid-match instead of letting
   it end normally. On restart, confirm this didn't leave the plugin in a bad
   state (this path goes through `abortMatch`, which resets the same as a
   normal match end).
9. Repeat step 3 against an arena instance with no bounds configured. Confirm
   changes are simply never reverted - this is the expected limitation of an
   unbounded instance, not a bug, but worth seeing once in practice.

## Advancements during a duel

Advancement progress earned incidentally inside a duel is suppressed before it
is granted rather than revoked afterwards, because revoking left the trigger
satisfied and the criterion was immediately re-awarded - a grant/revoke/grant
loop that spammed toasts and chat.

1. Start a duel with a kit that trivially earns something, and take damage,
   shoot a bow, or kill your opponent.
2. There should be no advancement toast, no chat broadcast, and crucially no
   repeating spam during or after the duel.
3. Run `/advancement` (or open the advancements screen) afterwards and confirm
   nothing from the duel was granted.
4. Outside a duel, earn a real advancement and confirm it still works normally
   with its toast and broadcast - the suppression must be scoped to players in
   a match.

## Spectator mode

```text
/duel spectate <player>
/duel spectate
/duel leave
```

With a live duel and a third player:

- `/duel spectate <player>` while they are in a duel starts spectating;
  gamemode becomes Spectator and the player cannot interfere with combat.
- `/duel spectate` with no target opens a menu of currently running duels;
  clicking one starts spectating it, and the menu reports clearly if nothing
  is running.
- The spectator cannot leave the arena's bounds - walking toward the edge
  returns them immediately, regardless of the arena's configured boundary
  mode for combatants (spectators are always held in, not warned or allowed
  to forfeit).
- `/duel leave` stops spectating and returns the player to their exact
  previous location, inventory, and gamemode.
- Spectating an arena with no bounds set is refused with a clear message
  rather than silently allowing an unbounded spectator.
- A combatant cannot spectate their own match. Spectating a match you are
  already spectating is rejected rather than starting a second session.
- When the match ends while being watched, the spectator is automatically
  ejected and told the duel has ended, then restored the same way `/duel
  leave` would restore them.
- Two players spectating the same duel simultaneously both work independently
  - one leaving does not affect the other.
- Disconnect while spectating, then reconnect. The player should not be stuck
  in Spectator mode - confirm they are put back in their original state on
  rejoin rather than needing help from staff.
- Disconnect and reconnect while the *match itself* ends during the outage
  (spectated match finishes while the spectator is offline); confirm rejoin
  still restores them cleanly rather than leaving them attached to a match
  that no longer exists.
