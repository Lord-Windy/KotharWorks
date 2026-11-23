#!/usr/bin/env bash
# Container-side: run one phase of the live suite with the JUnit console launcher.
#
#   run-live.sh <phase> [junit console args...]
#
# phase "main"          everything except @Tag("after-restart")
# phase "after-restart" only @Tag("after-restart") (fresh JVM, post kill -9)
# Extra args are passed straight to the launcher (e.g. --include-tag bot,
# --include-classname '.*Human.*').
set -uo pipefail

phase=${1:?phase required}; shift
reports=/reports/live/$phase
rm -rf "$reports" && mkdir -p "$reports"

finish() {
    local rc=$?
    if [[ -n "${HOST_UID:-}" ]]; then
        chown -R "${HOST_UID}:${HOST_GID:-$HOST_UID}" /reports 2>/dev/null || true
    fi
    exit "$rc"
}
trap finish EXIT

cp=/out/systest/classes
for jar in /out/systest/lib/*.jar; do cp="$cp:$jar"; done

case "$phase" in
    main) phase_args=(--exclude-tag after-restart) ;;
    after-restart) phase_args=(--include-tag after-restart) ;;
    *) echo "unknown phase: $phase" >&2; exit 2 ;;
esac

export SYSTEST_ARTIFACTS_DIR=/reports/live/artifacts
export SYSTEST_STATE_DIR=/state
export SYSTEST_HEADLESS_CP="$(printf '%s:' /out/headless/lib/*.jar)"

java -XX:+UseCompactObjectHeaders -jar /out/systest/junit-console.jar execute \
    --disable-banner --details=tree --details-theme=ascii \
    --class-path "$cp" --scan-class-path /out/systest/classes \
    --reports-dir "$reports" \
    "${phase_args[@]}" "$@"
