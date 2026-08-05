package com.ragent.ai.service.ingest;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 按文件名分条带的进程内锁（P9-5a）：
 * <p>
 * 串行化"同名文档"的并发操作——两阶段替换（新文档 READY 后删旧文档）、RECHUNK/RETRY 与
 * {@code delete()} 对同一文件的互斥。ReentrantLock 支持两阶段替换里嵌套的 {@code delete(exist)}
 * 重入获取同一把锁。条带化（64 条）避免全局锁降低并发，不同文件名互不阻塞。
 * <p>
 * 局限：进程内锁，多实例部署需换 DB 行锁 / Redis 锁（单实例部署足够）。
 */
@Component
public class KbFilenameLock {

    private static final int STRIPES = 64;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public KbFilenameLock() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** 在指定文件名的锁内执行 body（可重入）。filename 为 null 时用固定条带。 */
    public void runWithLock(String filename, Runnable body) {
        ReentrantLock lock = lockFor(filename);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    /** 带返回值的版本（如 {@code uploadTextIfAbsent} 需要在锁内返回新文档）。 */
    public <T> T runWithLock(String filename, Supplier<T> body) {
        ReentrantLock lock = lockFor(filename);
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String filename) {
        int hash = filename == null ? 0 : filename.hashCode();
        return locks[(hash & 0x7fffffff) % STRIPES];
    }
}
