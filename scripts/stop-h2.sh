#!/usr/bin/env bash
# =============================================================================
#  H2 Database Server —— 停止
#
#  依次尝试：
#    1) 优雅关停：org.h2.tools.Server -tcpShutdown（与启动时的 -tcpPassword 一致）
#    2) 兜底：按 pid 文件 / 进程名结束 java 进程
#
#  环境变量：与 start-h2.sh 相同（H2_HOME / H2_PORT / H2_TCP_PASSWORD）。
# =============================================================================
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${H2_HOME:-}" ]]; then
    if compgen -G "$HERE/bin/h2-*.jar" >/dev/null 2>&1; then
        H2_HOME="$HERE"
    else
        H2_HOME="/opt/h2"
    fi
fi

H2_PORT="${H2_PORT:-9092}"
H2_PID_FILE="$H2_HOME/h2.pid"

if [[ -z "${H2_TCP_PASSWORD:-}" && -f "$H2_HOME/h2.tcp-password" ]]; then
    H2_TCP_PASSWORD="$(cat "$H2_HOME/h2.tcp-password")"
fi
H2_TCP_PASSWORD="${H2_TCP_PASSWORD:-}"

H2_JAR="$(ls -1 "$H2_HOME"/bin/h2-*.jar 2>/dev/null | sort -V | tail -n 1 || true)"
if [[ -z "$H2_JAR" ]]; then
    printf '[h2] [ERROR] 在 %s/bin 下找不到 h2-*.jar\n' "$H2_HOME" >&2
    exit 1
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_EXE="$(command -v java)"
else
    printf '[h2] [ERROR] 找不到 java，请设置 JAVA_HOME\n' >&2
    exit 1
fi

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

kill_by_pid_file() {
    if [[ -f "$H2_PID_FILE" ]]; then
        local pid
        pid="$(cat "$H2_PID_FILE" 2>/dev/null || true)"
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            sleep 1
            kill -9 "$pid" 2>/dev/null || true
            printf '[h2] killed pid %s (pid file)\n' "$pid"
            return 0
        fi
    fi
    return 1
}

kill_by_pgrep() {
    command -v pgrep >/dev/null 2>&1 || return 1
    local pids
    pids="$(pgrep -f 'org.h2.tools.Server' || true)"
    [[ -n "$pids" ]] || return 1
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 1
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
    printf '[h2] killed pid(s) %s\n' "$(echo "$pids" | tr '\n' ' ')"
    return 0
}

if ! port_open; then
    kill_by_pid_file >/dev/null || kill_by_pgrep >/dev/null || true
    printf '[h2] not running - nothing to stop\n'
    exit 0
fi

printf '[h2] shutting down TCP server on port %s ...\n' "$H2_PORT"
if [[ -n "$H2_TCP_PASSWORD" ]]; then
    "$JAVA_EXE" -cp "$H2_JAR" org.h2.tools.Server \
        -tcpPassword "$H2_TCP_PASSWORD" -tcpShutdown "tcp://localhost:$H2_PORT" -tcpShutdownForce \
        || printf '[h2] [WARN] graceful shutdown failed, falling back to kill\n'
else
    "$JAVA_EXE" -cp "$H2_JAR" org.h2.tools.Server \
        -tcpShutdown "tcp://localhost:$H2_PORT" -tcpShutdownForce \
        || printf '[h2] [WARN] graceful shutdown failed, falling back to kill\n'
fi

for _ in $(seq 1 10); do
    if ! port_open; then
        printf '[h2] stopped (port %s released)\n' "$H2_PORT"
        exit 0
    fi
    sleep 1
done

printf '[h2] graceful shutdown timed out - killing the server process ...\n'
kill_by_pid_file || kill_by_pgrep || true
sleep 1

if port_open; then
    printf '[h2] [ERROR] 端口 %s 仍被占用，可能是别的进程\n' "$H2_PORT" >&2
    exit 1
fi
printf '[h2] stopped (port %s released)\n' "$H2_PORT"
