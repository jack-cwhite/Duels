# Phase 6 Design: Reliable Match Outcomes and Configurable Rewards

_Status: design agreed 2026-09-28. Implementation not started._

## Purpose

Two related pieces of work:

1. **A single authoritative match outcome** that several independent systems consume,
   instead of each one reaching into `MatchManager.endMatch` and doing its own side
   effect inline.
2. **Configurable rewards** - Vault currency, experience, items and console commands -
   granted against that outcome, with durability and failure visibility appropriate to
   handing out real currency on someone else's server.

The second cannot be built honestly without the first, which is why they are one phase.

## Problem / Opportunity

A leaderboard row that fails to save is an annoyance. A player's balance that fails to
save, or saves twice, is a support ticket and potentially an economy exploit. Phase 5
was allowed to log a failure and move on; Phase 6 is not.

At the same time, Duels currently has no concept of "this match produced outcome X, and
here is the list of things that must happen because of it." It has one hard-coded side
effect. Phase 8's ratings will be the third such consumer, and the point at which three
side effects live inside one method is the point at which duplicate-completion bugs and
inconsistent failure handling become inevitable.

## Current Behaviour

`MatchManager.endMatch(Match, MatchConclusion)` (`match/MatchManager.java:248`) does all
of the following in sequence, on the main thread:

1. Guards against re-entry via `match.getState() == MatchState.ENDED`, then sets `ENDED`.
2. Cancels the countdown and ejects spectators.
3. Builds a `MatchResult` record from live `Match` state.
4. Calls `plugin.getStatsManager().recordMatch(result)` inside a `try/catch`, discarding
   the returned `CompletableFuture`.
5. Opens the rematch window, restores both participants, releases the arena.

What already exists and is good:

- **`MatchResult`** (`match/MatchResult.java`) is already an immutable record carrying
  arena, both players and names, winner, both applied kit ids, start/combat/end
  timestamps, end reason, ended state and damage cause. Phase 6 does not need to invent
  it - Phase 5 got this right.
- **`MatchConclusion`** (`match/MatchConclusion.java`) already separates "the facts the
  gameplay rule supplied" from "the record we persist."
- **`StatsManager.recordMatch`** returns a future and serialises later reads behind
  pending writes, so a profile opened immediately after a match cannot read stale data.
- **`SqlStatsRepository.recordMatch`** wraps its insert in `database().transaction(...)`
  on an async task.

What is missing, and is exactly what Phase 6 must add:

- **No stable result identity.** `MatchResult` has no id. The row's identity is the SQL
  auto-increment key, which does not exist until the insert succeeds and does not exist
  at all on the YAML backend. Nothing can say "reward 4471 belongs to result 9c2f..."
  and nothing can detect that a result is being processed twice.
- **No consumer boundary.** The stats call is hard-coded into `endMatch`.
- **No durability beyond a log line.** `SqlStatsRepository` logs
  `"Could not record match result - data lost unless recovered manually"` and rethrows.
  Honest, and acceptable for statistics. Not acceptable for currency.
- **No draw or abort outcome.** `MatchConclusion` requires a non-null winner, and
  `abortMatch` produces no result at all.

## Desired Behaviour

### How servers actually expect this to work

Every established duels/minigame plugin that pays out has broadly the same shape, and
admins will arrive expecting it:

- Rewards configured per outcome (win/loss), overridable per kit and per arena, with
  permission-based multipliers for donor ranks.
- Several reward *types* in one bundle, because servers hand out currency **and** a
  crate key from another plugin in the same breath.
- Rewards off until switched on. A plugin that starts injecting currency into an
  existing economy the moment it is installed is a plugin that gets uninstalled.
- Anti-farm protection, because the first thing that happens on a live server is two
  friends discovering that instant-forfeit duels print money.
- Something an admin can look at when a player says "I didn't get paid."

### Target behaviour

- Match completion produces one `MatchResult` carrying a **stable UUID**, dispatched to
  every registered consumer exactly once.
- Statistics becomes a consumer rather than a hard-coded call, and its insert becomes
  idempotent on that UUID.
- Rewards are fully configurable: currency, XP, items, console commands; per
  win/loss; overridable per kit and per arena; permission multipliers; anti-farm limits.
- Vault is an optional soft dependency. With no Vault installed, everything else in the
  plugin behaves exactly as it does today and currency rewards are skipped with one
  clear startup line.
- A reward that cannot be delivered now (player offline, inventory full) is **held as a
  claim**, not dropped.
