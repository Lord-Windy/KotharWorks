local ql = require("queensland")

ql.ai.register("opencode", {
    bin = "opencode",
    default_args = { "run", "--auto" },
    timeout = 6000,
})

ql.ai.set_merge_resolver("opencode", "resolve_conflict.md")

-- Enable loop mode: merge to feature branch, review after each iteration
ql.loop.enable({
    review_callback = "review_merge",
})
ql.loop.max_iterations(20)

local function ticket_key(t)
    if type(t) == "table" then
        return t.key or t.id
    end
    return t
end

local function set_ticket_status(key, status)
    if not key then
        return false
    end
    local result = ql.exec(".", "st", "status", key, status)
    if result.success then
        ql.log("info", string.format("Ticket %s -> %s", key, status))
    else
        ql.log("warn", string.format(
            "Failed to set ticket %s -> %s: %s", key, status, result.stderr or ""))
    end
    return result.success
end

local function ai_error(result, phase)
    -- The AI CLIs report some failures (e.g. expired auth) on stdout with an
    -- empty stderr, so fall back to stdout or the message is uselessly blank.
    local detail = result.stderr
    if detail == nil or detail == "" then
        detail = result.stdout or ""
    end
    return string.format("%s failed: %s", phase, detail)
end

local function fetch_tickets()
    local result = ql.exec(".", "st", "list", "--ready", "--project", "KOTHARWORKS", "--json")

    if not result.success then
        error("Failed to fetch tickets: " .. result.stderr)
    end

    local raw = ql.json.decode(result.stdout)
    local tickets = {}
    for _, t in ipairs(raw) do
        table.insert(tickets, {
            id = t.key,
            key = t.key,
            summary = t.summary,
            description = t.description,
            labels = t.labels,
            status = t.status,
            project = t.project,
        })
    end
    return tickets
end

function process_ticket(ticket)
    local dir = context.worktree_path
    local branch = "ticket-" .. ticket.id:lower()

    -- Move the ticket out of the ready queue while we work on it so it is not
    -- picked up again on the next loop iteration.
    set_ticket_status(ticket.id, "in-progress")

    -- Phase 1: Implement
    local impl_prompt = ql.prompt("implement.md", { ticket = ticket })

    local impl_result = ql.ai.run({
        tool = "opencode",
        cwd = dir,
        -- cwd alone pins opencode to the ticket worktree: current versions
        -- resolve the project root from the working directory and follow
        -- linked worktrees (.git file) correctly. (Older builds needed an
        -- explicit flag here, but `opencode run` rejects --dir now.)
        prompt = impl_prompt,
    })

    if not impl_result.success then
        error(ai_error(impl_result, "Implementation"))
    end

    local status_result = ql.exec(dir, "git", "status", "--porcelain")
    if status_result.success and status_result.stdout ~= "" then
        ql.git.commit(dir, string.format("%s: %s", ticket.id, ticket.summary))
        ql.git.push(dir, branch)
    end

    -- Phase 2: Review
    local review_prompt = ql.prompt("review.md", { ticket = ticket })

    local review_result = ql.ai.run({
        tool = "opencode",
        cwd = dir,
        prompt = review_prompt,
    })

    if not review_result.success then
        error(ai_error(review_result, "Review"))
    end

    status_result = ql.exec(dir, "git", "status", "--porcelain")
    if status_result.success and status_result.stdout ~= "" then
        ql.git.commit(dir, string.format("%s: review %s", ticket.id, ticket.summary))
        ql.git.push(dir, branch)
    end

    ql.git.queue_merge(ticket.id, branch)

    return {
        status = "success",
        branch = branch,
        dir = dir,
    }
end

function review_merge(ctx)
    ql.log("info", string.format(
        "Review iteration %d: %d tickets merged (total: %d), %d failed",
        ctx.iteration,
        #ctx.tickets_merged,
        ctx.total_merged,
        #ctx.tickets_failed
    ))

    -- Mark every ticket that landed on the feature branch as done. This drains
    -- the ready queue so fetch_tickets() eventually returns nothing and the
    -- loop terminates instead of re-processing the same tickets forever.
    for _, ticket in ipairs(ctx.tickets_merged) do
        set_ticket_status(ticket_key(ticket), "done")
    end

    local review_prompt = ql.prompt("review_merge.md", {
        tickets = ctx.tickets_merged,
        feature_branch = ctx.feature_branch,
        iteration = ctx.iteration,
    })

    local result = ql.ai.run({
        tool = "opencode",
        cwd = ".",
        prompt = review_prompt,
    })

    if not result.success then
        ql.log("warn", ai_error(result, "Review merge"))
    end

    local status_result = ql.exec(".", "git", "status", "--porcelain")
    if status_result.success and status_result.stdout ~= "" then
        ql.git.commit(".", string.format("review merge iteration %d", ctx.iteration))
        ql.git.push(".", ctx.feature_branch)
    end
end

if context and context.action == "process_ticket" then
    return process_ticket(context.ticket)
end

return {
    project = "KOTHARWORKS",
    tickets = fetch_tickets(),
    concurrency = 1,
}
