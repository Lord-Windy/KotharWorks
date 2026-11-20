# Ironwood Wilds — Commands & the Simulation (IronwoodWildsCommandsAndSimulation.md)

> How intents become world changes: wire format → validation → ordering →
> the tick → events. Includes the lazy time model in full.
> Companion: [Architecture](IronwoodWildsServerArchitecture.md) (esp. §3-5).

## 1. The lifecycle at a glance

```
client          apps/samwilds (http threads)      apps/samwilds tick host      libs/samwilds
  │  POST /v1/commands                              │                            │
  ├────────────► shape+auth+rate check               │                            │
  │              (reject 400/401/429 — no world)     │                            │
  │  202 {accepted, batch} ──┐                       │                            │
  │  ◄───────────────────────┘                       │                            │
  │                          └──► inbox ────────────►│ at tick boundary:          │
  │                                                  │ order (§4) ───────────────►│ validate vs world
  │                                                  │                            │ apply / reject
  │                                                  │                            │ (events out)
  │  GET /v1/events?since=seq  ◄── published view + event log (§6) ◄────────────┤
```

Commands are **asynchronous by nature** — the response means "queued for
the next tick", not "done". Results come back as events. (SpaceTraders
works this way for long actions; samvillage returns per-command enqueue
results synchronously — we keep samvillage's immediacy for *validation*
(which is synchronous, §3.1) but outcomes are always events.)

## 2. Wire format

JSON over HTTPS. One mutating endpoint, batchable — a re-plan of five
people is one request, which matters for both bots and mobile:

```json
POST /v1/commands
[
  {"op":"move",       "actor":"p-114","to":{"r":3,"x":22,"y":40},"ref":"c-8841"},
  {"op":"mine",       "actor":"p-114","target":"e-9f02","ref":"c-8842"},
  {"op":"queue_clear","actor":"p-115","ref":"c-8843"},
  {"op":"craft",      "actor":"p-115","recipe":"stone_axe","count":1,"ref":"c-8844"},
  {"op":"party_form", "roster":["p-110","p-111","p-114"],"provisions":{"ration":12,"bandage":2}},
  {"op":"raid_plan",  "plan":"plan-v3"}
]
→ 202 {"batch":712,"accepted":6,"rejected":[{"ref":"c-8844","code":"NEEDS_WORKSHOP"}]}
```

- `ref` is the client's idempotency key: replays of the same ref within a
  retention window are dropped (network retries must be free).
- Ops map 1:1 onto a tagged union in the engine (§3.2). The vocabulary is
  the **whole** game verb set — there is no second, UI-only back door.
  Web, CLI, bots, and later a genetic controller all speak it.

### 2.1 Command vocabulary (v1 sketch)

| Group | Ops |
|---|---|
| movement | `move` (tile pathing, auto-routed), `halt` |
| gathering | `chop`, `mine`, `quarry`, `forage`, `hunt_join` |
| crafting | `craft` (recipe, count), `smelt`, `crate` (pack shipment) |
| construction | `build` (prefab at site), `repair`, `demolish` |
| queue mgmt | `queue_clear`, `queue_drop` (index), `reorder` |
| household | `claim` (banner move), `assign_bed`, `migrant_request` (spend Charters) |
| expedition | `party_form`, `party_disband`, `expedition_launch` (target + standing orders), `expedition_recall` |
| defense | `raid_plan` (post a named plan), `raid_plan_activate` |

Adding an op = add the union arm, validator(s), applier, and one table
row. No API versioning ceremony — the vocabulary is discoverable at
`GET /v1/rules` (samvillage D9 pattern) and additions are additive.

## 3. Validation — three gates, two of them visible

### 3.1 Gate 1: shape & auth (HTTP thread, synchronous)

JSON parses, op is known, actor id is one of *your* folk, ref is
well-formed, rate limits hold. Rejects here are 4xx with a machine code.
No world knowledge — this gate never blocks on the sim thread.

### 3.2 Gate 2: world rules at enqueue (tick host, same tick)

