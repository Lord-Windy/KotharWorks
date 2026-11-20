# Ironwood Wilds — Commands and the Lazy Time Model

**Status:** proposal v0.1 · 2026-11-20 · author: Fable **Scope:** how a player's
intent becomes a change in the world, and how the world moves through time
without a tick loop. **Feeds ticket:** `c853eef1` (samworld lazy time model
spec) — §6.5 answers that ticket's questions one by one. **Map:**
`maps/samworld-command-flow.dot`.

______________________________________________________________________

## 1. Summary

1. People send **commands**. A command either queues **actions** on one of their
   Actors or changes something directly.
2. An action has a start and a known end. The end goes into one world-wide
   **due-queue**. Nothing runs until something is due *and* someone touches the
   world.
3. The world at time *t* is a function of the seed and the commands stamped at
   or before *t* — never of who looked, or when.

| Word          | Meaning                                                        | Direction           |
| ------------- | -------------------------------------------------------------- | ------------------- |
| **Command**   | An API verb: `queue.append`, `patron.recruit`                  | in                  |
| **Action**    | A timed activity one Actor performs: `mine`, `smelt`, `travel` | queued by a command |
| **Due entry** | "This happens at world time *t*"                               | internal            |
| **Flow**      | A quantity that changes steadily, computed when read           | internal            |
| **Event**     | A fact that happened, stamped with when                        | out                 |
| **View**      | What one Family is allowed to see                              | out, on request     |

## 2. The life of one command

Brynn's owner clicks *Mine ×10* in the browser.

| #   | Step                                                                             | Where                  | What can stop it                             |
| --- | -------------------------------------------------------------------------------- | ---------------------- | -------------------------------------------- |
| 1   | Browser builds `{op, actor, args}` and POSTs it                                  | web client             | —                                            |
| 2   | TLS ends; request forwarded on loopback                                          | Caddy                  | size and rate limits                         |
| 3   | HTTP parsed, route matched                                                       | `samhttp`              | 400, 413, 431                                |
| 4   | Token → account → Family; rate limit; idempotency lookup                         | `api.c`, `auth.c`      | 401, 429; a repeat returns the first reply   |
| 5   | JSON → `SamWorldCommand`, driven by the command's arg spec                       | `wire.c`               | 400 unknown op or bad args; 404 unknown id   |
| 6   | `t = world_now()`; `samworld_advance_to(w, t)` — settle everything that was due  | `sim.c` → engine       | —                                            |
| 7   | `samworld_submit`: authorize → `check` (pure) → `apply`                          | engine                 | 403; 409 with a stable code. World untouched |
| 8   | Applied. The record `(seq, t, family, canonical JSON)` goes to the writer thread | `sim.c` → `samjournal` | —                                            |
| 9   | Reply: result, new `rev`, `now`                                                  | `wire.c`               | —                                            |
| 10  | Events emitted in steps 6–7 sit in the Family's feed for the next read or push   | engine                 | —                                            |

Steps 6 and 7 are the whole simulation. Everything else is plumbing, and all of
it runs on one thread, in order.

The lab runs steps 6–8 only: same engine calls, no network.

## 3. The wire format

One endpoint for every command. Field names are the ones from your
automation-game notes — `op`, `args`, `meta`.

```http
POST /v1/commands
Authorization: Bearer iw_api_7k2m_…

{ "op": "queue.append",
  "actor": "a-104",
  "args": { "actions": [
      { "do": "travel",  "to": "s-0.0.6" },
      { "do": "mine",    "at": "n-0.0.6.0", "count": 10 },
      { "do": "travel",  "to": "s-0.0.0" },
      { "do": "deposit", "into": "b-3", "what": "all" } ] },
  "id": "k3f9-0001",
  "meta": { "src": "web" } }
```

```json
{ "ok": true, "seq": 88213, "now": 73211000, "rev": 412,
  "result": { "queued": 4, "starts_at": 73211000, "first_ends_at": 74111000 } }
```

```json
{ "ok": false, "now": 73211000,
  "error": { "code": "QUEUE_FULL",
             "message": "a-104 already has 5 pending actions",
             "detail": { "cap": 5, "pending": 5 } } }
```

