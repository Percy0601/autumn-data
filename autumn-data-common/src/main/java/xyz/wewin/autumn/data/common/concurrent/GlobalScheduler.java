package xyz.wewin.autumn.data.common.concurrent;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全局调度线程池：框架内所有定时/异步任务的<b>唯一</b>线程池入口。
 *
 * <p><b>为什么是全局一个</b>：组件（如 {@code MetaDataSource} 的空闲池轮询）各自
 * {@code Executors.newXxx...} 会让线程数随功能线性增长，且无人统一回收。约定是
 * <b>不允许再新建线程池</b>，需要跑任务就调本类。</p>
 *
 * <p><b>形态</b>：单线程 {@link ScheduledExecutorService}，守护线程（不阻止 JVM 退出），
 * 首次使用时才创建（懒启动，纯单池/无定时任务的应用一个线程都不会多占）。
 * 单线程意味着<b>任务必须短小</b>：长任务或阻塞任务会推迟队列里的其他任务。</p>
 *
 * <p><b>异常语义</b>：{@link #scheduleWithFixedDelay} 会包装任务，异常只记日志、
 * <b>不取消后续调度</b>——JDK 的默认行为是任务一旦抛出就永久停止排期，共享线程池里
 * 这会让某个组件静默失效，故在此处兜住。</p>
 *
 * <p><b>生命周期</b>：{@link #shutdown()} 只关这一次，幂等；关闭后若再提交任务会重建线程池。
 * 各组件的「停止」应只撤销自己的 {@link ScheduledFuture}（见取消返回的 future），
 * 不要关掉全局池——别的组件还在用。</p>
 */
public final class GlobalScheduler {

    private static final String THREAD_NAME = "autumn-data-scheduler";

    private static final Logger LOG = Logger.getLogger("xyz.wewin.autumn.data.common.concurrent");

    /** 全局唯一实例；懒创建，{@code synchronized} 保证并发下只创建一个线程池。 */
    private static volatile ScheduledExecutorService scheduler;

    private GlobalScheduler() {
    }

    /**
     * 全局线程池本身：需要 {@code schedule}/{@code execute} 等原生能力时直接用它，
     * 但<b>不要</b>对它调用 {@code shutdown}（生命周期由 {@link #shutdown()} 统一管理）。
     *
     * @return 懒创建的单线程守护调度器，全 JVM 同一个实例
     */
    public static ScheduledExecutorService scheduler() {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            return current;
        }
        synchronized (GlobalScheduler.class) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return scheduler;
        }
    }

    /**
     * 在全局线程池上挂一个固定延迟任务（异常不中断后续调度）。
     *
     * @param task         任务体
     * @param initialDelay 首次执行前的延迟
     * @param delay        两次执行之间的间隔（非固定速率）
     * @param unit         时间单位
     * @return 句柄；组件停止时对它 {@code cancel} 即可摘掉自己的任务，线程池留给其他组件
     */
    public static ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Throwable ex) {
                LOG.log(Level.WARNING, "Scheduled task failed on global scheduler; next run still scheduled", ex);
            }
        };
        return scheduler().scheduleWithFixedDelay(guarded, initialDelay, delay, unit);
    }

    /**
     * 是否已创建过线程池（懒启动的可观测状态，主要给测试与诊断用）。
     */
    public static boolean isStarted() {
        return scheduler != null;
    }

    /**
     * 关闭全局线程池（幂等）。应用停止时调用；组件自己不要调，只 cancel 自己的 future。
     * 关闭后再提交任务会重新懒创建一个池。
     */
    public static void shutdown() {
        synchronized (GlobalScheduler.class) {
            ScheduledExecutorService current = scheduler;
            if (current != null) {
                current.shutdownNow();
                scheduler = null;
            }
        }
    }
}