The engine checks the command against current world state: skill gates,
tool presence, distance sanity, queue capacity, stock for crafting. This
is samvillage's §5.2 synchronous rejection — the client learns in the
202's `rejected` list *why* `craft stone_axe` won't fly (NEEDS_WORKSHOP),
with the game's own reason vocabulary (`samgrognard_refusal_hint`-style
plain-language strings ride along for UIs).

```c
typedef enum {
  SAMWILDS_CMD_MOVE = 0, SAMWILDS_CMD_MINE, SAMWILDS_CMD_CRAFT,
  SAMWILDS_CMD_QUEUE_CLEAR, /* ... */ SAMWILDS_CMD_COUNT,
} SamWildsCmdType;

typedef struct {
  SamWildsCmdType type;
  uint32_t account;              /* household */
  SamWildsEntity actor;
  char ref[24];
  union {
    SamWildsMove   move;
    SamWildsMine   mine;
    SamWildsCraft  craft;
    /* one arm per op — plain data, no pointers; journal-serializable */
  };
} SamWildsCommand;

/* the dispatch table IS the command pattern */
typedef struct {
  bool (*validate)(const SamWildsWorld *, const SamWildsCommand *, SamWildsReject *why);
  void (*apply)(SamWildsWorld *, const SamWildsCommand *, uint64_t tick);
} SamWildsCmdHandler;
extern const SamWildsCmdHandler SAMWILDS_CMD_HANDLERS[SAMWILDS_CMD_COUNT];
```

### 3.3 Gate 3: revalidation at action *start* (fail-soft)

