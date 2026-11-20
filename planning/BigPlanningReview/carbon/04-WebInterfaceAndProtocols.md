# 04 — Web Interface & Multi-Protocol Connectivity

You specified:

- *"Central server, bots and humans all connect and control either via REST or
  something like a Websocket or regular socket."*
- *"I imagine if a website version of this you can just click on multiple tabs
  where you control that person via the tabs."*

This document designs both **the protocol stack** (built cleanly on Ptah's
`samplex` + `samhttp`) and **the Multi-Tab Character Switcher Web UI**
(`samhtml` + tiny vanilla JS).

______________________________________________________________________

## 1. Protocol Strategy: Humans & Bots on Equal Footing

A trap in hobby multiplayer projects is spending six Fridays writing custom
WebSocket framing and client SDKs before a single tree can be chopped. Because
Ptah **already ships `samplex`, `samhttp`, and `samhtml`**, we stage
connectivity in two clean layers where **every protocol maps onto the exact same
`SamWildsCommand` and `SamWildsView` structs**:

### Layer 1 (Day One / Slice 0): `samhttp` REST JSON + Monotonic Event Cursor

Look at Ironwood Wilds' actual pacing: actions take **20 seconds to 5 minutes**.

- **Client-side countdowns need 0 network packets:** The server sends
  `started_ms`, `due_ms`, and `server_now_ms`. The browser tab animates smooth
  `02:59 → 02:58 → 02:57` progress bars locally in JS using `performance.now()`!
- **Lightweight Delta Sync (`GET /api/v1/sync?since=<seq>`):** Polled once every
  ~2 seconds (or immediately when a local timer hits `00:00`!). If nothing
  changed since `<seq>`, the server replies in `< 100 bytes`:
  `{"now_ms": 1732099400, "seq": 418, "events": []}`. When an action finishes or
  a raid hits, it returns the new events + dirty character/hold snapshots.
- **Instant Bot Compatibility:** Any language on earth (`curl`, Python
  `requests`, C `libcurl`, Rust, Go) can write a bot in 30 lines on Day 1, and
  `GET /api/v1/rules` makes the entire game self-describing (just like
  `samvillage`).

### Layer 2 (Phase 2 Upgrade): `samplex` Raw Bot Socket + `libs/samws` WebSockets

Because `samhttp` v1 uses `Connection: close` per-request chains
(`planning/samhttp.md` §1.2), we don't hack keep-alive spaghetti into `samhttp`
handlers. Instead, `SamplexEngine` already supports multiple concurrent
listening sockets and custom FD chains!

| Protocol                  | Port               | Target Audience                                   | How It Plugs Into `samplex`                                                                                                                                                                                                     |
| ------------------------- | ------------------ | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **HTTP REST + HTML**      | `:8140`            | Browsers, `curl`, simple bots                     | Existing `SamHttpServer` (`samhttp` + `samhtml`), zero modifications needed                                                                                                                                                     |
| **NDJSON Raw TCP Socket** | `:8141`            | High-speed C/Python/GA bots                       | ~150-line `Samplex` chain (`READ line → auth once → dispatch Command / push Event JSON line`). Persistent socket, zero HTTP header overhead!                                                                                    |
| **WebSocket (`/ws`)**     | `:8140` or `:8142` | Instant browser push (raids, party combat rounds) | Small `libs/samws` library: HTTP `101 Switching Protocols` handshake (`SHA-1` + base64 `Sec-WebSocket-Accept`) handing the client `fd` over to a persistent `Samplex` WS frame chain subscribed to the Household's `seq` cursor |

### Unified Wire Vocabulary (REST ↔ Socket Parity)

| Operation                          | HTTP REST Endpoint                          | NDJSON / WebSocket Frame                                      |
| ---------------------------------- | ------------------------------------------- | ------------------------------------------------------------- |
| Read static rules & tech tree      | `GET /api/v1/rules`                         | `{"op":"get_rules"}`                                          |
| Full household + characters + hold | `GET /api/v1/household`                     | `{"op":"snapshot"}`                                           |
| Delta events since sequence `N`    | `GET /api/v1/events?since=N`                | Pushed automatically as `{"op":"event", "seq":419, ...}`      |
| Inspect prose (`look` / `examine`) | `GET /api/v1/inspect/:target_id?depth=2`    | `{"op":"inspect", "target":"site-9", "depth":2}`              |
| Append / replace character queue   | `POST /api/v1/characters/:id/queue`         | `{"op":"cmd", "kind":"ENQUEUE_ACTION", "actor":"chr-4", ...}` |
| Clear pending queue                | `DELETE /api/v1/characters/:id/queue`       | `{"op":"cmd", "kind":"CLEAR_QUEUE", "actor":"chr-4"}`         |
| Construct / repair / research      | `POST /api/v1/hold/build` · `/research`     | `{"op":"cmd", "kind":"CONSTRUCT_HOLD_BLD", ...}`              |
| Update Hold Raid Doctrine          | `POST /api/v1/hold/doctrine`                | `{"op":"cmd", "kind":"SET_RAID_DOCTRINE", ...}`               |
| Form PvM Expedition Party          | `POST /api/v1/parties`                      | `{"op":"cmd", "kind":"FORM_PARTY", ...}`                      |
| Ship Tribute / Recruit Settler     | `POST /api/v1/charter/tribute` · `/recruit` | `{"op":"cmd", "kind":"SHIP_TRIBUTE", ...}`                    |

______________________________________________________________________

## 2. The Multi-Tab Web Interface Design

Following Ptah's house web pattern (`apps/samdashboard`, `apps/samtechtree`,
`apps/samgenai-studio`), the web UI requires **zero Node.js, zero React, zero
build step**:

