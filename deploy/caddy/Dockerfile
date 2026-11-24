# syntax=docker/dockerfile:1
# 콘솔(web)을 빌드해 Caddy 가 정적 파일로 낸다. 저장소 루트에서 빌드한다(docker-compose.oci.yml 의 build.context).
FROM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM caddy:2
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=web /src/web/dist /srv
