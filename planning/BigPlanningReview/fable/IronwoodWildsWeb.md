# Ironwood Wilds — Web Interface and Transports

**Status:** proposal v0.1 · 2026-11-20 · author: Fable
**Scope:** how people and programs connect, what the website is, and what
it is made of.
**Mock:** `mock/ironwood-wilds-mock.html` — a static, clickable picture of
the play screen with a miniature of the time model running inside it.

---

## 1. Summary

| # | Position | In one line |
|---|---|---|
| 1 | **One API** | The website is a client of the same API the bots use, with no extra powers |
| 2 | **The server decides; the client renders** | The client computes no rules. Its only arithmetic is a countdown |
| 3 | **Transport in three steps** | Polling with a cursor today; a stream next; WebSocket only if it earns it |
| 4 | **Two faces** | The *play client*, signed in, shows your House. The *observatory*, public, shows the world |

## 2. Who connects

| Client | Signed in | Uses | For |
|---|---|---|---|
| Play client (browser) | yes | views, options, commands, events | playing |
| Bot | yes | the same | playing |
| CLI *(optional)* | yes | the same | playing from a terminal; shell scripts |
| Observatory (browser) | no | public views and the public feed | watching; making things from it |
| Admin console | admin | admin endpoints | running the place |

## 3. Transports, in three steps

| Step | The client | What samhttp needs | When |
|---|---|---|---|
| **1. Poll with a cursor** | `GET /v1/events?since=N`; `If-None-Match` on views; sleep until `next_due` | Nothing. It works today | The slice |
| **2. Stream (SSE)** | Holds `GET /v1/stream` open; the server writes events as they happen. Commands stay `POST` | A **detached response**: a handler may keep the connection and write to it later | After the slice |
| **3. WebSocket** | One socket, both ways | Upgrade handshake (SHA-1, base64), framing, ping and pong — a new `samws` | Only if step 2 proves short |

**Why polling is enough to begin.** An action's end is known the moment it
starts. The client animates the countdown itself and needs the server only
when something actually changes — and every view says when that will be
(`next_due`). A client watching one settler mine asks once per lump, not
once per second.

**Why a stream before WebSocket.**

- Push in one direction is all this game needs. Commands are occasional and
  fine as `POST`s.
- The browser's `EventSource` reconnects by itself and sends
  `Last-Event-ID` — which *is* the feed cursor.
- It passes through any proxy, and a bot can read it with `curl -N`.
- It is lines of text: about a hundred lines of C on a detached response,
  against a whole RFC 6455.

**What "detached" means inside samhttp.** Today a connection's chain is
read → route → serialise → write → close, and the handler must finish at
once. The addition: a handler may say "I am keeping this connection". The
chain then loops — wait on a short timer or an eventfd, write whatever is
new, repeat — until either side closes. The same mechanism later lets slow
handlers (password hashing, registration) run on a samplex worker, and is
what a WebSocket would sit on.

**The fourth option: a plain socket.** Newline-delimited JSON over TCP is
the cheapest thing of all on samplex — no HTTP — and can be played from
`nc` like a MUD. It needs its own TLS answer at the edge, so it follows
HTTP rather than leading. It is a fourth adapter if you want it.

Every transport produces the same `SamWorldCommand` and reads the same
views and feeds. A transport is an adapter; the engine never learns which
one was used.

## 4. The edge

```
  browser, bot ──HTTPS──▶ Caddy ──┬── /         static files: the web client
                                  └── /v1/*     reverse proxy ──▶ 127.0.0.1:8130
                                                                   samworld-server
```

| Concern | Who handles it |
|---|---|
| TLS and certificates for the domain | Caddy, automatically |
| HTTP/2 and keep-alive toward clients | Caddy — it speaks `Connection: close` to samhttp on loopback |
| Compression, request size caps, static files | Caddy |
| Plain HTTP on loopback only | samworld-server |
| Process supervision | one stanza in `tools/web/servers.conf`; the existing systemd unit |
| Secrets | `~/.config/ptah-web/samworld-server.env` |

- **Port 8130** is a placeholder; 8090, 8095–8097 and 8123 are taken.
- **Same origin** for the site and the API, so the site needs no CORS. The
  API still sends `Access-Control-Allow-Origin: *` for third-party tools —
  safe, because credentials are bearer tokens, never cookies.
- **Where it runs.** tinysam sits on your private network; a public game
  needs a public host. A small VPS is plenty: one core, a gigabyte.

| Threat | Answer |
|---|---|
| Hostile bytes at a hand-written parser | Caddy in front; hard limits; fuzz samhttp's parser and the wire decoder; sanitizer runs in CI |
| Script injection through names | A strict character set at registration; one escape helper used everywhere; a Content-Security-Policy with no inline script |
| Token theft | Hashed at rest; revocable; HTTPS only |
| Flooding | Rate limits per token and per address |
| Cross-site request forgery | Not applicable — a bearer header, no cookies |

