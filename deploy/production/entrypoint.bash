#!/usr/bin/env bash
set -e

# The image runs all three processes: the Ktor server, the SvelteKit server and the Caddy that
# puts them behind one port. Nothing here is used locally -- werkbank starts the same pieces from
# the Werkbankfile.

function start_server {
  # Reads /data/config.json, see ApplicationConfig.
  # The heap gets most of the container, which it shares with bun and Caddy. A full heap ends the
  # process with a heap dump in /data, so there is something to analyse.
  OVERMAIL_STORAGE_DIRECTORY=/data java \
    -XX:MaxRAMPercentage=60 \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/data \
    -jar /app/server.jar
}

function start_web {
  cd /app/web
  bun index.js
}

function start_proxy {
  echo "Starting Caddy reverse proxy..."
  caddy run --config /app/deploy/production/Caddyfile --adapter caddyfile
}

start_server & start_web & start_proxy & wait
