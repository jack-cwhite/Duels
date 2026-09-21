# Phase 4B Design: Arena Selection and Dynamic Provisioning

_Implementation status: core capture, provisioning, selection, recovery, and
exclusive per-arena STATIC/DYNAMIC setup are implemented. Target-Paper manual
verification remains before this phase is declared complete._

## Revised per-arena type decision (2026-09-21)

This section supersedes older hybrid examples below. An arena's type is chosen
before setup. STATIC has any number of admin-built playable copies in existing
worlds and never uses structure capture. DYNAMIC has exactly one non-playable
build source; captured NBT provisions all of its playable copies in the Duels
dynamic world. Both arena types may coexist on a server, but a single arena
never mixes hand-built and generated playable copies.

The reason is admin clarity: the old “use manual copies first, then generate”
model made the source look like a playable copy and exposed irrelevant controls
in both setup flows. Match, spectator, reset, and release lifecycles still use
ordinary `ArenaInstance` objects for playable copies. Allocation filters by
arena type and instance origin; it never allocates a SOURCE.

Migration is non-destructive. One old MANUAL copy of an already-DYNAMIC arena
becomes SOURCE at startup. A STATIC arena with exactly one idle MANUAL copy can
be explicitly converted through the GUI or command; its ID, spawns, bounds
and physical blocks remain, but it stops hosting matches until captured and
provisioned. Multi-copy STATIC conversion is refused. An old DYNAMIC arena
with multiple manual copies is quarantined from manual allocation; an admin
selects one using `source adopt` and resolves the others deliberately.

The type cannot be casually toggled after instances/templates exist. Dynamic
template recapture/clear requires retiring generated copies first. The one
source cannot be deleted while its template or generated copies still exist.
`docs/IN_GAME_TEST_PLAN.md` is the current acceptance route; the hybrid
scenarios later in this historical design are not test instructions.

## Purpose

Phase 4B lets Duels support all of these deployments without forcing one server type's
infrastructure onto another:

- A standalone Paper server with manually built arena instances only.
- A Paper server with a mixture of manual and dynamically provisioned instances.
- A future Velocity network whose Paper backends use either of those local modes.

This phase creates arena copies inside one Paper server. It does not create new Paper
server processes and does not implement cross-server matchmaking; those are Phase 9
concerns.

## Current behaviour and the missing capability

`Arena` is already the template/policy object and `ArenaInstance` is already one
physical copy. `StaticArenaAllocator` claims a ready registered instance, `MatchManager`
uses it for the duel, `BlockChangeRollbackStrategy` cleans it, and the allocator releases
it afterward.

The first Phase 4B increment has added two allocation requests:

```text
allocate()          -> any enabled arena
allocate(arenaId)   -> one selected template only
```

`ArenaProvisioningMode` is also persisted with a safe `STATIC` default. What is still
missing is the structure template, the dedicated world and slots, asynchronous
preparation, player/admin selection surfaces, and failure/restart recovery.

## Why this is the next phase

Phase 3 supplied persistent physical instances and Phase 3B supplied the reset-before-
release lifecycle. Dynamic provisioning can now create another normal instance instead
of inventing a second match system. Arena selection is also needed before matchmaking:
a later queue must be able to request a template using the same operation as a direct
challenge.

Building this before deeper statistics or rewards keeps the risk local to arena
acquisition. Those later systems can continue consuming completed matches without caring
how their arena was obtained.

## Representative scenario

Castle has one manually built instance and is configured `DYNAMIC`. One Castle duel is
already active when a second Castle challenge is accepted. Duels reserves both players,
finds no free Castle copy, reserves slot 7, loads the slot's chunks, pastes Castle's
current template revision, derives its two spawns and gameplay bounds, persists a ready
`ArenaInstance`, and only then moves the players. When the match ends, rollback completes
before slot 7's instance returns to the free pool. A third Castle duel reuses that copy
without another paste.