- A crash mid-grant leaves a visible, resolvable record - never a silent double payment.
- An admin can preview what a match would pay without playing one.

## Why This Point In The Roadmap

- Phase 5 settled what a match result contains. Building the boundary now means moving
  one consumer onto it; building it after Phase 8 means moving three.
- Phase 7's queues and Phase 8's ratings both need "who won, how long did it take, on
  what kit" - the same facts the anti-farm rules need. They should all read one object.
- Phase 9's network work needs a result identity that is meaningful across JVMs. A
  UUID minted on the backend that owned the match is exactly that, and costs nothing
  now. _(Flagged as forward-looking: the UUID is justified by Phase 6's own idempotency
  need. Its usefulness to Phase 9 is a bonus, not the reason.)_

## Proposed Architecture

### The result boundary

```text
MatchManager.endMatch
    -> builds MatchResult (now with resultId: UUID)
    -> MatchResultDispatcher.dispatch(result)
         -> StatsResultConsumer        (Phase 5 behaviour, now idempotent)
         -> RewardResultConsumer       (Phase 6)
         -> RatingResultConsumer       (Phase 8, additive)
```

`MatchResultDispatcher` is deliberately small. Its only guarantees are:

1. Every registered consumer is offered each result exactly once per `resultId`, in
   registration order, on the main thread.
2. One consumer throwing cannot prevent another from running, and cannot prevent match
   cleanup. The existing `try/catch` around the stats call moves in here and becomes the
   rule for all consumers rather than a special case for one.
3. A duplicate `resultId` is dropped with a warning.

**What the dispatcher deliberately does not do: own durability.** Each consumer owns its
own, because the two consumers have genuinely different requirements:

- Statistics is one transactional insert. Keyed on `result_id` with a unique index, a
  replayed insert is naturally rejected. It needs no queue.
- Rewards call an external plugin that can succeed while our own bookkeeping fails. It
  needs a durable ledger.

Forcing both through one generic outbox would be inventing a shared mechanism for two
consumers that do not share a problem, which is the premature abstraction the project
rules warn about, and `V1_COMPLETION_PLAN.md` already states a generic durable-consumer
facility is a JCore candidate only after the second-minigame exercise. **Condition to
revisit:** if Phase 8's ratings consumer also needs a durable retry ledger, that is two
consumers with the same requirement and the mechanism should be extracted then.

### Result identity

Add `UUID resultId` as the first component of `MatchResult`, generated in `endMatch`
before any consumer runs.

Why a UUID rather than the database key: it exists before persistence is attempted, it
is identical on the SQL and YAML backends, it survives a failed write (so a retry can
reuse it), and it is printable in a log line an admin can search for.

Migration 2 adds `result_id` to `duels_matches` as `NOT NULL UNIQUE`, backfilling
existing rows with fresh UUIDs so an existing install migrates cleanly rather than
requiring the database to be wiped.

### Outcome model

`MatchEndReason` gains nothing. The reward side needs a coarser question than "how did
it end", so `RewardOutcome { WIN, LOSS }` is derived per player from
`result.winnerId()`. Deliberately not adding a draw: no gameplay rule in Duels produces
one, and inventing an unreachable state now would mean writing config and tests for
behaviour nobody can trigger. If timed queue matches in Phase 7 introduce draws, this
enum is where they land.

Aborted matches (`abortMatch`) pay nothing and produce no ledger row. Nobody won, and
the arena that just failed them is not an occasion for a payout.

### Reward model

```text
RewardTable      (immutable, loaded from rewards.yml)
  resolve(outcome, kitId, arenaId, player) -> RewardBundle

RewardBundle     (immutable, computed at result time)
  List<RewardGrant>

RewardGrant      sealed interface
  MoneyGrant(double amount)
  ExperienceGrant(int amount)
  ItemGrant(ItemStack item)
  CommandGrant(String command)
```

Resolution is a **sparse field merge** in ascending order of specificity, not a
whole-bundle replacement:

1. `defaults.<outcome>`
2. arena override for that arena id
3. kit override for that kit id
4. `overrides.combinations` entry naming **both** that arena and that kit
5. the single **highest** applicable permission multiplier

Each layer overrides only the fields it actually specifies. If an arena sets
`money: 200` and a kit sets `experience: 50`, the player gets both - they only compete
when they set the same field, and then the more specific layer wins.