| Field               | Notes                                                                                                                                                                     |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `op`                | Command name, namespaced by module                                                                                                                                        |
| `actor`             | Which of your Actors, when the command concerns one                                                                                                                       |
| `args`              | Typed per command; the schema is published at `GET /v1/rules`                                                                                                             |
| `id`                | Optional, client-chosen. Remembered per Family for ten minutes; a retry gets the original reply. Makes flaky networks safe for bots                                       |
| `meta`              | Free-form; journaled, never read by the engine. `src` says which control layer sent it — `web`, `cli`, `bot` — which is how humans and scripts get told apart in the data |
| `dry_run`           | `true` runs authorization and `check` only, and returns the preview: duration, what is taken, what is given                                                               |
| `now`, `seq`, `rev` | World time in ms; the command's journal number; the Family's new revision (§8)                                                                                            |

A body of `{"commands": [...]}` applies several in order. Each stands alone; the
reply is an array.

| Status    | Meaning                                                     |
| --------- | ----------------------------------------------------------- |
| 200       | Applied (or dry run passed)                                 |
| 400       | Malformed, unknown op, wrong arg types                      |
| 401 / 403 | No valid token / not your Actor                             |
| 404       | Unknown id                                                  |
| 409       | The rules say no — `error.code` is stable and documented    |
| 429       | Rate limited; `Retry-After` says for how long               |
| 503       | Storage is behind, or the world is paused. Reads still work |

**The slice's commands**

| `op`             | Args                 | Effect                                                                  |
| ---------------- | -------------------- | ----------------------------------------------------------------------- |
| `queue.append`   | `actor`, `actions[]` | Add to the end of the queue. All or nothing                             |
| `queue.replace`  | `actor`, `actions[]` | Replace what is pending. The running action is untouched                |
| `queue.clear`    | `actor`              | Drop what is pending                                                    |
| `action.cancel`  | `actor`              | Stop the running action; what it took is returned; the queue carries on |
| `patron.recruit` | —                    | Spend Favor on a new Actor                                              |
| `family.found`   | `name`               | Issued by the server at registration — journaled like any other         |

**Three ways to send one**, all ending in the same JSON at the same endpoint:
click in the web client; type `mine n-0.0.6.0 x10` in its command line; or write
a program. Your earlier "control layers" idea — buttons, then logic blocks, then
scripts, then the API — needs nothing more from the server than this.

## 4. Validation: five layers

| #   | Layer     | Code                                                       | Rejects                                 | Seen as                  |
| --- | --------- | ---------------------------------------------------------- | --------------------------------------- | ------------------------ |
| 1   | Transport | `samhttp`, `api.c`                                         | oversize, bad JSON, bad token, too fast | 400 / 401 / 429          |
| 2   | Decode    | `wire.c`, from arg specs                                   | unknown op, wrong types, unknown ids    | 400 / 404                |
| 3   | Authorize | engine                                                     | not your Actor; Actor dead              | 403 / 409                |
| 4   | Check     | the command's `check` — takes a `const` world              | the rules say no, *now*                 | 409 + code               |
| 5   | Start     | the mechanic's `check_start`, when the action's turn comes | the world changed since                 | an `action.failed` event |

Layers 4 and 5 are samvillage's two-phase validation, kept whole: tell the
client at once what is already wrong; cope later with what goes wrong.

## 5. Actions and queues

```
               queue.append                 unit done, more to do
   ┌──────┐ ──────────────────▶ ┌──────┐ ◀───────────────────────┐
   │ IDLE │                     │ BUSY │ ────────────────────────┘
   └──────┘ ◀────────────────── └──────┘
              unit done, queue empty           any state ──▶ DEAD
```

- **One running, a few pending.** Default cap: five pending. A rule constant,
  and a natural tech reward.
- **Units.** `count: 10` is ten units back to back. Each unit is its own due
  entry and re-checks at its own start, so an order interrupted at unit seven
  has produced six units of ore, not zero. `until: "full"` runs until the pack
  is full.
