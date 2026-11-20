# Ironwood Wilds — Commands, Actions, and Lazy Simulation

## 1. Five concepts that must remain distinct

| Concept         | Meaning                                                                                                         |
| --------------- | --------------------------------------------------------------------------------------------------------------- |
| Command         | An authenticated request to change the world now: append a queue, dispatch an expedition, recruit, cancel, etc. |
| Action          | A character's queued/running unit of work with a start, duration, completion, and rule handler                  |
| Scheduled event | A future transition in the world agenda: action completion, shipment arrival, raid round, regrowth              |
| Domain event    | An authoritative, ordered record of something that changed                                                      |
| Observation     | A read-only, audience-filtered view of committed state                                                          |

`POST /commands` is not itself an action. For example, `character.queue.append`
can start a `mine` action immediately if the character is idle, or leave it
pending. The eventual `action_completed` domain event is not a new command and
must not be sent back through the client command endpoint.

## 2. Logical time

Use a non-negative `int64_t` logical millisecond counter with checked addition,
matching PostgreSQL `BIGINT`; durations and deadlines are integers within that
bound. The name “tick” may appear in UI explanations, but there is **no loop
that visits every tick**.

A running world maps monotonic host time to logical time. It never uses the
client's clock. Host wall-clock adjustments, request latency, and thread counts
cannot change simulation rules.

For the first hosted version:

- clean shutdown freezes the world's logical clock;
- a crash/restart also resumes from saved logical time, with no offline
  catch-up;
- while running, a monotonic clock advances logical time;
- a separate world lifecycle flag controls whether admissions are open, paused,
  closing, or archived;
- offline progression can later be a world-setting with an explicit cap and
  rules, not an accidental effect of restart code.

This makes debugging and fair tests much easier. It can be revisited for a
permanent 24/7 deployment after actual hosting experience.

## 3. The agenda

The future-event agenda is a min-heap or equivalent ordered structure. An entry
contains at least:

```text
scheduled_event_id
world_id
due_logical_time
deterministic_tie_key
kind
subject_id / subject_generation
payload
ruleset_version
```

Order by `(due_logical_time, deterministic_tie_key, scheduled_event_id)`. The
tie key is assigned from persisted simulation order, not pointer value, thread
timing, or hash iteration. If a rule needs all combat deaths resolved before
another same-time phase, encode that phase explicitly in the tie key rather than
relying on insertion accidents.

Agenda entries are proposals that must be revalidated when popped. Cancelling an
action can mark it cancelled and leave a now-stale heap entry in place; when
popped, the action ID plus generation no longer matches and the entry emits
nothing. This avoids fragile heap deletion and use-after-free bugs.

### Core advancement algorithm

```text
advance(world, target_time, work_budget):
    require target_time >= world.logical_time
    require work_budget > 0

    while agenda.minimum_due_time <= target_time:
        if work_budget is exhausted:
            return CATCHUP_REQUIRED with effects through world.logical_time
            // runtime atomically commits that partial position

        entry = pop_minimum_in_stable_order()
        world.logical_time = entry.due_time

        if entry is stale against current state:
            record optional debug reason; consume budget; continue

        effects = dispatch_scheduled_event(world, entry)
        validate effects and invariants
        append effects in deterministic order
        consume work/progress budget

    world.logical_time = target_time
    return ADVANCED_TO_TARGET with all effects
    // runtime atomically commits the completed advance before command execution
```

A handler may schedule follow-up work, including work due immediately. Every
such cycle must either increase a sequence/progress value or have a hard bound
so a malformed content pack cannot create an infinite zero-time loop.

**Budget exhaustion never skips time or due work.** On `CATCHUP_REQUIRED`,
logical time is the last processed event time (or the original time if none was
processed), all still-due entries remain in the agenda, and the partial
effects/agenda position are committed atomically. The runtime sets a
`catching_up` flag (the world lifecycle remains `active`): new commands are not
admitted and no observation claims freshness beyond the committed revision. The
runtime continues bounded batches until `ADVANCED_TO_TARGET`; a client whose
command cannot wait receives `503 WORLD_CATCHING_UP`. Ordinary reads may still
use the explicitly older committed observation. A core invariant failure follows
the same stop/recover path as a persistence failure rather than committing a
broken batch.

### What wakes advancement?

- the runtime's timer at the earliest due time;
- an admitted command, before that command executes;
- explicit administrative fast-forward in an isolated test/experiment;
- snapshot/maintenance admission when it needs a fresh boundary.

Ordinary `GET` requests should read committed projections and their revision.
They should not independently mutate history merely because someone looked. The
timer and command admissions keep the world current. If an observation needs an
exact fresh boundary, route it through the same writer with the same
deterministic `advance_to(T)` operation; never give each HTTP worker its own
world clock.

