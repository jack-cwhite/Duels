# JCore + Duels Development Instructions

## Current Handoff

For the compact, verified project state and the exact next V2 target, read
`docs/SESSION_CONTEXT.md` first. It records the completed V1 scope, known
intentional limitations, architecture invariants, verification results, and
the planned Phase 4B starting point. `docs/ROADMAP.md` remains the detailed
phase-by-phase source of truth.

## Read This First

This file contains persistent project context and instructions for working on my Minecraft Paper projects.

The primary project is **Duels**.

Duels depends on my separate library/framework project called **JCore**.

Both projects have completed working V1 implementations.

Do not approach either project as an unfinished prototype that needs to be rewritten from scratch.

The objective is to:

1. understand the existing V1 systems,
2. identify meaningful improvements,
3. create a dependency-aware V2 roadmap,
4. improve my understanding of Java, Paper and modern Minecraft development while doing so,
5. incrementally implement future features,
6. preserve a simple standalone-server deployment model while allowing Duels to eventually evolve into a serious multi-server minigame system.

---

# Project Locations

The current repository is the **Duels** project.

JCore is a separate Maven project/repository.

Unless stated otherwise, expect JCore to be available as a sibling project at:

`../JCore`

When reviewing JCore functionality, inspect its implementation AND how Duels actually consumes it.

Do not judge a JCore API purely in isolation.

---

# JCore

JCore is my personal Paper plugin development library/framework.

It exists to reduce duplicated infrastructure across my Minecraft plugins.

## Critical Architectural Constraint

**JCore is NOT installed on the Minecraft server as a separate plugin.**

JCore is bundled/shaded into plugins that use it.

For example:

```text
Duels.jar
├── Duels code
└── bundled JCore code
```

If several plugins use JCore, each plugin may contain and operate its own bundled JCore instance.

Do NOT design JCore under the assumption that there will be one globally installed JCore plugin providing shared services to every plugin.

This distinction is fundamental.

---

# Current JCore Systems

JCore currently includes systems covering areas such as:

* command management
* cooldowns
* countdowns
* database integration
* item parsing
* menu/GUI management
* message management
* serialization
* state machines
* storage and file management
* task management
* general utilities

Some systems are intentionally more sophisticated than would be required for a tiny plugin because JCore is intended to support future projects.

However:

**Do not add complexity merely because this is a framework.**

Always distinguish between:

* genuinely reusable infrastructure,
* useful abstraction,
* premature abstraction,
* overengineering,
* duplicated functionality,
* APIs that are difficult to use correctly,
* functionality that belongs in Duels rather than JCore.

---

# Duels V1

Duels is a Paper plugin built using JCore.

The current V1 is functional, tested and sufficiently complete that it could be shipped.

Existing functionality includes areas such as:

* arena creation and management
* challenge requests
* challenge management
* challenge expiration
* commands
* kits
* controlling which kits can be used in particular arenas
* match creation
* match lifecycle/management
* gameplay listeners
* player-state capture and restoration
* administrative menus
* player-facing menus
* administrative operations that can generally be performed through GUIs instead of requiring commands

Treat this as a working V1 being evolved into V2.

Do not perform a rewrite merely because another design is theoretically cleaner.

---

# Current Future Feature Ideas

These are currently planned or being considered:

* arena bounds
* arena instancing
* spectator mode
* dynamic arena provisioning (arenas built on demand rather than hand-placed)
* deeper statistics and tracking
* Vault integration and configurable rewards
* matchmaking
* potentially ELO/MMR/SBMM-style ratings
* eventual Velocity/BungeeCord/network support

These are ideas, NOT a prescribed implementation order.

Part of your job is to determine their architectural dependencies and recommend an appropriate order.

You may identify additional features or improvements I have not considered.

Do not assume every possible feature belongs in the plugin.

**Scope target:** "Duels complete" means release-ready for a standalone server - the
full single-server feature set finished, tested, and technically shippable, whether or
not it is actually published. Dynamic arena provisioning, Vault, matchmaking, ELO, and
network readiness are deliberately v2+ work toward Jack's longer-term goal of a custom
minigame network built on JCore - they are not required for Duels itself to be considered
complete. See `docs/ROADMAP.md`'s scope note for the current dependency order. Until a
second minigame plugin actually exists and needs it, JCore stays Duels-driven rather than
being generalized for hypothetical multi-game reuse ahead of need.

