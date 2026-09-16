# Duels

A 1v1 duel plugin for Paper servers - challenge another player, pick a kit, fight in a dedicated arena. Kits and arenas are fully admin-configurable through in-game menus, and match results can be tracked in a database or a flat file, whichever suits your server.

## Requirements

- Paper (or a compatible Paper fork) 1.21.11
- Java 21

JCore is bundled inside the Duels jar (shaded at build time) - you don't need to install it separately.

## Installation

1. Drop `Duels-<version>.jar` into your server's `plugins` folder and restart the server.
2. On first start, Duels generates `config.yml`, `database.yml`, `messages.yml`, and `menus.yml` in `plugins/Duels/`.
3. Set up at least one arena and one kit (see below) before anyone can actually duel - `/duels` opens the admin menu if you're not sure where to start.

## Config files

- **`config.yml`** - core settings: how long a duel challenge stays pending before expiring, how long the kit-selection/free-roam window lasts before a fight starts, and whether match stats are stored in a database (`SQL`) or a flat file (`YAML`).
- **`database.yml`** - only read when `stats-storage: SQL`. Pick `SQLITE` (default, no setup required), `MYSQL`, `MARIADB`, or `POSTGRESQL`, and fill in the matching connection section. For anything other than SQLite, the database itself has to already exist on the server you point it at - Duels creates its own tables, not the database.
- **`messages.yml`** - every player-facing message, colour-coded with `&` codes and `{placeholder}` substitution. `core.prefix` is prepended to single-line messages; help-list lines are sent as written.
- **`menus.yml`** - titles, materials, names and lore for every menu button. Some items support `alternate-material`/`alternate-name`/`alternate-lore` keys for state-dependent appearance (e.g. an arena's enabled/disabled toggle) - only the items that actually need it use them, everything else ignores those keys entirely.

## Permissions

- `duels.duel` (default: everyone) - challenge, accept, deny, view stats, and manage your own duels.
- `duels.admin` (default: op) - full admin access; grants every node below it.
- `duels.admin.arena` / `duels.admin.kit` - access to the arena/kit management menus and their subcommands; each also has its own `.create`/`.delete`/`.list`/etc. children if you want to hand out narrower access.

See `plugin.yml` for the full list with descriptions. One thing worth knowing if you're setting up permissions by hand rather than granting the `duels.admin` umbrella: a subcommand checks its own permission *and* every parent's on the way down, so `/duels arena create` needs `duels.admin.help`, `duels.admin.arena`, and `duels.admin.arena.create` all granted - not just the last one.

## Commands

- `/duel <player>` - challenge someone (or accept their pending challenge to you, if they've already sent one).
- `/duel accept` / `/duel deny` - respond to a pending challenge.
- `/duel kit` - reopen the kit selection menu if you closed it mid-match.
- `/duel top` - view the leaderboard.
- `/duels` - open the admin menu (arenas, kits).
- `/duels arena create|delete|list|setspawn` and `/duels kit create|delete|list` - command equivalents of the admin menu. Creating a kit and setting arena spawns require a player; list and delete can be used from the console.

## How a duel plays out

1. One player challenges another; the other accepts within the configured expiry window.
2. Both players are teleported to a free arena. If that arena has any kits enabled, there's a free-roam window where both players pick a kit (or the countdown just ends in a bare-fisted fight if the arena has every kit disabled).
3. Once the countdown ends, the chosen kits are applied and combat is enabled.
4. The match ends on death, disconnect, or being reduced to no health from any source - the last one standing wins. Only damage from the two duelists themselves counts; anyone else's interference is blocked outright.
5. Both players are teleported back and have their pre-duel inventory/state restored, and (if the match actually reached combat) the result is recorded to stats.

## Arenas and kits

- An arena needs both spawn points set (`/duels arena setspawn` or the arena menu) before it's usable, and can be manually disabled from the arena menu if you want to take it offline for editing without deleting it.
- Every kit is allowed in every arena by default - use the arena's kit menu to disallow specific kits per arena instead of having to opt each one in.
- A kit's items are set by holding them and using the kit editor menu; a kit can also have a custom icon (any item, with its material and enchant glow preserved) set separately from its actual contents, shown in the kit list/selector menus instead of the default icon.
- Editing or deleting an arena that's currently hosting a match is blocked. Deleting or editing a kit during a duel is safe: each match takes its own kit snapshots when it is created, so changes only affect future matches.

## Development and release checks

- [Manual release test plan](docs/TESTING.md)
- [Focused retest after the latest fixes](docs/RETEST.md)
- [JCore and Duels architecture guide](docs/ARCHITECTURE.md)
- [Current release review](docs/RELEASE_REVIEW.md)

Build and run the automated tests with:

```powershell
mvn clean package
```

The finished plugin is written to `target/Duels-<version>.jar`. The build no longer copies files to a machine-specific test-server folder.
