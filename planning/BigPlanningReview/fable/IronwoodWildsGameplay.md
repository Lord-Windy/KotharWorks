# Ironwood Wilds — Game & Gameplay Brief

**Status:** proposal v0.1 · 2026-11-20 · author: Fable **Scope:** what the game
is and how it plays. Deliberately brief — the gameplay only has to be concrete
enough to pin the architecture. **Feeds ticket:** `60eb0b1b` (samworld TRD,
theme-independent game design).

> **Two vocabularies.** This document uses the *theme's* words (House, settler,
> the Gate). The engine documents use the *theme-independent* words your
> `tech-samworld` epic asks for (Family, Actor, Patron). The mapping is the
> glossary in §17. Every name in square brackets is a placeholder.

______________________________________________________________________

## 1. The pitch

Ironwood Wilds is a persistent, text-only, online world. Each player — a person
or a program — runs a **House**: a handful of settlers put ashore on an unmapped
world. Settlers do timed work one action at a time (fell, mine, smelt, build,
fight) and wait patiently when their orders run out. The House grinds up skills
and a deep tech tree, raises a hold from a camp to a castle, and ships goods
home through the Gate to a homeland at war. The homeland pays in **Favor**, and
Favor buys more people.

There is no script and no PvP. What happens is whatever the players and bots
make happen — and the server is built so that it can be watched, replayed, and
experimented on.

## 2. Your locked decisions (not reopened here)

| #   | Decision (yours, 2026-11-20)                                                                                                              | Where it lands                        |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| S1  | Text-based, procedurally generated                                                                                                        | §8, `IronwoodWildsProcgen.md`         |
| S2  | One central server; humans and bots connect the same way (REST, then socket-style push)                                                   | `IronwoodWildsWeb.md` §3              |
| S3  | A player controls several people; they act separately and are **patient** — no idle behaviour                                             | §6, §7                                |
| S4  | Actions take real time; a short queue per person; evaluation is lazy                                                                      | §7, `IronwoodWildsCommandsAndTime.md` |
| S5  | Strong tech tree; per-person skill levels; OSRS-style grind; the loop is cycling through your people                                      | §5, §9                                |
| S6  | PvM with parties formed from your own people. **No PvP, ever**                                                                            | §12, §13                              |
| S7  | Named Ironwood Wilds; quasi low fantasy; a household deposited on a new world to supply a homeland under attack by a rival doing the same | §4                                    |
| S8  | Supplying home earns points; points buy people; PvM can kill people                                                                       | §11, §12                              |
| S9  | Hold/castle construction is a main goal; holds are raided; defence is planned ahead; raids take **stuff, never people**                   | §10                                   |
| S10 | Kept open-ended; the server restarts from time to time                                                                                    | §15                                   |
| S11 | Gameplay detail is not the priority; the modular plan is                                                                                  | everything else                       |

## 3. Pillars

| #   | Pillar                          | The bar                                                                                                                   |
| --- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| P1  | **People, not units**           | Every settler is an individual with a name, skills, kit and a history. You can tell two of your miners apart after a week |
| P2  | **Time is the currency**        | Nothing is instant and nothing rewards fast fingers. A good plan made once beats ten frantic clicks                       |
| P3  | **The grind is the game**       | Numbers go up for a long time, and each tier changes what you do, not just how fast                                       |
| P4  | **Bots are citizens**           | Anything a human can do, a program can do through the same door, under the same published rules. No hidden mechanics      |
| P5  | **A world worth watching**      | A spectator with no account can follow what is happening and why. Every season leaves a record that can be replayed       |
| P6  | **Neighbours, not targets**     | Nothing one House does can take from, block, or hurt another. Rivalry lives on the ladders                                |
| P7  | **Loss stings, never ends you** | People die in the wilds and holds get stripped, but a House can always rebuild from one settler                           |

## 4. Premise [all names placeholder]

The **[Old Realm]** is losing a long war against the **[Rival Host]**. Both
sides have found the same way out: the **[Gate]**, a passage to an untouched
world thick with a timber as hard as iron — *ironwood* — and everything else a
war eats. Each great family of the Realm has sent a party through.

You are one of those parties. Nobody is coming to help. The Realm wants ore,
timber, steel and trophies, and it pays in Favor: more hands sent through the
Gate. The Host is here too, doing exactly what you are doing; its foragers and
outposts are the hostile half of the wilds.

Kept open-ended on purpose: the war is a backdrop and a clock, not a plot.

## 5. The loops