- **Take at start, give at completion.** When a unit starts it takes its inputs:
  goods from a pack or store, one lump from a seam's stock, a slot at a station.
  When it completes it gives outputs and XP. Cancelling gives back what was
  taken. Nothing is ever in two places, even if nobody looks for a day.
- **Fail-soft.** If a step cannot start, it is skipped with an `action.failed`
  event naming the reason, and the queue moves on. A bot copes, the way a
  SpaceTraders bot copes with a failed jump.
- **Auto-travel.** If an action's target is at another site, `queue.append` puts
  the `travel` in front of it and says so in the reply. The inserted step is an
  ordinary queue entry and counts against the cap. Cancel a walk and the steps
  that depended on it fail soft, each saying why.
- **Idle is an event.** When the queue empties the engine emits `actor.idle`.
  The web client's "who needs orders" rail and every bot key on it.

| Code (409)         | Meaning                                                                       |
| ------------------ | ----------------------------------------------------------------------------- |
| `QUEUE_FULL`       | Pending cap reached                                                           |
| `BAD_TARGET`       | The target is not the kind of thing this action works on                      |
| `NOT_KNOWN`        | Your Family has not discovered that site                                      |
| `NEEDS_UNMET`      | A skill, tech, tool or structure requirement is missing; `detail` lists which |
| `NO_INPUTS`        | Not enough of something to start                                              |
| `NO_ROOM`          | The pack or store cannot take the output                                      |
| `SLOT_BUSY`        | The station is in use                                                         |
| `NODE_EMPTY`       | Nothing left to gather right now                                              |
| `ACTOR_DEAD`       | —                                                                             |
| `NOT_ENOUGH_FAVOR` | —                                                                             |

## 6. The lazy time model

### 6.1 What you wrote, and the one correction

Your ticket puts the idea this way: *actions resolve when observed, not on
timers — mining takes 30 s; the ore lands when someone looks at 50 s.* Your
Biofabrica notes say the same about a slow server: do the calculation when a
player looks.

That is exactly right about **when the server does the work**. It needs one
correction about **what the work computes**: the ore lands *at 30 s*. The server
merely gets around to it at 50.

The difference matters as soon as two Families touch the same thing. If an
outcome depends on when someone looked:

- **Fairness breaks.** Whoever refreshes first wins the seam (§9).
- **Replay breaks.** Reproducing a world needs every read anyone ever made, not
  just the commands.
- **Experiments break.** Two lab runs differ because their polling differed.

### 6.2 Three ways to do it

|                                   | A. Tick loop                      | B. Resolve on observe, per entity                                        | C. Due-queue and flows, advanced on touch                                     |
| --------------------------------- | --------------------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------------------------------- |
| How                               | Every second, visit everything    | Each entity keeps its own start and end; settle it when someone reads it | Every completion goes in one time-ordered queue; any touch drains what is due |
| Cost while idle                   | Every entity, every tick          | Nothing                                                                  | Nothing                                                                       |
| A contested outcome is decided by | Tick order                        | **Whoever looks first**                                                  | World time, then arrival order                                                |
| Replay needs                      | Commands                          | Commands **and every observation**                                       | Commands                                                                      |
| Precedent                         | samvillage                        | —                                                                        | Every discrete-event simulator                                                |
| Verdict                           | Works; wasteful; one-second grain | Breaks under sharing                                                     | **Recommended**                                                               |

C keeps everything you wanted from B — no work without attention, no tick — and
removes the one thing that was wrong with it.

### 6.3 The model

**The due-queue.** One min-heap for the world, ordered by `(at, seq)`.

```c
/* samtime */
typedef int64_t SamTime;      /* world milliseconds since the world began */

typedef struct SamDue {       /* "this happens then" */
  SamTime  at;
  uint64_t seq;               /* tie-break: the order things were scheduled */
  uint32_t kind;              /* which handler */
  uint32_t subject;           /* which Actor, structure, … */
} SamDue;

/* samworld — the entire time engine */
void samworld_advance_to(SamWorld *w, SamTime t) {
  SamDue d;
  while (samdue_pop_if_due(&w->due, t, &d)) {  /* earliest (at, seq) first     */
    w->now = d.at;                             /* it happens *then*, not now   */
    samworld_dispatch_due(w, &d);              /* may schedule more, never     */
  }                                            /*   earlier than w->now        */
  if (t > w->now) w->now = t;
}
```

