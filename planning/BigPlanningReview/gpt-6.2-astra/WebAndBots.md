# Web and bot interfaces — many people, one household

**Make the household the unit of observation and the person the unit of control.** A single household read should tell a human or bot who needs attention, what can be afforded, where everyone is and what just happened. Nobody should have to poll one endpoint per settler to find the idle one.

## The human interface

Use `samhtml` for a same-origin, server-rendered page with ordinary forms. The top strip is an expedition selector, home stores and marks; below it are person links that look like tabs and also work as normal URLs. `/play/w1/people/p1` can be bookmarked or opened beside `/play/w1/people/p2`. The main panel shows location, carried goods, skill progress, running action, queued suffix and available actions. A persistent household column shows stock **available / reserved**, unlocked designs and recent deliveries. See the [worked screen](FirstExpedition.md).

For the mining trip, pick **Go to Greybank → Mine ironstone → Return to landing**, then **Queue these 3 steps**. The form serializes a bounded list and carries a CSRF token, idempotency key and displayed queue revision. With JavaScript off, three numbered rows/selects and one submit do the same thing. The handler invokes the same command service as the JSON route and redirects after success (`303`, post/redirect/get). On a stale edit it shows the current plan alongside the unsent proposal; the player decides whether to reapply. Never auto-submit an old plan against a changed queue.

- **Time:** a small script can display `max(0, due_s − estimated_server_s)` using the server's `as_of_s` and local monotonic elapsed time. At zero show “checking result” until a committed observation arrives. Render absolute season due times and a refresh link with scripts off. A countdown reaching zero isn't permission to credit cargo.
- **Attention:** show `waiting`, `busy until …` or `plan stopped: missing 2 ironstone`. A paused tab returning to focus refreshes first. Show future steps with estimates, not promised completion timestamps; someone else may claim their materials. Allow staging future travel/work combinations even when the predicted next step is impossible *right now*.
- **Cadence:** one household-level refresh on focus, after an edit, or near the earliest displayed deadline. While visible, poll the event feed every few seconds for other actors' updates; back off when idle/hidden and on `Retry-After`. The client can draw countdowns locally between polls. Native links, keyboard-operable forms and text statuses are sufficient for the whole slice.
- **Watching:** a separate read-only `/watch/w1` timeline shows an opt-in household's public milestones and aggregate shipments. An archived read-only page can expand a public milestone to its permitted causes, or put two opted-in holds side by side. Personal credentials, private queues and undiscovered sites aren't spectator content.

## Small proposed wire surface

All live scenic/game reads marked “household” below require a session or scoped key. The service infers the household from membership. Public catalog/view payloads use stable labels/IDs and an API version. Avoid returning raw database rows.

| Request | Result / intended caller |
|---|---|
| `POST /auth/exchange`, `POST /auth/logout`, `POST /account/keys`, `POST /account/keys/:id/revoke` | Private sign-in and key management, separate from simulation orders. See [identity design](AccountsAndStorage.md). |
| `GET /v1/worlds`, `POST /v1/worlds/:w/join` | Choose an open expedition or revisit an archive; create the account's one household for that season. Join accepts an idempotency key. |
| `GET /v1/worlds/:w/household` | Consistent household snapshot: `as_of_s`, roster (with queue revisions, remaining work/cargo and skill XP), stocks/claims, known map and routes, tech, buildings, marks, recent facts and matching private cursor. Good first bot read. |
| `GET /v1/worlds/:w/rules` | Player-visible, versioned vocabulary: known work/recipe IDs, costs, durations/skill modifiers, tech gates and reason descriptions. Hypothetical hidden sites/secret placements are not in this catalog view. |
| `POST /v1/worlds/:w/commands` | Submit a bounded typed edit with `Idempotency-Key`. Same ownership, revision and budget rules for humans and bots. [Full request and receipt](TimeAndCommands.md). |
| `GET /v1/worlds/:w/events?after=<cursor>&limit=50` | Ordered private household facts; response supplies `items`, `next_cursor`, `has_more`. The cursor is scoped to the world and audience. A stale retained window gives `410 CURSOR_EXPIRED` and a snapshot URL. |
| `GET /v1/worlds/:w/chronicle?after=<cursor>` | Separate, public-projected feed behind the watch page. It contains public milestones, not a client-side filter over everyone's private events. |

Example household fact, projected for the owner (values illustrative):

```json
{
  "cursor": "h.Abc19", "at_s": "240", "kind": "cargo_banked",
  "person": "p1", "site": "s0", "item": "item.ironstone", "qty": 2,
  "cause": {"command_id": "c17", "step_id": "a42"},
  "text": "Mara returned from Greybank with 2 ironstone."
}
```

`kind` and typed quantities are for bots; `text` is for people. **Reconnect rule:** take a snapshot and its high-water cursor together; request facts strictly after it, page until caught up, advance a saved feed cursor only after consuming a page. New facts invalidate the displayed snapshot. If also browsing history, keep its last-consumed cursor separate from any newer UI snapshot. An expired cursor triggers a fresh snapshot, not reissuing orders. Polling is sufficient because a missed delivery notification is recoverable; if live audiences later justify SSE/WebSocket support, stream these same projections/cursors and keep HTTP command semantics.

## A bot today and a genetic program later

A first external bot needs only four decisions: if a job is running leave it alone; if a plan stopped inspect its reason; if the hold has the inputs for the policy's chosen use of iron allocate a free person there; otherwise queue a needed round trip. Start with one miner and one woodworker. It discovers its IDs, known paths, costs and queue revisions just like the browser. Log the observation cursor, chosen action and receipt. If a network reply is lost, retry the same key; on a stale revision read again and make a **new** decision. Shared command budgets count per account/season, not per key or browser tab.

The **lab** is what makes experiments cheap: give a policy the same redacted observation and command semantics in-process; jump the virtual clock to the next decision point. Never pass the policy a world pointer, the seed, future events or the ability to fast-forward the public server. Replay a scripted schedule through both adapters and compare visible outcomes to keep the shortcut honest.

Later, a `SamGenaiEnvironment` adapter can reset to a pinned scenario, encode a bounded household observation (e.g. skill, stock, travel cost, time left, slots), map outputs to a legal actor/verb/target proposal, and score at a fixed horizon. Fix the observation/action encoding version, per-episode decision budget and training/test seed split. An invalid proposal spends a decision opportunity; it cannot yield unlimited retries at the same simulated instant. Score actual home supply and, if relevant, survivors/hold condition; log these measures separately so one scalar doesn't hide a population of failed holds. First compare hand-written policies in [the paired-seed experiment](FirstExpedition.md). Genetics is a later **client of the simulation**, not a requirement for the world to run.
