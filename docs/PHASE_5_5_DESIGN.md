# Phase 5.5 Design: Kit Effects, Clickable UX, and Rematches

_Status: product behaviour and implementation plan agreed on 2026-09-27.
Implementation has not started._

## Purpose

Phase 5.5 turns the completed standalone foundation into a more expressive and
discoverable player experience before rewards, queues, ratings, and networking add
more ways to enter and finish matches.

It adds three related but independently deliverable features:

1. permanent match-owned potion effects and debuffs on kits;
2. configurable Adventure-based clickable player interactions; and
3. mutual-consent rematches using the previous arena template and a fresh kit
   selection.

The phase does not change arena allocation, the match state machine, result semantics,
statistics, or player-state persistence. Each new path must enter the existing validated
challenge/match lifecycle rather than creating a parallel one.

## Agreed product decisions

### Kit effects

- A kit effect applies only to the player holding that kit. Kits do not directly apply
  effects to the opponent.
- Positive and negative effects use the same model. A debuff is simply a harmful effect
  such as Weakness, Slowness, Poison, or Wither configured on the holder's kit.
- Kit effects are permanent match baselines. They are applied with the selected kit at
  the end of kit selection, so they are present during the grace period and combat.
- A stronger temporary effect of the same type may replace the kit baseline. When it
  expires, the kit baseline returns.
- Milk and other removal mechanics remove ordinary effects normally. Any required kit
  baseline returns on the following server tick.
- A weaker or equal same-type effect does not downgrade a stronger kit baseline. This
  follows normal Minecraft effect precedence.
- Different effect types remain independent and behave normally.
- Kit effects have no configured duration. Their lifetime is the applied kit's lifetime
  in that match. Player-created potions/items continue to define their own durations.
- Instant effects are not valid kit baselines because an instant action cannot be
  meaningfully maintained for the duration of a match.
- The normal administration path is a GUI. A complete command fallback remains
  available for precise values and automation.

### Clickable interactions

- Every player-visible base line, action label, separator, colour, and hover description
  is configurable in `messages.yml`.
- The command attached to an action is owned by code, not editable display
  configuration. This prevents a formatting edit from changing security-sensitive
  behaviour.
- Clicks run the same permission-aware command/application path as typed commands.
  Clicking never bypasses current-state validation.
- Phase 5.5 adds clicks only where the underlying action exists now: challenge
  Accept/Deny, relevant help/discovery actions, post-match View Stats, and Rematch.
  Queue actions are added in Phase 7 rather than represented by dead buttons now.
- Paper's native Adventure `Component`, `ClickEvent`, and `HoverEvent` APIs are used.
  No raw JSON, packets, or NMS are required.

### Rematches

- Either participant may request a rematch after a completed match. The other must
  explicitly accept it.
- A rematch preserves the previous arena **template ID**, not the physical
  `ArenaInstance`. A clean copy is allocated normally when the rematch is accepted.
- Kit selection starts again. Previous selected/applied kits are not forced or
  preselected.
- A rematch never reserves an arena while the players decide.
- The rematch window has its own `rematch-expiry-seconds` setting, defaulting to 30.
  A value of `0` disables rematches. Positive values are the number of seconds after
  match completion in which the rematch may be requested and accepted.
- If the previous arena is disabled, deleted, invalid, or unavailable when accepted,
  the rematch does not silently change arenas. Players receive a rematch-specific
  explanation. A temporary capacity failure may be retried while the original rematch
  window remains open.
- Rematch eligibility and requests are runtime-only. They do not survive restart.

## Current behaviour and integration points

The relevant existing flow is:

```text
challenge accepted
  -> MatchManager reserves both players
  -> ArenaAllocator prepares/claims a physical ArenaInstance
  -> PlayerStateManager saves both players
  -> Match snapshots every available Kit
  -> players enter PREGAME and select a kit
  -> MatchManager applies the selected/default kit
  -> optional GRACE
  -> IN_PROGRESS
  -> MatchResult is created and sent to StatsManager
  -> players are restored
  -> arena reset completes
  -> allocator releases the instance
```

The new work attaches to this flow at narrow points:

