# Phase 5 - Filterable Statistics and Match History

## Product behaviour

`/duel stats` opens the viewer's profile. `/duel stats <player>` resolves an
online player first and then the last recorded name of an offline player.
Profiles show matches, wins, losses, win rate, current/best winning streak,
disconnect losses, and average combat duration.

Every profile query can independently filter by:

- opponent;
- viewed player's kit;
- opponent's kit;
- arena; and
- all time, the last 7 days, or the last 30 days.

The kit direction is intentional. On Bob's profile, "Bob's Kit: 4" is distinct
from "Opponent's Kit: 4", so mixed-kit matches and Bob-versus-Charlie queries
are unambiguous. Left-clicking a resource filter lists current configuration;
right-clicking accepts an exact numeric ID so deleted historical arenas and kits
remain queryable.

When viewing somebody else, right-clicking Opponent is a one-step head-to-head
filter against the viewer. Left-clicking accepts any recorded player name, which
is how A can inspect B versus C without being involved in those matches.
A request to compare the viewed player against themselves is rejected with a
clear message instead of producing a meaningless empty result.

The Match History screen uses the same active filters and retrieves 45 records
at a time. Each record exposes both kits, arena, result, date, session duration,
combat duration, ending phase, end reason, and final Paper damage cause where
one exists. Deleted configuration is displayed as `Deleted Arena #id` or
`Deleted Kit #id`; the ID remains the historical identity.

`/duel top` supports wins, matches played, win rate, best winning streak, and
current winning streak. Win rate has a configurable minimum-match floor at
`statistics.win-rate-minimum-matches` (10 by default).

## Timing semantics

A match has three timestamps:

- `started_at`: the match object was committed and the players entered its
  lifecycle;
- `combat_started_at`: the state changed to `IN_PROGRESS`; nullable when combat
  never began; and
- `ended_at`: a winner was produced.

Public "match length" and averages use combat duration. A kit-selection or
grace-period disconnect still counts as a match, loss, and streak break, but is
excluded from combat-duration calculations. It is not represented as a
zero-second fight. Session duration remains available in the match detail view.

A nullable kit means no kit was applied before the result. It is preserved as
real information and displayed as `No kit applied`; it is never converted into
a fake kit ID. This also correctly covers deliberately bare-fisted arenas.

## Result semantics

`MatchConclusion` makes the gameplay rule ending a duel supply structured facts
instead of just a winner UUID:

- `DEFEAT`, optionally with a Paper damage-cause enum name;
- `DISCONNECT`; or
- `BOUNDARY_FORFEIT`.

The state immediately before `ENDED` is also recorded. This lets history
distinguish a disconnect during kit selection, grace countdown, and live combat
without parsing display text. Server-stop aborts still produce no result.

## Query and ownership model

`StatsQuery` is the one immutable filter definition shared by summaries,
history, head-to-head views, and repository implementations. SQL translates its
non-null values into a dynamic `WHERE` clause. YAML turns each stored match into
the same player-relative `MatchHistoryEntry` and applies the equivalent
predicate.

`StatsManager` remains the plugin entry point and orders reads behind pending
writes. `StatsAnalytics` calculates totals and streaks from ordered history, so
there is no stored counter that can drift from the authoritative match records.
SQL work stays on JCore's asynchronous executor. YAML remains intended for
small installations because every save rewrites a growing file and filtered
queries scan memory.

Player UUIDs are authoritative. Participant names are stored with each result
so offline profiles and leaderboard names do not depend on a live Mojang lookup.
Offline entries deliberately use a generic player head; attempting to resolve
skins for a full historical leaderboard can rate-limit the server. Online
players still show their live skin.

## Fresh schema boundary

This phase deliberately has no upgrade migration because all current data is
test data. Before running this build against an existing installation, remove:

- SQL: `duels_match_participants`, then `duels_matches`, and the test database's
  `duels_migrations` table/row so migration 1 creates the new schema; or
- YAML: `stats.yml`.

Deleting the SQLite database file is the simplest local reset. Arena, kit,
instance, player-state, and spectator YAML files are unrelated and need not be
removed.

## Verification boundary

Automated coverage proves:

- SQL writes and portable boolean reads;
- arena, both kit directions, opponent, and time filters compose;
- SQL and YAML return identical summaries for the same composite query;
- filtered history is newest-first and paged;
- pre-combat forfeits have no combat duration;
- disconnect phase, null-kit behaviour, streaks, averages, and offline-name
  resolution; and
- all existing match, arena, spectator, containment, and provisioning tests
  continue to pass.

Live acceptance passed on 2026-09-27 against fresh SQLite and fresh YAML storage.
It covered normal defeat, environmental death, boundary forfeit, combat/grace/
kit-selection disconnects, all individual and composite filters, directional kits,
offline lookup, self-comparison rejection, history and leaderboard paging beyond 45
records, all leaderboard categories, Back/Next navigation, and restart persistence.
The acceptance run also confirmed that offline leaderboard/profile entries use generic
heads without triggering Mojang profile lookups or rate-limit warnings.