Initially the whole world shares `logical_time`. Per-character/per-region lazy
clocks are deferred because an observation or command touching two different
clocks would immediately reintroduce global ordering.

## 4. Command admission pipeline

```text
HTTP/bot request
  1. request size and JSON shape
  2. API/protocol version
  3. account/session/token authentication
  4. rate limits
  5. world is active and household membership/scope is valid
  6. command ID lookup: return original result if already committed (optimisation only)
  7. assign ordered admission to the one world writer
       |
       v
World writer
  8. stamp monotonic logical admission time T and admission sequence
  9. advance due work through T; if the bounded advance does not reach T,
     commit that partial batch and defer this command
 10. recheck command ID and canonical request hash inside the writer transaction
 11. check expected world/character revision if supplied
 12. recheck that the account is enabled and authorise membership, target ownership, and visibility against current state
 13. semantic validation against current state
 14. execute atomically; update state, agenda, events, idempotency result
 15. commit the durable transaction, including UNIQUE(world_id, admission_seq)
 16. publish projection updates/events and acknowledge
```

Transport may reject obviously malformed input early. Only the writer makes
authoritative game decisions.

### Required envelope fields

- `v`: command schema version;
- `command_id`: unique client-generated ID within the account/world scope;
- `type`: stable command key;
- `expected_revision` (optional but recommended for queue replacement and other
  read-modify-write operations); each command type defines whether this refers
  to its world, household, character, queue, or defence-plan revision;
- command-specific actor/target/payload.

The server derives account and household from the authenticated membership. A
payload saying “household = someone else” is never authority.

### Idempotency

A timeout after commit but before response is normal in network systems.
Repeating the same `(world, account, command_id)` returns the stored original
response without executing again. Reusing that ID with a different canonical
request is a conflict. The pre-admission lookup is only an optimisation: the
world writer rechecks the key and request hash in the same transaction that
commits effects, and `UNIQUE(world_id, admission_seq)` prevents two admissions
sharing an order position.

If the result of `COMMIT` is unknown, the runtime closes admissions, reconnects,
reloads the latest durable position, and reconciles both the world sequence and
command row before any retry. A committed command returns its stored response;
an absent command can be admitted again only after recovery. Timer-originated
handlers are never blindly repeated—the restored agenda and last applied
scheduled-event IDs determine what remains due. If reconciliation or a core
invariant check fails, the world remains paused for recovery and returns `503`
rather than continuing in memory.

Idempotency applies to all commands, including recruitment, shipments,
construction, and queue edits—not only payment-like operations.

## 5. Action queue state machine

```text
                append
                  |
                  v
             [ QUEUED ] --------------------> [ CANCELLED ]
                  |
         predecessor completes/fails
                  |
             start handler
             atomic checks/costs
                  |
        +---------+----------+
        |                    |
   start rejected        started + deadline
        |                    |
        v                    v
 [ FAILED ]             [ RUNNING ] --------> [ CANCELLED ]
                             |
                       due agenda entry
                             |
                    +--------+--------+
                    |                 |
                    v                 v
               [ COMPLETED ]       [ FAILED ]
```

A start rejection emits `action_failed` and tries the next pending action at the
same logical time. Queue depth bounds that immediate chain. A completion failure
must have explicit input/output/refund semantics; avoid it where possible by
reserving required capacity at start.

### Two validation times, two meanings

**Enqueue validation** answers “is this a well-formed, potentially meaningful
plan?”:

- character exists, is alive/controllable, and belongs to the caller;
- every action/target key, parameter, count, and target compatibility in the
  whole append/replace batch is valid;
- queue has room, and the batch is all-or-nothing;
- targets are known and the requested handler can apply to their definition (for
  example, a mine action names a mineable site);
- predecessor-dependent location, technology, tools, stock, capacity, and
  similar live prerequisites for a **pending** action are deferred until its
  start.

If the character is idle, the first action is also fully start-validated
immediately. A predictive queue simulator may warn that a later action appears
impossible or that a predecessor is expected to satisfy it, but those warnings
do not reject an otherwise valid pending plan. Thus
`move to ridge -> mine ridge` is legal even though the character is not at the
ridge when the batch is appended.

**Start validation** answers “can this exact unit of work begin now?”:

- character is still alive, at the required location, and not reserved by an
  expedition;
- target still exists and has capacity;
- required stock is atomically available;
- technology/building/worker-slot conditions still hold;
- any required resource/output reservation can be acquired.

Enqueue success is not a promise that a future action will succeed. The UI
should communicate this rather than hiding failures.

## 6. Costs, reservations, and outputs