That sparse merge matters more than it looks. A whole-bundle replacement would mean
configuring a kit's XP silently discarded the arena's money, which is the kind of
surprise an admin discovers weeks later from a complaint. The explicit
`overrides.combinations` layer then exists so "the Sword kit *on* the Sky arena pays
500" can be stated directly rather than being an emergent consequence of precedence
nobody wants to reason about.

Where arena and kit do set the same field, kit wins: a kit is the closer description of
what was actually played, and per-kit balance is the finer-grained knob. Any admin who
disagrees writes a `combinations` entry and gets exactly what they asked for.

Multipliers do not stack. A player holding both `donor` (1.5x) and `staff` (2x) gets
2x, not 3x. Stacking is the behaviour that produces an accidental 4x payout on a live
server, and "highest wins" is what admins expect from every plugin that does this.

### Grant tiers - what Duels can and cannot promise

This distinction is the honest core of the phase and must be documented for admins, not
just implemented.

| Type | Verifiable at call | Works offline | Covered by retry guarantee |
|---|---|---|---|
| Money | Yes - `EconomyResponse.transactionSuccess()` | Yes, Vault takes `OfflinePlayer` | Yes |
| Experience | Yes | No - needs a live `Player` | Yes, via claim |
| Item | Yes - `addItem` returns what would not fit | No | Yes, via claim |
| Command | **No** | Depends entirely on the command | **No - best effort** |

`Bukkit.dispatchCommand` returns whether the command existed and did not throw, not
whether the crate plugin actually gave anything. Duels cannot verify it, cannot reverse
it, and cannot know after a crash whether it ran. Therefore command grants are
explicitly outside the guarantee, are **never** auto-replayed during recovery, and say
so in `rewards.yml` comments and the README. Pretending otherwise would quietly hand out
duplicate crate keys.

### Ledger and its state machine

Two tables (plus YAML equivalents), owned by a `RewardRepository` mirroring the existing
`StatsRepository` split so the storage choice stays the one setting the admin already
made:

```text
duels_rewards         id, result_id, player_id, outcome, created_at,
                      status, resolved_at, resolved_by
duels_reward_grants   id, reward_id, type, payload, status,
                      attempted_at, completed_at, error
```

Per-grant status exists because a bundle genuinely splits: the money can land while the
item bounces off a full inventory. An admin needs to see that, not one opaque "failed".

```text
PENDING    intent is durable; nothing has been attempted
             -> safe to retry automatically, because nothing external happened
GRANTING   we are about to call out, or were when we died
             -> ambiguous after a crash; policy decides
COMPLETE   confirmed success
FAILED     confirmed failure (provider said no, inventory policy SKIP)
CLAIMABLE  cannot be delivered now; held for the player
AMBIGUOUS  crashed during GRANTING; awaiting an admin decision
```

The two-write cost before granting (`PENDING`, then `GRANTING`) is deliberate. It buys a
strictly smaller ambiguous window: a crash before the second write is *known* not to
have paid anything and is auto-recoverable. Two small async writes per match is nothing
next to getting currency wrong.

### Crash policy

The unavoidable constraint: no transaction can span Duels' database and a third-party
economy plugin. Vault's API has no transaction ids and no idempotency key, so after a
crash there is no question we can ask it to find out whether we already paid.

Intent-first ordering at least makes the ambiguity *recorded* rather than invisible.
Granting first and recording after would mean a crash leaves a paid player and no row at
all - undiagnosable, and re-paid by any retry.

On startup, `RewardRecovery` classifies every non-terminal row:

- `PENDING` -> re-grant. Nothing external was attempted.
- `GRANTING` -> apply `ambiguous-policy`, default **FLAG**: mark `AMBIGUOUS`, surface in
  `/duels diagnostics` and `/duels rewards pending`, wait for an admin.
- `CLAIMABLE` -> leave for the player to claim.

FLAG is the default because the two failures are not symmetrical. A missing reward is
bounded, visible and fixable in seconds by an admin. Duplicated currency is unbounded -
it inflates the economy, and if anyone works out how to reproduce the crash it is an
exploit. `RETRY` and `DISCARD` remain configurable for admins who would rather trade the
other way.

Command grants in a `GRANTING` row are marked `FAILED` with a note rather than retried,
per the tier table.

### Anti-farm

Evaluated when the bundle is resolved, before anything is written:

- **`minimum-duration-seconds`** (default 10) - a match shorter than this pays nothing.
  Kills the instant-forfeit loop. Read from `result.endedAt() - result.startedAt()`.
