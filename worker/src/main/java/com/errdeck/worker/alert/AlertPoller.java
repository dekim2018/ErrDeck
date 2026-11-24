package com.errdeck.worker.alert;

import org.springframework.context.SmartLifecycle;

/** 새 이슈와 재발은 10초마다, 빈도 규칙은 30초마다 본다(16.1). */
public class AlertPoller implements SmartLifecycle {

    private final AlertEngine engine;
    private volatile boolean running;
    private Thread thread;

    public AlertPoller(AlertEngine engine) {
        this.engine = engine;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("errdeck-alert-poller").daemon().start(() -> {
            long tick = 0;
            while (running) {
                engine.pollIssues();
                if (tick++ % 3 == 0) {
                    engine.pollFrequency();
                }
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
