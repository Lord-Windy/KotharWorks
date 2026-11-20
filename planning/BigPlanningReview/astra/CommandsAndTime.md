# Commands and time — what “mine for three minutes” means

An **order** is an instruction for a person, a **command** is a client's request to change a queue or the household, and an **event** is a fact the world has committed. Accepting a mining order promises that the person will try it when it reaches the head. It does not promise ore. This distinction lets you order “walk there, mine, walk back” while still at home.

## One command over the wire

At the opening of the [first expedition](FirstExpedition.md), clicking three choices in Mara's tab sends the equivalent of:

```http
POST /v1/seasons/alder-01/commands
Content-Type: application/json
Authorization: Bearer <household-token>

{"command_id":"demo-mara-01","type":"queue.append","person":"mara","if_queue_rev":0,"orders":[{"kind":"travel","to":"scar"},{"kind":"mine","site":"scar"},{"kind":"travel","to":"landing"}]}
```

An illustrative **200** after commit:

```json
{"command_id":"demo-mara-01","result":"accepted","at":0,"queue_rev":1,"running":{"kind":"travel","due_at":45},"pending":2}
```

The human sees “Walking to Slate Scar · 45 s; then mine, then return.” A bot sees typed fields. Neither provides a duration, reward, household owner or authority to run the clock. IDs here are mnemonic for the example; ordinary clients generate a unique `command_id` per intent and keep it on retry. World times are integer seconds since opening; entity IDs and cursors travel as strings.

## The queue contract

| Operation | Effect |
|---|---|
| `queue.append` | Atomically append a list. One head (running or blocked) plus at most three waiting. An idle worker tries the first order immediately. Bad shape, unknown verb, inaccessible target or overflow rejects the whole append. |
| `queue.replace` | Replace all **unstarted** work, including a blocked head. A running job keeps its finish time and reservations. Replacing with `[]` clears pending work. |
| `queue.resume` | Explicitly retry a blocked head against current conditions. No timer retries it and another player's page view cannot resume it. |
| `household.recruit` | Immediate account-authorized household decision; validate roster revision, room and current marks; debit and create one idle person together. |

Queue edits require `if_queue_rev` from that person's view. Increment the revision on a committed transition changing their queue (including automatic start, finish or block). Different people's tabs do not conflict just because they share a house. Recruitment instead requires `if_roster_rev`; spending or acquiring goods is always checked against current shared state. A conflict returns the relevant revision so a client can refresh before deciding again.

**Three checks happen at different times:**

1. **Admission.** Authenticate the account/token, check season and household ownership, syntax, known order/target types, queue revision and capacity. Don't require the person to be at the future destination at enqueue time. Look up the command's receipt after authorization and **before** checking revisions.
2. **Start.** Is the person alive and at the right place? Are the requisite tool, technology, stock and output space available? If so, reserve what the job needs, fix the duration from the actor's skill **now**, store `started_at` and `due_at`, and emit `action.started`. Queued work reserves nothing. A failure here leaves the head **blocked**, emits `action.blocked` with a reason such as `MISSING_INPUTS`, and pauses the tail. The UI can explain the problem, retry, or replace. This also applies if an accepted append tries to start immediately.
3. **Finish.** Consume the reservation, produce the goods or move the person, award XP, evaluate newly satisfied unlocks and remove the job, once. Arrival at Landing transfers carried goods to the store. The next action starts at that *logical* finish time if it can; time spent waiting for the process to wake is not added to its duration. In the slice, started jobs run to completion unless a season closes. Later death away from home would explicitly release that person's claims and cancel their queue.

For example, if two people try to build with the last two timber, the first legal start holds them. The second head blocks; stock never goes negative. Another packing shed would also have to satisfy the building's one-per-hold rule (reserve that slot on start). The journal identifies which job holds the material or slot.

## The clock algorithm

