FROM oven/bun:alpine AS bun-env
FROM caddy:2-alpine AS caddy-source

FROM eclipse-temurin:26-jre-alpine

RUN apk add --no-cache bash libstdc++
COPY --from=bun-env /usr/local/bin/bun /usr/local/bin/bun
COPY --from=caddy-source /usr/bin/caddy /usr/bin/caddy

WORKDIR /app
COPY web_build /app/web
COPY server-all.jar /app/server.jar
COPY deploy /app/deploy

# Config and everything the server writes, see ApplicationConfig.
VOLUME /data

EXPOSE 80

ENTRYPOINT ["bash", "/app/deploy/production/entrypoint.bash"]
