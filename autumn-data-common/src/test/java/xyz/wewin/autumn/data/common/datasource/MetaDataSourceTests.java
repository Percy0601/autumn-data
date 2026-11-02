package xyz.wewin.autumn.data.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetaDataSource 单元测试：注册 / 懒加载 / 单池不轮询 / 多池轮询回收 / 默认池保护 / 关闭清理。
 *
 * <p>回收判定使用 Mockito 精确控制 Hikari 池状态（HikariCP 7.x 移除了可配置的
 * housekeeping 周期，真实池难以在测试窗口内缩至 0 连接）；真实池用于验证
 * 注册、连接获取、懒加载等使用路径。</p>
 */
class MetaDataSourceTests {

    @BeforeEach
    void resetState() {
        MetaDataSource.shutdown();
        MetaDataSource.setSweepInterval(180);
    }

    @AfterEach
    void cleanUp() {
        MetaDataSource.shutdown();
    }

    /** 构造一个真实 Hikari 池（H2 内存库），用于使用路径验证。 */
    private static HikariDataSource hikari(String name, int maxPoolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:meta-" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPoolName("meta-" + name);
        ds.setMaximumPoolSize(maxPoolSize);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(3000);
        return ds;
    }

    /** 构造一个可精确控制连接数的 mock Hikari 池（验证回收判定）。 */
    private static HikariDataSource mockHikari(int total, int active) {
        HikariDataSource ds = mock(HikariDataSource.class);
        HikariPoolMXBean mx = mock(HikariPoolMXBean.class);
        when(ds.getHikariPoolMXBean()).thenReturn(mx);
        when(mx.getTotalConnections()).thenReturn(total);
        when(mx.getActiveConnections()).thenReturn(active);
        return ds;
    }

    /** 反射读取私有 sweeper 字段（仅测试断言轮询线程是否启动）。 */
    private static ScheduledExecutorService sweeperField() throws Exception {
        Field field = MetaDataSource.class.getDeclaredField("sweeper");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(null);
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static boolean ping(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT 1")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    // ------------------------------------------------------------------
    // 注册与委托
    // ------------------------------------------------------------------

    @Test
    void registerAndGetConnectionWorks() throws Exception {
        HikariDataSource db1 = hikari("reg1", 2);
        MetaDataSource.register("db1", db1);

        assertEquals(1, MetaDataSource.size());
        assertTrue(MetaDataSource.contains("db1"));
        assertTrue(MetaDataSource.names().contains("db1"));

        try (Connection conn = MetaDataSource.getConnection("db1")) {
            execute(conn, "CREATE TABLE IF NOT EXISTS t_meta (id INT PRIMARY KEY)");
            assertTrue(ping(conn));
        }
        // 默认池委托：无参 getConnection() 走第一个注册的池
        try (Connection conn = MetaDataSource.INSTANCE.getConnection()) {
            assertTrue(ping(conn));
        }
        assertTrue(MetaDataSource.INSTANCE.isWrapperFor(DataSource.class));
        assertEquals(MetaDataSource.INSTANCE, MetaDataSource.INSTANCE.unwrap(DataSource.class));
        // 委托验证：INSTANCE 的配置委托到默认池（与 db1 自身一致，而非 Hikari 的具体默认值）
        assertEquals(db1.getLoginTimeout(), MetaDataSource.INSTANCE.getLoginTimeout());
    }

    @Test
    void duplicateRegisterThrows() {
        MetaDataSource.register("dup", hikari("dup", 1));
        HikariDataSource extra = hikari("dup2", 1);
        try {
            assertThrows(IllegalStateException.class, () -> MetaDataSource.register("dup", extra));
        } finally {
            extra.close(); // 未入注册表，手动关闭避免泄漏
        }
    }

    @Test
    void unregisteredNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> MetaDataSource.get("nope"));
        assertThrows(SQLException.class, () -> MetaDataSource.getConnection("nope"));
    }

    @Test
    void unregisterClosesAndRemoves() throws Exception {
        HikariDataSource db = hikari("unreg", 1);
        MetaDataSource.register("db", db);
        assertEquals(db, MetaDataSource.unregister("db"));
        assertEquals(0, MetaDataSource.size());
        assertFalse(MetaDataSource.contains("db"));
        // 池已关闭：再拿连接抛异常
        assertThrows(SQLException.class, () -> db.getConnection());
    }

    // ------------------------------------------------------------------
    // 单池 / 多池模式
    // ------------------------------------------------------------------

    @Test
    void singlePoolDoesNotStartSweeper() throws Exception {
        MetaDataSource.register("only", hikari("only", 1));
        assertNull(sweeperField(), "单池模式不应启动轮询线程");
    }

    @Test
    void secondPoolStartsSweeper() throws Exception {
        MetaDataSource.register("a", hikari("sa", 1));
        assertNull(sweeperField());
        MetaDataSource.register("b", hikari("sb", 1));
        assertNotNull(sweeperField(), "多池模式应启动轮询线程");
    }

    // ------------------------------------------------------------------
    // 懒加载
    // ------------------------------------------------------------------

