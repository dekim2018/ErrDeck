#!/bin/sh
# PostgreSQL 을 매일 덤프한다(17.6). ClickHouse 는 보존 기간이 짧은 기록이라 백업하지 않는다.
# 서버의 crontab 에:  15 4 * * * cd /home/ubuntu/ErrDeck && scripts/deploy/backup.sh >> backups/backup.log 2>&1
set -eu
dir=${ERRDECK_BACKUP_DIR:-./backups}
keep=${ERRDECK_BACKUP_DAYS:-14}
mkdir -p "$dir"
file="$dir/errdeck-$(date +%Y%m%d-%H%M).sql.gz"
docker compose exec -T postgres pg_dump -U errdeck -d errdeck --no-owner | gzip > "$file"
find "$dir" -name 'errdeck-*.sql.gz' -mtime +"$keep" -delete
echo "$(date -Iseconds) $file $(du -h "$file" | cut -f1)"