Every driver calls this before it does anything else: before a command, before a
view, on the housekeeping timer, between decisions in the lab.

**Flows.** A quantity that changes steadily — a seam regrowing, a wall
weathering — is never ticked. It is stored as a value, a rate and a moment, and
computed when read.

```c
typedef struct SamFlow {
  int64_t value_m;            /* milli-units, as of `as_of`   */
  int64_t rate_m_per_hour;    /* may be negative              */
  int64_t min_m, max_m;
  SamTime as_of;
} SamFlow;

static inline int64_t samflow_at(const SamFlow *f, SamTime now) {
  int64_t v = f->value_m + f->rate_m_per_hour * (now - f->as_of) / SAMTIME_HOUR;
  return v < f->min_m ? f->min_m : v > f->max_m ? f->max_m : v;
}
```

Because the value is always computed from its base, reading it once or a
thousand times gives the same answer. A flow is re-based only when an event
changes it — a miner takes a lump — and events happen at determined times.

A flow gets a due entry only if crossing a threshold must *cause* something: a
wall reaching ruin emits an event, so the crossing time is computed and
scheduled. A seam regrowing causes nothing; the next miner just reads it.

**The calendar.** Day and hour are divisions of `now`. A day rolling over is one
due entry for the whole world, not one per Family.

### 6.4 Three laws

**Law 1 — Observation is a trigger, never an input.** Looking makes the server
do work it already owed. It cannot change what that work produces.

