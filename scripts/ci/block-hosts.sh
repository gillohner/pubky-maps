#!/usr/bin/env bash
# A DNS server on the runner (dnsmasq on 127.0.0.2:53) that answers NXDOMAIN for the given domains
# and all their subdomains and forwards every other name to the runner's resolver. The emulator
# uses it with `-dns-server 127.0.0.2`, so the phone cannot reach those hosts.
# Usage: block-hosts.sh start <state dir> <domain>...   |   block-hosts.sh stop <state dir>
set -euo pipefail

cmd=$1
dir=$2
shift 2
mkdir -p "$dir"

case "$cmd" in
start)
    args=()
    for domain in "$@"; do args+=("--address=/$domain/"); done
    upstream=$(awk '$1 == "nameserver" { print $2; exit }' /etc/resolv.conf)
    sudo dnsmasq --conf-file=/dev/null --listen-address=127.0.0.2 --port=53 --bind-interfaces \
        --no-resolv --no-hosts --server="${upstream:-127.0.0.53}" --cache-size=0 \
        --log-queries --log-facility="$dir/dnsmasq.log" --pid-file="$dir/dnsmasq.pid" "${args[@]}"
    sudo chmod 644 "$dir/dnsmasq.log" "$dir/dnsmasq.pid" 2> /dev/null || true
    # The block works on the runner too: blocked names fail, others resolve.
    for domain in "$@"; do
        if dig +short +time=3 +tries=1 @127.0.0.2 "$domain" A | grep -q .; then
            echo "::error::$domain still resolves through the blocking DNS server"
            exit 1
        fi
    done
    dig +short +time=3 +tries=2 @127.0.0.2 tiles.versatiles.org A | grep -q . || {
        echo "::error::the blocking DNS server does not resolve other names"
        exit 1
    }
    echo "blocking: $*"
    ;;
stop)
    if [ -f "$dir/dnsmasq.pid" ]; then
        sudo kill "$(cat "$dir/dnsmasq.pid")" 2> /dev/null || true
        sudo rm -f "$dir/dnsmasq.pid"
        sleep 1
    fi
    ;;
*)
    echo "usage: $0 start|stop <state dir> [domain...]" >&2
    exit 2
    ;;
esac