- **`repeat-opponent`** (default: 60-minute window, decay `100, 50, 25, 0`) - repeated
  wins against the same opponent decay rather than hitting a cliff. Two friends who
  genuinely enjoy duelling each other all evening still earn something; the exploit
  still dies.
- **`daily-cap`** (default 0 = off) - a per-player daily ceiling.

Ownership: duration and repeat-opponent are **runtime state** in the consumer, and reset
on restart. This is deliberate - it keeps a database query off the match-end path, and
the exploit it exposes requires the player to be able to restart the server. The daily
cap genuinely needs persistence, which is the main reason it is a separate opt-in
feature rather than a default.

Rejected: refusing rewards when both accounts share an IP address. It is the obvious
anti-alt heuristic and it punishes siblings, shared houses and LAN cafés - real players
blocked for a real-sounding reason.

Defaults are **active**, not off. A plugin aiming to be the default choice should not
ship trivially farmable, because that becomes its reputation in the first week. Every
value is overridable, and `0` disables each rule individually.

### Vault integration and the provider seam

Vault is fetched the conventional way, once, at enable:

```java
RegisteredServiceProvider<Economy> rsp =
        getServer().getServicesManager().getRegistration(Economy.class);
```

`softdepend: [Vault]` in `plugin.yml`, VaultAPI as a `provided` dependency via JitPack,
and nothing in the shaded jar. No Vault, or no economy provider registered, means
`MoneyGrant`s are skipped with one INFO line at startup and everything else works.

Behind that sits a one-method internal interface (`EconomyProvider.deposit(OfflinePlayer,
double) -> GrantOutcome`). This is a genuine seam rather than decoration: Vault's API is
ancient and double-based, VaultUnlocked and Treasury both exist as real alternatives
that real servers run, and the ledger must not know which one is installed. _(Flagged
as forward-looking: only the Vault implementation gets written in Phase 6. The interface
is justified now because it also keeps the economy call out of the ledger's tests, but
no second implementation is being built.)_

### Threading

| Step | Thread | Why |
|---|---|---|
| Build result, dispatch | Main | Reads live `Match` state |
| Resolve bundle, anti-farm | Main | Pure computation on config and in-memory state |
| Write `PENDING`, `GRANTING` | Async | Database I/O never on the tick |
| Grant money / XP / item / command | **Main** | Bukkit inventory and XP are main-thread only, and we cannot know an arbitrary economy provider's thread safety |
| Write terminal status | Async | Database I/O |
| Player feedback | Main | Adventure send |

The hop back to the main thread for granting is the part worth being careful about.
JCore's `TaskManager` already provides `runAsyncFuture`, and `JCore.shutdown()`
deliberately drains queued database work before disconnecting the pool, so a match
ending during shutdown still gets its ledger write.

### Configuration

A new `rewards.yml`, not more keys in `config.yml`. The structure - per-outcome tables,
per-kit and per-arena overrides, multipliers, anti-farm - is too nested to live
comfortably alongside flat settings, and a dedicated file per subsystem is the existing
convention (`database.yml`, `messages.yml`, `menus.yml`).

```yaml
# Rewards are off until you switch them on. Duels will not touch your economy
# until this is true.
enabled: false

# FLAG      mark crash-ambiguous payments for review (recommended)
# RETRY     pay again - may double-pay after a crash
# DISCARD   drop them - players may silently lose rewards
ambiguous-policy: FLAG

inventory-full: CLAIM       # CLAIM | DROP | SKIP

anti-farm:
  minimum-duration-seconds: 10
  repeat-opponent:
    window-minutes: 60
    decay: [100, 50, 25, 0]
  daily-cap:
    money: 0                # 0 = unlimited

defaults:
  win:
    money: 100
    experience: 0
    items: []
    commands: []            # best effort - never retried after a crash
  loss:
    money: 25

overrides:
  arenas:
    3: { win: { money: 200 } }
  kits:
    sword: { win: { money: 150 } }

multipliers:                # highest applicable wins; they do not stack
  - permission: duels.reward.multiplier.donor
    factor: 1.5
```

Validation happens at load, in the style of `ActionMessenger.audit()`: unknown keys,
negative amounts, unparseable materials, malformed multipliers and an `enabled: true`
with no economy provider are all reported as specific, actionable startup lines. A bad
table never partially applies - the file is rejected and rewards stay off, because half
a reward table is worse than none.

`/duels reload` re-reads it, consistent with `messages.yml` and `config.yml`.

### Admin experience

