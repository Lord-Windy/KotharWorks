#!/usr/bin/env bash
# KotharWorks system test entry point — everything runs in Docker.
#
#   ./systest.sh                 full gate: unit + integration suite, then the live suite
#   ./systest.sh unit            only the regular Maven suite (mvn install, incl. Testcontainers)
#   ./systest.sh live            only the live suite (build skips unit tests)
#   ./systest.sh down            tear down a stack left running with --keep
#   ./systest.sh clean           remove the stack, volumes (incl. Maven cache), image and reports
#
# Live-suite options:
#   --tag TAG        only tests with this JUnit tag (repeatable): smoke, human, bot, mixed,
#                    security, regression, soak, restart, headless
#   --test NAME      only test classes whose name contains NAME (or matches a regex)
#   --no-restart     skip the kill -9 / restart / after-restart phase
#   --with-restart   run the restart phase even when --tag/--test filters are given
#   --soak SECONDS   bot fleet soak duration (default 20)
#   --keep           leave the stack running afterwards (inspect, then ./systest.sh down)
#   -- ARGS...       pass remaining args straight to the JUnit console launcher
#
# Reports land in systest/reports/ (unit/, live/main/, live/after-restart/,
# live/artifacts/ for browser screenshots + traces, live/*.log for server logs).
# Exit code is 0 only if every selected stage passed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYSTEST="$ROOT/systest"
REPORTS="$SYSTEST/reports"

c_blue=$'\033[1;34m'; c_green=$'\033[1;32m'; c_red=$'\033[1;31m'; c_off=$'\033[0m'
say()  { printf '%s[systest]%s %s\n' "$c_blue" "$c_off" "$*"; }
pass() { printf '%s[systest] %s%s\n' "$c_green" "$*" "$c_off"; }
fail() { printf '%s[systest] %s%s\n' "$c_red" "$*" "$c_off" >&2; }
usage() { sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

cmd=all
case "${1:-}" in
    all|unit|live|down|clean) cmd=$1; shift ;;
    -h|--help|help) usage; exit 0 ;;
esac

junit_args=()
filtered=0
restart=auto
keep=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) junit_args+=(--include-tag "$2"); filtered=1; shift 2 ;;
        --test)
            pattern=$2
            [[ "$pattern" =~ [\.\*\^\$\[\(\|] ]] || pattern=".*${pattern}.*"
            junit_args+=(--include-classname "$pattern"); filtered=1; shift 2 ;;
        --no-restart) restart=no; shift ;;
        --with-restart) restart=yes; shift ;;
        --soak) export SYSTEST_SOAK_SECONDS=$2; shift 2 ;;
        --keep) keep=1; shift ;;
        --) shift; junit_args+=("$@"); filtered=1; break ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown option: $1"; usage; exit 2 ;;
    esac
done
if [[ $restart == auto ]]; then
    restart=$([[ $filtered == 0 ]] && echo yes || echo no)
fi

command -v docker >/dev/null || { fail "docker is required"; exit 2; }
docker compose version >/dev/null 2>&1 || { fail "docker compose v2+ is required"; exit 2; }

PLAYWRIGHT_VERSION=$(sed -n 's:.*<playwright.version>\(.*\)</playwright.version>.*:\1:p' "$SYSTEST/pom.xml" | head -1)
export PLAYWRIGHT_VERSION
sdk_java=$(sed -n 's/^java=\([0-9.]*\).*/\1/p' "$ROOT/.sdkmanrc" 2>/dev/null | head -1)
if [[ -n $sdk_java ]] && ! grep -q "eclipse-temurin:${sdk_java}_" "$SYSTEST/docker/Dockerfile"; then
    fail "WARNING: .sdkmanrc pins java=$sdk_java but systest/docker/Dockerfile JDK_IMAGE differs — Docker results may not match dev machines"
fi
export HOST_UID=$(id -u) HOST_GID=$(id -g)
export COMPOSE_PROGRESS=${COMPOSE_PROGRESS:-plain}
compose() { docker compose -f "$SYSTEST/compose.yaml" "$@"; }
now_ms() { local t; t=$(date +%s%3N 2>/dev/null); [[ $t =~ ^[0-9]+$ ]] && echo "$t" || echo $(( $(date +%s) * 1000 )); }

stack_down() { compose --profile tools down -v --remove-orphans >/dev/null 2>&1 || true; }

