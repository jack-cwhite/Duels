# Duels release test plan

Run this plan on a clean Paper 1.21.11 server with Java 21. Use two clients for the main flow and three clients for interference checks. Four clients are useful for concurrent-arena testing.

Do a full server restart between storage-backend tests. Plugin managers and hot reloads do not reproduce a clean startup reliably.

## 1. Build and clean installation

From the JCore project:

```powershell
mvn clean install
```

From the Duels project:

```powershell
mvn clean package
Copy-Item .\target\Duels-1.0-SNAPSHOT.jar 'C:\path\to\paper\plugins\Duels.jar' -Force
```

Start Paper and check:

- Duels enables without an exception.
- `plugins/Duels/` contains `config.yml`, `database.yml`, `messages.yml`, and `menus.yml`.
- `/version Duels` shows the expected version.
- `/duel` shows player help and `/duels` opens the admin menu for an operator.
- A non-operator can use `/duel` but cannot use `/duels`.

## 2. Arena setup

As an operator, stand at the two desired spawn positions and run:

```text
/duels arena create TestArena
/duels arena setspawn 1 1
/duels arena setspawn 1 2
/duels arena list
```

Use the actual ID printed by the create command if it is not `1`.

Check the arena menus:

- The arena is green, ready, and enabled after both spawns are set.
- Left click on each spawn button updates that spawn.
- Right click teleports you to the saved spawn without changing it.
- Right clicking an unset spawn reports that it is unset.
- Rename persists after a full restart.
- Disable makes the arena unavailable; enable makes it usable again.
- Delete opens a confirmation screen. Cancel keeps the arena; confirm removes it.

Create and delete a temporary arena, then create another. The new ID must be higher than the deleted ID.

## 3. Kit setup and editor safety

Put a recognizable loadout in your inventory, armor slots, and offhand, then run:

```text
/duels kit create Sword
/duels kit list
```

Check:

- The command-created kit copies storage, armor, and offhand contents.
- A newly created empty kit can be filled through the editor.
- Armor slots reject the wrong equipment type.
- Save, close, and reopen preserves every slot.
- Closing without saving leaves the kit unchanged.
- Items moved into or out of the editor do not alter the admin's real inventory when the editor closes.
- Number-key and offhand swaps cannot place invalid armor in an equipment slot.
- Q/drop actions cannot drop copied kit items into the world.
- A custom icon preserves its material and enchantment glow while menu-defined name and lore are applied.
- Delete confirmation cancel/confirm both work.

Create and delete a temporary kit, then create another. Its ID must be higher than the deleted ID.

## 4. Challenge rules

With players `PlayerOne`, `PlayerTwo`, and `PlayerThree`:

```text
PlayerOne: /duel PlayerOne
PlayerOne: /duel PlayerTwo
PlayerThree: /duel PlayerOne
PlayerTwo: /duel deny
PlayerOne: /duel PlayerTwo
PlayerTwo: /duel accept
```

Check:

- Self-challenges are rejected.
- A player can receive and send requests involving several different players.
- A second request between the same pair is rejected.
- `/duel accept <player>` and `/duel deny <player>` select a specific request; omitting the player selects the latest applicable request.
- Deny informs both players.
- A challenge expires after `duel-request-expiry-time`; test `0` separately to confirm it never expires.
- Disconnecting either participant removes pending challenges and informs the remaining player.
- Accepting with no free arena reports that no arena is available.

## 5. Match start and kit selection

Set `kit-selection-time: 10`, restart, then start a duel.

Check:

- Both players are teleported to different arena spawns.
- Both become Survival, stop flying, lose temporary potion effects/fire/fall distance, and start at full usable health and food.
- Their original inventory, armor, offhand, game mode, location, XP, health, potion effects, and flight state are absent during the duel.
- Players can move during selection but cannot damage each other.
- Left click selects a kit. Right click previews it and Back returns to the selector.
- `/duel kit` reopens selection after manually closing it.
- Selecting nothing applies the first allowed kit when the timer ends.
- The selection menu closes when combat starts and stale clicks cannot change the chosen kit.

Snapshot check:

1. Start a duel with a long selection timer.
2. While selection is open, edit or delete one of the available kits from another admin account.
3. Preview and select that kit in the existing duel.
4. Confirm the existing duel still receives the old kit, while the next duel sees the updated kit list/content.