---

# Core Engineering Philosophy

The desired codebase should be:

* robust
* understandable
* maintainable
* reasonably extensible
* difficult to misuse
* practical
* appropriate for real Minecraft servers
* safe around concurrency
* correct around Paper/Bukkit threading rules
* easy to debug
* easy for me to continue developing myself

Avoid both underengineering and overengineering.

## Examples of underengineering

Avoid things such as:

* giant classes with unrelated responsibilities
* hidden shared mutable state
* unsafe asynchronous Bukkit/Paper calls
* poorly defined lifecycle ownership
* fragile persistence
* race conditions
* silent failures
* lack of validation
* inconsistent state after partial failures

## Examples of overengineering

Avoid things such as:

* interfaces with no meaningful alternative implementation or boundary
* excessive factory/service/repository layers
* patterns introduced merely because they are considered "good architecture"
* distributed infrastructure for hypothetical problems
* splitting straightforward behaviour across excessive numbers of classes
* rewrites whose only justification is "cleaner"
* generalising Duels-specific concepts into JCore prematurely

Prefer:

> The simplest architecture that gives us the properties we genuinely need.

---

# Problem Before Pattern

Never begin an architectural recommendation with a design pattern.

Do not say:

> We should use the Strategy Pattern.

Instead explain:

> MatchManager currently needs to know how every arena allocation mechanism works. If local allocation and future network allocation differ, this creates coupling. We need a boundary through which MatchManager can request an arena without knowing where it came from. An interface is one possible implementation.

The problem must justify the abstraction.

The abstraction must not justify itself.

---

# JCore vs Duels Responsibilities

Always ask whether functionality belongs in:

* JCore,
* Duels,
* an optional Duels integration,
* a future network/proxy module,
* or nowhere.

JCore should contain genuinely reusable plugin-development infrastructure.

Duels should generally contain domain concepts such as:

* Duel
* Challenge
* Match
* Arena
* ArenaInstance
* Kit
* Queue
* matchmaking rules
* ranking rules
* spectators

unless a genuinely reusable abstraction emerges.

Do not make JCore larger merely because something can theoretically be generalized.

---

# Understand Runtime Flows

Do not review classes only in isolation.

Trace important runtime flows.

Examples include:

```text
Challenge creation
-> challenge storage
-> expiration / acceptance
-> match creation
-> arena acquisition
-> player-state capture
-> match preparation
-> countdown
-> active gameplay
-> match result
-> player restoration
-> arena release
-> cleanup
```

Also examine flows such as:

```text
Admin opens menu
-> resource selected
-> edit performed
-> validation
-> runtime state update
-> persistence
-> menu refresh
```

Understand ownership and state transitions across the entire flow.

---

# Reliability Review

When reviewing code, actively investigate realistic failure scenarios.

Examples include:

* two players causing concurrent operations
* two administrators editing the same object
* modifying an arena while it is used by a match
* modifying a kit while several matches use it
* deleting something currently in use
* disconnecting during a challenge
* disconnecting during matchmaking
* disconnecting during a countdown
* disconnecting during a match
* reconnecting
* player death
* world unload
* plugin disable
* server shutdown
* server crash
* exception during a state transition
* partial player-state restoration
* database failure
* persistence failure
* delayed async result arriving after state has changed
* stale GUI/menu state
* duplicate completion
* duplicate cleanup
* memory/resource leaks

Prioritise realistic problems.

Do not fill the review with theoretical problems that are extremely unlikely to matter.

---

# Configuration State vs Runtime State

Pay particular attention to the difference between configured resources and runtime state.

For example, an Arena configuration might contain:

```text
id
name
spawn points
allowed kits
bounds
other configuration
```

An active match may require runtime information such as:

```text
configured arena identity
runtime world/instance
current players
spectators
runtime entities
timers
occupancy
temporary state
```

Do NOT assume this exact design is required.

Inspect the current implementation and determine whether a distinction like this would help.

Apply similar reasoning to Kits and other configurable objects.

