#!/usr/bin/env bash
# ============================================================
# TicketHub 一键部署脚本（Ubuntu ECS / 单机 / 无域名 HTTP）
#
# 设计目标：可重复执行（幂等）。第一次跑是「从零装好」，
# 之后再跑是「更新代码并重启」，不会破坏已有的数据库数据。
#
# 使用方式（服务器上，项目已放在 /opt/tickethub）：
#   sudo bash /opt/tickethub/deploy/deploy.sh
#
# 只更新后端（前端没变时更快）：
#   sudo bash /opt/tickethub/deploy/deploy.sh --app-only
#
# 脚本做了什么：
#   1. 检查系统环境（Ubuntu / 内存 / 磁盘 / root 权限）
#   2. 2G 内存的机器自动创建 2G swap，避免 OOM
#   3. 安装 Docker、Docker Compose 插件、nginx
#   4. 首次运行时生成项目根目录 .env（随机密码），已存在则不覆盖
#   5. 部署前端静态文件到 /opt/tickethub/frontend/dist
#   6. 安装 nginx 站点配置并删除 Ubuntu 默认站点
#   7. docker compose up -d 启动 app/mysql/redis/rabbitmq
#   8. 等待并通过健康检查，失败时打印日志并退出非 0
#
# 重要：本脚本不会删除数据卷。要清库请手动执行
#   docker compose down -v
# ============================================================

set -Eeuo pipefail

# ---------------- 可调参数 ----------------
APP_DIR="/opt/tickethub"          # 项目在服务器上的固定目录
FRONTEND_DIR="${APP_DIR}/frontend/dist"
UPLOAD_DIR="${APP_DIR}/uploads"
NGINX_CONF="/etc/nginx/conf.d/tickethub.conf"
NGINX_DEFAULT="/etc/nginx/sites-enabled/default"
SWAP_SIZE="2G"                    # 交换分区大小
HEALTH_TIMEOUT=180                # 健康检查最长等待秒数

# ---------------- 输出样式 ----------------
C_RESET="\033[0m"; C_RED="\033[31m"; C_GREEN="\033[32m"
C_YELLOW="\033[33m"; C_BLUE="\033[36m"; C_BOLD="\033[1m"

log()  { printf "${C_BLUE}[信息]${C_RESET} %s\n" "$*"; }
ok()   { printf "${C_GREEN}[成功]${C_RESET} %s\n" "$*"; }
warn() { printf "${C_YELLOW}[注意]${C_RESET} %s\n" "$*"; }
err()  { printf "${C_RED}[失败]${C_RESET} %s\n" "$*" >&2; }
step() { printf "\n${C_BOLD}==== %s ====${C_RESET}\n" "$*"; }

# 任何命令失败时，打印出错行号，方便定位
trap 'err "脚本在第 $LINENO 行执行失败，已中止。"' ERR

# ---------------- 参数解析 ----------------
APP_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --app-only) APP_ONLY=1 ;;
    -h|--help)
      sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) err "未知参数：$arg（可用：--app-only）"; exit 2 ;;
  esac
done

# ============================================================
# 第 1 步：环境检查
# ============================================================
step "1/8 环境检查"

if [[ "${EUID}" -ne 0 ]]; then
  err "请用 root 权限运行：sudo bash $0"
  exit 1
fi

if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  log "系统：${PRETTY_NAME:-未知}"
  if [[ "${ID:-}" != "ubuntu" ]]; then
    warn "本脚本按 Ubuntu 编写，当前是 ${ID:-未知}，apt 命令可能不适用，请谨慎继续。"
  fi
else
  warn "读不到 /etc/os-release，无法确认发行版。"
fi

if ! command -v apt-get >/dev/null 2>&1; then
  err "未找到 apt-get，本脚本只支持 Debian/Ubuntu 系。"
  exit 1
fi

MEM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
log "物理内存：${MEM_MB} MB"

AVAIL_MB=$(df -Pm / | awk 'NR==2 {print $4}')
log "根分区可用空间：${AVAIL_MB} MB"
if [[ "${AVAIL_MB}" -lt 5120 ]]; then
  err "磁盘可用空间不足 5GB，Docker 镜像和数据库很快会写满，请先扩容或清理。"
  exit 1
