package com.errdeck.loadgen;

/**
 * 부하와 폭주를 만드는 명령줄 도구(18.2).
 *
 * <pre>
 * ./gradlew :loadgen:run --args="burst --dsn=http://key@localhost:8080/1 --rate=5000 --duration=5m"
 * </pre>
 */
public final class LoadGen {

    private LoadGen() {
    }

    public static void main(String[] args) throws InterruptedException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(Options.USAGE);
            System.exit(2);
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("환경         코어 %d, 최대 힙 %,dMB, 대상 %s%n",
                runtime.availableProcessors(), runtime.maxMemory() / 1024 / 1024, options.dsn().envelopeUri());
        Result result = new Runner(options, Runner.envelopes(options), System.out).run();
        System.out.print(result.format());
    }
}
