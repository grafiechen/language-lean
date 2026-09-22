#!/bin/sh
set -eu

# Windows 目录通过 WSL 挂载后，文件事件在不同 Docker 版本上并不完全可靠。
# 这里比较源码内容指纹，仅在实际变化时调用编译；DevTools 随后重启应用上下文。
fingerprint() {
  find src/main -type f -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    | sha256sum \
    | cut -d ' ' -f 1
}

echo "[dev] 首次编译后端源码"
mvn -q -DskipTests compile
last_fingerprint="$(fingerprint)"

watch_sources() {
  while sleep 1; do
    current_fingerprint="$(fingerprint)"
    if [ "$current_fingerprint" != "$last_fingerprint" ]; then
      last_fingerprint="$current_fingerprint"
      echo "[dev] 检测到后端源码变化，正在重新编译"
      if mvn -q -DskipTests compile; then
        echo "[dev] 编译完成，等待 Spring Boot 重启"
      else
        echo "[dev] 编译失败；修正代码并再次保存后会重试" >&2
      fi
    fi
  done
}

watch_sources &
watcher_pid=$!
mvn -q -DskipTests spring-boot:run &
application_pid=$!

# 容器停止时同时结束监听器和 Maven，避免留下孤儿进程。
cleanup() {
  kill "$watcher_pid" "$application_pid" 2>/dev/null || true
  wait "$watcher_pid" "$application_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
wait "$application_pid"