fi

# 项目目录检查：脚本必须能从项目根目录找到 compose 与 jar
if [[ ! -f "${APP_DIR}/docker-compose.yml" ]]; then
  err "未找到 ${APP_DIR}/docker-compose.yml"
  err "请先把项目上传到 ${APP_DIR}（见 docs/DEPLOY.md 第 2 章），再运行本脚本。"
  exit 1
fi

# ============================================================
# 第 2 步：swap（2G 内存机器的保命措施）
# ============================================================
step "2/8 交换分区（swap）"

if [[ "${MEM_MB}" -lt 3072 ]]; then
  # 注意：这里不能用 `swapon ... | grep -q .`。
  # grep -q 一旦匹配就立即退出，会给 swapon 发送 SIGPIPE，
  # 在 set -o pipefail 下整条管道返回非 0，导致「明明有 swap 却判定为没有」。
  EXISTING_SWAP="$(swapon --show=NAME --noheadings 2>/dev/null || true)"
  if [[ -n "${EXISTING_SWAP}" ]]; then
    ok "已存在 swap，跳过创建。"
  else
    log "物理内存不足 3GB，创建 ${SWAP_SIZE} 交换分区……"
    if [[ ! -f /swapfile ]]; then
      # fallocate 在部分文件系统（如某些 overlay）上不可用，失败则退回 dd
      fallocate -l "${SWAP_SIZE}" /swapfile 2>/dev/null \
        || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
      chmod 600 /swapfile
      mkswap /swapfile >/dev/null
    fi
    swapon /swapfile 2>/dev/null || true
    # 写入 fstab 保证重启后自动挂载（幂等：先判断是否已存在）
    if ! grep -q '^/swapfile' /etc/fstab; then
      echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
    # 内存吃紧的机器降低 swap 使用倾向，但保留兜底能力
    sysctl -w vm.swappiness=10 >/dev/null
    if ! grep -q '^vm.swappiness' /etc/sysctl.conf; then
      echo 'vm.swappiness=10' >> /etc/sysctl.conf
    fi
    # 同样避免管道给 head 触发 SIGPIPE，改用命令替换取第一行
    SWAP_SIZE_NOW="$(swapon --show=SIZE --noheadings 2>/dev/null || true)"
    ok "swap 创建完成：${SWAP_SIZE_NOW%%$'\n'*}"
  fi
else
  ok "内存 ${MEM_MB} MB，无需额外 swap。"
fi

# ============================================================
# 第 3 步：安装 Docker / nginx
# ============================================================
step "3/8 安装 Docker 与 nginx"

export DEBIAN_FRONTEND=noninteractive

if command -v docker >/dev/null 2>&1; then
  ok "Docker 已安装：$(docker --version)"
else
  log "安装 Docker（使用官方脚本 get.docker.com）……"
  curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
  sh /tmp/get-docker.sh
  rm -f /tmp/get-docker.sh
  ok "Docker 安装完成：$(docker --version)"
fi

# 确保 Docker 服务开机自启并正在运行
systemctl enable --now docker >/dev/null 2>&1 || true
if ! docker info >/dev/null 2>&1; then
  err "Docker 守护进程没有正常运行，请执行 systemctl status docker 查看原因。"
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  ok "Docker Compose 插件可用：$(docker compose version)"
else
  err "未找到 docker compose 插件。官方安装脚本通常自带，"
  err "若缺失请手动安装 docker-compose-plugin 后重试。"
  exit 1
fi

if command -v nginx >/dev/null 2>&1; then
  ok "nginx 已安装：$(nginx -v 2>&1)"
else
  log "安装 nginx……"
  apt-get update -qq
  apt-get install -y -qq nginx
  ok "nginx 安装完成：$(nginx -v 2>&1)"
fi

# ============================================================
# 第 4 步：生成 .env
# ============================================================
step "4/8 生成环境变量文件"

# 注意：环境文件必须放在项目根目录，因为 docker compose 默认只读取
# 「compose 文件所在目录」下的 .env。放在 deploy/ 子目录里不会被读取，
# 会导致 ${MYSQL_PASSWORD:?...} 报错、容器直接启动失败。
ENV_FILE="${APP_DIR}/.env"
ENV_EXAMPLE="${APP_DIR}/deploy/.env.example"

