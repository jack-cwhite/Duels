# Release review

## Status

The codebase is a release candidate. Automated tests and packaging pass. A real Paper server still needs to complete the manual matrix in `TESTING.md`, especially inventory interaction, crash recovery, damage attribution, and each database engine intended for release.

## Release issues fixed during review

- Active matches are aborted safely and online players restored during plugin disable.
- Match termination is idempotent, preventing duplicate restoration or stat writes.
- Existing matches hold independent kit snapshots; live kit edits and deletion affect future matches only.
- Arena and kit IDs are allocated monotonically and no longer reused after deletion.
- First database access is synchronized, preventing competing pools and migrations.
- Migration tables are namespaced per consuming plugin.
- Duels' initial SQL migration is repeatable for existing installations, including MySQL indexes.
- JCore drains queued async work before closing the database pool.
- JCore closes owned menus before shutdown so editable-menu restore callbacks run.
- JCore uses a bounded background executor instead of an unbounded cached pool.
- Pending chat-input requests are thread-safe and removed on disconnect.
- Invalid saved-player entries are skipped with a warning instead of stopping startup.
- Player-state cache entries are removed only after a successful apply and file update.
- Kit editor item movement no longer consumes admin items or permits copying kit items.
- Editable menus block drop actions and validate offhand swaps.
- Kit-selection navigation starts a fresh navigation flow and stale clicks are rejected.
- Kit previews use the match snapshot even if the live kit has been deleted.
- Third-party damage is blocked in both directions, including projectiles, tamed pets, primed TNT, lingering clouds, and evoker fangs.
- SQL win/loss and leaderboard queries use portable boolean parameters for PostgreSQL, MySQL, MariaDB, and SQLite.
- Players are cleaned again when selection ends, so empty kits and bare-fist arenas cannot retain countdown pickups or effects.
- Arena spawn buttons implement their documented left-click set and right-click teleport behavior.
- Players may hold requests from several opponents; duplicate requests for the same pair are rejected and unrelated requests are cleared when a match starts.
- Invalid countdown settings fall back to safe values with warnings.
- Newly added YAML defaults are reloaded immediately, and database defaults merge without an existing-file warning.
- Admin menu sections respect arena/kit permissions.
- Arena and kit list order is deterministic by ID.
- The machine-specific Maven copy step was removed.
- Duels now has integration tests for IDs, challenges, kit snapshots, command-created kit slot separation, and deep kit copies.

## Known boundaries

- Duels does not define physical arena regions. It prevents combat interference, but another protection or world-management system must stop outsiders entering, placing blocks, or teleporting into an arena.
- YAML stats rewrite a growing file and are intended for small installations. SQLite or an external SQL server is the better long-term choice.
- SQL write failures are logged and do not block match cleanup. There is no durable retry queue, so a result can be lost while the database is unavailable.
- Arena and kit definitions remain YAML-backed even when match statistics use SQL.
- Existing installations created before monotonic ID metadata cannot reconstruct IDs that were already deleted. From this release onward IDs are not reused.
- External database behavior still needs real-engine acceptance tests; SQLite unit tests cannot substitute for MySQL, MariaDB, and PostgreSQL.

## Automated verification

- JCore: 300 tests run with no failures or errors. One inventory-click test is skipped because MockBukkit does not implement the required slot conversion; its behavior remains in the Paper acceptance plan.
- Duels tests cover plugin startup, monotonic IDs, multiple challenge requests, match kit snapshots, command-created kit slot separation, kit deep copies, strict arena fields, and SQLite-backed stats queries. Shutdown restoration remains a real-server acceptance check because MockBukkit does not implement every PlayerState API used by Paper.
- Duels packages successfully with JCore and database drivers shaded into the plugin jar.
