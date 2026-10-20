#!/usr/bin/env bash
# iroh spike — RELAY leg driver.
# Topology: two containers on SEPARATE docker bridge networks (inter-bridge
# traffic dropped by docker isolation), both with internet NAT. Direct IP
# paths between them are impossible, so all flow traverses the N0 public
# relay fleet — the honest relay-only + rate-limit leg. Mid-run, the joiner
# is attached to the host's bridge, opening a direct path so the
# relay->direct path-change stall is measured from the ping stream.
set -uo pipefail

OUT=/e/pocket_realm_complete/out/completion/w6/n2
# Code-red round-5 R3-r5-1: the spike identities live ONLY in the
# gitignored secrets.env beside the measurement record — never embedded
# in a tracked file. Loud refusal when absent: write HOST_SECRET /
# JOIN_SECRET (64-hex each) into that store and re-run. The measured-run
# identities died with their containers (docker rm -f below); the
# records the revocation posture.
SECRETS="$OUT/secrets.env"
if [ ! -f "$SECRETS" ]; then
  echo "refusing: $SECRETS is missing - the spike secrets live in the" >&2
  echo "gitignored store, never in this script; write HOST_SECRET and" >&2
  echo "JOIN_SECRET (64-hex each) there and re-run" >&2
  exit 2
fi
# shellcheck disable=SC1091
. "$SECRETS"
: "${HOST_SECRET:?secrets.env must define HOST_SECRET (64-hex)}"
: "${JOIN_SECRET:?secrets.env must define JOIN_SECRET (64-hex)}"
SPIKE_HOST_SECRET="$HOST_SECRET"
SPIKE_JOIN_SECRET="$JOIN_SECRET"
INSTALL='E:\pocket_realm_complete\spikes\iroh-n2\desktop-echo\build\install\iroh-n2-spike'
export MSYS_NO_PATHCONV=1

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

docker network create spike-net-a 2>/dev/null || true
docker network create spike-net-b 2>/dev/null || true
docker rm -f spike-host spike-join 2>/dev/null || true

log "starting host container on spike-net-a (relay leg, 420s window)"
docker run -d --name spike-host --network spike-net-a \
  -v "$INSTALL:/spike" eclipse-temurin:21-jre \
  /spike/bin/iroh-n2-spike host --secret "$SPIKE_HOST_SECRET" --relay-mode n0 --max-seconds 420

HOST_ID=""
HOME_RELAY=""
for i in $(seq 1 40); do
  sleep 2
  LINE=$(docker logs spike-host 2>&1 | grep -a "SPIKE_HOST_READY" | tail -1 || true)
  if [ -n "$LINE" ]; then
    HOST_ID=$(echo "$LINE" | grep -ao "id=[0-9a-f]*" | head -1 | cut -d= -f2)
    HOME_RELAY=$(echo "$LINE" | grep -ao "relay=[^ ]*" | head -1 | cut -d= -f2)
    [ "$HOME_RELAY" = "-" ] && HOME_RELAY=""
    [ -n "$HOST_ID" ] && [ -n "$HOME_RELAY" ] && break
  fi
done
if [ -z "$HOST_ID" ] || [ -z "$HOME_RELAY" ]; then
  log "FATAL: host never became ready"; docker logs spike-host 2>&1 | tail -20; exit 1
fi
log "host ready id=$HOST_ID home_relay=$HOME_RELAY"
docker logs spike-host > "$OUT/relay-host.log" 2>&1

HOST_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' spike-host)
log "host container IP (net-a): $HOST_IP"

log "starting joiner container on spike-net-b (isolated from net-a)"
docker run -d --name spike-join --network spike-net-b \
  -v "$INSTALL:/spike" eclipse-temurin:21-jre \
  /spike/bin/iroh-n2-spike join --secret "$SPIKE_JOIN_SECRET" \
  --host-id "$HOST_ID" --relay-mode n0 --relay "$HOME_RELAY" \
  --pings-sec 240 --bulk-mb 24

sleep 20
log "=== isolation + internet checks from the joiner container ==="
docker exec spike-join bash -c "timeout 3 bash -c 'cat < /dev/tcp/$HOST_IP/9' && echo NET_A_REACHABLE || echo NET_A_BLOCKED" 2>&1 | tail -1
docker exec spike-join bash -c "timeout 5 bash -c 'cat < /dev/tcp/1.1.1.1/443' && echo INTERNET_OK || echo INTERNET_FAIL" 2>&1 | tail -1

log "relay soak running; stall insertion (attach joiner to net-a) at ~+100s"
sleep 80
log "attaching spike-join to spike-net-a — direct path now possible"
docker network connect spike-net-a spike-join

sleep 160
log "waiting for joiner to finish"
for i in $(seq 1 40); do
  sleep 5
  if docker logs spike-join 2>&1 | grep -aq "SPIKE_SUMMARY"; then break; fi
done
docker logs spike-join > "$OUT/relay-join.log" 2>&1
docker logs spike-host > "$OUT/relay-host.log" 2>&1
docker rm -f spike-host spike-join >/dev/null 2>&1
log "relay leg COMPLETE — logs at $OUT/relay-{join,host}.log"