gen_password() {
  # 生成 24 位不含特殊字符的随机密码，避免 shell / URL 转义问题。
  #
  # 这里刻意不用 `tr -dc ... < /dev/urandom | head -c 24`：
  # head 取够 24 字节就退出，会给 tr 发送 SIGPIPE，
  # 在 set -o pipefail 下整条管道返回 141，脚本会直接中止（实测踩过）。
  # 改为先读固定长度再截断：cut 会读完整个输入，不会触发 SIGPIPE。
  # 256 字节里字母数字的期望数量约 62 个，足够截出 24 位。
  LC_ALL=C head -c 256 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-24
}

if [[ -f "${ENV_FILE}" ]]; then
  ok ".env 已存在，保留现有密码（不会覆盖你改过的配置）。"
else
  if [[ ! -f "${ENV_EXAMPLE}" ]]; then
    err "缺少模板 ${ENV_EXAMPLE}"
    exit 1
  fi
  log "首次运行：从模板生成 ${ENV_FILE} 并写入随机密码……"
  cp "${ENV_EXAMPLE}" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"

  MYSQL_ROOT_PW=$(gen_password)
  MYSQL_APP_PW=$(gen_password)
  MQ_PW=$(gen_password)

  # 用 | 作分隔符，避免密码里出现 / 时出错（这里密码只含字母数字，双保险）
  sed -i "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PW}|" "${ENV_FILE}"
  sed -i "s|^MYSQL_PASSWORD=.*|MYSQL_PASSWORD=${MYSQL_APP_PW}|" "${ENV_FILE}"
  sed -i "s|^RABBITMQ_PASSWORD=.*|RABBITMQ_PASSWORD=${MQ_PW}|" "${ENV_FILE}"

  ok ".env 生成完成（权限 600，已写入随机密码）。"
  warn "MySQL root 密码已写入 ${ENV_FILE}，需要时执行："
  warn "  sudo grep MYSQL_ROOT_PASSWORD ${ENV_FILE}"
fi

# ============================================================
# 第 5 步：准备前端与上传目录
# ============================================================
step "5/8 准备前端静态文件"

mkdir -p "${UPLOAD_DIR}"
# nginx 以 www-data 运行，需要能读取上传目录里的图片。
# 755 保证可遍历可读，但不允许其他用户写入（只有 root/容器可写）。
chmod 755 "${UPLOAD_DIR}"

if [[ "${APP_ONLY}" -eq 0 ]]; then
  if [[ ! -f "${FRONTEND_DIR}/index.html" ]]; then
    err "未找到前端构建产物：${FRONTEND_DIR}/index.html"
    err "请在本地执行 frontend 目录下的 npm run build，"
    err "再把 dist 目录上传到 ${FRONTEND_DIR}（见 docs/DEPLOY.md 第 2 章）。"
    exit 1
  fi
  # 确保 nginx 能读取（Ubuntu 的 nginx 以 www-data 运行）
  chmod -R a+rX "${APP_DIR}/frontend"
  ok "前端文件就绪：$(find "${FRONTEND_DIR}" -type f | wc -l) 个文件"
else
  log "--app-only 模式：跳过前端检查。"
fi

# 后端 jar 检查（Dockerfile.runtime 依赖它）
if ! compgen -G "${APP_DIR}/target/ticket-hub-*.jar" >/dev/null; then
  err "未找到后端 jar：${APP_DIR}/target/ticket-hub-*.jar"
  err "请在本地执行 mvn -DskipTests clean package，再上传 target 目录。"
  exit 1
fi

# ============================================================
# 第 6 步：安装 nginx 配置
# ============================================================
step "6/8 配置 nginx"

if [[ "${APP_ONLY}" -eq 1 ]]; then
  log "--app-only 模式：跳过 nginx 配置更新。"