A uniform “deduct at completion” rule creates bad edge cases, so use this
default:

- **Stock inputs:** checked and consumed atomically at start.
- **Queued actions:** do not reserve future stock while still pending. Their
  schedule is an estimate, and start failure is visible.
- **Exclusive entities:** character, party, workstation, or building worker
  slots are reserved at start/dispatch as appropriate.
- **Finite resource nodes:** reserve the amount being harvested at start so two
  simultaneous miners cannot both receive the last unit.
- **Output storage:** reserve required capacity at start when otherwise a
  completed action could lose its output.
- **Outputs and XP:** produced at completion.
- **Reservations:** have stable IDs and are released or resolved on completion,
  cancellation, death, and recovery exactly once.

This is an **optimistic pending queue with atomic starts**, not a guarantee of
future resources. A client-side planner can calculate expected stock across its
own queue and warn, but the server remains authoritative.

Each action definition must document cancellation refunds and interruption
behavior. Default: pending cancellation is free; running cancellation loses
consumed inputs and releases unused reservations; some movement/combat actions
cannot be cancelled midway. Exceptions must be explicit content/rules, not
handler accidents.

## 7. Queue estimates versus authoritative deadlines

Only the running action needs an authoritative `complete_at`. Pending actions
have queue positions and estimated `not_before`/duration based on current
assumptions.

Example:

| Position | Action             | State   | Time information                           |
| -------- | ------------------ | ------- | ------------------------------------------ |
| —        | Move to iron ridge | Running | started at 12:00:00, completes at 12:02:00 |
| 0        | Mine iron ore      | Queued  | estimated 12:02–12:05                      |
| 1        | Mine iron ore      | Queued  | estimated 12:05–12:08                      |
| 2        | Move to camp       | Queued  | estimate depends on prior outcomes         |

If the first mine fails for a non-time reason, the next action is considered
immediately and estimates are recalculated. Queue changes never silently turn an
already running action into another action.

## 8. Initial command vocabulary

The exact list belongs in the versioned protocol specification. A reasonable
small set is:

| Command                   | Effect                                                                            |
| ------------------------- | --------------------------------------------------------------------------------- |
| `character.queue.append`  | Append one or more actions, all-or-nothing for that append request                |
| `character.queue.replace` | Replace pending actions at an expected character/queue revision                   |
| `character.queue.clear`   | Cancel all pending actions; running action follows its own cancellation rule      |
| `character.action.cancel` | Cancel a specific running/pending action if legal                                 |
| `character.equipment.set` | Assign/unassign an available equipment instance at an expected character revision |
| `household.recruit`       | Spend Favor and schedule/create a newcomer according to rules                     |
| `holding.defence.set`     | Replace the standing defence plan at an expected revision                         |
| `holding.building.place`  | Create a construction site; actual work remains a character action                |
| `shipment.dispatch`       | Consume/reserve shipment goods and schedule its result                            |
| `expedition.create`       | Create a party and validate initial membership                                    |
| `expedition.update_plan`  | Change formation/tactics before dispatch                                          |
| `expedition.dispatch`     | Reserve party members and schedule travel/combat                                  |
| `expedition.cancel`       | Apply action-specific retreat/cancellation rules                                  |

Ordinary actions submitted inside queue commands include `move`, `gather`,
`mine`, `craft`, `research`, `build`, `repair`, `deposit`, `rest`, and `defend`.
Do not create separate HTTP endpoints whose handlers bypass this command
vocabulary.

## 9. Example: mine ore

Request intent:

```text
character.queue.append
character = Alda
actions = [
  { type: move, destination: iron_ridge },
  { type: mine, site: iron_ridge, recipe: iron_ore, count: 1 }
]
```

Committed immediate effects might be:

1. `action_started` for movement, with start/end logical times;
2. `action_queued` for mining;
3. `character_queue_changed` with the new queue revision;
4. `world_revision_advanced`.

At the movement deadline, the agenda produces `action_completed` and
`character_moved`, then starts mining if all start rules pass. Mining reserves
its finite node yield and schedules a completion. At that deadline, the node,
carried inventory, XP, and next action are resolved in one ordered mutation and
an `action_completed` event describes the outcome. Household stock changes only
after a later return and `deposit` action.

A client that retries the original queue command receives the original response
and does not append another copy.

## 10. Group actions and combat

An expedition has its own state and agenda entries. Dispatch validates and
reserves all members atomically. The vertical slice requires every member to be
alive, at the holding, and to have no running or pending personal action before
dispatch; later versions may add suspension/resume as a separately specified
feature.

Combat rounds are scheduled events. A round:

