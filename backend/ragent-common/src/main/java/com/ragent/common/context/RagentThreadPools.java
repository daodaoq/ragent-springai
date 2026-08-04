package com.ragent.common.context;

import com.alibaba.ttl.TtlCallable;
import com.alibaba.ttl.TtlRunnable;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池工厂：线程池隔离 + 上下文透传（TTL 传递 {@link RagentContext}，MDC 恢复使异步日志带 traceId/userId）。
 * 用法：把项目里手写的 {@code new ThreadPoolExecutor(...)} 换成 {@link #newExecutor(String, int, int, int, RejectedExecutionHandler)}，
 * 提交的任务会自动携带调用线程的上下文，在 worker 线程上恢复（含 SLF4J MDC，logback/ELK 白名单字段生效）。
 */
public final class RagentThreadPools {

    private RagentThreadPools() {
    }

    /**
     * 创建命名、有界队列、TTL+MDC 透传的线程池（线程名便于排查，daemon 不阻 JVM 退出）。
     *
     * @param threadName  线程名前缀
     * @param core        核心线程数
     * @param max         最大线程数
     * @param queueSize   有界队列容量
     * @param policy      拒绝策略（通常 DiscardOldestPolicy / CallerRunsPolicy）
     */
    public static ThreadPoolExecutor newExecutor(String threadName, int core, int max,
                                                 int queueSize, RejectedExecutionHandler policy) {
        return new TtlContextExecutor(threadName, core, max, queueSize, policy);
    }

    /** 在线程池上透传上下文的执行器：submit/execute 统一包装。 */
    private static final class TtlContextExecutor extends ThreadPoolExecutor {

        TtlContextExecutor(String threadName, int core, int max, int queueSize, RejectedExecutionHandler policy) {
            super(core, max, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueSize),
                    r -> {
                        Thread t = new Thread(r, threadName);
                        t.setDaemon(true);
                        return t;
                    },
                    policy);
        }

        @Override
        public void execute(Runnable command) {
            super.execute(wrap(command));
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return super.submit(wrap(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return super.submit(wrap(task), result);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return super.submit(wrap(task));
        }
    }

    private static Runnable wrap(Runnable r) {
        Runnable ttl = TtlRunnable.get(r); // 透传 RagentContext（TTL）
        Map<String, String> mdc = MDC.getCopyOfContextMap(); // 透传 MDC
        return () -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                ttl.run();
            } finally {
                MDC.clear();
            }
        };
    }

    private static <T> Callable<T> wrap(Callable<T> c) {
        Callable<T> ttl = TtlCallable.get(c);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return ttl.call();
            } finally {
                MDC.clear();
            }
        };
    }
}
