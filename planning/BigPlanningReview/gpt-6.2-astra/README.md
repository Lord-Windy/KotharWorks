# Ironwood Wilds — a plan for the first expedition

Design proposal by GPT-6.2 Astra · 20 November 2026 · proposed Ptah family:
**`samwilds`**

**Build a place where two reasonable decisions make two different histories.**
Give a household two people, a stand of alder, an iron outcrop and a gate home.
Its first load of iron can go through the gate for recruitment credit, or into a
haul shed that shortens every subsequent trip. A spectator should be able to
watch either household, understand its choice, and ask what would have happened
on the same land with the other policy. That's a useful first Friday project
even if a genetic algorithm never enters the picture.

## Reading order

| If you want to know…                                                                                  | Read                                                                                                   |
| ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| What it feels like to run or watch a household                                                        | [Ironwood Wilds gameplay brief](IronwoodWildsGameplay.md)                                              |
| What the prototype looks like, what takes 3 minutes, and where to stop each Friday                    | [The first expedition](FirstExpedition.md) — includes a text screen, two outcomes and acceptance gates |
| What goes in `libs/` versus `apps/`, and what C uses instead of an object hierarchy                   | [Server architecture](ServerArchitecture.md)                                                           |
| What happens when a human clicks **Queue**, two people want the same ore, or nobody logs in for a day | [Time and commands](TimeAndCommands.md)                                                                |
| How to generate land worth making decisions about, and how to add content                             | [World generation and content](WorldGenerationAndContent.md)                                           |
| Who owns a person, what is durable, and what a new expedition resets                                  | [Accounts and storage](AccountsAndStorage.md)                                                          |
| How a human, a bot and a spectator see the same expedition                                            | [Web and bot interfaces](WebAndBots.md)                                                                |

## Decisions at a glance

| Choice              | Recommendation                                                                                                                                                  | Why it matters here                                                                                                                                   |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| Simulation unit     | A **season** is one world. One authoritative writer orders its changes. The first server hosts one active season.                                               | A household has several independently busy people but one stockpile. Spending the same iron twice is a bigger early risk than running out of servers. |
| Time                | Integer seconds, positive action durations, a wake-up at the next due event. Advance due work before accepting a new order.                                     | Three minutes of mining cost one start and one completion. Watching more often never earns more ore. A lab can jump three minutes instantly.          |
| Extensibility       | Typed entities, data-defined prototypes and a small table of action handlers in C11. A headless `samwilds` library, a separate generator and thin applications. | New recipes are data. A genuinely new behaviour has one implementation shared by the server and experiments.                                          |
| Online interface    | HTTP commands and cursor-based observations. Server-rendered person tabs; optional JavaScript for countdowns.                                                   | A person can play with a form, a bot with an HTTP client. Today's `samhttp` suffices.                                                                 |
| Records             | PostgreSQL current state and deadlines, with command receipts and a chronicle committed in the same transaction. Pin the map and rules used.                    | A restart can resume a half-finished trip; an archived season can tell its story and be replayed.                                                     |
| Shared frontier     | Shared geography and home-world contributions; in the first season each household has its own working allotment at a site.                                      | Another player cannot starve a household of its starting ore. Scarcity still forces choices **within** a household.                                   |
| First research tool | A virtual clock, two scripted policies, matched seeds and an exportable causal timeline.                                                                        | You can tweak a population or a rule and measure the result before building a learning system.                                                        |

**Scope of the evidence:** the first full slice ends when a browser and an
external bot can each run a household on a generated world, spend the first ore
differently, and leave a legible record that survives a server restart. Groups,
monsters, hold raids, a larger tech tree and evolved controllers have places in
the design and later milestones. All timings, yields and budgets below are
proposed test fixtures, not measured balance or existing Ptah features.
