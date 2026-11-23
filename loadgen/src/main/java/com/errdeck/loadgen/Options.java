package com.errdeck.loadgen;

import com.errdeck.core.dsn.Dsn;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 명령줄 옵션.
 *
 * @param rate         초당 보낼 이벤트 수
 * @param fingerprints 지속 부하에서 섞을 지문 종류 수. 폭주는 항상 1이다
 * @param batch        봉투 하나에 담을 이벤트 수. Spring SDK 는 최대 50건을 묶는다(11.2)
 * @param maxInFlight  응답을 기다리는 요청의 상한. 넘으면 보내지 않고 건수만 센다. 도구가 먼저 쓰러지지 않게 한다
 * @param timeout      요청 하나의 제한 시간. SDK 와 같은 3초다(14.9)
 */
public record Options(
        Scenario scenario,
        Dsn dsn,
        int rate,
        Duration duration,
        int fingerprints,
        int batch,
        int maxInFlight,
        Duration timeout) {

    static final String USAGE = """
            사용법: loadgen <sustained|burst> --dsn=<DSN> [옵션]
              --rate=500           초당 이벤트 수
              --duration=60s       실행 시간 (예: 30s, 5m)
              --fingerprints=1000  지문 종류 수 (sustained 만)
              --batch=1            봉투 하나에 담을 이벤트 수
              --max-in-flight=2000 응답을 기다리는 요청의 상한
              --timeout=3s         요청 하나의 제한 시간
            """;

    public Options {
        if (rate < 1) {
            throw new IllegalArgumentException("--rate 는 1 이상이어야 한다");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("--duration 은 0보다 커야 한다");
        }
        if (fingerprints < 1 || batch < 1 || maxInFlight < 1) {
            throw new IllegalArgumentException("--fingerprints, --batch, --max-in-flight 는 1 이상이어야 한다");
        }
        if (scenario == Scenario.BURST) {
            fingerprints = 1;
        }
    }

    static Options parse(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("시나리오가 없다");
        }
        Scenario scenario = Scenario.parse(args[0]);
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("옵션은 --이름=값 형식이다: " + arg);
            }
            values.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        String dsn = values.remove("dsn");
        if (dsn == null) {
            throw new IllegalArgumentException("--dsn 이 없다");
        }
        Options options = new Options(
                scenario,
                Dsn.parse(dsn),
                Integer.parseInt(values.getOrDefault("rate", "500")),
                parseDuration(values.getOrDefault("duration", "60s")),
                Integer.parseInt(values.getOrDefault("fingerprints", "1000")),
                Integer.parseInt(values.getOrDefault("batch", "1")),
                Integer.parseInt(values.getOrDefault("max-in-flight", "2000")),
                parseDuration(values.getOrDefault("timeout", "3s")));
        for (String known : new String[] {"rate", "duration", "fingerprints", "batch", "max-in-flight", "timeout"}) {
            values.remove(known);
        }
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("모르는 옵션: " + values.keySet());
        }
        return options;
    }

    /** {@code 500ms}, {@code 30s}, {@code 5m} 형식. */
    static Duration parseDuration(String text) {
        if (text.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
        }
        long value = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) {
            case 's' -> Duration.ofSeconds(value);
            case 'm' -> Duration.ofMinutes(value);
            default -> throw new IllegalArgumentException("시간은 500ms, 30s, 5m 형식이다: " + text);
        };
    }

    /** 초당 보낼 봉투 수. 이벤트 수를 묶음 크기로 나눈 것이다. */
    double envelopesPerSecond() {
        return (double) rate / batch;
    }
}