Commands, all under `duels.admin.rewards`:

- `/duels rewards preview <win|loss> [kit] [arena] [player]` - the resolved bundle
  including multipliers and current anti-farm state. Lets an admin verify a reward table
  without arranging a duel.
- `/duels rewards pending` - non-terminal and ambiguous entries.
- `/duels rewards resolve <id> <grant|dismiss>` - the documented way out of an ambiguous
  entry.
- `/duels rewards history <player>` - what that player has actually been paid, for when
  they say they were not.
- `/duels rewards debug fail-after <intent|grant>` - forces the next reward to die in a
  chosen window, so both crash paths can be tested deliberately instead of by pulling
  the plug and hoping. Gated behind a config flag and off by default.

`/duels diagnostics` gains pending grants, ambiguous entries, unclaimed claims and the
detected economy provider's name, so all of it shows up in the existing
baseline/compare flow.

Player-facing: `/duel claim`, a join notification when something is waiting, and
feedback naming what was granted and why ("You won 150 coins - Sword kit bonus").

**A partial deviation from the GUI-first convention, with a GUI path.** Slice 6 adds a
**Rewards button to the existing kit and arena editors** that sets that resource's money
and experience amounts for win and loss, written back into `rewards.yml` as the
corresponding override. That covers the common case - "this arena pays more" - without
leaving the editors.

What stays file-only is the rest of the structure: item lists, command lists,
multipliers, combination overrides and anti-farm. Those are genuinely more legible and
diffable in YAML than in chest inventories, an admin configuring an economy is already
in their config files, and building a chest-inventory editor for a list of console
command strings would be worse to use than a text editor, not better.

The GUI must therefore treat the file as the source of truth and re-read before writing,
so an amount set in a menu cannot silently discard a hand-edited item list in the same
override block.

## JCore vs Duels

Everything in this phase is Duels. Rewards, Vault, the match result boundary and the
anti-farm rules are all duelling domain behaviour.

`MatchResultDispatcher` is the one plausible JCore candidate, and it stays in Duels
because a fan-out with two consumers is roughly twenty lines - the abstraction would be
larger than the thing it abstracts. The second-minigame exercise is the right place to
find out whether a durable result-consumer mechanism generalises, and by then we will
have three real consumers to design it against instead of guessing.

No JCore production change is planned. If one turns out to be needed - most likely a
helper on `AbstractDatabase` - it gets raised separately rather than smuggled in.

## Java / Paper Concepts Involved

- **Idempotency keys.** Why `resultId` makes a replayed insert safe, and why Vault
  having no equivalent is the whole reason `AMBIGUOUS` has to exist.
- **Delivery guarantees.** At-least-once (retry, risk duplicates), at-most-once (drop,
  risk loss), and why exactly-once across two systems that cannot share a transaction is
  not available at any price.
- **`CompletableFuture` composition and thread hops** - `thenRun` on an async future
  does not come back to the main thread by itself, and a Bukkit call from the wrong
  thread is a bug that usually appears as an intermittent exception under load.
- **`ServicesManager` and soft dependencies** - how a Paper plugin uses another plugin's
  API when present without requiring it.
- **`OfflinePlayer`** - what can and cannot be done for a player who is not on the
  server.
- **Sealed interfaces and records** for the grant types, giving an exhaustive `switch`
  that fails to compile when a fifth grant type is added and a handler is forgotten.
- **Enum state machines** - the ledger status transitions, and why the illegal ones
  should throw rather than be tolerated.

## Real Scenario

Alice beats Bob on arena 3 with the Sword kit, eight seconds after the match started.
She holds `duels.reward.multiplier.donor`. Bob's inventory is full. The server is
running EssentialsX Economy behind Vault.

1. `endMatch` builds a `MatchResult` with `resultId = 9c2f...`, then dispatches.
2. `StatsResultConsumer` inserts it keyed on that id.
3. `RewardResultConsumer` resolves Alice's bundle: defaults win 100 -> arena 3 override
   200 -> Sword kit override 150 -> donor 1.5x = **225**. Then anti-farm rejects the
   whole bundle, because the match lasted eight seconds and the minimum is ten. No
   ledger row is written for Alice, and she is told why.
4. Bob's loss bundle is 25 coins and one item. Money is deposited; `addItem` returns
   the item as leftover, so with `inventory-full: CLAIM` that grant becomes `CLAIMABLE`
   and Bob is told he has something waiting.
