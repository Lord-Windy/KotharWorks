#!/usr/bin/env bash
# Container-side: build the reactor from a pristine copy of the checkout, run the
# regular unit + integration suite (unless SKIP_UNIT=1), and stage runnable
# classpaths for the server, the headless CLI and the live suite into /out.
#
#   /src     read-only bind of the repository (never written to)
#   /work    scratch copy that Maven builds in (ephemeral)
#   /root/.m2  named volume: Maven repository cache shared across runs
#   /out     named volume: staged artifacts consumed by server + runner
#   /reports bind of systest/reports on the host
set -uo pipefail

log() { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }

finish() {
    local rc=$?
    if [[ -n "${HOST_UID:-}" ]]; then
        chown -R "${HOST_UID}:${HOST_GID:-$HOST_UID}" /reports 2>/dev/null || true
    fi
    exit "$rc"
}
trap finish EXIT

log "copying sources (excluding .git, target/, worktrees/, reports)"
rm -rf /work && mkdir -p /work
tar -C /src \
    --exclude=./.git --exclude=./worktrees --exclude=./.idea --exclude=./.zcode \
    --exclude=./systest/reports --exclude=target \
    -cf - . | tar -C /work -xf -
cd /work

MVN=(mvn -B -ntp -Psystest)
DEP_PLUGIN=org.apache.maven.plugins:maven-dependency-plugin:3.8.1

if [[ "${SKIP_UNIT:-0}" == "1" ]]; then
    log "mvn install (unit/integration tests SKIPPED)"
    "${MVN[@]}" -DskipTests install ${MVN_EXTRA_ARGS:-}
    rc=$?
else
    log "mvn install — full unit + integration suite (Testcontainers via host Docker)"
    "${MVN[@]}" install ${MVN_EXTRA_ARGS:-}
    rc=$?
    rm -rf /reports/unit && mkdir -p /reports/unit
    for dir in */*/target/surefire-reports; do
        [[ -d "$dir" ]] || continue
        module=$(echo "$dir" | cut -d/ -f1-2 | tr / -)
        cp -r "$dir" "/reports/unit/$module"
    done
    log "surefire reports -> systest/reports/unit/"
fi
if [[ $rc -ne 0 ]]; then
    log "Maven build FAILED (exit $rc)"
    exit "$rc"
fi

log "staging runnable classpaths into /out"
"${MVN[@]}" -q -pl apps/wilds-server,apps/wilds-headless \
    "$DEP_PLUGIN:copy-dependencies" -DincludeScope=runtime || exit $?
rm -rf /out/* && mkdir -p /out/server/lib /out/headless/lib /out/systest
cp apps/wilds-server/target/wilds-server-*.jar apps/wilds-server/target/dependency/*.jar /out/server/lib/
cp apps/wilds-headless/target/wilds-headless-*.jar apps/wilds-headless/target/dependency/*.jar /out/headless/lib/
cp -r systest/target/test-classes /out/systest/classes
cp -r systest/target/systest-lib /out/systest/lib
cp systest/target/junit-console.jar /out/systest/
log "done"