## 5. The API surface

| Method and path | Needs | Purpose |
|---|---|---|
| `POST /v1/register` | an invite | Create an account and a House. Returns the token once |
| `GET /v1/meta` | — | Version, rules digest, world time, day, season state |
| `GET /v1/rules` | — | Every def and the command catalog. The self-describing part |
| `GET /v1/family` | read | Your House: summary, and every Actor's state, action and queue |
| `GET /v1/actors/{id}` | read | One Actor in full: skills, pack, kit |
| `GET /v1/actors/{id}/options` | read | What this Actor can do here, now — durations, costs, and why not |
| `GET /v1/sites/{id}` | read | A site you know: prose, nodes, structures, who is there |
| `GET /v1/map` | read | The regions you know, as ASCII and as data |
| `GET /v1/events?since=N` | read | Your feed |
| `POST /v1/commands` | play | Every command |
| `GET`, `POST`, `DELETE /v1/tokens` | play | Manage your tokens |
| `GET /v1/public/world` | — | World summary, war effort, population |
| `GET /v1/public/ladders` | — | The ladders, by league |
| `GET /v1/public/events?since=N` | — | The public feed |
| `GET /v1/public/houses/{id}` | — | A House's public page |
| `GET /health` | — | For `ptah-web status` |

`GET /v1/rules` is what keeps clients honest: a client that hardcodes
nothing but endpoints is the bar, as it was for samvillage.

## 6. The play client

### 6.1 Principles

1. **The server decides; the client renders.** It draws views, lists the
   options the server computed, and prints the event text the server wrote.
   This is samgrognard's rule — all decisions stay in C — and it means new
   content needs no client change.
2. **The only arithmetic is the countdown**: `ends_at − now`, with `now`
   refreshed by every reply.
3. **Keyboard first.** The game is cycling through people. That must be one
   key.
4. **Text first.** Monospace, no images. It should feel like a well-kept
   terminal.
5. **Every Actor has a URL**, so real browser tabs work as well as the
   in-page ones.

### 6.2 The screen

```
┌────── IRONWOOD WILDS · House Ashdown · Favor 40 · Day 3, 14:20 · War effort [###.......] 31% ──────┐
│ PEOPLE                 │ BRYNN ASHDOWN  at Redcap Seam                  │ THE LOG                  │
│ 1 Brynn   mining   0:21│ A raw red scar in the hillside, loud with      │ 14:19 Brynn pries a lump │
│ 2 Odo     walking  2:40│ water. Someone has shored the upper cut.       │       of iron ore loose. │
│ 3 Hale    IDLE      << │                                                │ 14:18 Odo sets out for   │
│                        │ NOW   mine iron ore      3 of 10         0:21  │       Hollin Stand.      │
│ [n] next idle          │ NEXT  1 travel > Ashdown Landing         3:00  │ 14:15 Hale has nothing   │
│                        │       2 deposit all > Storehouse         0:10  │       to do.             │
│ STORES                 │                                                │ 14:02 Brynn is now a     │
│ logs 14      ore 6     │ HERE YOU CAN                                   │       level 6 miner.     │
│ charcoal 3   ingots 2  │ [m] mine iron ore     0:30 each    Mining 6    │                          │
│                        │ [t] travel...                                  │                          │
│                        │ [x] smelt   not here: needs a bloomery         │                          │
├────────────────────────┴────────────────────────────────────────────────┴──────────────────────────┤
│ > mine n-0.0.6.0 x10_                                                                              │
└────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Region | Comes from | Changes when |
|---|---|---|
| Title bar | `GET /v1/family`, `GET /v1/public/world` | Favor or the day changes |
| **People** | `family.actors[]` | An Actor's state changes |
| **The sheet** — where they are, what they are doing, what is queued | The site view; the Actor's action and queue | The selected Actor changes |
| **Here you can** | `GET /v1/actors/{id}/options` | The Actor moves or finishes |
| **Stores** | The home site's structures | Goods move |
| **The log** | `GET /v1/events` — the server's own `text` | Anything happens |
| **Command line** | Parsed against the catalog in `/v1/rules` | You type |

### 6.3 The attention loop

The whole game is noticing who needs orders and giving them. The client is
built around that.

| Feature | What it does |
|---|---|
| **`n` — next idle** | Jumps to the next Actor with nothing to do. The idle-worker key from every RTS. This one key *is* the core loop |
| Idle first | Idle Actors are marked and float to the top of the rail |
| The tab title | Shows the count: `(2) Ironwood Wilds` |
| `1`–`9` | Select an Actor directly |
| Letters | Fire the option shown beside them |
| **`r` — repeat** | Give this Actor the same orders as last time. Client-side only: it resends what it remembers. Probably the most valuable convenience a grind loop can have |
| A chime or notification | Optional, when someone goes idle |

### 6.4 Tabs

- **In the page:** the people rail. Click, or press a digit.
- **In the browser:** every Actor has a link — `/play#a-104` — so an Actor
  can live in its own tab or window. Each tab polls for itself, which is
  cheap because unchanged views answer `304`.