## Required behaviour

### Static arena

```text
request arena
  -> claim a free manually registered instance
  -> no free instance: fail without creating infrastructure
```

### Dynamic arena

```text
request arena
  -> claim an existing free instance if possible
  -> otherwise reserve a slot
  -> load the required chunks
  -> paste the saved template
  -> persist a normal ArenaInstance
  -> claim it for the match
```

Static and dynamic templates may coexist. `DYNAMIC` means “provision when the registered
pool is exhausted,” not “ignore manual instances.”

## Decisions

The principal rejected alternatives are:

- **One world per match:** expensive blocking world lifecycle and memory cost, while
  still bounded by one server's capacity.
- **Variable-size spatial packing:** denser but substantially harder to prove safe and
  reconstruct after failures.
- **Paste/delete every match:** simple conceptually but creates avoidable latency and
  world work compared with pooling plus the reset system already implemented.
- **Mandatory WorldEdit/FAWE:** useful for some networks but an unnecessary installation
  requirement for standalone servers.
- **Synchronous match start:** cannot represent chunk preparation or delayed providers
  without blocking the server thread or mutating players too early.

### 1. Paper structures are the baseline

The baseline provider uses Paper 1.21.11's `StructureManager` and `Structure` APIs. Paper
can capture a cuboid, save/load it as NBT, and place it without a WorldEdit dependency.
Files are owned by Duels under its data folder rather than placed in an administrator's
WorldEdit directory.

The provider does not capture entities initially. Entity copying introduces identity,
duplication, and cleanup problems that blocks do not. Display entities or decorative
armor stands can be reconsidered when a real arena requires them.

Paper structures also do not provide WorldEdit's full schematic/editing ecosystem. An
optional WorldEdit/FAWE provider may later add `.schem` import, biome-rich clipboard
support, and high-volume edit handling. It is not required for Phase 4B completion.

The code should have a narrow Duels-owned `ArenaStructureProvider` boundary because it
isolates an external world API, allows deterministic test doubles, and has a concrete
future alternative. The first implementation remains `PaperArenaStructureProvider`;
no WorldEdit code is added yet.

Proposed provider responsibilities:

```text
capture(corners, targetFile)
load(templateDefinition)
place(templateDefinition, world, origin)
```

All provider completions return to the server thread. Provider-specific exceptions are
translated into Duels result statuses and logs rather than escaping into commands or
match cleanup.

### 2. A separate capture region is required

Gameplay bounds and structure bounds are not necessarily the same. A castle wall may
sit outside the area players are allowed to enter. Capturing only gameplay bounds would
silently omit that wall.

Arena edit mode therefore gains two temporary **structure capture corner** tools plus a
capture action. The existing instance still supplies the authoritative player spawns
and gameplay bounds. Capture corners exist only in the edit session and are not part of
the runtime `ArenaInstance`.

The admin flow is:

```text
build and register one manual instance
  -> configure its spawns and gameplay bounds
  -> enter edit mode
  -> select structure capture corners
  -> capture template
  -> validate/test provision
  -> enable DYNAMIC mode
```

The capture region must contain both spawns and the complete gameplay bounds. Capture is
rejected if worlds differ, required values are missing, the source instance is active,
or the structure exceeds configured dimensions/volume.

### 3. Template coordinates are relative to the capture origin

The minimum block coordinate of the capture cuboid is the structure origin. The template
definition stores:

```text
revision
format/provider id
Duels-owned structure filename
structure size (x, y, z)
spawn 1 offset (x, y, z, yaw, pitch)
spawn 2 offset (x, y, z, yaw, pitch)
gameplay bounds corner 1 offset (x, y, z)
gameplay bounds corner 2 offset (x, y, z)
```

When pasted, adding each offset to the destination origin produces the absolute
locations already expected by `ArenaInstance`, `MatchManager`, `BoundaryEnforcer`, and
`SpectatorManager`.