```text
Kit snapshot creation
  -> include immutable effect definitions

Kit application
  -> apply inventory/equipment
  -> record exact applied kit snapshot on Match
  -> apply required effect baselines

Potion-effect change event
  -> ask the participant's Match for the applied snapshot
  -> retain a stronger temporary effect, or restore the kit baseline next tick

Match completion
  -> capture runtime rematch context from the completed Match
  -> restore players normally
  -> show configured result/actions if both remain eligible

Rematch accepted
  -> request ArenaSelection.specific(previousArenaTemplateId)
  -> use the normal asynchronous MatchManager start boundary
```

## Part A: Kit effect data model

### `KitEffect`

Add an immutable Duels value object or record with:

```text
effect key      namespaced registry key, e.g. minecraft:speed
level           user-facing level, 1..255
ambient         whether the effect has ambient appearance
particles       whether particles are shown
icon            whether the inventory HUD icon is shown
```

Persist a user-facing `level`, not Bukkit's zero-based amplifier. Convert at the Paper
boundary (`amplifier = level - 1`). This makes hand-edited YAML and command output match
what an administrator sees as Strength I/II.

The namespaced key is the durable identity. Enum names or numeric IDs are not persisted,
because registry-backed Minecraft identities are the supported modern representation.

### `Kit` ownership and copying

`Kit` gains an effect collection with these invariants:

- at most one baseline per effect type;
- the collection exposed to callers cannot mutate the kit accidentally;
- `Kit.copy()` copies the collection so an active `Match` remains isolated from live
  kit edits; and
- equality/lookup uses the effect key, not object identity.

The exact applied `Kit` snapshot should be recorded on `Match`, rather than copying its
effects into a second manager-owned map. `Match` may continue exposing the applied kit
ID for statistics while internally retaining the snapshot required by effect handling.

This keeps one owner for match configuration and prevents effect state from leaking
after `MatchManager` forgets a participant.

### YAML shape

A kit with Speed II and Weakness I should resemble:

```yaml
kits:
  4:
    name: Scout
    effects:
      - type: minecraft:speed
        level: 2
        ambient: false
        particles: true
        icon: true
      - type: minecraft:weakness
        level: 1
        ambient: false
        particles: true
        icon: true
```

Existing kits without `effects` load with an empty list. No migration or file rewrite is
required merely to load an old kit.

Deserialization is strict:

- the key must resolve in the target Paper effect registry;
- the effect must not be instant;
- level must be within 1..255;
- booleans must be actual booleans; and
- duplicate types are rejected.

A corrupt effect definition must identify the kit and field in the startup warning. It
must not silently create a partly different kit. The existing repository policy for an
invalid saved entry remains the outer failure boundary.

## Part B: Kit-effect administration

### GUI flow

`KitDetailMenu` gains an Effects item showing the configured count. It opens a
paginated `KitEffectsMenu` built from the target server's live effect registry and
sorted by display/key for deterministic pages.

Recommended entry controls:

- **Left click:** add the effect at level I, or remove it if already configured.
- **Right click:** increment the configured level through I-V, wrapping to I.
- **Shift-right click:** open an effect detail screen for precise settings.

The detail screen provides:

- increase/decrease level;
- exact level input (1..255) through the existing chat-input system;
- ambient toggle;
- particles toggle;
- HUD icon toggle; and
- remove effect.

Level cycling stops at V because that is convenient for normal administration. Exact
input preserves full customisation without requiring up to 255 clicks.

All titles, item materials, names, lore, state text, and instructions live in
`menus.yml`. Registry-derived effect names may use Minecraft translatable components
where practical; a readable key is the fallback.

Menu callbacks re-resolve the kit by ID before every mutation, matching existing stale
menu protection. Saving goes through `KitManager`; an active match keeps its old
snapshot.

### Command fallback

Add an admin command family under the existing kit edit permission boundary:

```text
/duels kit effect list <kitId>
/duels kit effect add <kitId> <effectKey> [level]
/duels kit effect remove <kitId> <effectKey>
/duels kit effect level <kitId> <effectKey> <1-255>
/duels kit effect ambient <kitId> <effectKey> <true|false>
/duels kit effect particles <kitId> <effectKey> <true|false>
/duels kit effect icon <kitId> <effectKey> <true|false>
```

`effectKey` accepts a canonical namespaced key and may accept the `minecraft:` omission
as a convenience, but persistence always writes the canonical key. All commands and GUI
controls call the same `KitManager` mutation methods and return the same validation
results.

Using `duels.admin.kit.edit` for both item and effect editing keeps the permission model
cohesive: both operations change the playable contents of a kit. No separate permission
is needed unless real server-owner feedback later demonstrates a need to delegate them
independently.

