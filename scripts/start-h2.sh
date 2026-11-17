#!/usr/bin/env bash
# =============================================================================
#  H2 Database Server —— 启动（TCP + MySQL 兼容模式）
#
#  用法：
#    ./start-h2.sh                后台启动 TCP 服务（日志写 logs/h2-server.log）
#    ./start-h2.sh --foreground   前台启动，日志直接打在终端
#    ./start-h2.sh --status       查看服务是否在监听
#    ./start-h2.sh --help
#
#  环境变量（都可选）：
#    H2_HOME           安装目录；默认：本脚本所在目录（含 bin/h2-*.jar）否则 /opt/h2
#    H2_PORT           TCP 端口                （默认 9092）
#    H2_BASE_DIR       数据库文件目录          （默认 $H2_HOME/data）
#    H2_ALLOW_OTHERS   1 = 允许其它主机连接    （默认 0，仅本机）
#    H2_PASSWORD       自动建库时使用的 sa 口令（默认空）
#    H2_TCP_PASSWORD   "-tcpShutdown" 用的关停口令（stop-h2.sh 会读同一份）；
#                      默认自动生成并保存在 $H2_HOME/h2.tcp-password
#    H2_DATABASES      需要在 MySQL 模式下预置的库，逗号/空格分隔（默认 autumn_data）
#    JAVA_HOME         JDK 11+；未配置时用 PATH 上的 java
# =============================================================================
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# ---- 定位 H2_HOME -----------------------------------------------------------
if [[ -z "${H2_HOME:-}" ]]; then
    if compgen -G "$HERE/bin/h2-*.jar" >/dev/null 2>&1; then
        H2_HOME="$HERE"
    else
        H2_HOME="/opt/h2"
    fi
fi

H2_PORT="${H2_PORT:-9092}"
H2_BASE_DIR="${H2_BASE_DIR:-$H2_HOME/data}"
H2_ALLOW_OTHERS="${H2_ALLOW_OTHERS:-0}"
H2_DATABASES="${H2_DATABASES:-autumn_data}"
H2_PASSWORD="${H2_PASSWORD:-}"
H2_TCP_PASSWORD="${H2_TCP_PASSWORD:-}"
H2_LOG_DIR="$H2_HOME/logs"
H2_LOG="$H2_LOG_DIR/h2-server.log"
H2_PID_FILE="$H2_HOME/h2.pid"

# 逗号归一成空格，便于 for 循环
H2_DATABASES="${H2_DATABASES//,/ }"

die() { printf '[h2] [ERROR] %s\n' "$*" >&2; exit 1; }

# ---- 定位 jar（bin 下版本号最大的那个） ------------------------------------
H2_JAR="$(ls -1 "$H2_HOME"/bin/h2-*.jar 2>/dev/null | sort -V | tail -n 1 || true)"
[[ -n "$H2_JAR" ]] || die "在 $H2_HOME/bin 下找不到 h2-*.jar"

# ---- 定位 java（H2 2.x 需要 JDK 11+） --------------------------------------
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_EXE="$(command -v java)"
else
    die "找不到 java，请设置 JAVA_HOME 指向 JDK 11+"
fi

"$JAVA_EXE" -cp "$H2_JAR" org.h2.tools.Server -help >/dev/null 2>&1 \
    || die "$JAVA_EXE 无法运行 H2 2.x（需要 JDK 11+）"

# ---- 关停口令：环境变量 -> 文件 -> 生成一次 -------------------------------
if [[ -z "$H2_TCP_PASSWORD" && -f "$H2_HOME/h2.tcp-password" ]]; then
    H2_TCP_PASSWORD="$(cat "$H2_HOME/h2.tcp-password")"
fi
if [[ -z "$H2_TCP_PASSWORD" ]]; then
    H2_TCP_PASSWORD="$RANDOM$RANDOM$RANDOM$RANDOM$RANDOM"
    printf '%s\n' "$H2_TCP_PASSWORD" > "$H2_HOME/h2.tcp-password"
    chmod 600 "$H2_HOME/h2.tcp-password" 2>/dev/null || true
fi

SERVER_ARGS=(-tcp -tcpPort "$H2_PORT")
if [[ "$H2_ALLOW_OTHERS" == "1" ]]; then
    SERVER_ARGS+=(-tcpAllowOthers)