Live time is `max(last_committed_second, floor(UTC_now − season_open_UTC))`; use a monotonic timer for sleeping and recheck the target on wake. The lab gives the engine an explicit integer target instead. Only inputs and due work change game state. There is no once-a-second save and no “tick all people” loop.

For a target second **T**, drain the season's due entries at or before T in `(second, phase, stable_sequence)` order. Fix the phases as part of the rules version:

| At the same second | Ordering rule |
|---|---|
| 0. Finish existing work | Finish **all** due jobs, including unloading and crediting, before trying the next queue heads. Within the phase, use the persisted scheduling sequence. |
| 1. Apply due world events | A scheduled relief deadline or later NPC raid uses the post-completion state. Thus a shed finished on the deadline really does count. If the season closes now, close is first in this phase and suppresses remaining events and starts. |
| 2. Try waiting heads made ready by completions | Stable schedule sequence decides contention for newly available goods. Blocked heads stay blocked. Apply immediate consequences in the same transition. |
| 3. Admit new external commands | Serialize by the writer-assigned input order at that second. An accepted command can start an idle person's first timed job immediately. A job's duration must be positive, so it cannot add a finish into an earlier phase of the same second. |

The writer timestamps an order when it takes it for adjudication; client timestamps have no effect. The input tape records that second and tie order. The live writer wakes on the earliest due item even with no clients. On a read or write it also makes sure it has settled to the requested live horizon before answering with current state. Looking at a region never consumes random numbers or inserts a gameplay event. A browser may interpolate `due_at − now`; only a server event says the work actually finished.

**Walk-away example.** Mara starts mining at 45, due 225; an already queued return is due at 270 once started. If the process stops at 46 and restarts at 300, it resolves mining **at 225**, starts the return **at 225**, unloads **at 270**, then finds no more work. It does not give her another 30 seconds' ore or start an unrequested trip. A same-second client order at 270 sees the unloaded ore.

Recover from long downtime in bounded transactions, yielding between batches. Persist the full ordered due keys, including phase-2 starts if the writer yields between phases. While behind, advertise the committed horizon and `catching_up`; reject new game orders temporarily with **503** and a retry hint rather than allowing an order to overtake earlier work. Do not drop overdue entries, cap earned progress silently or write “still idle” events. A scheduled season close has its own cutoff as described in [storage](AccountsAndStorage.md).

## Retries and failures that a client can act on

| Situation | Observable result |
|---|---|
| Same account, season, command ID and normalized payload | Return the stored status and receipt. If the connection broke after commit, the retry still cannot create a second job or person. Same ID with different contents → `409 COMMAND_ID_REUSED`. |
| Broken JSON or unsupported fields/verb | `400 MALFORMED` or `422 UNKNOWN_ORDER`. No queue changes. |
| Not signed in / insufficient token scope / another household's entity | `401` / `403` / `404`, respectively. An unauthorized caller cannot retrieve somebody else's receipt. |
| Stale revision, full queue, or insufficient marks for an immediate recruit | `409` with a stable reason (`STALE_QUEUE`, `QUEUE_FULL`, `INSUFFICIENT_MARKS`, etc.). Fetch the current view; a genuinely revised intent gets a **new** ID. |
| Inputs missing when a valid queued job reaches the head | Earlier receipt stays accepted. An `action.blocked` event and queue view carry job ID, reason and missing quantity. |
| Rate/busy limit | `429` for a principal's limit or `503` for temporary unavailability. Retry the same intent with the same ID. A closed season rejects a new command with `409 SEASON_CLOSED`. |

The input tape (season genesis, adjudicated household commands and external system inputs such as a season-close announcement, all with their times/order) and the pinned rules, generator and pack suffice to re-drive a run to a declared horizon. Outcomes are retained too: compare the ordered domain events and final state, excluding wall-time logging, session IDs and transport metadata. In particular, keep time-ordering tests for completion versus new order, two-tab retries, and continuous versus delayed advance. These are rules people will notice if they change.
