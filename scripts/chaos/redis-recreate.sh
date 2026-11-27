#!/bin/sh
# Redis 를 새 주소로 다시 만들어도 ingest 와 worker 가 따라가는지 본다(30 작업).
# 부하를 건 채 Redis 를 멈추고, 옛 주소는 다른 컨테이너가 차지하게 한 뒤 Redis 를 새로 만든다.
#
#   docker compose --profile core up -d && ./gradlew :loadgen:installDist
#   scripts/chaos/redis-recreate.sh [부하 초, 기본 150]
#
# 실험 프로젝트(3, loadgenhigh3)가 있어야 한다(scripts/experiments/seed.sql). ingest 는 호스트 18080 으로 받는다.
set -e
cd "$(dirname "$0")/../.."
OUT=scripts/experiments/.run/redis-recreate-load.txt
mkdir -p scripts/experiments/.run
loadgen/build/install/loadgen/bin/loadgen sustained --dsn=http://loadgenhigh3@localhost:18080/3 --rate=200 \
  --duration="${1:-150}s" --batch=10 --fingerprints=100 > "$OUT" 2>&1 &
LG=$!
sleep 20
OLD=$(docker inspect errdeck-redis-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
docker compose stop redis > /dev/null 2>&1
docker run -d --rm --name errdeck-ipholder --network errdeck_default --ip "$OLD" redis:7 sleep 600 > /dev/null
docker compose up -d --force-recreate --no-deps redis > /dev/null 2>&1
NEW=$(docker inspect errdeck-redis-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
echo "redis $OLD -> $NEW ($(date +%T))"
wait $LG
docker rm -f errdeck-ipholder > /dev/null 2>&1
sed -n '/== 결과/,$p' "$OUT"
