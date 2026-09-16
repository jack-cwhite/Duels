# Focused release retest

This plan covers the behavior changed after the first full manual pass. The
parts of `TESTING.md` that already passed do not need to be repeated. Use a
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
