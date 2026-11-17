# 本地 H2 服务脚本

本地联调用的 H2 服务端启停脚本，Windows 与 Linux 各一套，逻辑一致：

| 平台 | 启动 | 停止 |
| --- | --- | --- |
| Windows | `start-h2.bat` | `stop-h2.bat` |
| Linux / macOS | `start-h2.sh` | `stop-h2.sh` |

## 脚本做了什么

1. 后台起一个 **H2 TCP 服务**（默认端口 9092），数据库文件落在 `$H2_HOME/data`，带 `-ifNotExists`，第一次连接不存在的库会自动建；
2. 起完之后按 `H2_DATABASES` 逐个**预置为 MySQL 兼容模式**：用带 `;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE` 的连接串打开一次，让标识符大小写行为一次性定下来；
3. 打印可直接粘贴的 JDBC URL；
4. 停止时先走 H2 自带的优雅关停（`-tcpShutdown`），失败再兜底杀进程。

## 「支持 MySQL」是指兼容模式，不是 MySQL 协议

H2 只提供 **TCP** 与 **PG** 两种服务端协议，没有 MySQL 线协议；它的 MySQL 兼容是**连接级参数** `MODE=MySQL`
（`SET MODE` 只对当次会话生效，不会写进库文件）。所以：

- 服务端只有一份，端口 9092；
- 客户端 URL 必须自己带 `MODE=MySQL`，脚本打印的 URL 与 `application.properties` 里的写法一致。

## 环境变量（都可选）

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `H2_HOME` | 安装目录（含 `bin/h2-*.jar`） | Windows `D:\opt\h2`；Linux `/opt/h2`；脚本自身所在目录若含 jar 则优先用它 |
| `H2_PORT` | TCP 端口 | `9092` |
| `H2_BASE_DIR` | 数据库文件目录 | `$H2_HOME/data` |
| `H2_ALLOW_OTHERS` | `1` = 允许其它主机连接 | `0`（仅本机） |
| `H2_PASSWORD` | 自动建库时用的 `sa` 口令 | 空 |
| `H2_TCP_PASSWORD` | `-tcpShutdown` 用的关停口令 | 自动生成，存 `$H2_HOME/h2.tcp-password` |
| `H2_DATABASES` | 需要预置成 MySQL 模式的库，逗号/空格分隔 | `autumn_data` |
| `JAVA_HOME` | JDK 11+（H2 2.x 要求） | 取 `PATH` 上的 `java` |

## 用法

Windows（脚本已随安装目录加入用户 `PATH`，可直接按名调用）：

```bat
start-h2.bat
start-h2.bat --status
start-h2.bat --foreground
stop-h2.bat
```

Linux：

```bash
./start-h2.sh
./start-h2.sh --status
./start-h2.sh --foreground
./stop-h2.sh
```

## 关于口令

- **关停口令**：H2 2.x 的 `-tcpShutdown` 要求与服务端管理库口令一致，没设 `-tcpPassword` 时服务端会用随机口令，
  远程关停必然失败（只能杀进程）。所以 `start-h2.sh/bat` 未显式给 `H2_TCP_PASSWORD` 时会生成一份写进
  `$H2_HOME/h2.tcp-password`，`stop-h2.sh/bat` 读同一份。
- **数据口令**：`H2_PASSWORD` 只在**建库那一次**生效（H2 把用户口令写进库文件），之后连接必须用同一口令。
  想按示例配置用 `sa/123456`，就带口令启动一次：`H2_PASSWORD=123456 H2_DATABASES=autumn_security start-h2.bat`。

## 与示例配置对应

`autumn-data-jdbc-spring-boot-starter-using` 的 `application.properties` 用的是：

```properties
spring.datasource.url=jdbc:h2:tcp://localhost:9092/autumn_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
spring.datasource.username=sa
spring.datasource.password=123456
```

对应做法：先 `H2_PASSWORD=123456 H2_DATABASES="autumn_security autumn_report" start-h2.bat`（或 `.sh`）把库按
MySQL 模式建出来，再 `mvn spring-boot:run -Dspring-boot.run.profiles=demo`。
建库 SQL 见 [../db/README.md](../db/README.md)。