1. validates the encounter generation/state;
2. builds a stable participant order;
3. evaluates explicit tactics and targets;
4. draws from the encounter's deterministic random stream;
5. applies damage, injuries, deaths, retreat, and loot facts;
6. emits an auditable round event;
7. schedules the next round or expedition return.

Expedition completion/death must release member and resource reservations
exactly once. A dead character's queued actions become cancelled/failed with
visible reasons and cannot later produce output from stale agenda entries.

## 11. Passive world systems

Not every value needs an event:

- Display-only quantities can be pure functions of `(last_state, elapsed_time)`.
- Contested resources, deaths, arrivals, combat, and shared ecology transitions
  need ordered scheduled events.
- Long-running independent systems should use closed-form elapsed calculations
  or bounded/coalesced intervals rather than replaying millions of empty
  periods.
- A scheduled wake-up ensures an absent player's shipment or raid still resolves
  without waiting for that player's next login.

Examples:

| System                        | Mechanism                                                    |
| ----------------------------- | ------------------------------------------------------------ |
| Mine/craft completion         | agenda event                                                 |
| Shipment arrival              | agenda event                                                 |
| Recruitment arrival           | agenda event                                                 |
| Holding raid rounds           | agenda events                                                |
| Regrowth at a known timestamp | agenda event                                                 |
| Display of elapsed duration   | pure calculation                                             |
| Seasonal ecology              | coarse agenda event, possibly coalesced under explicit rules |

## 12. Randomness and determinism

Use named, independent random streams or counter-based rolls:

```text
(world_seed, "worldgen", region/site coordinates, purpose, attempt)
(world_seed, "combat", expedition_id, round, roll_index)
(world_seed, "loot", expedition_id, encounter_id)
(world_seed, "raid", raid_id, round, roll_index)
```

Rules:

- integer/fixed-point authoritative arithmetic where practical;
- stable sort and neighbour order;
- no dependence on hash-map iteration, pointers, uninitialised padding, locale,
  timezone, or HTTP timing;
- all durations/probabilities use explicit rounding;
- state hashes include canonical world state, agenda contents/order, sequence
  counters, logical time, and any RNG state;
- rules, generator, record, and observation versions are stored separately.

A replay holds fixed the ordered **admitted commands and their logical times**,
not the original network arrival timing. It should reproduce the same domain
events and final state under the pinned engine/rules. Normal recovery applies
authoritative recorded effects to a snapshot rather than rerunning commands and
emitting new side effects.

## 13. Priority invariants and tests

### Invariants

- Logical time never goes backwards, and a completed advance has no unprocessed
  event due at or before its claimed time.
- A budget-exhausted advance remains at its actual committed event boundary with
  all overdue work intact; it never reports the requested target as reached.
- World revision and event sequence increase monotonically without gaps.
- No character runs two actions or belongs to two dispatched expeditions.
- Stock, capacity, health, Favor, and quantities stay within documented bounds.
- Every live reservation has exactly one owner and eventual resolution.
- Every non-stale agenda entry references the correct entity generation.
- No dead character starts or completes ordinary work.
- No output is acknowledged before its durable commit.
- Observing or failing to observe a region does not change generated definitions
  or subsequent outcomes.

### High-value tests

01. Two characters attempt to consume the same final stock unit; only the first
    atomic start succeeds.
02. Completion and cancellation share a timestamp; the documented ordering gives
    the same result every time.
03. A client times out after commit and retries; no duplicate output occurs.
04. A cancelled action's stale heap entry is popped harmlessly.
05. A crashed server restores running actions, agenda, counters, and
    reservations without double completion.
06. Frequent polling versus no polling produces identical state for the same
    admitted command record.
07. A handler scheduling immediate follow-ups cannot loop forever.
08. A huge elapsed interval obeys the configured freeze/catch-up/coalescing
    policy and work budget.
09. Different thread counts and client request timing do not affect replay when
    logical admissions are fixed.
10. An old world's delayed command cannot affect a new season because the world
    ID and lifecycle differ.
11. A tiny work budget returns `CATCHUP_REQUIRED`, commits only processed
    events, retains overdue entries, and reaches the same final hash after
    continuation as one unbounded advance.
12. `move to ridge -> mine ridge` passes batch validation while pending, but
    mining still revalidates its actual location and prerequisites when it
    starts.

## 14. Future accelerated controllers

The same `advance_to(T)` core supports isolated, headless episodes:

```text
reset isolated world -> observe household -> controller selects legal commands
-> advance to next decision/due event -> collect reward/events -> done
```

No production network or database is needed for training. The controller chooses
among validated commands rather than emitting arbitrary memory mutations. This
is the clean future seam for `samgenai`; it requires no GA-specific code in the
live simulation now.