case "$cmd" in
    down) stack_down; say "stack removed"; exit 0 ;;
    clean)
        stack_down
        docker volume rm kotharworks-systest-m2 >/dev/null 2>&1 || true
        docker image rm "kotharworks-systest-toolbox:$PLAYWRIGHT_VERSION" >/dev/null 2>&1 || true
        rm -rf "$REPORTS"
        say "removed stack, Maven cache volume, toolbox image and reports"
        exit 0 ;;
esac

started=$(date +%s)
mkdir -p "$REPORTS"
docker volume create kotharworks-systest-m2 >/dev/null

say "toolbox image (JDK 25 + Maven + Chromium, Playwright $PLAYWRIGHT_VERSION)"
compose build toolbox >/dev/null || { fail "toolbox image build failed (re-run: docker compose -f systest/compose.yaml build toolbox)"; exit 1; }

stack_down
trap '[[ $keep == 1 ]] || stack_down' EXIT

# ---------------------------------------------------------------- build (+ unit stage)
if [[ $cmd == live ]]; then export SKIP_UNIT=1; else export SKIP_UNIT=0; fi
say "build$([[ $SKIP_UNIT == 0 ]] && echo ' + unit/integration suite')"
if ! compose run --rm build; then
    fail "BUILD$([[ $SKIP_UNIT == 0 ]] && echo '/UNIT') STAGE FAILED — see output above and systest/reports/unit/"
    exit 1
fi
[[ $SKIP_UNIT == 0 ]] && pass "unit + integration suite passed"
if [[ $cmd == unit ]]; then
    pass "SYSTEST PASSED (unit) in $(( $(date +%s) - started ))s"
    exit 0
fi

# ---------------------------------------------------------------- live stage
rm -rf "$REPORTS/live" && mkdir -p "$REPORTS/live"
dump_logs() { compose logs --no-color --timestamps server postgres > "$REPORTS/live/$1.log" 2>&1 || true; }

say "starting stack (postgres + wilds-server)"
if ! compose up -d --wait server; then
    dump_logs stack-boot
    fail "stack failed to become healthy — see systest/reports/live/stack-boot.log"
    exit 1
fi

status=0
say "live suite: main phase"
compose run --rm runner main ${junit_args[@]+"${junit_args[@]}"} || status=1

if [[ $restart == yes ]]; then
    dump_logs server-before-crash
    say "crash phase: SIGKILL wilds-server, boot it again on the same database"
    t0=$(now_ms)
    compose kill -s SIGKILL server >/dev/null
    if compose up -d --wait server; then
        # Downtime = kill -> container start (a lower bound: the JVM boots after that and
        # the logical clock resumes later still). Measuring to "healthy" would count
        # boot-time uptime as downtime. Falls back to 0 (weakest bound) if unparsable.
        started_at=$(docker inspect -f '{{.State.StartedAt}}' "$(compose ps -q server)" 2>/dev/null || true)
        started_ms=$(date -d "$started_at" +%s%3N 2>/dev/null || echo "")
        if [[ $started_ms =~ ^[0-9]+$ && $started_ms -gt $t0 ]]; then
            export SYSTEST_DOWNTIME_MS=$(( started_ms - t0 ))
        else
            export SYSTEST_DOWNTIME_MS=0
        fi
        say "server back (down ${SYSTEST_DOWNTIME_MS}ms before restart); live suite: after-restart phase"
        compose run --rm runner after-restart ${junit_args[@]+"${junit_args[@]}"} || status=1
    else
        fail "server did not recover after SIGKILL"
        status=1
    fi
fi
dump_logs server

elapsed=$(( $(date +%s) - started ))
if [[ $keep == 1 ]]; then
    say "stack left running. Poke it with:"
    say "  docker compose -f systest/compose.yaml exec server curl -s 127.0.0.1:8080/api/v1/meta"
    say "  docker compose -f systest/compose.yaml --profile tools run --rm runner main --include-classname '.*Bot.*'"
    say "tear down with: ./systest.sh down"
fi
if [[ $status == 0 ]]; then
    pass "SYSTEST PASSED ($cmd) in ${elapsed}s — reports: systest/reports/"
else
    fail "SYSTEST FAILED ($cmd) in ${elapsed}s — reports: systest/reports/live/ (artifacts/ has browser screenshots + traces)"
fi
exit $status
