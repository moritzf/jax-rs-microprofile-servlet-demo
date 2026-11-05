#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
umask 077
mkdir -p state
exec 9>state/lock
flock --nonblock 9 || exit 0

repository=ghcr.io/moritzf/jax-rs-microprofile-servlet-demo
channel="$repository:production"
current=
rejected=
[[ ! -f state/current ]] || read -r current < state/current
[[ ! -f state/rejected ]] || read -r rejected < state/rejected

if ! pull_output=$(docker pull "$channel"); then
    # Keep the last working release available during a registry outage.
    if [[ -n "$current" ]]; then
        APP_IMAGE="$current" docker compose up -d --pull missing
    fi
    exit 1
fi

digest=$(awk '$1 == "Digest:" {print $2}' <<< "$pull_output")
if [[ ! $digest =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "Could not resolve $channel to a repository digest." >&2
    exit 1
fi
candidate="$repository@$digest"

if [[ "$candidate" == "$rejected" ]]; then
    if [[ -n "$current" ]]; then
        APP_IMAGE="$current" docker compose up -d --pull missing
    fi
    exit 0
fi

if [[ "$candidate" != "$current" ]]; then
    echo "Deploying $candidate"
    if APP_IMAGE="$candidate" docker compose up -d --no-deps --pull never app &&
        curl --fail --silent --show-error --output /dev/null --retry 30 --retry-delay 2 \
            --retry-all-errors --max-time 5 http://127.0.0.1:9080/api/trails/kammspur/reports; then
        printf '%s\n' "$candidate" > state/current.tmp
        mv -f state/current.tmp state/current
        rm -f state/rejected
        current=$candidate
    else
        APP_IMAGE="$candidate" docker compose logs --tail=50 app >&2 || true
        if [[ -n "$current" ]]; then
            APP_IMAGE="$current" docker compose up -d --no-deps --pull missing app
        else
            APP_IMAGE="$candidate" docker compose stop app
        fi
        printf '%s\n' "$candidate" > state/rejected.tmp
        mv -f state/rejected.tmp state/rejected
        echo "Rejected $candidate after failed startup." >&2
        exit 1
    fi
fi

APP_IMAGE="$current" docker compose up -d --pull missing