**Law 2 — Schedule independence.** For any `t1 ≤ t2`, `advance_to(t1)` then
`advance_to(t2)` leaves exactly the world that `advance_to(t2)` alone does. So
any pattern of reads, polls and heartbeats — or none — yields the same world.
This is stronger than what the ticket asks for ("determinism under a fixed
observation schedule"): no schedule needs fixing.

**Law 3 — Stillness.** With no new commands, the due-queue drains to nothing but
the calendar. Your own rule makes this true: people are *patient*. An unattended
House finishes its queues and stops. The work the server can ever owe is bounded
by the work players have queued.

Law 3 is a rule for content as much as for code: an autonomous process must be a
flow or a sparse world-level due entry — never a repeating timer per Actor.

### 6.5 Your ticket's questions, answered

| Question in `c853eef1`                            | Answer                                                                                                                                                                                                                        |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Who is the observer of record?                    | **Nobody.** The journal's timestamps are the record. A REST read, a subscription wake-up, a bot poll, a lab step and the housekeeping timer all do the same thing — `advance_to(now)` — and by Law 2 it does not matter which |
| Multi-observer consistency                        | Every read runs on one thread against a world settled to the same `now`. Two observers at the same moment see the same thing                                                                                                  |
| Two actors hit one node between observations      | Decided by world time, then scheduling order. Never by who looked. §9 works one through                                                                                                                                       |
| Offline progression                               | Not a feature. A queue runs whether or not its owner is connected; when it is empty the Actor is idle                                                                                                                         |
| Bot polling as a de facto tick                    | A poll cannot advance anything that was not already due. Polling faster buys a bot nothing but its rate limit                                                                                                                 |
| Controlling evaluation cadence                    | Not needed for correctness. For load: per-token rate limits; an unchanged view costs a comparison (§8); every view says when it will next change; push later so polling stops being necessary                                 |
| Idempotency                                       | Client-chosen command ids, remembered per Family                                                                                                                                                                              |
| Replay                                            | Re-apply the journal — `advance_to(t)`, then `submit`, record by record. Hash checkpoints in the journal prove it reproduced                                                                                                  |
| Reproducible fitness for genetic bots             | Total. Same seed, rules and commands → same world, on any schedule. The lab has no observations at all; it jumps from one decision to the next (§11)                                                                          |
| Server cost scales with attention, not world size | Yes — §6.6                                                                                                                                                                                                                    |

### 6.6 What it costs

Estimates, to be pinned by a `samtime_perf` test.

| Work                                            | Rough cost          | Grows with  |
| ----------------------------------------------- | ------------------- | ----------- |
| Housekeeping with nothing due                   | one comparison      | time        |
| One due event (pop and handle)                  | under a microsecond | queued work |
| One command (decode, check, apply)              | 5–20 µs             | attention   |
| One view (build and encode)                     | 20–200 µs           | attention   |
| One HTTP exchange (accept, parse, write, close) | 50–100 µs           | attention   |

A world of 2,000 Houses with ten Actors each, every one of them busy on
one-minute actions, produces about 330 due events a second: roughly 0.03 % of
one core. A world a hundred times that size is still a few percent.

So the hosting win you wanted is real, for two reasons:

- **Unattended Houses fall still** (Law 3). They owe the server nothing.
- **The expensive part of a request is HTTP and JSON**, which only happens when
  somebody asks.

Memory is about a kilobyte per Actor: 20,000 Actors is 20 MB.

The slice runs a 1 Hz housekeeping timer. It exists to keep feeds fresh and to
stamp time marks in the journal — not for correctness. Turn it off and the world
is still right, just settled in larger batches.

### 6.7 World clock and wall clock

- **World time** is milliseconds since the world began, as `int64`.
- The driver maps wall time to world time through a scale: 1:1 live, 60:1 for a
  fast test world, unbounded in the lab.
- **The world clock runs only while the server runs.** A restart resumes from
  the last time mark in the journal. A raid never resolves during an outage.
- Every reply carries `now`. Clients show `ends_at − now` and do their own
  countdown.

```c
typedef struct SamClock {          /* the mapping is pure; the driver supplies */
  SamTime  base_world;             /*   the monotonic reading                  */
  int64_t  base_mono_ns;
  uint32_t scale_num, scale_den;
  bool     paused;
} SamClock;

SamTime samclock_world(const SamClock *c, int64_t mono_ns);
```

### 6.8 Dice: keyed rolls

This game wants dice. It does not want one global stream of them.

```c
/* samdata — stateless: the same key always gives the same number */
uint64_t samrng_keyed(uint64_t seed, uint64_t stream, uint64_t subject,
                      uint64_t counter);

/* samworld — every roll names its purpose and its subject */
uint32_t samworld_roll(SamWorld *w, uint32_t purpose, SamWorldActor *a,
                       uint32_t sides) {
  uint64_t r = samrng_keyed(w->seed, purpose, a->id, a->rolls++);
  return (uint32_t)(((r >> 32) * sides) >> 32);
}
```

|                            | One global stream (samvillage)                  | Keyed rolls                                                              |
| -------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------ |
| Saving it                  | Store a draw counter; replay the stream on load | Nothing to save — the counters are already in the Actors                 |
| One House changes its plan | Every later roll in the world shifts            | Only rolls it causally touches change                                    |
| Good for                   | Simplicity                                      | **Experiments.** Change one thing; everything unaffected stays identical |

The last row is the one your statistics-course idea needs. With a shared stream,
any tweak reshuffles all luck and the comparison drowns in noise. With keyed
rolls, an A/B pair differs only where the change reaches.

### 6.9 How this maps to your two-tier idea

| Your Biofabrica note                                                         | Here                                                                                                           |
| ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| "Calculations on a 10-second basis, lazily evaluated when players look"      | Flows for steady change, due entries for completions. No ten-second grid — the values are exact at any instant |
| "Global events on a 15-minute tick"                                          | Calendar due entries: one per period for the whole world                                                       |
| "If an area needs to be depleted we need an idea of what is happening in it" | Depletion is exact. Every gather is a due event against the node's flow                                        |
| "As the world gets bigger we can just check less frequently"                 | Never needed                                                                                                   |

## 7. Events and feeds

```http
GET /v1/events?since=99120
```

```json
{ "now": 74150000, "cursor": 99123, "events": [
  { "id": 99121, "t": 74141000, "type": "action.done", "actor": "a-104",
    "data": { "do": "mine", "item": "iron_ore", "qty": 1,
              "xp": { "mining": 25 } },
    "text": "Brynn Ashdown pries a lump of iron ore from the seam." },
  { "id": 99122, "t": 74141000, "type": "skill.level", "actor": "a-104",
    "data": { "skill": "mining", "level": 7 },
    "text": "Brynn Ashdown is now a level 7 miner." } ] }
```

- **Stamped with when it happened**, not when it was computed. An hour-old event
  found by a late reader says an hour ago.
- **A monotonic cursor**, as in samvillage. Poll with it today; the same objects
  arrive over a stream later, and `Last-Event-ID` is the cursor.
- **Audience.** Each event is public or belongs to one Family. A feed is the
  events you may see.
- **`text`** is rendered by the server from the pack's template for that event
  type. Every client shows the same prose; a bot ignores it; an LLM-driven bot
  reads it.
- **Events are the delta; views are the resync.** A client that kept up with its
  feed never needs to refetch a view.

## 8. Views, revisions, and "when should I look again?"

```json
{ "rev": 415, "now": 74175000, "next_due": 74201000,
  "family": { "id": "f-12", "name": "Ashdown", "favor": 40, "home": "s-0.0.0" },
  "actors": [
    { "id": "a-104", "name": "Brynn", "site": "s-0.0.6", "state": "busy",
      "action": { "do": "mine", "at": "n-0.0.6.0", "unit": 3, "of": 10,
                  "started_at": 74171000, "ends_at": 74201000 },
      "queue": [ { "do": "travel", "to": "s-0.0.0" },
                 { "do": "deposit", "into": "b-3", "what": "all" } ],
      "pack": { "used": 6, "cap": 30 } } ] }
```

Three rules make views cheap:

1. **Views hold moments, never countdowns.** `ends_at`, not "90 seconds left". A
   flow is sent as value, rate and moment. So a view does not change just
   because time passed.
2. **Each Family has a revision**, bumped whenever anything it can see changes.
   The revision is the `ETag`. A poll with `If-None-Match` on an unchanged House
   costs one comparison and returns `304`.
3. **Every view carries `next_due`** — the earliest moment anything of yours
   will change by itself. A well-behaved bot sleeps until then.

The countdown is the client's job. It is the same laziness, pushed one more step
toward the edge.

`GET /v1/actors/{id}/options` answers "what can this Actor do, here, now?" —
each option with its target, duration, cost and, if unavailable, the reason. The
engine computes it by asking every mechanic to `enumerate`. The web client
renders it as buttons; a simple bot picks from it; a genome's output is an index
into it.

## 9. Worked example: two Houses, one seam

A seam holds five lumps and regrows slowly — one every two hours, so not at all
on the scale of this example. Mining takes thirty seconds a lump.

- 0:00 — Brynn (House Ashdown) is ordered to mine ×3.
- 0:10 — Odo (House Brackwater) is ordered to mine ×3 at the same seam.
- Then nobody looks at anything for an hour.

At 1:00:00 someone — anyone, about anything — touches the world, and
`advance_to` drains the queue in time order:

| World time | Due entry   | What happens                                           | Seam |
| ---------- | ----------- | ------------------------------------------------------ | ---- |
| 0:00       | *(command)* | Brynn starts unit 1: takes a lump                      | 4    |
| 0:10       | *(command)* | Odo starts unit 1: takes a lump                        | 3    |
| 0:30       | Brynn done  | +1 ore, +25 XP. Starts unit 2: takes a lump            | 2    |
| 0:40       | Odo done    | +1 ore. Starts unit 2: takes a lump                    | 1    |
| 1:00       | Brynn done  | +1 ore. Starts unit 3: takes the last lump             | 0    |
| 1:10       | Odo done    | +1 ore. Unit 3 cannot start: `NODE_EMPTY`. Odo is idle | 0    |
| 1:30       | Brynn done  | +1 ore. Brynn is idle                                  | 0    |

**Brynn has three, Odo has two** — the same whoever looked, whenever, in
whatever order. The events in both feeds are stamped 0:30 to 1:30, not 1:00:00.

Now the same hour under *resolve on observe*. Odo's owner checks in at two
minutes; Odo's three units settle against a seam nobody else has touched, and
Odo walks away with three. Brynn's owner checks in at the hour and finds two
lumps where there should have been three — beaten by someone who started later.
Had the owners looked in the other order, the result would flip. That is the bug
the due-queue removes.

## 10. Determinism: the rules and the tests

**Rules**

| Rule                                              | Because                                               |
| ------------------------------------------------- | ----------------------------------------------------- |
| Integer arithmetic only in the engine             | Floats differ across compilers, flags and WebAssembly |
| No wall clock inside the engine                   | Time arrives as an argument                           |
| Randomness only through keyed rolls               | No hidden stream state                                |
| Due entries ordered by `(at, seq)`                | Ties need a rule                                      |
| Subscribers and modules run in registration order | Dispatch order is behaviour                           |
| Never iterate a hash map to produce effects       | Its order depends on history; iterate vectors         |
| `apply` cannot fail                               | The journal holds only what happened                  |

**Tests**

| CTest                | Proves                                                                                                                    |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `samworld_golden`    | A fixed seed and command script reach a committed world hash. `--bless` is where a rules change gets consciously accepted |
| `samworld_schedule`  | The same script under a thousand random advance-and-read schedules reaches one hash. This is Law 2, executable            |
| `samworld_replay`    | A recorded journal, replayed from every snapshot, matches every hash checkpoint                                           |
| `samworld_clone`     | Snapshot, restore, continue equals never having stopped                                                                   |
| `samworld_arch_test` | The purity rules of the architecture document                                                                             |
| `samtime_perf`       | Due events per second; a regression guard                                                                                 |

## 11. Bots: decision points and two hosts

A bot's brain is a **policy**: a function from what a Family can see to
commands. It is written once and hosted two ways.

| Host           | How it touches the world    | Used for                                                |
| -------------- | --------------------------- | ------------------------------------------------------- |
| **In-process** | Direct engine calls         | The lab: speed, experiments, breeding                   |
| **HTTP**       | The public API, via libcurl | `samworld-bot`: proves the API is playable from outside |

Both hand the policy the same views and the same options, so a bot cannot know
more than a person.

In the lab there is no polling and no wall clock. The world jumps from one
moment someone needs orders to the next:

```c
while (samworld_now(w) < end) {
  SamWorldDecision d;
  if (!samworld_next_decision(w, &d)) break;   /* advance until an Actor is idle */
  policy->decide(brain, &d.view, &d.options, &orders);
  for (uint32_t i = 0; i < orders.n; i++)
    samworld_submit(w, &orders.items[i], &reply);
}
```

**The genetic guiding star** — out of the slice, but shaping it, the way OpenCL
translation shapes samvm:

| Invariant kept now                                 | So that later                                                                           |
| -------------------------------------------------- | --------------------------------------------------------------------------------------- |
| The engine runs headless and deterministic         | A fitness evaluation is a function call                                                 |
| `samworld_next_decision` exists                    | An episode steps by decisions, not by ticks                                             |
| The engine enumerates each Actor's options         | A genome's action is an index into a list — `argmax` over outputs, as in the gem hunter |
| Views are fixed structs of integers                | Features for `samvm`'s float registers are a flat copy                                  |
| One policy is called once per Actor needing orders | One genome runs a whole Family — division of labour has to emerge from shared code      |
| A world restores from a snapshot in memory         | Every genome faces the same start                                                       |
| Keyed rolls                                        | Two genomes' luck differs only where their choices do                                   |

`samvm_run` clears its working registers on each call, so a genome has no memory
between decisions. The features can carry what it needs — each Actor's previous
action, for instance.

LLM-driven bots need nothing extra: the HTTP API, with the `text` fields as
their reading material and the rate limiter as their leash.

## 12. Later, deliberately

| Not now                                                      | The seam it will use                                                                                                                     |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------- |
| **Standing orders and scripts** ("keep ten ingots in store") | A module that subscribes to `actor.idle` and submits commands on the Family's behalf, journaled as system commands so replay holds       |
| **Exact wake-ups for push**                                  | `samworld_next_due()` already exists; arm a timer for it when someone is subscribed                                                      |
| **More than one core**                                       | A due-queue per region. Interactions are local to sites, so regions are natural partitions. Not needed at any size this project will see |
| **Strict durability** (reply only after the journal commits) | Needs samhttp's detached responses                                                                                                       |