## 6. Bare-fist arena

In the arena's Allowed Kits menu, disallow every kit and start another duel.

Check:

- No selector opens.
- Both players are told the duel has no kits.
- The countdown still runs.
- Combat starts with empty inventory, armor, and offhand.

## 7. Damage and winner matrix

Run separate duels for these conditions:

- Direct melee lethal hit.
- Arrow or other projectile lethal hit.
- Primed TNT, lingering potion clouds, and evoker fangs caused by a duelist.
- Fire/fire tick lethal damage.
- Fall damage after being knocked from a height.
- Lava, drowning, and void damage.
- `/kill <loser>` while a duel is in progress.
- One player disconnecting during selection.
- One player disconnecting during combat.

For each completed combat duel, the opponent should win exactly once and both online players should be restored exactly once.

With a third player present:

- Third-player melee and projectiles cannot damage a duelist.
- A duelist cannot damage the third player.
- A tamed pet owned by a third player cannot damage a duelist.
- A duelist's tamed pet cannot damage the third player.
- TNT and lingering effects caused by a third player cannot damage a duelist.

## 8. Arena locking and concurrency

During an active match, try the GUI and commands:

```text
/duels arena setspawn <id> 1
/duels arena delete <id>
```

Also try rename, enable/disable, and allowed-kit changes in the GUI. Every mutation must be rejected until the match ends.

With two complete arenas and four players, start two duels. Each duel must receive a different arena. A fifth challenge should report no arena if both are occupied.

## 9. State recovery and shutdown

Before a duel, give each player distinctive inventory, armor, XP, potion effects, health, location, and game mode. Verify exact restoration after:

- A normal win.
- Environmental death.
- Disconnect and reconnect.
- `stop` while a match is active, followed by a server restart.
- Forced process termination while a match is active, followed by restart and player login.

For the forced-termination case, `playerstates.yml` should retain snapshots before the crash and remove each snapshot after that player rejoins and is restored.

## 10. Stats backends

Complete at least three duels with known winners for each backend. Restart after editing configuration.

### YAML

Set `stats-storage: YAML`. Confirm `stats.yml` contains one match object per completed combat duel. Selection-stage disconnects should not count.

### SQLite

Set `stats-storage: SQL` and `type: SQLITE`. From the server directory:

```powershell
sqlite3 .\plugins\Duels\database.db "SELECT * FROM duels_matches;"
sqlite3 .\plugins\Duels\database.db "SELECT * FROM duels_match_participants;"
```

### MySQL or MariaDB

```sql
USE duels;
SHOW TABLES;
SELECT * FROM duels_matches ORDER BY id DESC;
SELECT * FROM duels_match_participants ORDER BY match_id DESC;
SELECT player_id, SUM(won) AS wins FROM duels_match_participants GROUP BY player_id;
```

### PostgreSQL

```sql
\c duels
\dt
SELECT * FROM duels_matches ORDER BY id DESC;
SELECT * FROM duels_match_participants ORDER BY match_id DESC;
SELECT player_id, COUNT(*) FILTER (WHERE won) AS wins FROM duels_match_participants GROUP BY player_id;
```

For every SQL backend, verify `/duel top`, restart persistence, deleted-kit historical rows, and that only one match plus two participant rows are written per duel.

## 11. Configuration and failure behavior

- Set an invalid `stats-storage`; verify a clear warning and documented fallback.
- Set `kit-selection-time: 0`; verify a warning and fallback to 15 seconds.
- Set a negative challenge expiry; verify a warning and fallback to 30 seconds.
- Stop an external database while the server runs. Gameplay must still finish without tick lag; stats operations should log a bounded error asynchronously.
- Restore the database and restart; connection and migrations should succeed once without duplicate-index errors.
- Corrupt one arena, kit, or saved-player entry in YAML. Startup should warn and skip that entry instead of disabling the plugin.
- Remove or mistype a menu material. The affected item should become the visible invalid-item barrier and the console should identify its config path.

## Release acceptance

Ship only when:

- `mvn clean package` passes for JCore and Duels.
- The full gameplay, state recovery, editor safety, and chosen storage-backend sections pass on a real Paper server.
- The console contains no unexpected exceptions or repeated connection-pool warnings.
- A Spark profile during several simultaneous duels shows no database work on the server thread.
