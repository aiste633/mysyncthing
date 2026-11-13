#!/usr/bin/env bash
# The root half of scripts/e2e/net.py: a network namespace for its test servers, and a small helper
# that runs a fixed list of actions for net.py, which itself runs as the repository's owner.
#
#   scripts/e2e/netns.sh up     (as root, before net.py)
#   scripts/e2e/netns.sh down   (as root, after net.py, also after a failed run)
#
# up: namespace "mst-net" with one veth pair, 198.51.100.1 on the host side and 198.51.100.2 inside
# (TEST-NET-2, RFC 5737: reserved, never on a real network, and allowed by scripts/check-leaks.sh). The
# namespace has no route out, and a firewall rule in it refuses every new connection to the host
# side: its processes can answer the emulator but reach nothing on this machine, so the test peer
# can never dial the phone (it is behind the emulator's NAT) nor any Syncthing of this machine.
# Then it starts the helper, which reads one request per line from /run/mst-net/ctl (a FIFO only
# the owner may write), "<id> <verb> <arg>", and answers "<id> ok|fail ..." on /run/mst-net/reply.
# Every request is checked against this list; anything else is refused:
#   start disco|relay|peer   the test discovery server, relay or peer, inside the namespace, as the
#                            owner, from <repo>/build/nettest/bin with fixed arguments
#   stop  disco|relay|peer
#   net   clean|slow|cut     netem on both ends of the link: none; 150±30 ms, 1 % loss, 2 Mbit/s;
#                            100 % loss (a silent cut, as a real one: no error reaches either side)
# down: stops the helper and every process in the namespace, removes the namespace and its link.
# Nothing else on the machine is changed: no ip_forward, no host firewall rule, no route.
set -euo pipefail

NS=mst-net HOST_IF=mst-h NS_IF=mst-n HOST_IP=198.51.100.1 NS_IP=198.51.100.2 RUN=/run/mst-net
REPO=$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)
T="$REPO/build/nettest"
OWNER=$(stat -c %U "$REPO")
SELF=$(readlink -f "$0")

die() { echo "netns.sh: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || die "run as root"
[ "$OWNER" != root ] || die "the repository belongs to root; it must belong to the user who runs net.py"

nsx() { ip netns exec "$NS" "$@"; }

# The command line that runs "${@:3}" as the owner inside the namespace, with HOME=$1 and its output
# in $2. Root never opens a file under $T: the log is opened by the owner's shell. Every step execs,
# so the pid of the background job is the server itself.
# shellcheck disable=SC2016  # $1 and $@ in the single quotes are the inner shell's
owner_cmd() {
  CMD=(ip netns exec "$NS" setpriv --reuid="$OWNER" --regid="$OWNER" --init-groups --inh-caps=-all --reset-env
       env HOME="$1" STNODEFAULTFOLDER=1 STNOUPGRADE=1 sh -c 'log=$1; shift; exec "$@" >"$log" 2>&1' sh "$2" "${@:3}")
}

start() {
  local name=$1
  [ ! -s "$RUN/$name.pid" ] || ! kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null || die "$name already runs"
  case $name in
    disco) owner_cmd "$T/disco" "$T/disco.log" "$T/bin/stdiscosrv" --listen="$NS_IP:8443" \
             --cert="$T/disco/cert.pem" --key="$T/disco/key.pem" --db-dir="$T/disco" ;;
    relay) owner_cmd "$T/relay" "$T/relay.log" "$T/bin/strelaysrv" -listen="$NS_IP:22067" -keys="$T/relay" \
             -pools= -status-srv= -protocol=tcp4 ;;
    peer)  owner_cmd "$T/peer" "$T/peer-stdout.log" "$T/bin/st-peer" serve --home="$T/peer" --no-browser \
             --no-restart --log-file="$T/peer.log" ;;
  esac
  "${CMD[@]}" </dev/null &
  echo $! >"$RUN/$name.pid"
  sleep 1
  kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null || die "$name exited at once (see $T/$name*.log)"
}

stop() {
  local f="$RUN/$1.pid" pid
  [ -s "$f" ] || return 0
  pid=$(cat "$f"); rm -f "$f"
  kill "$pid" 2>/dev/null || return 0
  for _ in $(seq 20); do kill -0 "$pid" 2>/dev/null || return 0; sleep 0.5; done
  kill -9 "$pid" 2>/dev/null || true
}