No rotation or mirroring is supported in the baseline. The captured orientation is the
placed orientation. This avoids having to rotate spawn yaw, bounds, and every relative
coordinate before there is a product need.

### 4. Structure files and metadata use atomic replacement

Structure files live at paths controlled by Duels, for example:

```text
plugins/Duels/structures/arena-12-r3.nbt
```

User-provided names are not used as paths. This avoids rename problems and path
traversal.

A capture writes a new revision to a temporary file, verifies that it can be loaded,
moves it to its final revision path, and only then persists the new arena metadata. A
failed capture leaves the previous template usable.

Replacing or clearing a template requires the arena to be disabled. No new allocation
may begin while replacement is in progress. Free provisioned instances from the old
revision are retired; an active or pending use blocks replacement until it finishes.
Manual instances are unaffected.

### 5. Dynamic arenas use one dedicated void world

The baseline uses one Duels-owned void world. `DynamicArenaWorldManager` creates or
loads it only when a dynamic template or persisted provisioned instance exists. A
static-only installation does not create the world.

The world is created through `WorldCreator` with a Duels void `ChunkGenerator` and
vanilla structure generation disabled. Its identity is recorded and marked as
Duels-owned. If the configured name points at an unrelated existing world, startup
refuses dynamic provisioning rather than modifying it.

One world is sufficient for the single-server scaling target. When it reaches its
configured capacity, the correct next network-scale answer is another Paper backend,
not silently creating unlimited worlds.

### 6. Slots use a fixed persisted grid

Variable-sized rectangle packing saves empty space but makes restart reconstruction,
deletion, and overlap proofs harder. Empty space in a void world is cheap, so Phase 4B
uses fixed horizontal slots.

The persisted layout contains:

```text
layout version
world name and UUID
slot width and length
slot padding
base Y
slots per row
maximum slots
```

The slot origin is deterministic:

```text
column = slotIndex % slotsPerRow
row    = slotIndex / slotsPerRow
originX = column * slotWidth + padding
originZ = row * slotLength + padding
originY = baseY
```

The structure must fit inside the slot's padded interior and the world's vertical
limits. The smallest unoccupied slot index is selected. Slot geometry becomes immutable
once provisioned instances exist; changing it requires an explicit future migration or
deleting the dynamic pool. This prevents a configuration edit from moving the logical
coordinates of existing arenas.

`dynamic-layout.yml` owns layout/world identity. Slot occupancy is not duplicated there;
it is reconstructed from persisted provisioned `ArenaInstance` records.

### 7. Provisioned instances are pooled and reused

A provisioned instance is not destroyed after every duel. Normal release remains:

```text
match ends
  -> spectators leave
  -> players restore
  -> block changes roll back
  -> dynamic instance becomes clean/ready
  -> allocator releases it to the pool
```

This preserves the existing reset lifecycle and avoids a full schematic paste for every
match. A full repaste is reserved for initial provisioning, crash recovery, a known
unclean instance, or template replacement.

Clearing to air happens only when an instance is retired/deleted. It is performed in
bounded batches while the slot remains occupied, so another provision cannot overlap a
partially cleared slot.

### 8. Dynamic instance health is persisted

`ArenaInstance` gains an origin/type with a backwards-compatible `MANUAL` default.
Provisioned instances additionally store dynamic placement metadata:

```text
slot index
template revision
structure size
dynamic state
```

Proposed dynamic states:

| State | Meaning |
| --- | --- |
| `PROVISIONING` | Slot and instance record exist, but the paste is not confirmed complete. |
| `READY` | Clean and eligible for allocation. |
| `DIRTY` | Claimed by a match or cleanup was interrupted; never allocate after restart. |
| `RETIRING` | Being cleared and removed. |
| `FAILED` | Quarantined after a failure that automatic recovery could not repair. |

Before pasting, Duels reserves the slot and persists a `PROVISIONING` instance record.
After a successful paste and coordinate calculation it persists `READY`. Before players
enter, a provisioned instance is persisted as `DIRTY`; after reset it returns to
`READY`.

