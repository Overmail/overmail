#!/usr/bin/env bash
set -e

# The image runs all three processes: the Ktor server, the SvelteKit server and the Caddy that
# puts them behind one port. Nothing here is used locally -- werkbank starts the same pieces from
# the Werkbankfile.

function start_server {
  # Reads /data/config.json, see ApplicationConfig.
  # The percentage is of the container's memory limit, or of the whole host without one -- so the
  # container needs a limit. The heap gets less than half of it: the JVM needs more beside it
  # (Netty's direct buffers, capped here, metaspace, threads), and bun and Caddy share the rest.
  # A full heap or too many direct buffers end the process with a heap dump in /data, so there is
  # something to analyse; `jcmd <pid> VM.native_memory summary` tells where the rest goes.
  OVERMAIL_STORAGE_DIRECTORY=/data java \
    -XX:MaxRAMPercentage=45 \
    -XX:MaxDirectMemorySize=256m \
    -XX:NativeMemoryTracking=summary \
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

start_server & start_web & start_proxy &

# The container ends with the first process that does, so its restart policy brings all three
# back. A plain `wait` would keep it up on the other two, with the server gone for good.
wait -n
