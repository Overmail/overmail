#!/usr/bin/env bash
set -e

# The image runs all three processes: the Ktor server, the SvelteKit server and the Caddy that
# puts them behind one port. Nothing here is used locally -- werkbank starts the same pieces from
# the Werkbankfile.

function start_server {
  # Reads /data/config.json, see ApplicationConfig.
  OVERMAIL_STORAGE_DIRECTORY=/data java -jar /app/server.jar
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