This ordering makes crashes recoverable:

- Crash before the record: no world mutation has begun.
- Crash after `PROVISIONING`: startup knows which slot may contain a partial paste.
- Crash during a match/reset: `DIRTY` triggers a full repaste before reuse.
- Crash during retirement: `RETIRING` cleanup resumes before the slot is freed.

`FAILED` instances remain visible to administrators with diagnostics and continue to
occupy their slots. Automatically forgetting them could cause a later arena to overlap
unknown blocks.

### 9. Allocation and match start become asynchronous workflows

The current synchronous `Optional<ArenaInstance>` is sufficient only when every instance
already exists. Dynamic provisioning may need asynchronous chunk loads and several
server-thread stages. It must not teleport or clear players before preparation succeeds.

The allocator evolves toward:

```text
allocate(ArenaSelection)
    -> CompletableFuture<ArenaAllocationResult>
```

`ArenaSelection` represents either `ANY` or one specific arena ID.
`ArenaAllocationResult` distinguishes success from meaningful failures such as disabled
arena, invalid template, capacity reached, provisioning failure, or cancellation.

`MatchManager` owns a `pendingPlayers` set and a start-request token:

1. Validate both players on the main thread.
2. Atomically mark both pending so neither can enter another duel.
3. Ask the allocator for an instance.
4. Keep both players unchanged while the arena is prepared.
5. Revalidate online/busy state when allocation completes.
6. Save player state, create the match, teleport, and begin pregame.
7. On any failure, release the instance/slot and clear both pending markers.

All futures complete onto the server thread before manager, player, world, or menu state
is touched. Disk reads may be prepared asynchronously, and Paper's asynchronous chunk
loading API may be used, but structure capture/place and Bukkit world mutation remain on
the main thread.

Paper structure placement itself is synchronous. The baseline therefore enforces
structure dimension/volume limits and measures real paste time. FAWE may later provide a
different provider for installations needing very large asynchronous edits.

### 10. The allocator owns claims; the provisioner owns construction

Dynamic construction must not maintain a second independent “allocated” set. Two owners
could each believe the same instance is free.

The responsibilities are:

| Component | Owns |
| --- | --- |
| `ArenaManager` | Arena policy, provisioning mode, template definition, safe mode/template mutations. |
| `ArenaInstanceManager` | Persisted manual and provisioned instances. |
| `ArenaAllocator` / allocation coordinator | The one authoritative claim set and allocation decisions. |
| `DynamicArenaProvisioner` | Reserve slot, prepare chunks, paste, create/recover/retire provisioned instances. |
| `DynamicArenaWorldManager` | Duels-owned world creation/loading and identity validation. |
| `DynamicArenaSlotManager` | Layout, slot reservations, occupied-slot reconstruction and overlap prevention. |
| `ArenaStructureProvider` | Capture/load/place one structure format. |
| `MatchManager` | Pending players, match creation, player mutation, and eventual release. |
| `ChallengeManager` | Challenge terms, including arena selection, and acceptance claim state. |

The allocator first claims an existing eligible instance. Only when none exists and the
selected arena is `DYNAMIC` with a valid template does it call the provisioner. Once the
provisioner returns a ready instance, the same allocator claims it before completing the
request.

For `ANY`, existing free instances are preferred over creating new ones. The initial
implementation preserves today's deterministic first-eligible behaviour; random or
round-robin arena variety is a separate product decision.

## Challenge and player-selection design

Arena selection is a term of the challenge, chosen by the challenger:

```text
ANY
or
specific arena ID
```

The challenged player accepts exactly those terms. They do not silently choose a
different arena during acceptance.

Existing behaviour remains available:

```text
/duel <player>                    -> challenge for ANY arena
/duel challenge <player>          -> collision-safe ANY challenge
```

Additive selection surfaces:

