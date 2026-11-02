package xyz.wewin.autumn.data.common.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * GlobalScheduler 单元测试：全 JVM 共用一个池、任务抛异常不掐断后续调度、关闭后可懒重建。
 *
 * <p>断言用「轮到命中 + deadline」而不是固定 sleep，避免慢机器抖动。</p>
 */
class GlobalSchedulerTests {

    private ScheduledFuture<?> task;

    @AfterEach
    void cancelOwnTask() {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(25);
        }
        if (!condition.getAsBoolean()) {
            fail("条件未在超时内成立: " + what);
        }
    }

    @Test
    void everyCallerSharesOneScheduler() {
        assertSame(GlobalScheduler.scheduler(), GlobalScheduler.scheduler(), "反复取必须拿到同一个池");
        task = GlobalScheduler.scheduleWithFixedDelay(() -> {
        }, 1, 1, TimeUnit.HOURS);
        assertSame(GlobalScheduler.scheduler(), GlobalScheduler.scheduler(), "提交任务后取到的仍是同一个池");
    }

    @Test
    void taskThrowingDoesNotKillItsOwnSchedule() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        task = GlobalScheduler.scheduleWithFixedDelay(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("boom on first run");
            }
        }, 0, 60, TimeUnit.MILLISECONDS);
        // JDK 原生语义是「抛出即永久停止排期」；包装后必须继续跑，否则共享池里的组件会静默失效
        awaitUntil("首次抛异常后仍继续调度", () -> runs.get() >= 3);
    }

    @Test
    void cancelStopsFurtherExecutions() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        task = GlobalScheduler.scheduleWithFixedDelay(runs::incrementAndGet, 0, 40, TimeUnit.MILLISECONDS);
        awaitUntil("任务至少跑过两轮", () -> runs.get() >= 2);
        task.cancel(false);
        int atCancel = runs.get();
        Thread.sleep(200);
        assertTrue(runs.get() - atCancel <= 1, "取消后不应再持续执行");
    }

    @Test
    void shutdownReleasesThePoolAndRelaziesOnNextUse() throws Exception {
        ScheduledExecutorService first = GlobalScheduler.scheduler();
        task = GlobalScheduler.scheduleWithFixedDelay(() -> {
        }, 1, 1, TimeUnit.HOURS);
        assertTrue(GlobalScheduler.isStarted(), "提交过任务就说明池已懒启动");

        GlobalScheduler.shutdown();
        assertTrue(first.isShutdown(), "shutdown 必须关掉取到的那个实例");
        assertFalse(GlobalScheduler.isStarted(), "shutdown 后不应残留实例");

        ScheduledExecutorService recreated = GlobalScheduler.scheduler();
        assertNotSame(first, recreated, "重建出来的必须是新实例");
        assertFalse(recreated.isShutdown(), "重建后的池必须可用");
        task = GlobalScheduler.scheduleWithFixedDelay(() -> {
        }, 1, 1, TimeUnit.HOURS);
        assertSame(GlobalScheduler.scheduler(), GlobalScheduler.scheduler(), "重建后依旧全局唯一");
    }
}