---

# Administrative Editing and Active Usage

One important area is how editing interacts with active gameplay.

Consider scenarios such as:

* deleting an arena during a match
* moving an arena spawn during a match
* changing allowed kits during a match
* changing a kit while several active matches use it
* two admins editing an arena simultaneously
* two admins editing a kit simultaneously

Determine whether solutions should involve concepts such as:

* immutable runtime snapshots
* usage tracking
* resource locking
* edit sessions
* versioning
* optimistic concurrency
* preventing destructive actions
* delayed application of edits

Do not assume Arenas and Kits require identical solutions.

Explain the state-management/concurrency reasoning involved.

---

# Performance

Look for meaningful performance problems such as:

* synchronous database operations on the server thread
* unnecessary polling
* excessively frequent scheduled tasks
* expensive event handlers
* repeated persistence reads in hot paths
* poorly bounded caches/collections
* unnecessary world/entity work
* unnecessary menu reconstruction

Do not micro-optimise insignificant code.

---

# V2 Roadmap Requirement

After understanding V1, create a dependency-aware V2 roadmap.

Do NOT simply repeat my desired feature list.

The roadmap should explain which foundational changes are required before future functionality.

A roadmap might eventually include areas resembling:

```text
Foundation/reliability
Admin and player QoL
Arena architecture
Match improvements
Spectator functionality
Statistics/rewards
Matchmaking
Ranked/MMR
Network-readiness
Actual network implementation
Advanced/optional systems
```

This example is not a required ordering.

Derive the ordering from the existing architecture.

---

# Roadmap Item Requirements

For each substantial roadmap item explain:

## Problem / Opportunity

What are we solving?

## Current Behaviour

How does the current implementation work?

Reference relevant classes.

## Desired Behaviour

What should eventually happen?

How do you typical servers handle these type of operatrions.
We should be deisnging systems around how theyre expected to function.

## Why This Point In The Roadmap

What depends on this?

What future rework does it avoid?

## Proposed Architecture

Explain:

* who owns the state
* who is allowed to change it
* lifecycle
* failure handling
* persistence
* runtime-only state
* synchronous behaviour
* asynchronous behaviour

## JCore vs Duels

State where the functionality belongs and why.

## Java / Paper Concepts

Identify concepts required to understand the solution.

## Existing Duels Example

Relate the concept to code I already have whenever possible.

## Real Scenario

Use an actual Duels-style example rather than Foo/Bar examples.

## Implementation Plan

Break implementation into understandable steps.

## Testing Plan

Explain important success and failure scenarios.

## Definition Of Done

State what must be true before the roadmap item is complete.

---

# Development Method

For substantial new features, do NOT immediately write the finished implementation.

Use this general process:

```text
problem
-> existing behaviour
-> requirements
-> architectural options
-> trade-offs
-> proposed design
-> implementation steps
-> incremental implementation
-> testing
-> documentation
```

For small straightforward changes, this can be abbreviated.

Avoid turning trivial changes into architecture exercises.

---

# Refactoring Rule

A substantial refactor needs a concrete reason.

Good reasons include:

* fixing a realistic bug
* preventing race conditions
* clarifying lifecycle/ownership
* reducing duplication
* making an important future feature substantially easier
* establishing a meaningful boundary
* simplifying an API that is currently difficult to use correctly

"Cleaner" alone is not enough justification for a major rewrite.

---

# Developer Learning Goal

Improving the software is only one goal.

I also want to become a very strong modern Minecraft/Paper developer.

I developed Minecraft plugins around a decade ago but have been away from serious Minecraft development for a long time.

My general software-development knowledge is significantly stronger now. I am a Computer Science graduate with a software-development background.

However:

**Do NOT assume this means fundamentals should be skipped.**

I would rather hear an explanation of something I already know than accidentally build advanced knowledge on top of a missing fundamental.

Do not talk down to me.

Do explain fundamentals clearly.

If I demonstrate that I understand something, move through it more quickly.

---

# Teaching Method

When introducing an important concept, teach it approximately in this order:

## 1. Problem

What are we actually trying to solve?

## 2. Naive/simple approach

What might someone naturally try first?

## 3. Limitation