```text
/duel challenge <player> <arenaId>
/duel select <player>             -> open ArenaSelectionMenu
```

`ArenaSelectionMenu` contains “Any Arena” plus enabled templates that have a viable
manual instance or a valid dynamic template. It shows provisioning mode and current free
capacity, but selection does not reserve capacity; the arena is acquired on acceptance.

`Challenge` stores the selection. Challenge messages name the selected arena or “Any.”

Accepting an asynchronous start claims the challenge so its expiry task cannot announce
expiration while the arena is being prepared. On success the challenge is consumed. On
failure it is unclaimed and retained if its original expiry has not passed; otherwise it
expires normally. Disconnect or cancellation releases the claim and any arena resources.

Players receive “Preparing arena…” feedback only when preparation is actually delayed.
They remain in their current state/location until the match commits.

## Administrator design

### Commands

Proposed command surface:

```text
/duels arena provisioning <arenaId> <STATIC|DYNAMIC>
/duels arena template info <arenaId>
/duels arena template capture <instanceId>
/duels arena template clear <arenaId>
/duels arena template test <arenaId>
/duels arena instance retry <instanceId>
```

Capture is player-only and requires an edit session with both structure corners selected.
Pure configuration/status commands work from console. Template replacement, clearing,
and switching back to static use confirmation for destructive cleanup.

### Menus

`ArenaDetailMenu` gains:

- Provisioning mode and validation state.
- Template information/capture guidance.
- Test provision.
- Clear/replace template with confirmation.
- Dynamic capacity and failed-instance diagnostics.

`ArenaInstanceListMenu` identifies `MANUAL` versus `PROVISIONED`, slot index, revision,
and health. Manual instances retain all existing edit actions. Provisioned instances do
not allow manual spawn/bounds edits because those values are derived from template
offsets; they support inspect, retry/rebuild, and retire/delete.

Edit mode adds structure-corner particles in a different colour from gameplay bounds,
plus a capture tool. This makes the two boxes visually distinct.

## Configuration

The first implementation should introduce a `dynamic-arenas` section resembling:

```yaml
dynamic-arenas:
  world-name: duels_dynamic_arenas
  max-slots: 64
  slots-per-row: 8
  slot-width: 256
  slot-length: 256
  slot-padding: 16
  base-y: 64
  max-template-volume: 2000000
  provision-timeout-seconds: 30
  cleanup-blocks-per-tick: 1024
```

These are safe starting values, not universal performance claims. Every value is
validated with warnings and conservative fallbacks. Layout values are copied into the
persisted layout when the world is first created. Once slots exist, persisted geometry
wins over edited config and a clear warning explains why, preventing coordinate drift.

Biome copying is not included in the Paper baseline. The dynamic world uses a stable
configured/default biome. Per-template or full-volume biome capture should be added only
if real arenas require it; WorldEdit/FAWE is another future route for biome-rich
schematics.

## Failure handling and recovery

| Failure | Required response |
| --- | --- |
| Missing/invalid template | Do not enable dynamic mode; manual instances remain usable. |
| No free slot | Return capacity failure; do not mutate players or consume the challenge. |
| Chunk load timeout/failure | Mark provisioning failed, keep/recover the slot record, and return players to non-pending state. |
| Structure load/paste exception | Quarantine or clean the slot; never publish the instance as ready. |
| Instance persistence failure | Do not start a match; retain enough slot state to prevent overlap. |
| Player disconnects while preparing | Cancel start, release/retire prepared resources, preserve normal player state. |
| Duplicate simultaneous requests | Main-thread slot reservation assigns different slots; one instance cannot satisfy two requests. |
| Plugin disable during preparation | Cancel futures/tasks and leave persisted non-ready state for startup recovery. |
| Server crash during a dynamic match | Persisted `DIRTY` state causes full repaste before reuse. |
| World missing or UUID mismatch | Disable dynamic provisioning and log a prominent diagnostic; never recreate over ambiguous data. |
| Duplicate persisted slot IDs | Quarantine conflicting instances and block that slot until an admin resolves it. |
| Template replaced | Arena must be disabled; old provisioned instances retire before re-enable. |

