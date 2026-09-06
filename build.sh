#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
image='gradle:8.1.1-jdk17@sha256:e7a4bc8f4ee27feae2eac4de61ca64406b7137e6f6b107052accd24bf0806043'
bash "$root/scripts/fetch-dependencies.sh"
command -v docker >/dev/null || { echo 'Docker is required; see docs/DEVELOPMENT.md for native builds.' >&2; exit 1; }
name="internal-furnace-build-$(id -u)-$$"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
args=(--rm --name "$name" --memory=3g --cpus=2 --pids-limit=512 --user "$(id -u):$(id -g)"
      -e GRADLE_USER_HOME=/work/.gradle-home -v "$root:/work" -w /work)
timeout --signal=TERM --kill-after=30s 1800 docker run "${args[@]}" "$image" \
    gradle --no-daemon --max-workers=1 clean build
bash "$root/verify.sh"