| Loop          | Length    | What you do                                                                                                                        |
| ------------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **Attention** | ~30 s     | Jump to the next settler who needs orders, give them a short queue, move on. *This is the core loop — cycling through your people* |
| **Session**   | 10–30 min | Get everyone working, move goods to where they are needed, start a build or a study, send a shipment home                          |
| **Day**       | check-ins | Come back to finished queues, an expedition's outcome, what last night's raid took                                                 |
| **Season**    | weeks     | Camp → hold → keep. Climb the tech tree. Push the war effort. The Gate closes; the Hall of Fame is written; a new shore            |

The scarce resource is **your attention**. More settlers means more throughput
and more to keep track of — which is exactly the pressure that makes a bot (or a
good queue) worth having.

## 6. Your people

- A House starts with a **founding party** of three settlers [tunable].
- Each settler has **skills** (woodcutting, mining, smithing, building, fighting
  …), levelled 1–60 by XP from doing the work. The curve is OSRS-shaped: each ~7
  levels doubles the cost.
- Each settler has small rolled **aptitudes** (±10–20 % in a few skills). This
  is what makes specialisation an actual decision.
- Each settler carries a limited load and equips tools and gear.
- **Patient.** A settler with no orders stands still and does nothing.
- **No upkeep needs** (hunger, sleep) in v1 — see §16.
- **Death** comes only from PvM. The dead stay dead and are listed on the
  House's roll.
- **Recruits** are bought with Favor. Each one costs more than the last, and
  each needs a bed in the hold.

## 7. Time, actions, queues

- Every action has a **duration** in real time: tens of seconds for a single
  log, minutes for a smelt, hours of pooled labour for a wall.
- A settler holds **one running action plus a short queue** (default 5 pending).
  An action can carry a count (*mine × 20*) or a stop rule (*until my pack is
  full*).
- Orders keep running while you are logged out. When the queue is empty the
  settler waits. There is no other "offline progress" — it is the same thing.
- **Travel is an action.** Being somewhere matters; hauling is a job.
- Skill, tools and stations shorten durations. Nothing makes them zero.
- A failed step (the vein ran dry, the store is full) is skipped with a note in
  the log and the queue moves on.

## 8. The land

- The world is **generated**: regions of named places — woods, seams, tarns,
  ruins, lairs — joined by routes that take time to walk.
- Everything is **described in text**, to the depth you care to look.
- You start knowing your landing and what can be seen from it. The rest is found
  by exploring.
- **Farther is richer and worse.** Distance from any landing raises both the
  yield and the danger.
- The land is **shared**. Other Houses work the same wilds.

## 9. Making things and the tech tree

Raw material → refined goods → tools, gear and parts → structures.

Two ladders, both grindy, gating different things:

| Ladder     | Belongs to  | Climbed by                               | Gates                                                  |
| ---------- | ----------- | ---------------------------------------- | ------------------------------------------------------ |
| **Skills** | one settler | doing the work                           | what *that settler* can do, and how fast               |
| **Tech**   | the House   | spending goods + study time at a station | recipes, structures, queue depth, party size, defences |

A tech usually needs both: a settler with Smithing 20, a built forge, forty
ingots and six hours of study. The tree is deep rather than wide, and branches
force a choice of order.

Sinks keep the economy from flooding: tools wear, structures cost upkeep
materials at higher tiers, shipments home remove goods for good.

## 10. The hold, and raids

- Your landing grows in tiers: **camp → palisade → hall → keep → castle**
  [names]. Each tier is a set of structures (stores, workshops, housing, walls,
  towers, the gate-yard) raised by pooled work.
- **Raids.** A hold attracts raiders in proportion to *its own* wealth and
  renown — never the server's — so a newcomer is not flattened by a threat level
  they did not build.
- You get **warning** (more with watchtowers), and you set a **defence plan** in
  advance: who stands where, what is protected first, which traps are set.
- The raid resolves by itself. Outcomes: goods taken and structures damaged.
  Defenders can be **wounded** (slower for a while). **Nobody dies in a raid.**

## 11. The \[Old Realm\]: shipments, Favor, the war effort

- The Gate in your hold accepts **shipments**. Each good is worth Favor by a
  **demand table** that shifts every few days as the war's needs change.
- **Favor** buys recruits first; later, charters and blueprints.
- Every shipment also feeds a server-wide **war effort** meter. Milestones
  unlock things for everyone, and the meter is the season's clock. *(Carried
  over from your Biofabrica notes — collective milestones. Cheap to drop.)*

## 12. Monsters and parties

- The wilds hold **lairs**, tiered. Host outposts are the top tiers.
- You form a **party** from your own settlers and set **tactics**: stance, who
  to target, when to fall back.
