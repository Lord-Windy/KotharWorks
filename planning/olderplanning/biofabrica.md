# Biofabrica — Design Notes

> **Status: early braindump, captured 2026-06-09.** Intentionally broad strokes —
> the core of this game has lived in Sam's head for years; this doc exists so the
> *mechanic* and the *framing* aren't lost. Expand later.
>
> **Name:** the game is **Biofabrica**. *"Gestalt"* is what the **player** is (a
> hive mind — see §1.2), not the title.

## 1. Overview

### 1.1 What it is

An **API-based game** — players interact with the world through API requests
rather than a rendered client, in the spirit of the
[SpaceTraders API](https://spacetraders.io). The depth and gameplay feel are
drawn from:

- **Factorio** — factory-like automation; building and optimising production.
- **EVE Online** — large-scale economy and persistent universe.
- **A Tale in the Desert** — cooperative, society-driven tech progression and
  large communal projects.

### 1.2 Core fantasy

The player is a **Gestalt** — a hive mind. Rather than controlling a single
avatar, the player controls **many extensions of itself** in the form of
**mind ships**. Each unit is the player; the player is a distributed mind.

### 1.3 Setting

**Science fiction.** (This was originally agnostic between space and fantasy;
sci-fi is now the decision.)

## 2. The core mechanic

**Players control more than one unit (game object) via API requests to complete
factory-like tasks.** Multi-unit command is the heart of the game — orchestrating
a fleet of mind ships through API calls to run production and logistics, the way
a Factorio player orchestrates belts and assemblers.

## 3. Two interlocking loops: combat ⇄ resources

The two pillars of gameplay **wrap around each other and feed each other:**

- **Resource collection & transformation** — the Factorio-flavoured crafting
  loop: gather raw materials, refine and transform them up the tech tree, build.
- **Combat** — the autobattler loop (§5), run as expeditions into hostile
  branches.

**Combat gives resources.** Fighting and scavenging in other timelines is a
primary *input* to the crafting loop; crafting (better ships, gear) is in turn
what lets you fight deeper. Neither loop is self-sufficient — that mutual
dependence is the intended core tension.

> **Sam's call (not for fleshing out here):** combat wants its own *reward
> track* — something parallel to the contribution-weighted voting on which
> mega-project to build next (§4), **in addition to clout**. Doing combat should
> grant standing/influence the way contributing to server tasks does, plus its
> own payoff. Shape TBD.

## 4. Economy & cooperation

- A **large tech tree** and **economy** underpin progression.
- **Players do not trade with each other directly.**
- Instead, players **cooperate to complete large server-wide tasks**.
- Players **vote on which server tasks to pursue**, with voting weight derived
  from their **contributions to previous tasks**. (Contribution-weighted
  governance — influence is earned by helping.)

### 4.1 Divergent end states (goal tension)

Crafters and fighters should **not want the same thing.** Build in a deliberate
**conflict of goals** between the two temperaments — ideally expressed as **two
fundamentally different end states** that pull against each other, contested
through the mega-project vote above. Placeholder shapes:

- **Hide / survive** (crafter-aligned) — e.g. a megaproject that lets the nation
  slip away into another system, escaping the threat.
- **Strike a major blow** (combat-aligned) — e.g. landing a decisive hit against
  the entity that defeated you.

> Needs more thought — the specific end states are placeholders. **The tension
> is the important part:** a server that leans all-crafting or all-combat should
> feel the pull toward incompatible victories and have to fight it out at the
> vote.

## 5. PvE as an autobattler

The breakthrough that made PvE finally click: **do it as an autobattler.** This
fits cleanly with frequent server resets and with progression, and it gives the
game **an enemy and a plot**.

## 6. Server resets & progression

- The **world restarts each week.**
- **Why:** an API game of this shape would otherwise drift into runaway
  **power creep**. A weekly reset bounds it.
- The reset cadence also creates natural room for a recurring **enemy and
  narrative arc** each cycle.

### 6.1 Escalation & awareness

- **Activity builds awareness.** The more the collective acts (and the more an
  individual player acts), the more the **opposing forces notice.**
- As awareness rises, the **opposing forces get harder.** A cycle ramps from
  quiet at reset toward genuinely dangerous by the end of the week.

> **Core tension — activity is double-edged.** The same *activity* that raises
> awareness here is also what earns **voting clout** (§4) and combat rewards
> (§3). So acting harder simultaneously makes a player **more influential** and
> **more hunted.** "Push hard for clout and a say in the end state" vs. "keep
> your head down to stay alive" becomes a genuine strategic dial — and it scales
> the threat in proportion to the players actually driving the server, which also
> protects the quiet newcomer (§6.3). Worth treating as a central lever.
> *(Connection spotted late — not yet designed, but deliberate now.)*

### 6.2 Loss conditions

A cycle needs a defined way to **lose**, not just the win-shaped end states in
§4.1. Likely vector: awareness/escalation outrunning the collective until the
opposing force overwhelms you — but **how a cycle is actually lost is undecided**
(total wipe? failing to reach any end state before reset? a branch falling?).
Flagged in §9.

### 6.3 Measuring activity — collective vs. player (anti-gank)

Activity must be tracked at **two scales**:

- **Collective** — aggregate activity across the whole ~week-long session;
  drives the server-wide awareness/difficulty ramp (§6.1).
- **Per-player** — each player's own activity level.

**Why per-player matters:** difficulty can't be purely collective. Someone who
**joins on Friday**, the day before a Saturday reset — when collective awareness
is near its peak — must **not get ganked** by late-cycle difficulty they had no
hand in building. A player's personal activity should gate the heat *they* face,
so newcomers ramp from their own zero rather than being dropped into the
endgame.

## 7. Multiverse structure & narrative

- Each Gestalt lives across **a few hidden branches** — think **multiverse
  timelines.**
- The player **sends resource parties out to other branches (timelines)** to
  **gather resources.**
- While in another branch, a **significantly more powerful entity attacks you** —
  so players must **fight, gather, and scavenge** to extract value before being
  overwhelmed. (This is where the autobattler PvE lives: the expedition into a
  hostile timeline.)

### 7.1 Backstory

Your nation **lost a major war after trying to conquer the universe.** Now
you're **trying to rebuild.** The powerful entity hunting your resource parties
across the branches is the shadow of that defeat.

## 8. Technical position (provisional)

This game has no home in the codebase yet. It would naturally sit on top of the
existing Ptah infrastructure:

- **[samhttp](samhttp.md)** — the API surface players hit.
- **[procgen_mvp](procgen_mvp.md)** — procedural generation of worlds/resources
  across the branches (already framed for "text adventures, MUDs, or any system
  where a player looks at something").
- **[samplex](samplex.md)** — async/parallel work engine to drive per-request
  and per-tick simulation.

*(Speculative — not yet designed. Listed so the dependency intuition isn't lost.)*

## 9. Open questions / to flesh out later

These are deliberately unanswered — flags for the next pass, not decisions:

- **What to prototype first?** The two loops are mechanically very different
  (autobattler combat vs. crafting/transformation). Pick the one to build first
  such that it *feeds* the other — i.e. which is the better seed? Can combat be
  prototyped against placeholder resources, or does the crafting loop need
  combat-sourced inputs before it's interesting? (Near-term decision.)
- **Combat reward track** — what is it, beyond clout? (See §3 note.) How does it
  relate to the mega-project voting weight?
- **Divergent end states** (§4.1) — what are the actual competing win
  conditions, and how does the vote arbitrate when crafters and fighters want
  opposite outcomes?
- **How is a cycle lost?** (§6.2) — wipe, failure-to-reach-an-end-state, a
  branch falling, or something else?
- **Collective vs. per-player awareness** (§6.3) — how do the two scales combine
  into the actual difficulty a given player faces? What stops a high-collective
  server from still steamrolling a fresh joiner?
- What exactly is a "factory-like task" in API terms? What does a mind ship
  *do* per request?
- What are the units of contribution that weight a player's vote?
- What is the autobattler's combat model (auto-resolved? player-configured
  loadouts?)?
- How do the weekly reset and the multiverse branches interact — do branches
  persist across resets, or reset with the world?
- What carries over a reset, if anything (meta-progression vs. clean slate)?
- Win/lose condition per cycle — is rebuilding ever "finished"?