Why might that approach fail?

## 4. Relevant Java/Paper mechanism

Explain the tool or language concept.

## 5. Duels example

Relate it directly to this project.

## 6. Alternatives

If there are meaningful alternatives, explain them.

## 7. Reason for our choice

Explain the trade-off.

I care more about understanding **why** a mechanism exists than memorising syntax.

---

# Prefer Real Examples

Use examples involving concepts such as:

* Player
* Challenge
* Match
* Arena
* Kit
* Queue
* Spectator
* PlayerState
* MatchResult
* Rating
* ArenaInstance
* GameServer

instead of meaningless examples like Foo, Bar, Animal or Car.

For example, concurrency might be explained using:

```text
Player A joins matchmaking.
Player B joins shortly afterwards.

Two operations attempt to consume Player A's queue entry.

How do we ensure Player A cannot enter two matches?
```

Transactions might be explained through:

```text
Match finishes.

We need to update:
- match history
- wins/losses
- rating
- reward state

What happens if the third update fails?
```

---

# Developer Knowledge Roadmap

Alongside the engineering roadmap, maintain a second roadmap describing the knowledge needed to understand each stage.

Relevant concepts may include:

## Java

* references
* equality
* mutability/immutability
* collections
* generics
* interfaces
* abstract classes
* functional interfaces
* lambdas
* records
* exceptions
* object ownership
* concurrency
* ExecutorService
* CompletableFuture
* synchronization
* atomicity
* thread safety
* JVM concepts where relevant

## Software Design

* cohesion
* coupling
* responsibility
* dependency direction
* lifecycle
* invariants
* composition
* state machines
* services
* repositories
* caching
* transactions
* event-driven architecture
* dependency injection
* distributed state when eventually relevant

## Paper/Minecraft

* plugin lifecycle
* server tick
* main server thread
* scheduler
* events
* event priority/cancellation
* entities
* worlds
* chunks
* inventories
* ItemStack behaviour
* player lifecycle
* persistence
* Adventure
* configuration
* commands
* Paper-specific APIs

Do not turn this into a separate university course.

Teach concepts when real development gives them context.

---

# Rebuild My Modern Minecraft Development Knowledge

Assume parts of my Minecraft-development knowledge may be approximately ten years out of date.

Actively help update that mental model.

I want to learn what modern Paper and modern Minecraft allow developers to build.

I cannot ask about technologies that I do not know exist.

Therefore occasionally identify capabilities that are relevant or valuable even if I did not explicitly ask about them.

---

# Modern Capability Awareness

As development progresses, identify useful modern capabilities in areas such as:

* Adventure Components
* MiniMessage
* titles
* boss bars
* sounds
* interactive text
* modern commands
* lifecycle APIs
* PersistentDataContainer
* modern item data/components
* registries
* display entities
* interaction entities
* entity visibility
* particles
* recipes
* resource packs
* custom item models
* custom sounds
* fonts
* transformations/animations
* plugin messaging
* modern Paper APIs

This is NOT a checklist.

Do not add something simply because it is modern.

Explain where it is useful.

---

# Capability Discovery

When appropriate, give me occasional:

> "You should know this exists."

moments.

For a capability I may not know about, explain:

### What it enables

What can players actually experience?

### How it works

What mechanism makes it possible?

### Layer

Does it use:

* Bukkit
* Paper
* Adventure
* resource packs
* packets
* NMS
* proxy APIs
* another library

### Relevance

Classify it approximately as:

* relevant now
* useful soon
* useful background knowledge
* advanced/later

### Duels Example

Show how it could apply to this project where useful.

Do not derail current development simply to explore interesting technologies.

---

# Prefer Supported APIs

Generally prefer the highest appropriate abstraction:

```text
Paper/Bukkit API
-> maintained higher-level library
-> packet/protocol manipulation
-> NMS/server internals
```

This is not an absolute rule.

If a lower-level approach provides capability we genuinely need, use it.

Do NOT use NMS or packets merely because they seem more advanced.

A strong Minecraft developer should understand when lower-level access is unnecessary.

---

# Packets and Protocol

I eventually want a strong understanding of Minecraft packets and the protocol.

