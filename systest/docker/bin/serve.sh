#!/usr/bin/env bash
# Container-side: run the staged wilds-server against the compose PostgreSQL.
# Binds loopback only (dev-local auth's startup fuse forbids anything else); the
# runner container joins this container's network namespace to reach it.
set -euo pipefail
exec java -XX:+UseCompactObjectHeaders -cp '/out/server/lib/*' com.kotharworks.wilds.server.Main \
    --bind 127.0.0.1 --port 8080 \
    --seed "${WILDS_SEED:-1}" \
    --time-scale "${WILDS_TIME_SCALE:-10}" \
    --pump-interval-ms "${WILDS_PUMP_INTERVAL_MS:-100}" \
    --checkpoint-interval-ms "${WILDS_CHECKPOINT_INTERVAL_MS:-15000}" \
    --rate-per-sec "${WILDS_RATE_PER_SEC:-10}" --rate-burst "${WILDS_RATE_BURST:-20}" \
    --storage postgres \
    --jdbc-url "jdbc:postgresql://postgres:5432/wilds" --db-user wilds --db-password wilds
