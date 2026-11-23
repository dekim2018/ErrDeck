package com.errdeck.worker.in.scheduler;

import com.errdeck.worker.app.Pipeline;
import com.errdeck.worker.out.redis.RedisQueueSource;
import org.springframework.context.SmartLifecycle;

/**
 * 애플리케이션이 뜨면 단계 구조를 시작하고, 종료 신호를 받으면 읽기를 멈추고 처리 중인 묶음을 쓴 뒤 끝낸다(17.6).
 * 웹 서버보다 늦게 멈추도록 단계를 낮게 둔다. 멈추는 동안에도 상태 확인과 지표를 볼 수 있다.
 */
public class PipelineLifecycle implements SmartLifecycle {

    private final Pipeline pipeline;
    private final RedisQueueSource source;
    private volatile boolean running;

    public PipelineLifecycle(Pipeline pipeline, RedisQueueSource source) {
        this.pipeline = pipeline;
        this.source = source;
    }

    @Override
    public void start() {
        source.ensureGroups();
        pipeline.start();
        running = true;
    }

    @Override
    public void stop() {
        pipeline.stop();
        source.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2048;
    }
}