## Part C: Runtime kit-effect lifecycle

### Application

When `MatchManager` resolves the selected/default kit:

1. apply its inventory, armour, and offhand as today;
2. record the exact applied `Kit` snapshot on `Match`;
3. apply each non-instant baseline with effectively infinite duration and the configured
   appearance flags; and
4. retain the existing applied kit ID view for `MatchResult` and statistics.

Effects are applied at the same point as equipment: after the second player cleanup at
the end of `PREGAME`, before `GRACE` or immediate combat.

### Maintaining the baseline

Add a Duels-owned potion-effect listener. It contains no independent authoritative map.
For a player-effect change it asks:

```text
is this player in a live Match?
  -> what exact Kit snapshot was applied to them?
  -> does that kit require a baseline of this effect type?
```

The listener's policy is:

- if the new active effect is stronger than the baseline, leave it untouched;
- if the required baseline remains active at equal or greater strength, do nothing;
- if the effect is removed, expires, or becomes weaker, schedule one recheck for the
  next server tick;
- on that recheck, confirm the same player is still in the same live match with the
  same required baseline before applying it; and
- never interact with a player or `Match` from an asynchronous thread.

The delayed recheck avoids mutating an effect collection inside its own change event,
deduplicates mass-removal events such as milk, and revalidates against matches that may
have ended during the tick.

Temporary effects of the same type are not tracked in a parallel custom stack. The
listener observes the current Paper state and restores only the match-owned minimum.
This is enough to produce the agreed stronger-temporary-then-baseline behaviour without
reimplementing Minecraft's potion engine.

### Cleanup and restoration

No new persisted player-state format is required. Existing `PlayerState` already saves
active effects before the duel and, on restore, clears current duel effects before
reapplying the captured collection.

Ordering matters at match end:

1. remove/forget the participant from `MatchManager`;
2. restore `PlayerState`;
3. any resulting potion-effect events now see no live match and cannot reapply a kit
   baseline.

The current `restoreParticipant` order already establishes this property. Tests must
lock it in so a future refactor cannot leave a permanent kit effect after a duel.

Plugin disable uses the same rule. A full server stop keeps durable player snapshots for
join restoration; there is no live match after restart, so no kit baseline is restored.

## Part D: Configurable clickable messages

### Why this stays in Duels

JCore currently loads configurable legacy-colour strings and performs placeholder
substitution. It does not own a general vocabulary for challenge, rematch, or stats
actions. Phase 5.5 therefore adds a small Duels-owned renderer rather than expanding
JCore around one consumer.

Reconsider a JCore component-message facility only after the second-minigame exercise
shows another plugin needs the same safe component-template mechanism.

### Template model

Keep the existing `&` colour convention so this phase does not force administrators to
migrate every message to MiniMessage. Interactive messages use reserved component
placeholders inside configured text, for example:

```yaml
duel:
  challenge-received: '&e{player} &7challenged you in &e{arena}&7! {accept} &8| {deny}'
  actions:
    accept:
      text: '&a&l[ACCEPT]'
      hover: '&7Accept {player}''s challenge'
    deny:
      text: '&c&l[DENY]'
      hover: '&7Decline {player}''s challenge'
```

The Duels renderer:

1. substitutes ordinary text placeholders safely;
2. deserializes ordinary legacy-colour segments into Components;
3. replaces reserved action placeholders with configured label/hover Components; and
4. attaches the code-owned click event.

All visible output is configurable. Action commands and permissions are not treated as
presentation configuration.

Unknown or omitted action placeholders do not crash message delivery. The base message
still renders, and missing configured action text produces a clear startup/runtime
warning consistent with the existing message system.

### Initial action surfaces

Phase 5.5 should cover:

- incoming challenge: Accept and Deny;
- `/duel` help: suggest a challenge command and run/open Spectate, Stats, and
  Leaderboard actions where useful;
- match result: Request Rematch, View Your Stats, and optionally View Opponent Stats;
- incoming rematch: Accept and Deny; and
- sent/expired/failed rematch feedback with no dead click actions.

The challenge/rematch click command includes the other player's current name so the
same pair-specific command handler is used. Minecraft player names cannot contain
spaces or command separators, but the handler must still resolve and validate the
current online player/request rather than trusting the displayed string.