net() {
  local q=()
  case $1 in
    clean) ;;
    slow)  q=(netem delay 150ms 30ms distribution normal loss 1% rate 2mbit) ;;
    cut)   q=(netem loss 100%) ;;
  esac
  tc qdisc del dev "$HOST_IF" root 2>/dev/null || true
  nsx tc qdisc del dev "$NS_IF" root 2>/dev/null || true
  if [ ${#q[@]} -gt 0 ]; then
    tc qdisc add dev "$HOST_IF" root "${q[@]}"
    nsx tc qdisc add dev "$NS_IF" root "${q[@]}"
  fi
}

serve() {
  # Held open for reading and writing: the FIFO always has a reader, so no request is lost between two
  # reads and a writer never waits; each line is one request, "<id> <verb> <arg>", answered "<id> ...".
  exec 3<>"$RUN/ctl"
  local id verb arg out
  while read -r id verb arg <&3; do
    [[ $id =~ ^[0-9]{1,18}$ ]] || { echo "$(date -u +%H:%M:%S) refused, no id: $id $verb $arg" >>"$RUN/helper.log"; continue; }
    case "$verb $arg" in
      "start disco"|"start relay"|"start peer"|"stop disco"|"stop relay"|"stop peer"|"net clean"|"net slow"|"net cut")
        if out=$( ("$verb" "$arg") 2>&1); then out="ok $out"; else out="fail $out"; fi ;;
      *) out="fail refused: $verb $arg" ;;
    esac
    echo "$(date -u +%H:%M:%S) $id $verb $arg -> $out" >>"$RUN/helper.log"
    # Opening a FIFO waits for its reader. A net.py stopped between its request and the answer never
    # reads it: give up after 10 s, so one stopped run cannot hold up the next (which skips answers
    # that are not for its own id).
    # shellcheck disable=SC2016  # $1 and $2 are the inner shell's
    timeout 10 bash -c 'printf "%s\n" "$1" >"$2"' _ "$id ${out//$'\n'/ }" "$RUN/reply" \
      || echo "$(date -u +%H:%M:%S) answer to $id not read by anyone; dropped" >>"$RUN/helper.log"
  done
}

up() {
  ! ip netns list | grep -qw "$NS" || die "$NS exists; run 'netns.sh down' first"
  command -v nft >/dev/null || die "nft is missing (apt install nftables)"
  command -v setpriv >/dev/null || die "setpriv is missing (util-linux)"
  command -v modprobe >/dev/null && modprobe sch_netem || true
  ip netns add "$NS"
  ip link add "$HOST_IF" type veth peer name "$NS_IF"
  ip link set "$NS_IF" netns "$NS"
  ip addr add "$HOST_IP/24" dev "$HOST_IF"
  ip link set "$HOST_IF" up
  tc qdisc add dev "$HOST_IF" root netem delay 1ms && tc qdisc del dev "$HOST_IF" root \
    || { ip netns del "$NS"; die "netem does not work here (kernel module sch_netem)"; }
  nsx ip addr add "$NS_IP/24" dev "$NS_IF"
  nsx ip link set "$NS_IF" up
  nsx ip link set lo up
  nsx nft -f - <<EOF
table inet mst {
  chain out {
    type filter hook output priority 0; policy accept;
    ip daddr $HOST_IP ct state new reject
  }
}
EOF
  mkdir -p "$RUN"
  chmod 755 "$RUN"
  rm -f "$RUN/ctl" "$RUN/reply"
  mkfifo -m 600 "$RUN/ctl" "$RUN/reply"
  chown "$OWNER": "$RUN/ctl" "$RUN/reply"
  : >"$RUN/helper.log"
  setsid "$SELF" serve </dev/null >>"$RUN/helper.log" 2>&1 &
  echo $! >"$RUN/helper.pid"
  echo "up: $NS ($HOST_IP <-> $NS_IP), helper pid $(cat "$RUN/helper.pid"); servers run as $OWNER from $T"
}

down() {
  for n in peer relay disco; do stop "$n"; done
  if [ -s "$RUN/helper.pid" ]; then kill "$(cat "$RUN/helper.pid")" 2>/dev/null || true; fi
  if ip netns list | grep -qw "$NS"; then
    pids=$(ip netns pids "$NS" || true)
    [ -z "$pids" ] || kill $pids 2>/dev/null || true
    sleep 1
    pids=$(ip netns pids "$NS" || true)
    [ -z "$pids" ] || kill -9 $pids 2>/dev/null || true
    ip netns del "$NS"
  fi
  ip link del "$HOST_IF" 2>/dev/null || true
  rm -rf "$RUN"
  echo "down: $NS removed"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  serve) serve ;;
  *) die "usage: netns.sh up|down" ;;
esac