fi
SERVER_ARGS+=(-tcpPassword "$H2_TCP_PASSWORD" -ifNotExists -baseDir "$H2_BASE_DIR")

# ---- 端口探测（先 /dev/tcp，再退到 nc / ss） ------------------------------
port_open() {
    if (exec 3<>"/dev/tcp/127.0.0.1/$H2_PORT") 2>/dev/null; then
        exec 3<&- 3>&-
        return 0
    fi
    if command -v nc >/dev/null 2>&1 && nc -z 127.0.0.1 "$H2_PORT" >/dev/null 2>&1; then
        return 0
    fi
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":$H2_PORT "; then
        return 0
    fi
    return 1
}

prepare_db() {
    local db="$1" url
    url="jdbc:h2:tcp://localhost:${H2_PORT}/${db};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    if "$JAVA_EXE" -cp "$H2_JAR" org.h2.tools.Shell -url "$url" \
            -user sa -password "$H2_PASSWORD" -sql "SET MODE MySQL" >/dev/null 2>&1; then
        printf '[h2] database "%s" prepared in MySQL mode\n' "$db"
    else
        printf '[h2] [WARN] database "%s" not prepared - check password or permissions\n' "$db"
    fi
}

print_urls() {
    local db
    printf '\n[h2] ready. Connect with a MySQL-compatible URL, for example:\n'
    for db in $H2_DATABASES; do
        printf '     jdbc:h2:tcp://localhost:%s/%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE\n' \
            "$H2_PORT" "$db"
    done
    printf '\n[h2] stop with: stop-h2.sh\n'
}

# ---- 分发 ------------------------------------------------------------------
case "${1:-}" in
    --help|-h)
        sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        printf '\n当前: H2_HOME=%s H2_PORT=%s H2_BASE_DIR=%s H2_ALLOW_OTHERS=%s\n' \
            "$H2_HOME" "$H2_PORT" "$H2_BASE_DIR" "$H2_ALLOW_OTHERS"
        exit 0
        ;;
    --status)
        if port_open; then
            printf '[h2] running - tcp://localhost:%s\n' "$H2_PORT"
        else
            printf '[h2] not running - port %s is closed\n' "$H2_PORT"
        fi
        exit 0
        ;;
    --foreground)
        mkdir -p "$H2_BASE_DIR"
        printf '[h2] running in foreground on port %s - press Ctrl+C to stop\n' "$H2_PORT"
        exec "$JAVA_EXE" -cp "$H2_JAR" org.h2.tools.Server "${SERVER_ARGS[@]}"
        ;;
esac

# ---- 后台启动 --------------------------------------------------------------
if port_open; then
    printf '[h2] port %s is already in use - the server looks started already.\n' "$H2_PORT"
    printf '[h2] use stop-h2.sh to stop it, or set H2_PORT to another port.\n'
    exit 0
fi

mkdir -p "$H2_BASE_DIR" "$H2_LOG_DIR"
printf '[h2] h2 home   : %s\n' "$H2_HOME"
printf '[h2] h2 jar    : %s\n' "$H2_JAR"
printf '[h2] java      : %s\n' "$JAVA_EXE"
printf '[h2] base dir  : %s\n' "$H2_BASE_DIR"
printf '[h2] log file  : %s\n' "$H2_LOG"
printf '[h2] starting TCP server on port %s ...\n' "$H2_PORT"

nohup "$JAVA_EXE" -cp "$H2_JAR" org.h2.tools.Server "${SERVER_ARGS[@]}" \
    >>"$H2_LOG" 2>&1 &
echo $! > "$H2_PID_FILE"

for _ in $(seq 1 30); do
    if port_open; then
        break
    fi
    sleep 1
done

if ! port_open; then
    printf '[h2] [ERROR] 30 秒内端口 %s 没起来，见日志 %s\n' "$H2_PORT" "$H2_LOG" >&2
    exit 1
fi

printf '[h2] TCP server is up on port %s (pid %s)\n' "$H2_PORT" "$(cat "$H2_PID_FILE" 2>/dev/null || echo '?')"
for db in $H2_DATABASES; do
    prepare_db "$db"
done
print_urls
