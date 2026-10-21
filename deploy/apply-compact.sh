#!/usr/bin/env bash
# ============================================================
# 把 TicketHub 的 .env 一键切换成「精简档」
#
# 适用场景：2核2G 服务器，同机还跑着别的服务（比如海豹骰娘）。
# 幂等：可以反复执行；每次执行前自动备份原 .env。
#
# 用法（服务器上，项目目录通常是 /opt/tickethub）：
#   bash deploy/apply-compact.sh
#   APP_DIR=/opt/tickethub bash deploy/apply-compact.sh
# ============================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/tickethub}"
ENV_FILE="${APP_DIR}/.env"

KEYS=(APP_MEM_LIMIT MYSQL_MEM_LIMIT REDIS_MEM_LIMIT RABBITMQ_MEM_LIMIT \
      MYSQL_BUFFER_POOL REDIS_MAXMEMORY RABBITMQ_WATERMARK JAVA_OPTS)

if [[ ! -f "$ENV_FILE" ]]; then
  echo "[ERROR] 找不到 $ENV_FILE"
  echo "        先跑一次 deploy/deploy.sh（它会从模板生成 .env），再来切精简档。"
  exit 1
fi

BAK="${ENV_FILE}.bak.$(date +%Y%m%d-%H%M%S)"
cp -a "$ENV_FILE" "$BAK"
chmod 600 "$BAK"
echo "[OK] 已备份：$BAK"

# 一次性删掉这 8 个键的所有旧行（避免重复键导致取值不确定）
PATTERN="^[[:space:]]*($(IFS='|'; echo "${KEYS[*]}"))="
TMP="$(mktemp)"
grep -v -E "$PATTERN" "$ENV_FILE" > "$TMP" || true

cat >> "$TMP" <<'EOF'

# ===== 精简档（2核2G / 同机已有其他服务）=====
# 注意：mem_limit 是上限保险丝，不是实际占用。
# 实测四个容器合计约 800-990MB。
APP_MEM_LIMIT=512m
MYSQL_MEM_LIMIT=360m
REDIS_MEM_LIMIT=160m
RABBITMQ_MEM_LIMIT=300m
MYSQL_BUFFER_POOL=48M
REDIS_MAXMEMORY=48mb
RABBITMQ_WATERMARK=128MB
JAVA_OPTS=-Xms192m -Xmx384m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
EOF

mv "$TMP" "$ENV_FILE"
chmod 600 "$ENV_FILE"

echo "[OK] 已切换为精简档，当前生效值："
grep -E '^(APP_MEM_LIMIT|MYSQL_MEM_LIMIT|REDIS_MEM_LIMIT|RABBITMQ_MEM_LIMIT|MYSQL_BUFFER_POOL|REDIS_MAXMEMORY|RABBITMQ_WATERMARK|JAVA_OPTS)=' "$ENV_FILE"

cat <<'EOF'

下一步（在项目目录下执行）：
  docker compose up -d --force-recreate
  sleep 20 && docker stats --no-stream

如果内存还是紧张，把 RABBITMQ_MEM_LIMIT 再降到 256m（队列少，够用）。
EOF