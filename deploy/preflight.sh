#!/usr/bin/env bash
# ============================================================
# TicketHub 部署前置体检（只读，不改任何配置）
#
# 用途：在一台"已经跑着别的东西"的服务器上，判断能不能再叠加
#       TicketHub 的 4 个容器（app / mysql / redis / rabbitmq）。
#
# 用法：
#   bash preflight.sh              # 完整体检
#   bash preflight.sh --quiet      # 只打印结论行
#
# 退出码：
#   0 = 全部通过，可以部署
#   1 = 有阻断项（内存不足 / 端口被占 / 磁盘不足），不要部署
#   2 = 有警告项（可以部署但需要接受风险）
# ============================================================
set -uo pipefail

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

BLOCKERS=0
WARNINGS=0

say()  { [ "$QUIET" -eq 1 ] || echo "$@"; }
ok()   { say "  [OK]   $*"; }
warn() { say "  [WARN] $*"; WARNINGS=$((WARNINGS + 1)); }
bad()  { say "  [FAIL] $*"; BLOCKERS=$((BLOCKERS + 1)); }
sec()  { say ""; say "== $* =="; }

# ------------------------------------------------------------
# 1. 内存：这是本机唯一的真实阻断项
# ------------------------------------------------------------
sec "内存"
read -r mem_total mem_used mem_free mem_shared mem_buff mem_avail < <(
  free -m | awk '/^Mem:/{print $2, $3, $4, $5, $6, $7}'
)
say "  总内存 ${mem_total}MB / 已用 ${mem_used}MB / 可用 ${mem_avail}MB"

# 两档配置，对应 docker-compose.yml 的两套参数：
#
#   标准档（4G 及以上）：app 800m / mysql 500m / redis 256m / rabbitmq 400m
#       实际占用约 1230-1560MB，目标线 1500MB
#
#   精简档（2G 且已跑着别的服务）：app 512m / mysql 360m / redis 160m / rabbitmq 300m
#       实际占用约 850-950MB，目标线 1000MB
#
# 判定：缺口小于 30% 还能靠 swap 兜，缺口大于 30% 就意味着 MySQL 的
# buffer pool 会被换出，抢票链路延迟从毫秒变秒级。
NEED=1000          # 精简档目标线（本机默认按精简档判，因为同机已有服务）
FLOOR=$((NEED * 70 / 100))   # 700MB
if [ "$mem_avail" -lt "$FLOOR" ]; then
  bad "可用内存仅 ${mem_avail}MB，精简档需要约 ${NEED}MB（最低 ${FLOOR}MB）。缺口 $((${NEED} - ${mem_avail}))MB"
elif [ "$mem_avail" -lt "$NEED" ]; then
  warn "可用 ${mem_avail}MB < 精简档建议 ${NEED}MB，会占用一部分 swap，演示可用但首页加载略慢"
else
  ok "可用 ${mem_avail}MB，满足精简档 ${NEED}MB 需求"
  if [ "$mem_avail" -ge 1500 ]; then
    say "       （≥1500MB，也可以直接用标准档配置）"
  fi
fi

# ------------------------------------------------------------
# 2. Swap：2G 机器没有 swap 就等于没有安全网
# ------------------------------------------------------------
sec "Swap"
swap_line=$(free -m | awk '/^Swap:/{print $2" "$3}')
swap_total=$(echo "$swap_line" | awk '{print $1}')
swap_used=$(echo "$swap_line" | awk '{print $2}')
if [ "${swap_total:-0}" -eq 0 ]; then
  bad "没有 swap。2G 机器必须至少 2G swap，否则一次峰值就 OOM"
else
  say "  swap 总量 ${swap_total}MB / 已用 ${swap_used}MB"
  if [ "$swap_used" -gt $((swap_total / 2)) ]; then
    warn "swap 已用超过一半，说明内存长期紧张，叠加部署风险高"
  else
    ok "swap 余量充足"
  fi
fi

swappiness=$(cat /proc/sys/vm/swappiness 2>/dev/null || echo "?")
if [ "$swappiness" != "?" ] && [ "$swappiness" -gt 60 ]; then
  warn "vm.swappiness=${swappiness} 偏高，数据库容易被迫换出，建议 10-20"
else
  ok "vm.swappiness=${swappiness}"
fi

# ------------------------------------------------------------
# 3. 磁盘
# ------------------------------------------------------------
sec "磁盘"
disk_avail_mb=$(df -Pm / | awk 'NR==2{print $4}')
disk_use=$(df -Ph / | awk 'NR==2{print $5}')
say "  / 剩余 ${disk_avail_mb}MB（已用 ${disk_use}）"
# 镜像约 600MB + MySQL 数据 + 日志，留 8G 比较稳
if [ "$disk_avail_mb" -lt 5000 ]; then
  bad "根分区剩余不足 5G，拉镜像会失败"
elif [ "$disk_avail_mb" -lt 8000 ]; then
  warn "根分区剩余 ${disk_avail_mb}MB，建议清理到 8G 以上"
else
  ok "磁盘充足"
fi

inode_use=$(df -Pi / | awk 'NR==2{print $5}' | tr -d '%')
if [ "${inode_use:-0}" -gt 80 ]; then
  warn "inode 使用率 ${inode_use}%，清理 /var/lib/docker 或旧日志"