Queues outlive ticks; the world doesn't wait. When a queued action's
turn comes, its preconditions are rechecked — the vein may be exhausted,
the workshop burned. Failure emits an event
(`action_failed {code: VEIN_EXHAUSTED}`) and the queue advances
(samvillage's fail-soft advance, unchanged). Bots and players both cope
the same way: read events, re-plan. **This is a feature**: it's what
makes a shared, contested world feel alive without PvP.

## 4. Ordering & fairness at the tick

Per tick: drain the inbox, then apply commands in the order
`(batch_seq, account, actor, client_ref)` — stable, total, replayable.
No packet race ever decides who fells the tree: within a tick the order
is deterministic, across ticks the batch sequence is. (Two households
command the same tree in one tick: first-in-batch wins, second gets
`action_failed TREE_FELLED` at start. Rare, legible, fair enough.)

## 5. The time model — lazy evaluation in full

The requirement: actions cost real minutes, queues run deep, players go
offline, and 2,400 people must cost ~nothing while "busy". So **no
per-tick progress accounting anywhere**:

1. When an action starts, compute one thing: `end_tick = now + duration`
   (duration from rules × skill multiplier × tool × provisions; all
   integers, rounded up).
2. Push `(end_tick, actor, queue_slot)` onto a **min-heap keyed by
   end_tick**. Running state is two fields on the person (`action`,
   `end_tick`).
3. Each tick: pop entries with `end_tick <= now` — O(due) work. A
   household of 12 idle-waiting folk costs literally zero; a server of
   200 households idles at microseconds.
4. **Progress is computed on read, never accumulated**: the view layer
   derives `remaining = end_tick - now` and renders "2m41s left". The
   UI's smooth countdown is client-side math on data it already has.
5. On completion: apply effects (yield, xp, items), emit events, and
   start the next queued action (gate 3), which pushes its own heap
   entry. The heap only ever holds *running* actions — bounded by
   population, not by queue depth.
6. Offline = same path. Your folk finish the queue, then stand patient
   (the standing rule: uncommanded people do *nothing* — no wandering,
   no autonomous chores; that's the design pillar, and laziness gives it
   to us free).

Same trick, second place: **expeditions and shipments** are just actions
with long durations and milestone sub-events (`expedition_halfway`,
`encounter_wolves`). And a third: construction is work-accumulation
actions that re-queue themselves until the part's work points are done
(samvillage parallel BUILD generalized — multiple folk on one wall).

Clock integrity: wall time enters only in the app (`tick = wall_secs -
season_epoch`, monotonic clock; the engine never sees time). Tick
catch-up after a stall is clamped (a GC pause must not "fast-forward"
the world — actions complete on schedule regardless, because their
end_ticks are timestamps, not counters. This is why laziness is also
robustness).

## 6. Events — the output stream

Every mutation emits into one append-only, seq-ordered stream (per
world), exactly like samvillage's event log scaled up + samgrognard's
record envelope:

```json
{"seq":481233,"tick":1220390,"kind":"action_done",
 "actor":"p-114","account":"h-12","payload":{"action":"mine","xp":14,"gain":{"item":"iron_ore","n":3}}}
{"seq":481234,"tick":1220390,"kind":"action_failed","actor":"p-115",
 "payload":{"action":"craft","code":"NEEDS_WORKSHOP"}}
{"seq":481235,"tick":1220401,"kind":"raid_resolved","account":"h-7","payload":{...}}
```

- `GET /v1/events?since=<seq>&scope=...` — your household's feed, a
  region's feed, or (public spectate) the whole world's, redacted by
  scope rules (fog for unexplored tiles — later).
- Subscribers inside the server: journal writer (persistence),
  projection updater (leaderboard/Charter tables), fan-out (live pages),
  chronicle builder (narrative pages).
- **The journal *is* commands + events + per-tick world hash** — the
  determinism artifact from Architecture §4 and the replay source. An
  event without a causing command is a bug; alarms exist.

## 7. Read paths (how clients see state)

- `GET /v1/world` — epoch, tick, season metadata, rules digest.
- `GET /v1/map?r=3&z=...` — region tiles (lazy-detailed per
  Procgen doc §4; cached; versioned by tile revision for client caching).
- `GET /v1/household` — folk, queues, stock, buildings, skills, Charter
  balance, active plans.
- `GET /v1/person/p-114` — deep view (the tab page).
- `GET /v1/rules` — the whole rules tier-1 data (durations, recipes,
  techs, Charter values) — samvillage's self-describing-API rule: a
  client that hardcodes nothing but endpoints is the bar.
All reads come from the published per-tick view (Architecture §3/A8) —
consistent snapshot, no locks, any replica later.

## 8. A worked example ("mine the iron vein east of the ford")

Player, in a browser tab, selects Maren (`p-114`) 6 tiles away, clicks
the vein (`e-9f02`), then queues "chop firewood ×2" behind it. UI sends
one batch: `move p-114 → (r3,22,40)`, `mine e-9f02`, `chop firewood 2`.

- **t+0**: gate 1 passes; 202 batch 712.
- **t+1s**: tick drains inbox in order; `move` validates (path 6 tiles,
  48s), starts: `end_tick = now+48`, heap push; `mine` validates
  (distance rule: within reach *after* the move — validation is against
  the post-move position, a small rules nicety), queues behind running;
  `chop ×2` fills pending slots 2-3. Events: `action_started move`.
- **t+49s**: move pops; gate 3 recheck (tile occupied by a wolf? — if
  so: `action_failed` for `mine`, queue advances to chop). Assume clear:
  `mine` starts, `end_tick = now+180`, `action_started mine`.
- **t+3m49s**: `mine` pops → yields, xp event `action_done`, `chop`
  starts. The tab (closed or open — doesn't matter) shows all of this
  on next `GET /v1/events?since=`.
- Player asleep the whole time: identical. No special offline mode
  exists — that's the point of the time model.

## 9. Decision log

| # | Decision | Note |
|---|---|---|
| C1 | One batchable POST endpoint; async outcomes as events | retries free via `ref`; UI==bot==GA vocabulary |
| C2 | Three validation gates; gate 2 synchronous-reject, gate 3 fail-soft | samvillage §4.4/§5.2 discipline preserved |
| C3 | Deterministic in-tick order (batch, account, actor, ref) | replays and fairness without locks |
| C4 | Lazy completion-heap time model; progress computed on read | O(due) ticks; offline free; stall-robust |
| C5 | Single event stream, seq-ordered, scoped reads | spectate + chronicle + journal all one artifact |
| C6 | Engine sees ticks, never wall time | determinism seam |
