#!/usr/bin/env bash
# 빌드한 프론트의 소스맵을 ErrDeck api 에 올린다(7.2). 배포보다 먼저 실행하고, 실패하면 배포하지 않는다(7.5).
#
#   ERRDECK_TOKEN=edt_... scripts/upload-sourcemaps.sh <api 주소> <프로젝트 ID> <릴리스> <dist 폴더>
#   예: scripts/upload-sourcemaps.sh http://localhost:8081 9002 demo-web@1a2b3c4 demo-web/dist
#
# 릴리스는 SDK 초기화에 넣은 값과 같아야 한다. 파일 이름은 dist 기준 상대 경로에 .map 이 붙은 것이다(assets/index-a1.js.map).
set -euo pipefail

if [ $# -ne 4 ] || [ -z "${ERRDECK_TOKEN:-}" ]; then
  echo "사용법: ERRDECK_TOKEN=... $0 <api 주소> <프로젝트 ID> <릴리스> <dist 폴더>" >&2
  exit 2
fi
api="${1%/}"; project="$2"; release="$3"; dist="${4%/}"

curl -fsS -X POST "$api/api/projects/$project/releases" \
  -H "Authorization: Bearer $ERRDECK_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"version\":\"$release\"}" > /dev/null

count=0
while IFS= read -r -d '' file; do
  name="${file#"$dist/"}"
  curl -fsS -X PUT "$api/api/projects/$project/releases/$release/artifacts?name=$name" \
    -H "Authorization: Bearer $ERRDECK_TOKEN" -H 'Content-Type: application/json' \
    --data-binary "@$file" > /dev/null
  echo "올림: $name"
  count=$((count + 1))
done < <(find "$dist" -name '*.map' -type f -print0)

if [ "$count" -eq 0 ]; then
  echo "소스맵이 없다. 빌드 설정에 build.sourcemap: 'hidden' 이 있는지 확인한다" >&2
  exit 1
fi
echo "릴리스 $release 에 소스맵 ${count}개를 올렸다"