- `samhtml` renders clean, semantic, dark-parchment/iron-slate HTML server-side.
- A ~250-line inline vanilla JS controller manages instant tab-switching, smooth
  `requestAnimationFrame` / `1s` timer bars from `due_ms`, and `fetch()` calls
  to `/api/v1/...` (with standard `<form method="post">` fallbacks so every
  action is testable via `--self-test`!).

### 2.1 Character Tab View (The Core Plate-Spinning Screen)

Notice the **Tab Strip** across the top: every character tab shows their **live
status badge & countdown timer** even while you are looking at a different tab!
An amber `● IDLE` badge immediately draws your eye to whichever retainer needs
orders.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ IRONWOOD WILDS · Charter IV        House Ashvale  │ Favor: 140 ★ │ Raid Risk: LOW│
├──────────┬──────────────────────┬─────────────────┼──────────────────┬───────────┤
│ [🏰 Hold] │ [⛏ Aldric · 01:42]   │ [● Brenn · IDLE]│ [⚔ Maren · Delve]│ [+Recruit]│
├──────────┴──────────────────────┴─────────────────┴──────────────────┴───────────┤
│  BRENN OF KARST  (Idle at: Mist-Choked Bog Fen [site-12])                        │
│  HP: [██████████] 100/100   Stamina: [███████░░░] 72/100   Pack: 14 / 25 kg      │
├─────────────────────────────────────────────────┬────────────────────────────────┤
│ SURROUNDINGS (look · Mist-Choked Bog Fen)       │ ACTION QUEUE (0 / 4 pending)   │
│                                                 │                                │
│ Rust-red water pools between tussocks of        │  Active: — IDLE (waiting) —    │
│ wire-sedge. Thick crusts of orange bog-ore      │                                │
│ cling to the peat banks (Rich: ~82% left).      │  1. [ empty slot ]             │
│                                                 │  2. [ empty slot ]             │
│ Present here:                                   │  3. [ empty slot ]             │
│  • Aldric (House Ashvale) — Mining bog-ore      │  4. [ empty slot ]             │
│  • [Examine Bog-Iron Pan]  [Survey Region]      │  [Clear Pending] [Cancel Cur]  │
│                                                 ├────────────────────────────────┤
│ ── RECENT CHRONICLE (Brenn) ──────────────────  │ AVAILABLE COMMANDS AT SITE     │
│ 14:08  Completed: Mine Bog-Iron (×3) → +3 Ore   │                                │
│ 14:08  Mining rose to Level 14! (+120 XP)       │  [⛏ Mine Bog-Iron (52s)] [×3] │
│ 14:05  Arrived at Mist-Choked Bog Fen.          │  [🌿 Gather Bog-Myrtle (35s)]  │
│                                                 │  [🧭 Survey Hidden Sites (90s)]│
│                                                 │  [🥾 Travel → Ashvale Hold]    │
│                                                 │  [🎒 Queue: Mine×3 → Go Home]  │
├─────────────────────────────────────────────────┴────────────────────────────────┤
│ SKILLS: Mining 14 (64%) · Woodcut 8 · Smithing 5 · Vanguard 6   │ EQUIP: Iron Pick│
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 The `[🏰 Hold]` Tab (Macro Management & Pre-Planned Raid Defense)

Clicking `[🏰 Hold]` switches to your Household's strategic overview with four
clean sub-panels:

1. **Hold Yard & Construction:** Visual status cards for each structure
   (`Timber Palisade [88% cond]`, `Clay Bloomery [Active · 1 slot]`,
   `Keep Vault [Protects 60 kg]`) + `[Raise Structure]` and `[Queue Repair]`
   buttons.
2. **Stockpile & Charter Tribute:** Yard vs. Vaulted inventory counts alongside
   the live **Homeland War Dispatch** (*"Northern Siege: Iron Ingots pay 1.75×
   Favor — 01h 14m remaining"*).
3. **Household Tech Tree:** Interactive hex/card dependency graph (reusing the
   visual language you already built in `apps/samtechtree`!) showing Tier I–IV
   unlocks and material/research costs.
4. **Raid Defense Doctrine Planner:** Simple, legible dropdowns & sliders saved
   via `POST /api/v1/hold/doctrine`:
   - *Gate & Wall Roster:* Assign roles per character when present at Hold
     (`Shieldwall`, `Wall Archer`, `Fire-Brigade`, `Shelter in Vault`).
   - *Vault Priority Order:* Checkboxes (`[✓] Ingots & Steel`,
     `[✓] Tools & Weapons`, `[✓] Preserved Rations`, `[ ] Raw Timber`).
   - *Outer Yard Fallback Threshold:* Slider
     (`Retreat to Inner Keep when Palisade < 30%`).
   - *Last Raid Report:* Expandable turn-by-turn log of the most recent attack.

______________________________________________________________________

## 3. Why a Spectator / "God-View" Tab Matters for Your Goals

Remember Motivation #2: *"I am looking to make a kind of land that I can watch
people (or more likely bots) do interesting things... and make content for /
show off."*

Alongside the player UI, `apps/samwilds-server` should expose an admin/spectator
route (`/chronicle` or `/admin/world` gated by admin token):

- **Live Frontier Map (SVG/ASCII Grid):** Shows procedural biome regions, Hold
  locations, active delving parties, and depleted vs. rich resource zones.
- **Population & Economy Telemetry:** Total Tribute shipped per Household, skill
  specialization Lorenz curves, mortality causes, and live switches (in
  experiment mode) to trigger a **Homeland Price Shock** or **Winter Famine**
  and watch 50 bot tabs adapt in real time!