5. The server crashes while Bob's money grant is `GRANTING`. On restart, recovery marks
   it `AMBIGUOUS`. `/duels rewards pending` shows it; the admin checks Bob's balance,
   sees the 25 arrived, and runs `/duels rewards resolve <id> dismiss`. Bob's claimable
   item is untouched and still waiting.

That single scenario exercises the resolution order, anti-farm, a split bundle, the
claim path, the crash window and the admin resolution - which is why it becomes the
spine of the live test plan.

## Implementation Plan

Six slices, each its own commit after its tests pass.

**Slice 1 - Result identity and the dispatcher.** Add `resultId` to `MatchResult`;
migration 2 adds `result_id NOT NULL UNIQUE` with backfill; make the SQL and YAML
inserts idempotent on it; add `MatchResultDispatcher`; move the stats call behind
`StatsResultConsumer`. **No behaviour change** - Phase 5's existing tests are the gate
and must stay green.

**Slice 2 - Reward model and configuration.** `rewards.yml`, loading, validation,
`RewardTable`, `RewardBundle`, the grant types, resolution order, multipliers, and
`/duels rewards preview`. Nothing is granted or persisted yet, so the whole resolution
model can be tested and eyeballed in game before any money moves.

**Slice 3 - Vault, the ledger and granting.** `EconomyProvider`, the Vault
implementation, `RewardRepository` (SQL + YAML), migration 3, the state machine, the
grant pipeline with its thread hops, and player feedback.

**Slice 4 - Recovery, ambiguity and claims.** `RewardRecovery`, `ambiguous-policy`,
`/duels rewards pending|resolve|history`, `/duel claim`, join delivery, and the
`fail-after` debug command.

**Slice 5 - Anti-farm.** Minimum duration, repeat-opponent decay, optional daily cap,
and their diagnostics.

**Slice 6 - Hardening and sign-off.** Admin GUI screens, `/duels diagnostics`
additions, README and `docs/` updates, `docs/PHASE_6_TEST_PLAN.md` written against the
finished wording, then the live pass on Paper 1.21.11 with a real economy provider.

## Testing Plan

### Automated

- Dispatcher: every consumer receives each result; one consumer throwing does not stop
  the others or match cleanup; a duplicate `resultId` is dropped.
- Stats idempotency: inserting the same `resultId` twice leaves one row on both backends.
- Migration 2 backfill: pre-existing rows survive and gain unique ids.
- Resolution order across defaults, arena, kit and multipliers; multipliers do not stack.
- Anti-farm: below-minimum duration pays nothing; the decay curve steps correctly;
  window expiry resets it; daily cap blocks at the boundary.
- Ledger state machine: every legal transition, and illegal ones rejected.
- Recovery classification: `PENDING` re-granted, `GRANTING` flagged under each of the
  three policies, `CLAIMABLE` untouched, command grants never replayed.
- Vault absent, Vault present with no provider, and a provider returning failure.
- Inventory-full under `CLAIM`, `DROP` and `SKIP`.
- Offline player: money succeeds, XP and items become claims.
- Configuration validation: each malformed case produces its specific message and leaves
  rewards off.
- SQL/YAML reward parity, mirroring the Phase 5 parity tests.

### Live, on Paper 1.21.11 with Vault plus an economy provider

- The Alice/Bob scenario above, end to end.
- Both crash windows forced with `fail-after`, then resolved from
  `/duels rewards pending` - the check that this design's central claim is true.
- A disconnect loss paying an offline player, verified in their balance on return.
- `/duels reload` picking up an edited table mid-session.
- `/duels diagnostics baseline` before and `compare` after a batch of duels, returning
  to baseline with no leaked pending entries.
- Rewards enabled with Vault deliberately uninstalled, confirming the plugin still
  starts and duels still work.

## Definition of Done

- `MatchResult` carries a stable id and every consumer goes through the dispatcher.
- Statistics behaviour is unchanged and Phase 5's tests are still green.
- All four reward types work, configurable per outcome, kit and arena, with multipliers.
- A duplicate completion cannot pay twice; a crash cannot silently pay twice.
- Every non-terminal ledger entry is visible and resolvable by an admin.
- Nothing can be delivered to an offline or full-inventory player without being held.
- With Vault absent and with rewards disabled, Duels behaves exactly as it does today.
- `rewards.yml` ships off, documented, with its guarantees and the command-grant caveat
  stated in the file itself and the README.
- The live plan passes and is recorded in `docs/PHASE_6_TEST_PLAN.md`.
