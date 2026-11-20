# Ironwood Wilds — Web Interface (IronwoodWildsWebInterface.md)

> The browser client: what it is, what it is not, and the transport
> ladder. The rule from the start: the web UI is **just another API
> client** — it gets no endpoints a bot doesn't get.
> Companion: [Commands](IronwoodWildsCommandsAndSimulation.md) §2, §7 ·
> [Architecture](IronwoodWildsServerArchitecture.md) §7, §9.

## 1. Shape: server-rendered core + one small JS island

The repo has three working web shapes to draw on: samdashboard (fully
server-rendered HTML forms via samhtml), samgenai-studio (server pages +
a small inline-JS polling island — "the repo's first JavaScript"), and
samgrognard-prototype (WASM, canvas, glue.js). For Ironwood Wilds:

- **Server-rendered HTML** (samhtml) for: login, household dashboard,
  person tabs, rankings, admin, chronicle, spectate map. Works with JS
  disabled; the game is *text*, HTML is the natural medium.
- **One JS island** (vanilla, no build step, no node on the host — the
  tinysam decision) for the live parts: event feed append, countdown
  timers, queue re-ordering. It polls (§3), then upgrades itself when a
  push transport exists.
- No WASM client in v1. The samgrognard shell exists because that game
  needed a board renderer; ours needs scrolling text, tables, and a
  grid — HTML/CSS does this natively, and every minute not spent on
  glue.js is a minute of world.

Progressive-enhancement contract: every island action has a plain-HTML
fallback form (POST the same command batch). If the island breaks, the
game is still fully playable — which is also how we treat
no-JavaScript bots: they were here first.

## 2. The tab model (one tab = one person)

Sam's own UX sketch — multiple browser tabs, each controlling one
person — maps perfectly onto the read/write split:

- `GET /person/p-114` (tab page): that person's status, skills,
  running action with server-provided `end_tick`, queue with reorder
  buttons, local tile view. Polling scoped to that actor's events.
- Household tab: roster, stock, buildings, Charter balance, raid plan
  editor (§4), shipment schedule.
- World tab / spectate (§5).

Tabs are independent pages with independent polls — no SPA state to
desync, browser tabs *are* the window manager. (Later, if it matters:
a single-page "hold view" that shows all folk at once. Data's the same;
rendering is cheap.)

## 3. Transport ladder (mirrors Architecture §9)

| Stage | Mechanism | Notes |
|---|---|---|
| v1 | **Polling**: `GET /v1/events?since=seq` every 1-3s (adaptive: fast when tab focused, slow when hidden), `GET` reads from the published tick view | proven shape — samvillage-bot polls ~1 Hz today |
| v2 | **SSE** (`GET /v1/events/stream`) for feeds + spectate | needs streamed/chunked responses in samhttp; contained addition |
| v3 | **WebSocket** (`libs/samws`) if/when UX demands push for everything | commands stay POST even then; WS is read-side fan-out |

Command submission is always POST (§2 of the commands doc) — a push
transport changes nothing about validation, ordering, or the API. That
boundary is why this ladder is cheap to climb.

## 4. Raid plan editor & expedition planner

The two "planning is the gameplay" screens deserve real design love:

- **Raid plan editor**: a hold map (CSS grid) with per-part toggles
  (gate open/shut, oil ready), folk-to-post assignments, engage/withhold
  rules per wall section. Saving posts `raid_plan` with a versioned
  name; activating pins it. The page renders the *engine's* own
  validation feedback ("east post unmanned", "gate blocked by cart").
- **Expedition planner**: pick party + provisions + target + standing
  orders; the page previews duration/endurance from `/v1/rules` —
  client-side math on served data, the self-describing-API rule paying
  off as UX.

Both are pure forms over command endpoints — no hidden state, bots can
drive the same features.

## 5. Spectate & content surface (the show-off layer)

- `/spectate`: **public, no login**: world map (region tiles, fogged
  where unexplored by anyone), the global event feed (redaction rules:
  household stock/folk details private; public deeds — shipments,
  raids, deaths, discoveries — visible), leaderboard, season clock.
- `/chronicle/h-12` (owner-chooses-public): the auto-narrated history
  page — weekly chapters rendered from the event journal via the name/
  description grammar (Procgen §4) — this is the link-a-friend page.
- `/timelapse`: nightly-rendered map diffs as a scrubber (server-side
  job replays journal snapshots — free because worlds are
  journals, Procgen §1).
- Season landing page `/`: sign-up, season countdown/rules summary,
  world stats ("tiles explored: 3,112; wolves dispatched: 84") — the
  onboarding funnel *is* the spectate page.

## 6. Auth & session

- Browser: email+password → Argon2id (libargon2) → server-side session
  (PG-backed, cookie `HttpOnly`; samhttp plain-HTTP behind the reverse
  proxy/Tailscale per Architecture §8).
- Bots/scripts: **API keys** per account (scopable: household read,
  household command) — bearer tokens, samvillage's pattern generalized
  from one world-token to per-account keys.
- Rate limits at the edge of gate 1 (per-key command batches/sec);
  humans and bots share them — the API has one fairness story.
- No OAuth/email flows in v1 (password reset = admin-assisted; it's a
  friends-and-streamers season first).

## 7. Statics & serving topology

```
browser ─HTTPS─► reverse proxy (TLS, cache statics)
                    ├─► apps/samwilds  (pages + /v1 API)
                    └─► statics: /assets (one CSS file, island.js, icons)
                          served by the same samhttp router or samstatic
```

One CSS file, hand-written, text-game aesthetic (this is a *brand*
moment — parchment-dark, monospace-forward, the ironwood palette). No
framework, no build pipeline, no npm on tinysam. Icons: a tiny inline
SVG sprite. If the game succeeds, the client grows *into* a WASM
canvas shell later along the samgrognard-prototype pattern — the API
won't notice.

## 8. Decision log

| # | Decision | Note |
|---|---|---|
| W1 | Server-rendered HTML core + one vanilla JS island; no build step | samdashboard/samgenai-studio precedents; text game = native HTML |
| W2 | Tab-per-person pages; browser tabs are the window manager | Sam's own model; no SPA state |
| W3 | Poll first, SSE next, WS as a lib if earned | Architecture §9 |
| W4 | The UI uses only public API endpoints | parity with bots by construction |
| W5 | Public spectate + chronicle + timelapse from day one | the content goal is a first-class feature, not an afterthought |
| W6 | Sessions + Argon2id for browsers; scoped API keys for bots | one fairness story at gate 1 |