else
  ok "inode 使用率 ${inode_use}%"
fi

# ------------------------------------------------------------
# 4. 端口冲突（只看 TicketHub 要用的 5 个）
# ------------------------------------------------------------
sec "端口"
for p in 80 8081 3306 6379 5672; do
  if ss -lntH "sport = :$p" 2>/dev/null | grep -q .; then
    holder=$(ss -lntpH "sport = :$p" 2>/dev/null | head -1 | sed -n 's/.*users:(("\([^"]*\)".*/\1/p')
    if [ "$p" = "80" ]; then
      bad "80 已被 ${holder:-未知进程} 占用，nginx 起不来"
    else
      bad "${p} 已被 ${holder:-未知进程} 占用"
    fi
  else
    ok "${p} 空闲"
  fi
done

# ------------------------------------------------------------
# 5. Docker
# ------------------------------------------------------------
sec "Docker"
if ! command -v docker >/dev/null 2>&1; then
  warn "未安装 Docker，部署脚本会自动装"
else
  ok "Docker $(docker --version 2>/dev/null | awk '{print $3}' | tr -d ,)"
  if docker compose version >/dev/null 2>&1; then
    ok "Compose $(docker compose version --short 2>/dev/null)"
  else
    bad "缺少 docker compose 插件（v2），部署脚本依赖它"
  fi
  if docker info >/dev/null 2>&1; then
    cg=$(stat -fc %T /sys/fs/cgroup 2>/dev/null)
    ok "守护进程可连接，cgroup=${cg}"
  else
    bad "Docker 守护进程连不上（未启动或当前用户无权限）"
  fi
fi

# ------------------------------------------------------------
# 6. 现有容器与内存占用（判断"叠加"是否会挤爆）
# ------------------------------------------------------------
sec "现有容器"
if docker ps --format '{{.Names}}' >/dev/null 2>&1; then
  running=$(docker ps -q | wc -l)
  say "  运行中容器 ${running} 个"
  [ "$QUIET" -eq 0 ] && docker stats --no-stream --format '  {{.Name}} | {{.MemUsage}} | {{.MemPerc}} | CPU {{.CPUPerc}}' 2>/dev/null

  tickethub=$(docker ps -a --format '{{.Names}}' | grep -c '^tickethub-' || true)
  if [ "${tickethub:-0}" -gt 0 ]; then
    warn "已存在 ${tickethub} 个 tickethub-* 容器（可能是上次部署残留）"
  fi

  # 已存在容器的 mem_limit 之和，用来判断是否已经超卖
  limits=$(docker ps -q | xargs -r docker inspect --format '{{.HostConfig.Memory}}' 2>/dev/null \
           | awk '{s+=$1} END{printf "%d", s/1024/1024}')
  if [ -n "${limits:-}" ] && [ "$limits" -gt 0 ]; then
    say "  现有容器 mem_limit 合计 ${limits}MB"
    if [ "$limits" -gt "$mem_total" ]; then
      warn "现有容器内存上限合计已超过物理内存，本机已在靠 swap 运行"
    fi
  fi
else
  warn "无法读取 docker ps（Docker 未装或权限不足）"
fi

# ------------------------------------------------------------
# 7. 系统与安全
# ------------------------------------------------------------
sec "系统"
say "  $(uname -srm)"
say "  CPU $(nproc) 核，load $(cut -d' ' -f1-3 /proc/loadavg)"
[ "$QUIET" -eq 0 ] && say "  运行 $(uptime -p 2>/dev/null || echo '未知')"

if command -v ufw >/dev/null 2>&1; then
  ufw_state=$(ufw status 2>/dev/null | head -1 | awk '{print $2}')
  if [ "$ufw_state" = "active" ]; then
    ok "ufw 已启用（注意：必须放行 22 和 80，否则会把自己锁在外面）"
  else
    warn "ufw 未启用，公网暴露面完全依赖阿里云安全组"
  fi
fi

oom_hits=$(dmesg -T 2>/dev/null | grep -ciE 'out of memory|oom-kill|killed process' || true)
if [ "${oom_hits:-0}" -gt 0 ]; then
  warn "内核日志中有 ${oom_hits} 条 OOM 记录，说明本机曾被打爆过"
  [ "$QUIET" -eq 0 ] && dmesg -T 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -5
else
  ok "未发现 OOM 历史"
fi

# ------------------------------------------------------------
# 结论
# ------------------------------------------------------------
echo ""
echo "============================================"
if [ "$BLOCKERS" -gt 0 ]; then
  echo "结论：不可部署（${BLOCKERS} 个阻断项，${WARNINGS} 个警告）"
  echo "先处理上面标 [FAIL] 的项，再跑一次本脚本。"
  echo "============================================"
  exit 1
elif [ "$WARNINGS" -gt 0 ]; then
  echo "结论：可部署，但有 ${WARNINGS} 个风险项"
  echo "建议先看 docs/PRE_DEPLOY_CHECK.md 的「三条处置路线」。"
  echo "============================================"
  exit 2
else
  echo "结论：通过，可以按 docs/DEPLOY.md 部署"
  echo "============================================"
  exit 0
fi
