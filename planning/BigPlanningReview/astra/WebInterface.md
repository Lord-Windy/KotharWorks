# Web interface — a desk for managing a household

Open one household page. Its header tells you who needs an order and what is happening at home. Each person's name is a normal link, so you can open Mara and Ivo in separate browser tabs or move between them on a phone. Closing either tab has no effect on their orders.

## What the first page looks like

This is Run A at simulation time 2:15, after Ivo has started construction:

```text
IRONWOOD WILDS  /  Alder-01                         Elapsed 02:15
Household: Ash    People: 2    Marks: 0    Next passage: 3 marks

People      Mara · mining (1:30)      [Ivo · building (0:45)]

IVO  ·  Landing
Now         Build packing shed         finishes 03:00
Afterwards  No orders — Ivo will wait.                 [Plan an order]

Hold        Packing shed under construction
Store       Timber: 0 free (2 reserved for this shed)    Ore: 0
Tech        Timber Framing ✓    First 2 timber reached Landing at 02:00
Home        Timber writ: 2 timber → 3 marks. None free to ship.

Journal     02:00  Ivo returned with 2 timber.
            02:00  Your household learned Timber Framing.
            02:00  Ivo set aside those 2 timber for the packing shed.
```

**Plan an order** lists known places, travel times, inputs, yields, required location and whom the skill trains. The route costs and rules come from the observation/catalog. You can plan work that becomes legal after a pending journey; “requires Slate Scar when this starts” is different from “unknown site.” Show the running job separately from the editable tail. A blocked job gets a reason, a **Retry** control and a **Replace remaining orders** form. Don't quietly reorder the queue on the person's behalf.

The **Hold** view groups stock into carried, available at home and reserved. The **Tech** view lists the next reachable capabilities and the actual missing prerequisite, not just a grey icon. The **Relief** view shows offers, total earned, current balance and the recruit cost. Later the expedition planner can show a party's absence and risk of death; the hold planner edits standing priorities and previews which goods would be exposed in a raid. Those are different decisions and deserve different screens.

## Small surface shared with bots

Paths below are proposed; `:s` denotes a season ID. HTML handlers and JSON handlers call the same application service. Both receive only `samwilds_observe` projections.

| Request | Response / use |
|---|---|
| `GET /v1/seasons/:s/me` | A consistent household snapshot: people and revisions, locations, queues, stock, holds, offers, known places, capability reasons, `as_of`, server time and a matching `event_cursor`. Sufficient for a first bot. |
| `GET /v1/seasons/:s/people/:id` | Member-only detail for a bookmarkable tab: carried goods, skill progress, current deadline, up to three pending orders. |
| `GET /v1/seasons/:s/rules` | Versioned, player-visible item, job and unlock definitions for interpreting the view. Visible rules do not grant access to another household or unrevealed map data. |
| `POST /v1/seasons/:s/commands` | The [typed command envelope and receipt](CommandsAndTime.md). Scope, actor ownership, revisions, costs and limits are checked on the server. |
| `GET /v1/seasons/:s/events?after=<cursor>&limit=50` | Ordered events visible to this household, each with time, subject, cause and structured data; a next cursor and `has_more`. Empty page is normal. |
| `GET /play/:s/people/:id` and `GET /chronicles/:s/:slug` | Server-rendered member page and explicitly published read-only chronicle. The chronicle is a different projection with its own feed cursor. |

**Browser.** Render escaped text with `samhtml`; ordinary forms post a CSRF value, an intent ID, the displayed revision and the user's choices. A successful form submission redirects back to the person. Render conflicts inline with current state rather than overwriting an order from another tab. A small optional script interpolates deadlines, refreshes the household feed and announces actual completions to assistive technology. Navigation, ordering and manual refresh still work with scripts disabled. Never mark a job complete merely because the display hit `00:00`.

**Bot.** Fetch `me`, pick a household goal, submit the same `queue.append` or `household.recruit` as the browser, then wait until the next interesting deadline or poll interval. On `409 STALE_QUEUE`, reread before deciding. The external client uses its scoped bearer token; it has neither an engine link nor a fast-forward endpoint. The offline lab may skip real waiting but its policy receives the same fields and command grammar. Keep simulation speed a property of the host, not a privilege of a well-connected bot.

**Reconnect.** Read a snapshot with cursor C, then request events *after C*. A change committed between those requests is included. Cursors are opaque, bound to season and audience, and advance across pages without exposing private events or their payloads. Retrying a page can repeat an event; readers deduplicate by event ID. On expired cursor (`410 CURSOR_EXPIRED`), get a fresh snapshot/cursor pair. A tab on an archived season shows its final state and a link to the new expedition. It never resubmits its draft to a different season.

**Transport.** Start with HTTPS at a reverse proxy, regular HTTP behind it and short polls (e.g. 5 s while active; back off when idle/hidden). One household-level feed can update all the person panes. Respect `Retry-After`. The current `samhttp` writes a finite response and closes the connection. If measurements justify WebSocket or SSE later, the new adapter can notify clients that a cursor advanced; snapshots, command receipts and reconnects still work the same way.

## The page worth showing someone else

Let the owner publish a household chronicle by choice. An example entry is **“05:00 — Linn joined. Passage paid by the first packed-ore shipment.”** Follow its cause back to Ivo's shed and the first trip to Alder Coppice. Show elapsed time, roles and settled outcomes; omit account identifiers, tokens, unpublished household details and pending orders. Default private. Turning off publication closes the live public view; an intentionally exported story is a separate artifact.

For an experiment, put two published timelines side by side at their **first different order**, then jump to the first different outcome. That's a useful post or video even if the only artwork is three place names and a row of timestamps.