This is an advanced learning track, not an immediate requirement for Duels.

Eventually teach concepts such as:

* client/server responsibilities
* connection states
* packet direction
* entity IDs
* entity metadata
* spawning/despawning
* movement
* inventories
* player information
* client-visible state
* server-authoritative state
* packet ordering
* per-player visual state

I specifically want to understand how a server can maintain one authoritative state while particular clients may be shown different things.

Useful examples include:

* fake entities
* client-only entities
* NPCs
* player-specific displays
* temporary visual effects
* virtual objects

Also explain costs such as:

* synchronization
* version compatibility
* cleanup
* reconnect handling
* packet ordering
* entity ID management
* debugging difficulty

---

# NMS / Minecraft Internals

I eventually want to understand NMS and Minecraft server internals.

This should not distract us from learning strong Java/Paper fundamentals first.

When internals genuinely become useful, teach concepts such as:

* Bukkit API vs CraftBukkit vs Paper vs Minecraft internals
* mapped Minecraft classes
* Mojang mappings
* build tooling
* internal entities
* internal worlds
* registries
* networking internals
* server tick internals
* packet construction
* conversion between API and internal objects
* version fragility

Do not treat NMS as a badge of expertise.

Expertise includes knowing when NOT to use it.

---

# Source-Code Literacy

Help me learn how experienced developers discover answers themselves.

When useful, show how to investigate:

* Paper documentation
* Paper Javadocs
* source code
* Minecraft internals
* mappings
* GitHub repositories
* library implementations
* upstream changes

If documentation is insufficient, explain how to trace the implementation.

I want to become capable of researching undocumented behaviour rather than depending entirely on tutorials or AI.

---

# Avoid Outdated Advice

Minecraft development changes quickly.

When giving version-sensitive technical guidance:

* verify it against the project's target version,
* prefer official documentation,
* prefer current Javadocs,
* prefer source code,
* prefer maintained libraries.

Treat old Bukkit forum posts, old Spigot tutorials, historical NMS examples and old Stack Overflow answers cautiously.

They may be useful conceptually but may no longer represent the correct implementation.

---

# Network Development Goal

Duels should eventually be capable of supporting two broad deployment models.

## Standalone

Example:

```text
One Paper server
Duels installed
10 configured arenas
many local matches
no external infrastructure required
```

This use case must remain simple.

A standalone server owner should NOT need:

* Redis
* a proxy plugin
* a separate matchmaking service
* distributed locks
* multiple servers

unless they actually want network functionality.

## Network

Eventually we may support environments resembling:

```text
Velocity / BungeeCord
        |
        +-- Lobby
        |
        +-- Duels Server A
        |
        +-- Duels Server B
        |
        +-- Duels Server C
```

and potentially, much later, dynamically provisioned game servers/instances.

I do NOT currently have a deep understanding of modern Minecraft minigame-network infrastructure.

When this area becomes relevant, teach it from first principles.

---

# Network Learning

Explain concepts including, when relevant:

* what a proxy actually does
* proxy responsibilities vs backend Paper responsibilities
* transferring players
* server registration/discovery
* fixed servers
* dynamically created servers
* capacity
* matchmaking location
* arena allocation
* shared statistics
* shared databases
* server identity
* match identity
* reconnect handling
* crash handling
* multiple proxy instances
* cross-server communication
* messaging
* Redis/pub-sub where appropriate
* coordination
* duplicate allocation prevention

Do not introduce distributed infrastructure before there is a concrete problem requiring it.

---

# Network Evolution

Prefer an evolutionary model such as:

## Stage A

One Paper server.

Multiple configured arenas.

Multiple simultaneous matches.

## Stage B

One proxy.

Several permanent Duels backend servers.

## Stage C

Shared cross-server matchmaking.

## Stage D

Potential dynamic game server/instance allocation.

This is illustrative rather than mandatory.

For each stage explain:

* what problem appears,
* why the previous system becomes insufficient,
* what component is added,
* what state becomes shared,
* how failure behaviour changes.

Avoid building Stage D while we are still solving Stage A unless an architectural boundary genuinely prevents major future rework.

---

# Product / QoL Review

Do not treat the project only as a Java codebase.