### 6.5 The command line

`mine n-0.0.6.0 x10` · `go redcap` · `queue clear`

It is parsed in the browser against the command catalog, with completion
for verbs, places and goods. Before sending, it shows the JSON it built.
That last detail matters: it teaches the API, and is the bridge from
clicking to scripting that your earlier "control layers" notes wanted.

### 6.6 Staying in step with the server

1. On load: `GET /v1/rules` (cached by digest) and `GET /v1/family`.
2. Then loop: `GET /v1/events?since=cursor`; apply each event to the local
   copy.
3. If the reply's `rev` has moved further than the events explain — the
   feed's ring was outrun — refetch the view.
4. Sleep until `next_due`, capped at fifteen seconds; three while the
   player is active.
5. Every command's reply carries `rev` and `now`, so acting resynchronises
   for free.

With the stream, steps 2–4 collapse to an `EventSource`.

### 6.7 On a phone

One column. The rail becomes a strip across the top; the keys become
buttons. A grind game lives on being able to check on your people from a
bus.

## 7. The observatory

The public face — and the thing you make content from. No account needed.

| Panel | Shows | From |
|---|---|---|
| **The map** | The ASCII regions, with Houses and activity marked | The worldgen dump; `/v1/public/world` |
| **The ticker** | The public feed: recruits, level milestones, deaths, raids, firsts | `/v1/public/events` |
| **Ladders** | Favor, war effort, hold tier — humans, bots and hybrids apart | `/v1/public/ladders` |
| **House pages** | Roster, skills, the roll of the dead, a timeline | `/v1/public/houses/{id}` |
| **Charts** | Population, production and deaths across the season | The chronicle tables |
| **Replays** | Scrub through an archived season | The export bundle and the lab's replay |

- **What is public.** Without PvP there is little worth hiding. Default:
  everything about a House is public except its queues and its tokens.
- **Server-rendered.** These pages are built with samhtml and need no
  script — linkable, indexable, cheap — in the manner of samdashboard and
  samtechtree. The map and the ticker add a little script on top.

## 8. The admin console

Late, and small: pause or rescale the world clock; watch journal lag and
queue depth; snapshot now; broadcast a notice; suspend an account; mint
invites; see the rules digest and epoch. Every admin action is a journaled
system command or an `auth.audit_log` row. Until it exists, `curl` with an
admin token does the same.

## 9. Technology choices

| Option | For | Against | Verdict |
|---|---|---|---|
| A. Server-rendered pages (samhtml) and forms | House precedent; no script needed | A full page per action; countdowns need script anyway; puts UI in the server | **The public pages** |
| B. A static page and plain JavaScript on the JSON API | Proves the API, as the CLI did for samvillage; tabs and timers come naturally; no build step | A second language — though samgenai-studio and the grognard shell already crossed that line | **The play client** |
| C. C compiled to WebAssembly, as in the samgrognard shell | All logic in C | There is no client logic to put there; heavy for text | No |
| D. A framework | Ecosystem | A toolchain, a build, and churn, for a text interface | No |

Rules for B, in the spirit of samgrognard's "glue reviewed like C":

- No framework, no bundler, no npm at run time. ES modules, one HTML file,
  one stylesheet.
- A budget of about 1,500 lines. If it wants more, the server is
  describing too little.
- Six files: `api.js` (fetch, the cursor loop, back-off) · `store.js` (the
  local copy; applies events) · `view.js` (render functions and the one
  escape helper) · `keys.js` · `cmdline.js` · `main.js`.
- A Playwright smoke test like samgrognard's — register, give orders, wait,
  read the log line — run by script, not by CTest.

In production Caddy serves these as static files. In development the
server serves them itself from `--web-root`, using samstatic's handler
pattern.

## 10. Slice scope

| In the slice | After |
|---|---|
| Polling with a cursor and `ETag`s | The stream |
| The play client: people rail, sheet, options, queue, log, command line, `n` | Phone layout; `r` to repeat; notifications |
| One public page: world summary, a ladder, the ticker | The map, House pages, charts, replays |
| Sign in by pasting a token | Passwords |
| — | The admin console |