Failures are logged with arena ID, instance ID, slot, revision, stage, and exception. User
messages remain concise and configured through `messages.yml`.

## Chunk lifetime and cleanup

Before paste, every chunk intersecting the structure AABB is loaded asynchronously.
Duels holds plugin chunk tickets from preparation through match reset/retirement so chunks
cannot unload halfway through a paste or batched rollback. Tickets are removed only after
the instance is clean and released.

Normal release also removes non-player transient entities inside the arena bounds where
safe (projectiles, dropped items, and similar match debris). Template entities are not a
baseline concern because capture uses `includeEntities = false`.

## Persistence and migration

### `arenas.yml`

Existing rows without `provisioningMode` already default to `STATIC`. Add optional nested
template metadata. Static arenas do not need it.

### `arena-instances.yml`

Existing rows default to `MANUAL`. Provisioned rows add slot/revision/size/state metadata.

### `dynamic-layout.yml`

New file containing immutable layout version and world identity/geometry. Occupancy is
derived from instance rows.

### Structure files

Duels-owned versioned NBT files beneath `plugins/Duels/structures/`.

No migration creates a dynamic world or template automatically. Existing servers load
exactly as static until an administrator captures a template and explicitly enables
dynamic mode.

## Threading model

- Commands, menus, manager maps, allocation claims, slot reservations, world creation,
  structure capture/place, entity cleanup, and player mutation run on the Paper main
  thread.
- Paper asynchronous chunk loading is used before placement; completion is marshalled
  back to the main thread.
- Plain file-byte I/O may move off-thread only when it does not access a Bukkit/Paper
  object. Decoding/using `Structure` remains on the main thread unless Paper explicitly
  documents otherwise.
- Pending operations carry tokens. A late callback checks that its token is still current
  before changing any state.

The main thread remains the mutex for domain state. Concurrent collections and locks are
not introduced unless a future provider genuinely completes into shared state off-thread.

## Implementation sequence and commit checkpoints

1. **Template data model**
   - Relative position value objects and template definition.
   - Strict serializers and backwards-compatible tests.
   - Arena validation/status methods.

2. **Paper structure capture**
   - Provider boundary and Paper implementation.
   - Edit-session structure corners, capture tool, validation, atomic files.
   - Admin info/capture/clear commands and menus.

3. **Persistent dynamic layout**
   - Void generator and world ownership checks.
   - Immutable layout file, deterministic slot math, reservations.
   - Unit tests proving slot non-overlap and reconstruction.

4. **Provisioned instance model**
   - Manual/provisioned origin and dynamic state metadata.
   - Serializer migration defaults and admin diagnostics.
   - Recovery state transitions.

5. **Provisioning pipeline**
   - Chunk preparation/tickets, structure placement, coordinate materialization.
   - Persist-before-mutate ordering, cancellation, timeout, retirement.
   - Tests with a fake structure provider.

6. **Asynchronous allocation and match start**
   - Result types, pending-player ownership, revalidation.
   - Existing-instance-first policy and dynamic fallback.
   - Migrate challenge acceptance without weakening challenge retention.

7. **Player selection**
   - Selection stored on challenges.
   - Additive commands, menu, messages, and selected-arena acceptance.

8. **Reset/restart integration**
   - Dirty/ready transitions, startup repaste, disable cancellation.
   - Spectator, disconnect, death, and reset verification.

9. **Product hardening**
   - Permissions, diagnostics, configuration defaults, migration warnings.
   - Performance measurements and maximum-size tuning.
   - Full automated and manual test pass.

Each numbered increment is a candidate focused commit after its tests pass. Do not combine
all of Phase 4B into one commit.

## Testing plan

### Pure unit tests

