# ============================================================
# TicketHub 前端镜像（多阶段：Node 构建 → nginx 托管）
#
# 为什么在容器里构建：
#   组员的电脑不一定装了 Node 18+，也不一定愿意跑 npm install。
#   把构建过程放进镜像后，组员只需要 Docker，一条命令就能拿到
#   和生产一致的 dist，不需要在本机折腾 Node 版本。
#
# 构建上下文是 frontend/ 目录（见 docker-compose.local.yml）。
# ============================================================

# ---------- 构建阶段 ----------
FROM node:20-alpine AS build
WORKDIR /app

# 先只拷依赖清单，利用层缓存：改业务代码时不会重新 npm ci
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

# 本地/演示用站点配置：静态托管 + /api 反代到 app 容器
COPY nginx.local.conf /etc/nginx/conf.d/default.conf

# 构建产物
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