else
  if [[ ! -f "${APP_DIR}/deploy/nginx/tickethub.conf" ]]; then
    err "缺少 nginx 配置文件 ${APP_DIR}/deploy/nginx/tickethub.conf"
    exit 1
  fi

  # 备份旧配置，便于回滚
  if [[ -f "${NGINX_CONF}" ]]; then
    cp "${NGINX_CONF}" "${NGINX_CONF}.bak.$(date +%Y%m%d%H%M%S)"
  fi
  cp "${APP_DIR}/deploy/nginx/tickethub.conf" "${NGINX_CONF}"

  # Ubuntu 默认站点也监听 80 且是 default_server，
  # 不移除会与我们配置里的 default_server 冲突，nginx 直接启动失败。
  if [[ -e "${NGINX_DEFAULT}" ]]; then
    rm -f "${NGINX_DEFAULT}"
    log "已移除 Ubuntu 默认站点 ${NGINX_DEFAULT}（避免 80 端口冲突）。"
  fi

  if nginx -t; then
    ok "nginx 配置语法检查通过。"
  else
    err "nginx 配置语法错误，已保留备份 ${NGINX_CONF}.bak.*，请检查。"
    exit 1
  fi

  systemctl enable nginx >/dev/null 2>&1 || true
  systemctl reload nginx 2>/dev/null || systemctl restart nginx
  ok "nginx 已重新加载配置。"
fi

# ============================================================
# 第 7 步：启动容器
# ============================================================
step "7/8 启动 Docker Compose"

cd "${APP_DIR}"

# 检查 compose 能否正确解析（能提前发现 .env 缺失、语法错误）
if ! docker compose config >/dev/null 2>&1; then
  err "docker-compose.yml 解析失败，详情："
  docker compose config >&2 || true
  exit 1
fi

log "构建并启动容器（首次会拉取 MySQL/Redis/RabbitMQ 镜像，需要几分钟）……"
docker compose up -d --build --remove-orphans

ok "容器已启动，等待服务就绪……"

# ============================================================
# 第 8 步：健康检查
# ============================================================
step "8/8 健康检查"

wait_for() {
  local desc="$1" url="$2" expect="$3" deadline=$((SECONDS + HEALTH_TIMEOUT))
  local body
  while (( SECONDS < deadline )); do
    # 先把响应体读进变量再判断，不要用 `curl | grep -q`：
    # grep -q 匹配到就退出，可能给 curl 发送 SIGPIPE，
    # 在 set -o pipefail 下会让整条管道判为失败，出现「服务其实正常却报未就绪」。
    body="$(curl -fsS --max-time 5 "${url}" 2>/dev/null || true)"
    if [[ "${body}" == *"${expect}"* ]]; then
      ok "${desc} 正常"
      return 0
    fi
    sleep 5
  done
  err "${desc} 在 ${HEALTH_TIMEOUT} 秒内未就绪（${url}）"
  return 1
}

print_diagnostics() {
  err "下面是排查用的现场信息："
  echo "---------------- 容器状态 ----------------"
  docker compose ps || true
  echo "---------------- app 日志（末尾 80 行）----------------"
  docker compose logs --tail=80 app || true
  echo "---------------- 系统内存 ----------------"
  free -h || true
  echo "---------------- 磁盘 ----------------"
  df -h / || true
}

FAILED=0

# nginx 静态站（不带域名，直接访问 IP）
wait_for "nginx 静态页面" "http://127.0.0.1/" "<div id=\"app\">" || FAILED=1
# 后端 API（走 nginx 反代到 8081）
wait_for "后端 API" "http://127.0.0.1/api/show-category/list" '"success"' || FAILED=1

if (( FAILED != 0 )); then
  print_diagnostics
  err "部署未通过健康检查。数据库数据未受影响，修好问题后重跑本脚本即可。"
  exit 1
fi

PUBLIC_IP=$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "<你的ECS公网IP>")

echo
ok "=========================================="
ok " TicketHub 部署完成"
ok "=========================================="
echo "  访问地址：http://${PUBLIC_IP}/"
echo "  健康检查：http://${PUBLIC_IP}/health"
echo "  接口示例：http://${PUBLIC_IP}/api/show-category/list"
echo
echo "  查看日志：cd ${APP_DIR} && docker compose logs -f app"
echo "  查看容器：cd ${APP_DIR} && docker compose ps"
echo "  重启后端：cd ${APP_DIR} && docker compose restart app"
echo
echo "  演示账号：13800000001 ~ 13800000006（验证码在页面上直接返回）"
echo
warn "若浏览器打不开，先检查阿里云安全组是否放行 80 端口。"