- Relative-to-absolute coordinate conversion, including yaw/pitch.
- Capture cuboid validation and structure-size limits.
- Slot index to origin mapping and no overlap.
- Layout reconstruction and duplicate-slot detection.
- Serializer defaults for every old static format.
- Dynamic state transition validity.

### MockBukkit/service tests

- Specific selection never falls back to another template.
- Static mode never calls the provisioner.
- Dynamic mode prefers a free existing instance.
- Dynamic mode provisions only when the pool is exhausted.
- Two requests reserve different slots.
- Player disconnect/cancellation clears pending ownership.
- Failed provisioning never creates a match or mutates player state.
- Challenge acceptance is claimed during preparation and retained appropriately on
  failure.
- Template replacement/clearing protections.

MockBukkit is not expected to prove Paper structure placement. The provider boundary
allows these tests to use a deterministic fake.

### Real Paper tests

- Capture and place blocks/block entities on the project's target Paper version.
- Validate large-but-supported arena paste time and tick impact.
- Restart from each dynamic state (`PROVISIONING`, `DIRTY`, `RETIRING`, `FAILED`).
- Server crash simulation during a match followed by full repaste.
- Simultaneous matches in manual and provisioned instances.
- Spectator confinement and cleanup in a remote grid slot.
- World folder missing/renamed and layout UUID mismatch.
- Template recapture and dynamic pool retirement.
- Static-only install confirms no dynamic world/files are created beyond normal config.

### Later optional-provider tests

- WorldEdit/FAWE absent: Paper templates continue normally.
- WorldEdit present: `.schem` import/provider works.
- WorldEdit template configured but dependency removed: template becomes unavailable with
  a diagnostic; manual instances remain usable.

## Definition of done

Phase 4B is complete when:

- Existing static arenas require no migration work and behave as before.
- An arena can opt into dynamic mode only with a valid captured template.
- Any or specific arena selection is part of the challenge flow.
- A free existing instance is always reused before provisioning.
- A new provisioned instance is created without overlapping another slot.
- Players are not mutated until preparation succeeds.
- Reset completes before reuse, and crash-dirty dynamic instances are rebuilt.
- Startup reconstructs slots and handles incomplete operations safely.
- Static-only installations do not create/load the dynamic world.
- Commands and menus expose equivalent administration workflows.
- Automated tests pass and the complete flow is manually verified on Paper.
- Paper Structure API remains the dependency-free baseline.
- Optional WorldEdit/FAWE support is clearly documented as a compatible future extension,
  not a requirement for release.

## JCore versus Duels

All new domain concepts remain in Duels: templates, slots, arena worlds, allocation,
provisioning, selections, and recovery states. JCore supplies existing generic services
such as YAML files, serializers, tasks, messages, commands, and menus.

Nothing moves into JCore during Phase 4B. If a second minigame later needs the same
structure-provider or slot-layout abstraction, the two real implementations can be
compared before extracting reusable infrastructure.

## Concepts this phase teaches

- Configuration state versus physical runtime state.
- Relative and absolute coordinate systems.
- Resource reservation and two-phase publication (“persist/prepare, then make ready”).
- Asynchronous orchestration while keeping Bukkit mutation on the main thread.
- Idempotent crash recovery and quarantining ambiguous state.
- Capacity limits and deterministic spatial allocation.
- Optional integrations and dependency inversion for a concrete reason.
- Why a future completion must revalidate state rather than trusting the request that
  originally started it.

## Reconsideration triggers

- Add WorldEdit/FAWE when `.schem` demand, biome capture, or measured paste performance
  justifies it.
- Add rotation/mirroring when administrators need multiple orientations from one source.
- Add template entities only after ownership and cleanup rules are designed.
- Add multiple dynamic worlds only if one Paper backend genuinely exhausts a safe slot
  layout; network scaling should normally add backends instead.
- Move any abstraction to JCore only after another real plugin needs it.
