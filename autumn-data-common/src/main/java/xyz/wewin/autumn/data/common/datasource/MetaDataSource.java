package xyz.wewin.autumn.data.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 多数据源注册表（MetaDataSource）：为「几千个数据库连接池」场景设计的数据源管理器。
 *
 * <p><b>定位</b>：这不是一个自定义数据库连接池。它只负责多连接池的注册、懒加载与空闲回收，
 * 连接的生命周期仍由具体连接池（项目既定 HikariCP）自己维护；单连接池项目完全可以不用它。</p>
 *
 * <p><b>原理</b>：参考 Spring {@code AbstractDataSource} —— 本类自身实现 {@link DataSource}，
 * {@link #getConnection()} 系列方法委托给「默认池」（第一个注册的池）；同时以静态门面的形式
 * 提供 {@code register / get / getConnection(name) / unregister / shutdown} 等简单 API。</p>
 *
 * <p><b>核心行为</b>：</p>
 * <ol>
 *   <li><b>单池模式</b>：只注册一个池时，MetaDataSource 仅是轻量委托，不启动任何轮询线程，
 *       连接池完全由 HikariCP 内部自行维护。</li>
 *   <li><b>多池模式</b>：注册第二个池时，启动一个守护线程（{@code daemon}），默认每 3 分钟
 *       轮询一次，回收「已空」的 Hikari 池：{@code totalConnections == 0 && activeConnections == 0}
 *       视为连接池已空，执行 {@code close()} 并从注册表移除（见 {@link #sweep()}）。</li>
 *   <li><b>懒加载</b>：{@link #register(String, Supplier)} 只登记创建工厂不建池；首次
 *       {@link #get(String)} / {@link #getConnection(String)} 时才创建（{@code ConcurrentHashMap.computeIfAbsent}，
 *       并发下每个池只创建一次），未注册的名字抛出异常。</li>
 *   <li><b>默认池保护</b>：默认池（第一个注册的池）永不参与自动回收，避免 {@link #getConnection()}
 *       委托目标突然失效；主动 {@link #unregister(String)} 不受此限。</li>
 *   <li><b>非 Hikari 池</b>：无法观测内部连接状态，不参与自动回收，仅作为普通条目保留。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：所有静态方法线程安全；轮询线程为守护线程，应用退出不阻塞。</p>
 *
 * <p><b>关闭顺序</b>：应用停止时调用 {@link #shutdown()} 关闭全部连接池并停止轮询。</p>
 */
public final class MetaDataSource implements DataSource {

    /** 单例：需要把 MetaDataSource 本身当作 DataSource 使用时（委托默认池）直接引用。 */
    public static final MetaDataSource INSTANCE = new MetaDataSource();

    /** 池注册表：连接池名称 -> DataSource 实例。 */
    private static final ConcurrentHashMap<String, DataSource> POOLS = new ConcurrentHashMap<>();

    /** 懒加载工厂注册表：连接池名称 -> 创建工厂（仅 {@link #register(String, Supplier)} 使用）。 */
    private static final ConcurrentHashMap<String, Supplier<DataSource>> FACTORIES = new ConcurrentHashMap<>();

    /** 默认池（第一个注册的池）：DataSource 委托目标；永不参与自动回收。 */
    private static final AtomicReference<DataSource> PRIMARY = new AtomicReference<>();

    /** 空闲池轮询调度器（守护线程），仅在多池模式（池数 ≥ 2）下启动。 */
    private static volatile ScheduledExecutorService sweeper;

    /** 轮询调度器启动标记，保证只启动一次。 */
    private static final AtomicBoolean SWEEPER_STARTED = new AtomicBoolean(false);

    /** 默认轮询间隔：3 分钟。 */
    private static volatile long sweepIntervalSeconds = 180L;

    private static final Logger LOG = Logger.getLogger("xyz.wewin.autumn.data.common.datasource");

    private MetaDataSource() {
    }

    // ------------------------------------------------------------------
    // 注册与懒加载
    // ------------------------------------------------------------------

    /**
     * 注册一个已构建好的数据源。同名字重复注册抛 {@link IllegalStateException}。
     *
     * @param name       连接池名称（ConcurrentHashMap 的 key）
     * @param dataSource 数据源实例（通常为 {@link HikariDataSource}）
     * @return 传入的 dataSource，便于链式调用
     */
    public static DataSource register(String name, DataSource dataSource) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dataSource, "dataSource");
        DataSource previous = POOLS.putIfAbsent(name, dataSource);
        if (previous != null) {
            throw new IllegalStateException("A data source is already registered under name: " + name);
        }
        FACTORIES.remove(name);
        PRIMARY.compareAndSet(null, dataSource);
        ensureSweeper();
        return dataSource;
    }

    /**
     * 以懒加载方式注册：只登记创建工厂，不立即建池。首次 {@link #get(String)} /
     * {@link #getConnection(String)} 时才调用工厂创建，并发下每个池只创建一次。
     *
     * @param name    连接池名称
     * @param factory 数据源创建工厂（例如包装 HikariDataSource 的构造逻辑）
     */
    public static void register(String name, Supplier<DataSource> factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (POOLS.containsKey(name)) {
            throw new IllegalStateException("A data source is already registered under name: " + name);
        }
        FACTORIES.put(name, factory);
        // 懒加载：不立即创建池；后续首次访问时在 get(name) 中构建
    }

    /**
     * 获取指定名称的数据源；未注册抛 {@link IllegalArgumentException}。
     * 若该名称以工厂方式注册，此调用会触发懒加载创建。
     *
     * @param name 连接池名称
     * @return 数据源实例
     */
    public static DataSource get(String name) {
        Objects.requireNonNull(name, "name");
        DataSource dataSource = POOLS.get(name);
        if (dataSource != null) {
            return dataSource;
        }
        Supplier<DataSource> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("No data source registered under name: " + name);
        }
        dataSource = POOLS.computeIfAbsent(name, ignored -> factory.get());
        PRIMARY.compareAndSet(null, dataSource);
        ensureSweeper();
        return dataSource;
    }

    /**
     * 获取指定名称的连接。等价于 {@code get(name).getConnection()}。
     *
     * @param name 连接池名称
     * @return JDBC 连接
     * @throws SQLException 数据源未注册或获取连接失败
     */
    public static Connection getConnection(String name) throws SQLException {
        try {
            return get(name).getConnection();
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 查询与维护
    // ------------------------------------------------------------------

    /**
     * 是否已注册该名称（含仅登记工厂、尚未懒加载创建的情况）。
     */
    public static boolean contains(String name) {
        return POOLS.containsKey(name) || FACTORIES.containsKey(name);
    }

    /**
     * 当前已实例化的连接池数量（工厂注册但未懒加载的不计入）。
     */
    public static int size() {
        return POOLS.size();
    }

    /**
     * 当前已实例化连接池的名称集合（不可变视图）。
     */
    public static Set<String> names() {
        return Collections.unmodifiableSet(POOLS.keySet());
    }

    /**
     * 主动移除并关闭指定数据源（同时清除其懒加载工厂）；返回被移除的实例，不存在返回 {@code null}。
     */
    public static DataSource unregister(String name) {
        Objects.requireNonNull(name, "name");
        FACTORIES.remove(name);
        DataSource removed = POOLS.remove(name);
        if (removed != null) {
            PRIMARY.compareAndSet(removed, null);
            closeQuietly(removed);
        }
        return removed;
    }

    /**
     * 关闭全部连接池、清空注册表并停止轮询线程。应用停止时调用。
     */
    public static void shutdown() {
        ScheduledExecutorService scheduler = sweeper;
        if (scheduler != null) {
            scheduler.shutdownNow();
            sweeper = null;
            SWEEPER_STARTED.set(false);
        }
        for (DataSource dataSource : POOLS.values()) {
            closeQuietly(dataSource);
        }
        POOLS.clear();
        FACTORIES.clear();
        PRIMARY.set(null);
    }

    // ------------------------------------------------------------------
    // 空闲池轮询回收
    // ------------------------------------------------------------------

    /**
     * 设置空闲池轮询间隔（秒）。默认 3 分钟；已在运行多池模式时会以新间隔重启调度器。
     *
     * @param seconds 轮询间隔（秒），必须 ≥ 1
     */
    public static void setSweepInterval(long seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("Sweep interval must be >= 1 second, got: " + seconds);
        }
        sweepIntervalSeconds = seconds;
        ScheduledExecutorService scheduler = sweeper;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            sweeper = null;
            SWEEPER_STARTED.set(false);
            ensureSweeper();
        }
    }

    /**
     * 当前空闲池轮询间隔（秒）。
     */
    public static long getSweepInterval() {
        return sweepIntervalSeconds;
    }

    /**
     * 轮询回收：对每个非默认 Hikari 池，若内部连接池已空
     * （{@code totalConnections == 0 && activeConnections == 0}）则关闭并从注册表移除。
     * 默认池与非 Hikari 池跳过。包私有：供测试直接触发，轮询线程亦调用此方法。
     */
    static void sweep() {
        for (Map.Entry<String, DataSource> entry : POOLS.entrySet()) {
            DataSource dataSource = entry.getValue();
            if (dataSource == PRIMARY.get()) {
                continue; // 默认池永不自动回收
            }
            if (isHikariExhausted(dataSource)) {
                POOLS.remove(entry.getKey(), dataSource);
                closeQuietly(dataSource);
            }
        }
    }

    /** 判定 Hikari 池是否已空；非 Hikari 数据源无法观测内部状态，一律视为不空。 */
    private static boolean isHikariExhausted(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return pool.getTotalConnections() == 0 && pool.getActiveConnections() == 0;
        }
        return false;
    }

    /** 池数 ≥ 2 时启动单线程守护轮询器（只启动一次）。 */
    private static void ensureSweeper() {
        if (POOLS.size() >= 2 && SWEEPER_STARTED.compareAndSet(false, true)) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "autumn-data-meta-datasource-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            sweeper = scheduler;
            scheduler.scheduleWithFixedDelay(MetaDataSource::sweep,
                    sweepIntervalSeconds, sweepIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    private static void closeQuietly(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warning(() -> "Failed to close data source during unregister/sweep/shutdown: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // javax.sql.DataSource 委托（参考 Spring AbstractDataSource：getConnection 委托真实数据源）
    // ------------------------------------------------------------------

    /** 默认池；未注册任何池时抛 SQLException。 */
    private static DataSource requirePrimary() throws SQLException {
        DataSource target = PRIMARY.get();
        if (target == null) {
            throw new SQLException(
                    "No default data source registered; call MetaDataSource.register(...) or MetaDataSource.register(name, ...) first");
        }
        return target;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return requirePrimary().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return requirePrimary().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return requirePrimary().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        requirePrimary().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        requirePrimary().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return requirePrimary().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return LOG;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("MetaDataSource is not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