Stale clicks are expected input, not exceptional failures. They return the configured
"no pending request", "expired", "player unavailable", or equivalent message.

## Part E: Rematch ownership and lifecycle

### Runtime context

Add a small Duels-owned `RematchManager` and immutable `RematchContext`.

A context contains only:

```text
context UUID/token
player 1 UUID and last-known name
player 2 UUID and last-known name
previous arena template ID
expiry Instant
```

It does not retain `Player`, `Match`, `ArenaInstance`, `Kit`, inventory, or world
references. Each player has at most one current context: their most recently completed
eligible match.

Contexts are registered from the completed `Match` after a real result is produced.
Server-stop aborts have no winner/result and create no rematch context. A disconnect
result normally creates no usable prompt because both players are not online; the quit
cleanup invalidates the context.

### Relationship to challenges

A rematch is a specialised way to create a challenge with fixed arena terms. Do not
duplicate challenge claiming or asynchronous match preparation.

Extend the challenge boundary with a small origin/kind value (`DIRECT` or `REMATCH`) and
an explicit expiry supplied for rematches. `RematchManager` owns eligibility and the
post-match window; `ChallengeManager` continues owning pending pair invitations,
expiry tasks, pair uniqueness, claims, acceptance, and removal.

This division avoids both extremes:

- `ChallengeManager` does not need to understand what makes two players eligible for a
  rematch; and
- `RematchManager` does not copy the existing safe claim/allocation workflow.

### Player flow

After a completed match, both restored online participants receive configured result
actions. Either can run/click:

```text
/duel rematch [player]
```

With no player argument, the command targets the caller's current rematch context. With
an argument, it verifies that player is the opponent in that same context.

The first request creates a `REMATCH` challenge whose selection is
`ArenaSelection.specific(previousArenaId)`. If the opponent has already created the
reverse rematch request, the same action accepts it, mirroring the convenient existing
`/duel <player>` request-or-accept behaviour.

Explicit command fallbacks remain available:

```text
/duel rematch [player]
/duel accept [player]
/duel deny [player]
```

The normal Accept/Deny boundary can act on both direct and rematch challenges. Messages
branch on challenge kind so players are never told a rematch was a new arbitrary duel.

### Invalidation rules

The context and any related rematch challenge are invalidated when:

- the configured window expires;
- either player disconnects;
- either player commits to a new match;
- either player starts or accepts an unrelated direct challenge;
- either player joins a queue once Phase 7 exists;
- a newer completed match replaces the context; or
- the plugin disables.

Creating an ordinary challenge after a match is a deliberate choice to negotiate new
terms, so it invalidates the old rematch opportunity for the involved player.

All removals cancel their scheduled tasks. Diagnostics expose current rematch context
and pending-rematch counts so a completed/expired flow can be checked for leaks.

### Acceptance and failures

Acceptance revalidates on the main thread:

- both players are online;
- neither is active or pending in another match;
- the rematch context and challenge are still current and unexpired;
- the previous arena template still exists and is enabled; and
- its current configuration can be requested.

Then it uses `MatchManager.startMatchAsync` with the specific arena selection. Players
remain untouched until allocation succeeds, exactly as for a direct challenge.

Failure policy:

| Failure | Behaviour |
| --- | --- |
| Previous arena deleted/disabled/invalid | Remove the rematch request and explain that the previous arena is no longer available. |
| Dynamic capacity/full static copies | Keep the claimed request only by releasing its claim; allow retry while the original rematch expiry remains. |
| Player disconnects during preparation | Cancel/invalidate the rematch and release prepared resources through the existing match-start path. |
| Expiry occurs while allocation is claimed | Let allocation finish/fail; a failed allocation releases the claim and then expires rather than reviving a stale request. |
| Duplicate/stale click | Return configured no-pending/expired feedback; never start a second match. |
| Plugin disable | Cancel context/expiry tasks; active match shutdown remains owned by `MatchManager`. |

## Threading and concurrency

All kit, challenge, rematch, match, GUI, player, registry, and potion-effect state is
read or changed on Paper's main thread. The main thread remains the mutex.

No concurrent map or explicit lock is needed for these domain managers. A future callback
must return through `TaskManager.runSync` before touching them. Scheduled next-tick
effect restoration and expiry tasks revalidate identity/state because time has passed,
even though they run on the same thread.

## Files and components expected to change

Names are working names; responsibility matters more than exact spelling.

### New Duels classes

