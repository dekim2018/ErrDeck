# 오라클 무료 서버에 올리기

17.5 의 순서를 명령으로 적은 것이다(25 작업). 서버 접속과 명령 실행은 사용자가 한다. 서버를 만들기 전에 무료 한도(17.4: Arm 2코어, 12GB)를 오라클 문서에서 다시 확인한다.

## 1. 서버 만들기(오라클 콘솔)

1. 홈 리전은 서울이나 춘천. 나중에 바꿀 수 없다.
2. 인스턴스: VM.Standard.A1.Flex, OCPU 2, 메모리 12GB, Ubuntu 24.04, 부트 볼륨 100GB. SSH 공개 키 등록.
3. 용량 부족이면 다른 가용 도메인이나 시간을 두고 다시.
4. 가상 클라우드 네트워크의 보안 목록: 들어오는 TCP 80, 443 을 0.0.0.0/0 에, 22 는 내 IP 에만.

## 2. 서버 준비(SSH 로 접속해서)

```bash
# 인스턴스 안의 방화벽. 보안 목록만 열고 접속이 안 되면 대부분 이것이 빠진 것이다.
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save

# 도커
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker

git clone https://github.com/dekim2018/ErrDeck.git && cd ErrDeck
```

## 3. 도메인과 비밀

1. DuckDNS 에서 도메인(예: `errdeck.duckdns.org`)을 만들고 서버의 공인 IP 를 넣는다.
2. `.env` 를 만든다.

```bash
cp .env.example .env
for k in ERRDECK_DB_PASSWORD ERRDECK_CLICKHOUSE_PASSWORD ERRDECK_CLICKHOUSE_WORKER_PASSWORD ERRDECK_CLICKHOUSE_API_PASSWORD \
         ERRDECK_GRAFANA_PASSWORD ERRDECK_INTERNAL_SECRET; do
  sed -i "s|^$k=.*|$k=$(openssl rand -hex 24)|" .env
done
sed -i "s|^ERRDECK_JWT_SECRET=.*|ERRDECK_JWT_SECRET=$(openssl rand -hex 48)|" .env
# 에이전트 비밀번호(팬파라이브 서버의 에이전트가 쓴다). 평문은 따로 적어 둔다.
AGENT_PW=$(openssl rand -hex 16); echo "에이전트 비밀번호: $AGENT_PW"
sed -i "s|^ERRDECK_AGENT_PASSWORD_HASH=.*|ERRDECK_AGENT_PASSWORD_HASH=$(docker run --rm caddy:2 caddy hash-password --plaintext $AGENT_PW)|" .env
# 도메인과 메일 주소를 고친다
nano .env
```

## 4. 올리기

```bash
docker compose -f docker-compose.yml -f docker-compose.oci.yml --profile core --profile obs up -d --build
docker compose ps
```

첫 빌드는 Arm 에서 10분 안팎 걸린다. Caddy 가 인증서를 받으면 `https://{도메인}` 에 콘솔이 뜬다.

## 5. 처음 설정

1. 콘솔에서 가입한다(`.env` 의 `ERRDECK_SIGNUP_ENABLED=true` 일 때만 된다).
2. 시스템과 프로젝트를 만든다.

```bash
docker compose exec -T postgres psql -U errdeck < scripts/seed/systems.sql
docker compose exec -T postgres psql -U errdeck -c \
  "INSERT INTO system_members (system_id, user_id, role) SELECT s.id, u.id, 'OWNER' FROM systems s, users u WHERE u.email = '가입한 이메일' ON CONFLICT DO NOTHING"
```

3. 가입을 닫는다: `.env` 에서 `ERRDECK_SIGNUP_ENABLED=false` 로 바꾸고 api 만 다시 띄운다.

```bash
docker compose -f docker-compose.yml -f docker-compose.oci.yml --profile core up -d api
```

4. 백업을 건다(17.6).

```bash
(crontab -l 2>/dev/null; echo "15 4 * * * cd $PWD && scripts/deploy/backup.sh >> backups/backup.log 2>&1") | crontab -
```

## 6. 감시 대상 연결

[INTEGRATION.md](INTEGRATION.md) 의 2~5. DSN 의 호스트는 이 도메인이다.

## 7. 2코어에서 다시 재기(17.4, 25 작업)

서버 안에서 같은 실험을 돌려 EXPERIMENTS.md 의 환경 "오라클" 로 적는다. loadgen 은 같은 서버에서 돌리면 코어를 나눠 쓰므로 결과에 그렇게 적는다.

```bash
./gradlew :loadgen:installDist   # 서버에 JDK 가 없으면 docker compose --profile demo build loadgen
docker compose -f docker-compose.yml -f docker-compose.oci.yml --profile demo run --rm loadgen \
  sustained --dsn=http://{키}@ingest:8080/{프로젝트} --rate=500 --duration=5m --batch=10
```

## 되돌리기와 갱신

```bash
git pull
docker compose -f docker-compose.yml -f docker-compose.oci.yml --profile core --profile obs up -d --build
```

스키마는 api 가 뜰 때 적용한다(Flyway, ClickHouse 실행기). 되돌릴 때는 백업한 덤프를 `gunzip -c backups/... | docker compose exec -T postgres psql -U errdeck` 로 넣는다.
