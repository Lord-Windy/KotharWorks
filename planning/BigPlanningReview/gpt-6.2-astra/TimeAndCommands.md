# Time and commands — what “mine for three minutes” means

**Store the finish time, not 180 countdown updates.** A person has at most one
running step and three pending steps. A step has a stable ID even while pending.
A running step records its start, due time, locked duration/effect inputs, and
any claims on goods, cargo or work slots. Waiting with an empty queue schedules
nothing.

## The clock and the order of things

Use nonnegative integer **season seconds**. The live service maps elapsed UTC
since the season's opening to this scale, including time spent offline. Clamp
against the last applied time so a backwards clock adjustment cannot undo work;
use a monotonic clock to sleep until the next deadline and recheck on wake.
Persist the time of committed changes. A virtual clock provides exactly the same
values to the lab. Clients have no command to set either clock. Time, revisions,
counters and cursors travel as strings when they could exceed JSON's exact
integer range.

`advance(T)` selects the earliest due time `d ≤ T`, applies **everything at
`d`**, and repeats. Newly started queued steps start at `d`, even if the server
is waking up at `T` hours later. At one timestamp use this order:

1. **Completions**, by ascending numeric job ID. Apply each step's transfers and
   consequences together (arrival at home unloads cargo, which can unlock
   technology). An underway trip retains the duration locked at its start.
2. **Season closure**, if this is the announced cutoff: archive after the
   completions, release outstanding claims and cancel unfinished work. No new
   starts at cutoff. Otherwise process scheduled world effects, e.g. a future
   warned raid, in stable ID order.
3. **Queue handoffs**, after all those effects. For people just freed, in
   ascending numeric person ID, attempt their next head. Thus timber delivered
   at this second can be used by a builder starting this second. An empty or
   held queue does nothing.
4. **New external commands** at this second, in the sequence assigned by the
   single world writer. Each command may immediately start one idle person's
   head. Once admitted, this order is in the input record; replay doesn't try to
   reconstruct network arrival order. Previously scheduled work wins ties with a
   new request.

Durations are always at least one second; instant bookkeeping such as XP or
unloading is part of its causing transition, never another self-scheduling job.
No hash-map iteration, worker-thread timing or browser polling decides who gets
a scarce input. For large backlogs advance in bounded **whole-timestamp**
batches, committing progress between batches. If a request can't reach its
target time within budget, return temporary `503 CATCHING_UP` with
`Retry-After`; the background wake-up keeps progressing. Never run a fresh
command against yesterday's stock because catching up was expensive.

**Offline example:** at `t=0`, queue a 30 s walk, 180 s mining, 30 s return.
Shut the server at `t=70`; restart at `t=500`. The saved job is due at `210`.
Its ore and XP appear at `210`, the return starts at `210`, and the ore enters
the hold at `240`. The person then waited for 260 seconds. Reopening the page at
`200`, `241` or `500` can't change that record. A min-heap makes the live
wake-up cheap; durable due rows allow the heap to be rebuilt. No running jobs or
world deadlines means no simulation wake-ups.

## A request a bot could actually send

For the fixture in [the first expedition](FirstExpedition.md), `s0` is the
landing and `s1` is Greybank. The household comes from the credential, never a
claimed owner field. Starting queue revision is `0`.

```http
POST /v1/worlds/w1/commands
Authorization: Bearer <household-command-key>
Idempotency-Key: b3722996-dd31-4ddb-bf68-8d064b4eeb91
Content-Type: application/json

{"kind":"queue.append","person":"p1","expected_queue_rev":"0","steps":[{"verb":"travel","to":"s1"},{"verb":"gather","at":"s1","rule":"gather.ironstone"},{"verb":"travel","to":"s0"}]}
```

```json
{
  "command_id": "c17", "disposition": "accepted", "at_s": "0",
  "person": "p1", "queue_rev": "1", "steps": ["a40", "a41", "a42"],
  "running": {"step": "a40", "due_s": "30"}
}
```

`201` means the order and receipt are durable. It promises **admission**, not
three future successes. `a40` is running; `a41` and `a42` are pending. The
receipt is historical, not a live queue view. A lost response can be retried
with the same key and same semantic body: the authorized caller gets the
original status/body, even if the work has since finished. A fresh observation
tells the caller what is true now.

### Command and work vocabulary