- `kit/KitEffect`
- `listener/KitEffectListener` (or a focused `KitEffectController` plus listener)
- `menu/admin/kit/KitEffectsMenu`
- `menu/admin/kit/KitEffectDetailMenu`
- `message/InteractiveMessageRenderer`
- `rematch/RematchContext`
- `rematch/RematchManager`
- focused result enums/records for kit-effect mutation and rematch requests where
  callers need more than success/failure

### Existing Duels classes/resources

- `Kit`, `KitSerializer`, `KitManager`
- `Match`, `MatchManager`
- `Challenge`, `ChallengeManager`, `ChallengeExpiryHandler`
- `DuelCommand`, `DuelsCommand`, `Duels`, `DuelsSettings`, `DuelsDiagnostics`
- `KitDetailMenu`, `KitViewMenu` and kit-selection lore where effects should be visible
- `Message`, `messages.yml`, `menus.yml`, `config.yml`, `plugin.yml`
- automated tests and public/admin documentation

### JCore

No JCore production change is planned for Phase 5.5. Its existing menus, input,
messages, tasks, commands, serializers, and `PlayerState` are sufficient.

If implementation reveals a genuinely generic missing primitive, stop and review the
problem before adding it to JCore. Do not move Duels interaction vocabulary or rematch
state into the framework.

## Implementation sequence and commit checkpoints

### Slice 1 - Effect model and persistence

1. Add `KitEffect` and validation.
2. Add effect storage/mutation to `Kit`/`KitManager`.
3. Extend `Kit.copy()` and `KitSerializer` with backwards-compatible loading.
4. Add serializer, copy, invalid-data, duplicate, and old-kit tests.

**Checkpoint:** effects round-trip and snapshot correctly; gameplay/UI unchanged.

### Slice 2 - Effect administration

1. Add shared kit-effect mutation results used by GUI and commands.
2. Add commands and permissions/help text.
3. Add registry-driven effects list and detail menus.
4. Add configurable menu entries and messages.
5. Expose effects in kit preview so players can understand a kit before selecting it.

**Checkpoint:** an admin can completely manage effects through GUI or commands and
restart without data loss.

### Slice 3 - Runtime effect lifecycle

1. Record the applied `Kit` snapshot on `Match`.
2. Apply baselines with the kit before grace/combat.
3. Add the effect-change listener and next-tick revalidation.
4. Register lifecycle cleanup and diagnostics if any scheduled rechecks are retained.
5. Add unit/service tests for stronger temporary effects, milk/removal, expiry, match
   end, disconnect, shutdown, and live-kit edits.

**Checkpoint:** effects behave correctly through every match exit and player state is
restored exactly.

### Slice 4 - Interactive message rendering

1. Add Duels-owned safe component-template rendering.
2. Add fully configurable action labels and hover text.
3. Convert challenge receive/help/current stats discovery surfaces.
4. Verify click paths call existing commands and stale actions fail cleanly.

**Checkpoint:** existing challenge/stat/spectate flows are discoverable without changing
their service semantics.

### Slice 5 - Rematch contexts and invitations

1. Add settings, context lifecycle, expiry, and diagnostics.
2. Extend challenges with origin/explicit expiry without changing direct-challenge
   behaviour.
3. Register eligible contexts from completed matches.
4. Add `/duel rematch`, request-or-accept behaviour, messages, and click actions.
5. Route acceptance through normal specific-arena asynchronous allocation.
6. Add invalidation and failure-path tests.

**Checkpoint:** consecutive rematches work without reserving instances or duplicating
match-start logic.

### Slice 6 - Product hardening and sign-off

1. Run all JCore and Duels automated tests.
2. Write `docs/PHASE_5_5_TEST_PLAN.md` against the finished command/menu wording and
   exact clicks.
3. Run it on Paper 1.21.11 with at least two clients and an admin account.
4. Use `/duels diagnostics baseline/compare` around effect/rematch flows.
5. Update README, command help, Phase 5.5 status, session context, and release checks.

**Checkpoint:** Phase 5.5 is marked complete only after the live suite passes.

Every slice is a candidate focused commit after its tests pass. Do not combine the
whole phase into one implementation commit.

## Automated testing plan

### Pure model and serializer tests

- Existing kit YAML without effects loads unchanged.
- Every appearance flag and levels 1, 2, 5, and 255 round-trip.
- Unknown keys, instant effects, level 0/256, wrong types, and duplicates fail with a
  useful error.