    @Test
    void lazyFactoryCreatesOnFirstAccess() {
        AtomicInteger creations = new AtomicInteger();
        MetaDataSource.register("lazy", () -> {
            creations.incrementAndGet();
            return hikari("lazy", 1);
        });

        assertTrue(MetaDataSource.contains("lazy"));
        assertEquals(0, MetaDataSource.size(), "工厂注册后不应立即建池");

        MetaDataSource.get("lazy");
        assertEquals(1, MetaDataSource.size());
        assertEquals(1, creations.get(), "首次访问只创建一次");
    }

    @Test
    void concurrentLazyAccessCreatesOnce() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        MetaDataSource.register("conc", () -> {
            creations.incrementAndGet();
            return hikari("conc", 2);
        });

        int threads = 8;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try (Connection conn = MetaDataSource.getConnection("conc")) {
                    assertTrue(ping(conn));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        assertEquals(1, creations.get(), "并发首次访问必须只创建一个池");
        assertEquals(1, MetaDataSource.size());
    }

    // ------------------------------------------------------------------
    // 空闲池回收（回收判定用 mock 池精确控制）
    // ------------------------------------------------------------------

    @Test
    void sweepRemovesExhaustedNonPrimaryPool() throws Exception {
        MetaDataSource.register("primary", hikari("prim", 1));
        HikariDataSource tenant = mockHikari(0, 0); // 池空：total=0 && active=0
        MetaDataSource.register("tenant1", tenant);

        MetaDataSource.sweep();

        assertFalse(MetaDataSource.contains("tenant1"), "空池应被回收移除");
        assertThrows(SQLException.class, () -> MetaDataSource.getConnection("tenant1"));
        verify(tenant).close();
    }

    @Test
    void sweepKeepsPoolWithActiveOrIdleConnections() throws Exception {
        MetaDataSource.register("primary", hikari("prim2", 1));
        HikariDataSource busy = mockHikari(3, 2);   // 有连接：total=3 && active=2
        HikariDataSource idle = mockHikari(1, 0);   // 有空闲连接：total=1
        MetaDataSource.register("busy", busy);
        MetaDataSource.register("idle", idle);

        MetaDataSource.sweep();

        assertTrue(MetaDataSource.contains("busy"));
        assertTrue(MetaDataSource.contains("idle"));
        verify(busy, never()).close();
        verify(idle, never()).close();
    }

    @Test
    void sweepSkipsPrimaryEvenWhenEmpty() throws Exception {
        HikariDataSource primary = mockHikari(0, 0); // 默认池也空
        MetaDataSource.register("primary", primary);
        MetaDataSource.register("tenant1", mockHikari(0, 0));

        MetaDataSource.sweep();

        assertTrue(MetaDataSource.contains("primary"), "默认池永不自动回收");
        verify(primary, never()).close();
        assertFalse(MetaDataSource.contains("tenant1"));
    }

    @Test
    void sweepSkipsNonHikariDataSource() {
        MetaDataSource.register("primary", hikari("prim3", 1));
        MetaDataSource.register("plain", new StubDataSource());

        MetaDataSource.sweep();

        assertTrue(MetaDataSource.contains("plain"), "非 Hikari 数据源不参与自动回收");
    }

    @Test
    void realSweeperThreadReclaimsExhaustedPool() throws Exception {
        MetaDataSource.setSweepInterval(1); // 1 秒轮询
        MetaDataSource.register("primary", hikari("prim4", 1));
        HikariDataSource tenant = mockHikari(0, 0);
        MetaDataSource.register("tenant1", tenant);

        // 等待真实守护轮询线程执行回收（间隔 1s，2s 内应命中）
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && MetaDataSource.contains("tenant1")) {
            Thread.sleep(50);
        }
        assertFalse(MetaDataSource.contains("tenant1"), "守护轮询线程应在间隔内回收空池");
        verify(tenant).close();
    }

    // ------------------------------------------------------------------
    // 关闭与配置
    // ------------------------------------------------------------------

    @Test
    void shutdownClearsEverything() throws Exception {
        MetaDataSource.register("a", hikari("sd1", 1));
        MetaDataSource.register("b", hikari("sd2", 1));
        MetaDataSource.register("lazy", () -> hikari("sd3", 1));
        MetaDataSource.get("lazy");

        MetaDataSource.shutdown();

        assertEquals(0, MetaDataSource.size());
        assertFalse(MetaDataSource.contains("a"));
        assertFalse(MetaDataSource.contains("lazy"));
        assertNull(sweeperField(), "shutdown 后轮询线程应停止");
        assertThrows(SQLException.class, MetaDataSource.INSTANCE::getConnection);
    }

    @Test
    void setSweepIntervalValidates() {
        assertThrows(IllegalArgumentException.class, () -> MetaDataSource.setSweepInterval(0));
        assertThrows(IllegalArgumentException.class, () -> MetaDataSource.setSweepInterval(-5));
        MetaDataSource.setSweepInterval(10);
        assertEquals(10, MetaDataSource.getSweepInterval());
    }

    // ------------------------------------------------------------------
    // 测试桩：非 Hikari 数据源（不参与回收）
    // ------------------------------------------------------------------

    /** 极简 DataSource 桩：getConnection 抛"未实现"，仅用于验证非 Hikari 不被回收。 */
    private static final class StubDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("stub: no connection");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("stub: no connection");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