- The fight is an **autobattler**: it plays out by itself in timed rounds you
  can watch, and you may order a retreat while it runs. *(Your Biofabrica "PvE
  as an autobattler" breakthrough, reused as-is.)*
- **Loot** includes materials that cannot be gathered — combat feeds crafting,
  and crafted gear is what lets you fight deeper.
- Settlers who fall are gone.

## 13. Other Houses

You share the land and cannot hurt each other.

- No attacking, no stealing, no blocking.
- Shared resource nodes run low and **grow back**. Being beaten to a seam costs
  you a walk, not the seam.
- Rivalry is the **ladders**: Favor, war-effort contribution, hold tier, deepest
  lair cleared.
- No direct trade in v1 (§16). Joint expeditions and shared great works are the
  natural later form of co-operation.

## 14. Bots, leagues, the Lab

- A bot is a player. It registers, gets a token, and uses the same API the
  website uses.
- Accounts declare what drives them — **human, bot, or hybrid** — and ladders
  are split by that, so a person is never ranked against a script that never
  sleeps.
- **The Lab** is the same world run without a network or a wall clock: thousands
  of simulated days in seconds, with any rule adjustable. It is where balance is
  tested, where hypotheses about the population get run ("what if ore regrows
  twice as fast?"), and later where genetic programs are bred.

## 15. Seasons

Restarts are part of the fiction: the Gate drifts, and each crossing lands on a
new shore.

- A season is one generated world from landing to the Gate's closing.
- At the end, the **Hall of Fame** is written and the world is archived whole —
  replayable, forkable, quotable.
- Accounts, names and honours carry over. Power does not.

## 16. Defaults I chose where you were silent

Each is a rule constant or a content choice, not architecture. Override any.

| Question                    | Default                                                                    | Why                                                                                                    | Cost to change                            |
| --------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ----------------------------------------- |
| Hunger / sleep needs        | **None.** Food is a buff and an expedition supply                          | Patient people + a world that runs while you sleep. Starving overnight is punishment for having a life | Low — add flows                           |
| Shared or private resources | Nodes at your landing are **yours**; wilds nodes are **shared and regrow** | Keeps "neighbours, not targets" true while still letting Houses meet                                   | Low — a flag per node kind                |
| Direct trade                | **None** in v1                                                             | Your earlier stance; also removes mule accounts and bot-run markets from the first balance pass        | Medium — a new module                     |
| Randomness                  | **Seeded dice** for yields, combat and events                              | The texture of an OSRS-style grind. Every roll stays replayable                                        | Low                                       |
| Queue                       | 1 running + 5 pending, count ≤ 50                                          | Roughly three hours of unattended work per visit                                                       | Trivial — constants; tech can raise it    |
| Death                       | Only from PvM                                                              | Matches S8 and S9                                                                                      | Low                                       |
| Season length               | **2 weeks** during alpha, longer once it holds                             | Short seasons let breaking changes ship without migrations                                             | Trivial                                   |
| World clock                 | Runs only while the server runs; 1 world day = 2 real hours                | A raid must never resolve during an outage                                                             | Trivial                                   |
| Worlds                      | One world per season, no shards                                            | Emergence needs everyone in one place                                                                  | High later — but not needed at this scale |
| What carries over a season  | Account, name, honours. Nothing with power                                 | No pay-to-win, no veteran snowball                                                                     | Low                                       |

## 17. Glossary: theme word ↔ engine word

| In the game                  | In the engine (`samworld`) | Meaning                               |
| ---------------------------- | -------------------------- | ------------------------------------- |
| House, household             | **Family**                 | The unit a player controls            |
| Settler, your people         | **Actor**                  | One individual who performs actions   |
| The [Old Realm]              | **Patron**                 | Where shipments go; pays Favor        |
| Favor                        | **Favor**                  | The currency that buys recruits       |
| Place (woods, seam, lair)    | **Site**                   | A node in the world graph             |
| Vein, stand, quarry face     | **Node**                   | A resource source at a site           |
| Building                     | **Structure**              | Something built at a site             |
| The hold                     | **Home site**              | A Family's landing and its structures |
| Order, job                   | **Action**                 | A timed activity one Actor performs   |
| —                            | **Command**                | An API verb that changes the world    |
| The log, the news            | **Event**                  | A fact that happened                  |
| What you can see             | **View**                   | A read model fogged to one Family     |
| Ironwood Wilds (the content) | **Pack** (`samironwood`)   | Theme data bound to the engine        |
| Season                       | **World**                  | One generated world, start to finish  |