Treat Duels as a real plugin product.

Identify meaningful improvements for players and administrators.

Potential areas include:

## Players

* useful feedback
* challenge UX
* queue UX
* kit selection
* match information
* spectators
* rematches
* statistics
* reconnect handling
* errors
* titles/action bars/sounds
* sensible cooldown/countdown feedback

## Administrators

* permissions
* setup flow
* validation
* diagnostics
* debugging tools
* logs
* startup warnings
* safe configuration changes
* migration behaviour
* destructive-action confirmation
* arena validation
* resource-in-use protection
* concurrent edits
* database diagnostics
* version/update information
* documentation

These are examples, not mandatory features.

Recommend only things that provide real value.

---

# Documentation

Project documentation currently lives under:

`docs/`

Existing files may include documents such as:

* `docs/ARCHITECTURE.md`
* `docs/TESTING.md`

These are project documentation and may be read/updated when appropriate.

As the V2 planning process progresses, maintain documentation such as:

* `docs/ROADMAP.md`
* `docs/ARCHITECTURE.md`
* `docs/LEARNING.md`

when useful.

Do not create documentation merely to duplicate code comments.

## ROADMAP

Should track:

* completed foundation
* current phase
* upcoming work
* dependencies
* deferred work
* major future ideas

## ARCHITECTURE

Should describe important architectural concepts and boundaries.

Prefer explaining WHY the architecture exists, not simply listing classes.

## LEARNING

Can track major Java/Paper/Minecraft concepts being introduced and useful technologies to revisit.

Do not turn it into exhaustive tutorial notes.

---

# Architecture Decisions

For substantial architectural choices, document:

* problem
* alternatives considered
* chosen approach
* reasons
* trade-offs
* conditions under which we would reconsider it

Examples might eventually include:

* arena configuration vs runtime arena instance
* immutable kit snapshots vs resource locks
* local queue vs shared network queue
* database choice
* local arena allocation vs network allocation
* proxy integration structure

---

# Code Changes

Before substantial changes:

1. inspect the current implementation,
2. inspect relevant call sites,
3. understand dependencies,
4. explain the problem,
5. propose the design,
6. implement incrementally.

When doing an audit/review, do NOT modify production code unless I explicitly ask you to begin implementation.

When we have agreed to implement a roadmap item, code changes are expected.

---

# Git / V1 Baseline

Both JCore and Duels have working V1 commits.

Treat those commits as useful stable reference points.

Do not casually rewrite large portions of working V1.

When appropriate, use Git history/diffs to understand why systems exist or to compare V1 with later work.

Do not revert or destroy unrelated work.

---

# Review Priority

For significant findings, classify them roughly as:

* Fix now
* Fix before a named future feature
* Useful improvement
* Optional cleanup
* Leave as-is

The review is not successful merely because it finds many things to change.

Explicitly saying that an existing system is appropriate and should remain as-is is a valid and useful finding.

---

# Communication Style

Be technically detailed but understandable.

Do not hide reasoning behind phrases such as:

> "best practice"

or:

> "industry standard"

Explain why something is appropriate for THIS codebase.

When I misunderstand something, correct the misunderstanding clearly.

When several approaches are reasonable, explain the trade-offs.

Do not agree with an architectural idea merely because I suggested it.

Challenge it if there is a technical reason.

Likewise, do not replace my implementation merely because another design is more fashionable.

---

# Learning Checkpoints

At sensible milestones, briefly identify:

## What I should now understand

The important concepts we have used.

## Things I can now build

Examples of systems those concepts enable.

## Worth exploring next

One or two technologies that naturally follow.

Do not overwhelm me with large unrelated learning lists.

---

# Ultimate Goal

The objective is not simply to add more features to Duels.

By the end of this longer development process I want:

* a strong JCore library
* a strong Duels plugin
* a clear understanding of both codebases
* confidence maintaining them without AI
* strong modern Java knowledge
* strong Paper knowledge
* understanding of modern Minecraft development capabilities
* understanding of minigame network architecture
* eventual understanding of Minecraft protocol/packets
* eventual understanding of Minecraft internals/NMS
* the judgment to know which abstraction level is appropriate

The software and my understanding should improve together.