- `Kit.copy()` cannot be changed through the source effect list or vice versa.
- Match snapshots retain old effects after live kit edit/delete.

### Kit administration tests

- GUI and command mutations produce equivalent saved `Kit` state.
- Stale/deleted-kit actions fail without recreating or mutating anything.
- Registry keys canonicalise consistently.
- Right-click cycling and exact level input enforce their respective ranges.
- Preview displays the match snapshot rather than the current live kit.

### Effect lifecycle tests

- Baselines are applied after kit selection and before grace/combat.
- Positive and negative effects both work.
- Removing a baseline restores it next tick while the match remains live.
- A stronger temporary same-type effect remains until expiration, then the baseline
  returns.
- A weaker same-type effect cannot downgrade the baseline.
- Milk removes temporary effects; required baselines return; unrelated removed effects
  do not return.
- Normal win, environmental death, boundary forfeit, pre-combat/combat disconnect,
  plugin disable, and join recovery leave no kit effect and restore captured effects.
- A scheduled recheck after match end does nothing.

### Interactive message tests

- Configured base, label, hover, separator, colour, and placeholders appear correctly.
- Accept/Deny clicks execute the same handlers as typed commands.
- Missing optional action placement leaves usable base text.
- Missing required message configuration warns without throwing into event/command
  handling.
- Stale, expired, wrong-player, offline-player, and already-in-match clicks cannot
  mutate state.

### Rematch tests

- Either participant can request; the other must accept.
- Reverse simultaneous requests become one accepted request, never two matches.
- The previous template ID is used while a different physical instance may be
  allocated.
- Kit selection is fresh.
- Context expires, disconnects clean up, newer matches replace it, and disable cancels
  tasks.
- Disabled/deleted arena fails explicitly with no fallback.
- capacity failure leaves players untouched and can be retried within the window.
- Live kit edits affect the rematch's new snapshot but not the completed match.
- Duplicate completion or repeated clicks cannot create duplicate contexts/matches.
- Diagnostics return to baseline after success, denial, expiry, failure, and disconnect.

## Live acceptance suite shape

The final easy-to-follow Paper suite should be written after the UI exists so every
instruction matches the shipped button name and click. It will use this order:

1. build/install and baseline diagnostics;
2. create effects through GUI and verify command parity;
3. restart and verify persistence/preview;
4. normal buff/debuff match and snapshot isolation;
5. milk, stronger temporary, weaker temporary, particles/icon/ambient checks;
6. win, death, disconnect, shutdown/rejoin restoration;
7. challenge Accept/Deny/help/stats click actions and stale-click failures;
8. rematch request/deny/accept/expiry;
9. unavailable arena, capacity, kit edit/delete, and simultaneous click failures;
10. diagnostics comparison and console-log review.

The suite should state exact setup once, use checkboxes, distinguish expected warnings
from failures, and avoid retesting completed arena/statistics behaviour except where a
Phase 5.5 flow depends on it.

## Definition of done

Phase 5.5 is complete when:

- kits persist validated holder-only permanent effects/debuffs;
- effect management is fully available through configurable GUI and command fallbacks;
- active matches use immutable effect snapshots;
- stronger temporary effects, milk, expiry, cleanup, and original-state restoration
  follow the agreed semantics;
- challenge/help/result/rematch interactions use configurable Adventure components and
  the existing validated command/service paths;
- rematches require mutual consent, preserve the arena template, reopen kit selection,
  and never reserve the old physical instance while pending;
- every expiry, disconnect, stale-click, unavailable-arena, capacity, shutdown, and
  duplicate path cleans up deterministically;
- JCore remains unchanged unless a separately reviewed reusable need is proven;
- all automated suites pass; and
- the dedicated live Paper suite passes with diagnostics returning to baseline.

## Reconsideration triggers

- Move component-template support to JCore only after another plugin needs compatible
  configurable actions.
- Add finite kit-effect durations only if a real kit design needs a timed built-in
  effect distinct from an item/potion.
- Add opponent-targeted effects only as a separately designed gameplay mechanic; do not
  overload holder baselines.
- Add rematch persistence only if networks or restart-spanning player journeys create a
  real product need.
- Revisit same-type stacking only if Paper/Minecraft exposes a supported source-aware
  effect stack or real playtesting shows the stronger-temporary policy is confusing.