| Input                                           | Proposed rule                                                                                                                                                                                                                                       |
| ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `queue.append`                                  | Append 1–3 steps atomically. Reject the whole edit if it would put more than three not-yet-started steps in the pending list before promotion. An idle unheld person tries the first head immediately. Appending to a held list doesn't release it. |
| `queue.replace_pending` / `queue.clear_pending` | Replace the pending suffix (1–3 new steps) or empty it. Replacement clears a hold and tries the new head if idle. Neither operation interrupts a running step or returns its claims.                                                                |
| `queue.resume`                                  | Release a held suffix and try its first remaining step. The failed step was removed; to retry it, explicitly submit a replacement including it. No automatic retry/poll loop.                                                                       |
| `travel(to)` / `gather(at, rule)`               | Travel one known edge. On arrival at home, bank carried goods. Gather only at the person's actual site with the right skill, empty load space, an available household work slot and a nonempty allotment.                                           |
| `build(rule)` / `ship(rule)` / `recruit(rule)`  | Timed steps at the landing. Claim materials, a contract's cargo, or marks and a bunk at start. Activate the building, award marks, or create an idle person **only on completion**. A building project claims its unique building slot too.         |

**Admission checks** cover authenticated membership, actor ownership/visibility,
known verb and valid prototype/target shape, list bounds, and expected revision.
A queued return trip is legal to *submit* from home: location, stock, tech,
proficiency and slots are rechecked **at start**, when earlier steps may have
changed them. Freeze duration and claims then. At finish, consume the claims,
add the outputs and XP, release slots and emit facts in one transition. An
ordinary queue edit can't cancel running work in v0.

**Start failure:** emit `step_failed(step, reason, missing)` once, consume no
time/material, remove that head, and hold the remaining suffix for its owner.
With no suffix, show the reason and go idle. Example: if a gather failed, a
queued return shouldn't silently haul an empty pack. This rule applies even if
failure occurs immediately in the command that admitted the steps. The receipt
still reports admission; the feed reports the outcome. The UI can offer *replace
plan* or *continue without that step*.

**Concurrency:** edits carry the person's `expected_queue_rev`. Increment it
once per admitted edit that changes the queue and once per due timestamp that
changes it; the start/finish/promotion internal to one such boundary counts
once. Revisions don't change because another person's countdown was rendered. A
stale tab receives `409 STALE_QUEUE` with the current revision; reload before
making a new decision. Deduplicate an already committed request **before**
comparing revisions. Distinct people can both have valid revisions yet want the
same stock: their start-time claims are what resolve that.

## A conflict that matters to the game

At `t=240` Mara has returned the first 2 ironstone. Kit and Mara are both at the
hold. One tab queues Kit to build the shed; another queues Mara to ship the same
iron. If the build is admitted first, it reserves 2 iron and 3 timber. The ship
fails at start with `STOCK_UNAVAILABLE` and the missing quantity; it cannot
promise marks. If the ship wins, it reserves the iron, and the build fails
instead. Two `201` receipts can therefore coexist, but **never a new shed and
marks from the same ore**. A raid later can take only unreserved, unsheltered
stores. Resource conservation is enforced in the engine and transaction, not by
disabling a button.

| Failure a client sees                                                                                             | What the client should do                                                                                              |
| ----------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `400 BAD_REQUEST`, `422 UNKNOWN_VERB`                                                                             | Fix the malformed envelope or unsupported vocabulary.                                                                  |
| `401` invalid/expired credential, `403` insufficient scope, `404` actor/target outside the visible set            | Sign in, use the right scope, or act on an ID in the observation. Hidden and nonexistent actors are indistinguishable. |
| `409 STALE_QUEUE`, `409 QUEUE_FULL`                                                                               | Refresh; make a revised command with a new idempotency key.                                                            |
| `409 KEY_REUSED`                                                                                                  | This key identifies another normalized command in this world/account. Do not reinterpret its receipt.                  |
| `409 WORLD_CLOSED`                                                                                                | A new command cannot mutate this archived expedition. An authorized retry of an existing receipt can still read it.    |
| `429` / `503 CATCHING_UP`                                                                                         | Back off, then retry the **same** request/key. These transient replies are not admissions.                             |
| Feed: `step_failed` with `WRONG_SITE`, `STOCK_UNAVAILABLE`, `SITE_BUSY`, `CARGO_FULL`, `TECH_LOCKED` or `NO_BUNK` | An admitted plan could not start. Show the missing prerequisite and the held suffix. Replan explicitly.                |

**Later party extension:** one command names the party members and their
revisions. Validate common household, co-location and availability together;
claim every member or none. The party has one expedition deadline, not one
independent combat outcome per person. Deaths close the relevant personal queues
and release claims in that resolution. A hold raid is a different scheduled
effect: it reads a standing defence plan and cannot produce a person-death
effect. Both extensions use the ordering and chronicle above.
