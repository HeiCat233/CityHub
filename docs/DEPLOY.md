# TicketHub 部署手册（阿里云 ECS · Ubuntu · 无域名 HTTP）

> ⚠️ **如果这台服务器上已经跑着别的服务**（例如海豹骰娘 + NapCat），
> 请先读 [PRE_DEPLOY_CHECK.md](./PRE_DEPLOY_CHECK.md) 并跑一遍 `deploy/preflight.sh`。
> 同机叠加部署的第一道坎不是端口，而是内存余量——不够就会 OOM，页面能开但接口 502。

> 本文档面向「第一次部署 Java 项目」的情况，**每个命令都解释它做什么、为什么、怎么判断成功**。
> 阅读顺序建议：第 0 章看懂架构 → 第 1 章准备 → 第 2 章手动部署（自己动手，能学到东西）
> → 第 3 章自动部署（我替你跑，省事）→ 第 4 章验证 → 第 5~7 章按需查阅。

**目录**

1. [第 0 章 原理与架构](#第-0-章-原理与架构)
2. [第 1 章 部署前准备](#第-1-章-部署前准备)
3. [第 2 章 手动部署教程](#第-2-章-手动部署教程)
4. [第 3 章 自动部署教程](#第-3-章-自动部署教程我能替你做到的)
5. [第 4 章 上线验证](#第-4-章-上线验证)
6. [第 5 章 OSS 图片配置](#第-5-章-oss-图片配置)
7. [第 6 章 日常运维与故障排查](#第-6-章-日常运维与故障排查)
8. [第 7 章 项目目录与文件职责](#第-7-章-项目目录与文件职责)
9. [附录 A 服务器内构建](#附录-a服务器内构建备选方案)
10. [附录 B 升级到域名 + HTTPS + OSS/CDN](#附录-b升级到域名--https--osscdn)

> 相关文档：[PRE_DEPLOY_CHECK.md](./PRE_DEPLOY_CHECK.md)（部署前体检清单）、
> [PROJECT_GUIDE.md](./PROJECT_GUIDE.md)（代码讲解）

---

## 第 0 章 原理与架构

### 0.1 整体架构

```
                        用户浏览器
                            │
                            │ http://<ECS公网IP>/
                            ▼
        ┌───────────────────────────────────────────┐
        │        阿里云 ECS（Ubuntu, 2核2G）          │
        │                                           │
        │   ┌─────────────────────────────────┐     │
        │   │  nginx  :80  （对外唯一入口）     │     │
        │   │                                 │     │
        │   │  /            → 前端 dist 静态页 │     │
        │   │  /assets/*    → JS/CSS（长缓存） │     │
        │   │  /imgs/*      → 图片             │     │
        │   │  /api/*       → 反代到 8081      │     │
        │   │  /health      → 探活             │     │
        │   └───────────────┬─────────────────┘     │
        │                   │ 127.0.0.1:8081        │
        │                   ▼                       │
        │   ┌─────────────────────────────────┐     │
        │   │  Spring Boot（Docker 容器）      │     │
        │   └───┬─────────────┬───────────────┘     │
        │       │             │                     │
        │   ┌───▼───┐   ┌─────▼────┐   ┌────────┐   │
        │   │MySQL 8│   │ Redis 7  │   │RabbitMQ│   │
        │   │ 容器  │   │  容器    │   │  容器  │   │
        │   └───────┘   └──────────┘   └────────┘   │
        │      ↑ 3306/6379/5672 只在 Docker 内网     │
        └───────────────────────────────────────────┘

    阿里云 OSS（香港桶）── 只存用户上传的图片（可选）
```

### 0.2 为什么前端不放 OSS，而是放 nginx

这是本项目**最反直觉**的一个决策，必须讲清楚，否则会被问住。

阿里云 OSS 确实能做静态网站托管，但有一个限制：

- **OSS 默认域名**（形如 `bucket.oss-cn-hongkong.aliyuncs.com`）访问 `index.html` 时，
  阿里云会强制返回 `Content-Disposition: attachment`，浏览器**直接下载文件**而不是渲染页面。
- 想让它正常渲染，必须给 OSS 绑定**自定义域名**（走 CNAME）。

而我们当前的约束是「**不买域名**」，所以：

- 前端 HTML/JS/CSS 由 **ECS 上的 nginx** 托管 → 用 IP 就能正常打开；
- OSS 只用来存**用户上传的图片**（`.jpg`/`.png` 不受上面的限制，默认域名可以直接访问）；
- 好处是顺带把 nginx 练透了：静态托管、反向代理、限流、gzip、长缓存、SPA 回退，一个不少。

> 答辩时可以这样表述：
> 「我们评估过 OSS + CDN 托管前端，这是生产环境的标准做法；但 OSS 默认域名会把 HTML 强制下载，
> 需要绑定备案域名。在课程阶段我们用 nginx 同时承担静态托管和 API 网关，把 OSS 留给图片存储，
> 既省了域名成本，也把 nginx 的反代/限流/缓存都练了一遍。后续买到域名后可以平滑升级（见附录 B）。」

### 0.3 为什么用 Docker Compose

不用 Docker 的话，要手动装 MySQL、Redis、RabbitMQ，每个都要配配置文件、开机自启、版本管理，
换台机器就要重来一遍。Docker Compose 的价值：

| 问题 | 传统方式 | Docker Compose |
|---|---|---|
| 装依赖 | 每个服务单独 apt 装 + 配 | `docker compose up -d` 一条命令 |
| 版本固定 | 依赖系统源，版本随系统变 | 镜像 tag 写死（`mysql:8.0`） |
| 隔离 | 端口/配置互相干扰 | 各容器独立，只在虚拟网络内互通 |
| 数据持久化 | 自己规划目录 | 声明 `volumes`，重建容器数据不丢 |
| 换机器 | 全部重装 | 装好 Docker，`up -d` 即可 |

本项目 `docker-compose.yml` 里定义了 4 个服务：`app`（Spring Boot）、`mysql`、`redis`、`rabbitmq`。
它们同属一个自定义 bridge 网络 `tickethub`，**容器之间用服务名互相访问**
（例如 app 连接 `jdbc:mysql://mysql:3306/...`，`mysql` 就是服务名，由 Docker 内置 DNS 解析）。

### 0.4 为什么 2核2G 必须做内存限制

这是**最容易翻车的地方**。四个服务的默认内存需求加起来远超 2G：

| 组件 | 默认可能占用 | 本项目限制 | 限制手段 |
|---|---|---|---|
| JVM（Spring Boot） | 默认堆 ≈ 物理内存的 1/4，加元空间/线程栈可达 800M+ | 堆 512M | `JAVA_OPTS=-Xmx512m` + `mem_limit: 800m` |
| MySQL 8 | buffer pool 默认 128M + performance_schema，常驻 600M+ | 约 350M | `--innodb-buffer-pool-size=128M` `--performance-schema=OFF` + `mem_limit: 500m` |
| Redis 7 | 默认无上限，可吃光内存 | 128M | `--maxmemory 128mb` + `mem_limit: 256m` |
| RabbitMQ | Erlang VM 默认按物理内存申请 | 约 300M | `vm_memory_high_watermark 0.4` + `mem_limit: 400m` |
| 系统 + Docker | 200~300M | — | — |

**不加限制会发生什么**：Linux 的 OOM Killer 会挑「占内存最多」的进程杀掉，
通常是 MySQL 或 Java，表现为「容器莫名其妙重启」「日志里有 `Killed`」。

**为什么还要建 swap**：swap 是磁盘上的虚拟内存。物理内存不够时，系统把不常用的内存页
换到磁盘，避免直接杀进程。代价是慢（磁盘比内存慢几个数量级），所以 `vm.swappiness=10`
表示「尽量别用 swap，只在快撑不住时兜底」。2G 机器上加 2G swap 是性价比最高的保命手段。

**Redis 的 `maxmemory-policy` 为什么是 `volatile-lru` 而不是 `allkeys-lru`**：
本项目用 Redis 存秒杀库存（`seckill:stock:{tierId}`），这些 key **没有设置 TTL**。
如果用 `allkeys-lru`，内存吃紧时 Redis 可能把库存 key 淘汰掉，
之后 Lua 脚本读到 nil 会返回 `-1`，抢票直接报「库存未预热」而全部失败。
`volatile-lru` 只淘汰**设置了过期时间**的 key（登录 token、演出缓存等），业务安全。

### 0.5 端口可见性：谁能访问谁

| 端口 | 服务 | 是否对公网开放 | 说明 |
|---|---|---|---|
| 22 | SSH | ✅ 必须 | 你登录服务器用，建议在安全组限制来源 IP |
| 80 | nginx | ✅ 必须 | 唯一对外入口 |
| 8081 | Spring Boot | ❌ 禁止 | compose 里绑定 `127.0.0.1:8081`，外网无法直连 |
| 3306 | MySQL | ❌ 禁止 | 只在 Docker 网络内，容器名 `mysql` |
| 6379 | Redis | ❌ 禁止 | 只在 Docker 网络内，容器名 `redis` |
| 5672 | RabbitMQ | ❌ 禁止 | 只在 Docker 网络内，容器名 `rabbitmq` |
| 15672 | RabbitMQ 管理页 | ❌ 禁止 | 需要时用 SSH 隧道看（见 4.5） |

**为什么必须这样**：把 MySQL 的 3306 暴露到公网，等于把数据库交给全世界的扫描器。
互联网上每天都有大量自动化脚本扫描 3306/6379 端口尝试爆破或未授权访问。
即使密码很强，也没有必要开这个口子。

### 0.6 请求的完整生命周期

以「用户点开演出详情」为例：

```
1. 浏览器请求 http://<IP>/show/1
2. nginx 查 root 目录，没有 /show/1 这个文件
3. try_files 回退到 /index.html（Vue Router history 模式必需）
4. 浏览器加载 index.html + assets/*.js，Vue 启动，路由解析 /show/1
5. Vue 调用 axios.get('/api/show/1')
6. nginx 匹配 location /api/，rewrite 去掉 /api 前缀 → /show/1
7. proxy_pass 转发到 127.0.0.1:8081
8. Spring Boot 的 RefreshTokenInterceptor 校验 token（有则续期）
9. ShowController 调 ShowServiceImpl.queryById()
10. Redis 命中则直接返回；未命中走缓存重建逻辑
11. 响应原路返回，Vue 渲染页面
```

**为什么前端用 `/api` 相对路径**：因为前端和 API 都由同一个 nginx 提供，
浏览器认为是**同源请求**，不存在跨域，也就不需要 CORS 和 HTTPS。
如果前端放 OSS、API 放 ECS，就是跨域，必须配 CORS 且要求 HTTPS，复杂度高得多。

---

## 第 1 章 部署前准备

### 1.1 需要准备的信息清单

部署前把这些信息记下来（打勾确认）：

- [ ] **ECS 公网 IP**：阿里云控制台 → 实例列表 → 公网 IP
- [ ] **SSH 登录方式**：密钥对（`.pem` 文件）或 密码
- [ ] **SSH 用户名**：Ubuntu 镜像默认是 `ubuntu`
- [ ] **ECS 地域**：国内大陆（杭州/北京/上海等）或 香港
- [ ] **Ubuntu 版本**：22.04 或 24.04（登录后 `lsb_release -a` 确认）
- [ ] **OSS 信息**（可选）：Bucket 名称、地域 Endpoint、AccessKey ID/Secret

### 1.2 阿里云安全组放行端口（必须做，否则打不开）

安全组是阿里云在**云平台层面**的防火墙，比服务器内部的 `ufw` 更外层，
两层都要放行才通。

**操作路径**：阿里云控制台 → ECS → 实例 → 点进实例 → 安全组 → 配置规则 → 入方向 → 手动添加

| 端口范围 | 授权对象 | 用途 |
|---|---|---|
| 22/22 | `0.0.0.0/0`（或你自己的出口 IP 更安全） | SSH 登录 |
| 80/80 | `0.0.0.0/0` | 网站访问 |
| 443/443 | `0.0.0.0/0` | 将来上 HTTPS 用（现在可先不加） |

**不要添加**：3306、6379、5672、15672、8081。这些只在服务器内部使用。

> **验证方法**：放行后用手机流量（不是家里 WiFi）访问 `http://<公网IP>/`。
> 如果浏览器一直转圈最后超时，99% 是安全组没放行或服务器没起服务。

### 1.3 本地构建产物（为什么不在服务器上构建）

**关键决策：构建在本地做，服务器只运行。**

原因：2核2G 的机器上跑 `mvn package` 需要下载几百 MB 依赖并编译，
峰值内存很容易超过 1G，会把 MySQL 挤到 swap 甚至触发 OOM Killer。
而本地机器性能充足，构建完只需要上传一个约 50MB 的 jar。

在**本地 Windows**（项目根目录）执行：

```powershell
# 1. 构建后端（跳过测试，加快速度）
mvn -DskipTests clean package

# 2. 构建前端
cd frontend
npm run build
cd ..
```

**怎么判断成功**：

```powershell
# 后端 jar 应存在，大小约 50MB
Get-ChildItem target\ticket-hub-*.jar

# 前端 dist 应有 index.html 和 assets 目录
Get-ChildItem frontend\dist
```

如果 `mvn` 命令找不到，用 IDEA 自带的 Maven：

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.1.1.1\plugins\maven\lib\maven3\bin\mvn.cmd' -DskipTests clean package
```

### 1.4 本地需要安装的 SSH 工具

Windows 10/11 自带 OpenSSH，直接可用。验证：

```powershell
ssh -V
# 预期输出类似：OpenSSH_for_Windows_9.5p1
```

如果没有，在「设置 → 应用 → 可选功能」里安装「OpenSSH 客户端」。

---

## 第 2 章 手动部署教程

> 这一章是「你自己动手」的完整流程，一共 8 步。
> 每一步都说明：**做什么 → 为什么 → 怎么判断成功**。
> 如果你想让我代劳，直接跳到第 3 章。

### 2.1 第 1 步：SSH 登录服务器

**做什么**：从本地电脑连接到 ECS。

**Windows PowerShell 命令**（用密钥）：

```powershell
ssh -i "D:\你的路径\你的密钥.pem" ubuntu@<ECS公网IP>
```

**用密码**（如果创建实例时设了密码）：

```powershell
ssh ubuntu@<ECS公网IP>
```

**为什么用 `ubuntu` 用户**：Ubuntu 官方镜像默认禁用 root 远程登录，
只允许 `ubuntu` 用户通过密钥登录，该用户有 `sudo` 权限。

**常见报错**：

| 报错 | 原因 | 解决 |
|---|---|---|
| `Permission denied (publickey)` | 密钥文件不对，或用户名错了 | 确认用户名是 `ubuntu`（不是 `root`） |
| `WARNING: UNPROTECTED PRIVATE KEY FILE` | Windows 上密钥权限过宽 | 忽略警告，或复制到 `%USERPROFILE%\.ssh\` 下 |
| `Connection timed out` | 安全组没放行 22 端口 | 回 1.2 检查安全组 |
| `REMOTE HOST IDENTIFICATION HAS CHANGED` | 服务器重装过，本地缓存的指纹过期 | `ssh-keygen -R <IP>` 清除后重连 |

**登录成功的标志**：提示符变成类似 `ubuntu@iZbp1xxxxx:~$`。

登录后先确认系统信息：

```bash
lsb_release -a       # 看 Ubuntu 版本
free -h              # 看内存
df -h /              # 看磁盘剩余
```

### 2.2 第 2 步：创建 swap（已有则跳过）

**做什么**：确认机器有 swap；没有才创建。

> ⚠️ **先检查再动手。** 如果服务器上已经跑着别的服务（比如海豹骰娘），
> 它可能已经建过 `/swapfile`。此时再执行 `fallocate` + `mkswap` 会失败，
> 甚至干扰正在使用的交换区。正确做法是先跑下面这一条：

```bash
# 检查是否已有 swap。有输出（形如 /swapfile ... 4G）就说明已经存在，
# 直接跳到第 3 步，后面的命令一条都不要执行。
swapon --show
free -m | grep -i swap
```

**只有在上面两条都没有任何输出时**，才执行下面的创建流程：

```bash
# 创建 2G 的 swap 文件
sudo fallocate -l 2G /swapfile \
  || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=progress

# 权限设为 600（只有 root 可读写，swap 文件必须这样）
sudo chmod 600 /swapfile

# 格式化为 swap
sudo mkswap /swapfile

# 启用
sudo swapon /swapfile

# 写入 fstab，保证重启后自动挂载（先判断，避免重复写入）
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 降低 swap 使用倾向（尽量用内存，swap 只兜底）
sudo sysctl vm.swappiness=10
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf
```

**为什么**：见 0.4 节。没有 swap 时，内存峰值会直接触发 OOM Killer 杀进程。

**怎么判断成功**：

```bash
free -h
# Swap 一行不应是 0B。已有的机器会显示 4.0Gi 之类，属正常。
swapon --show
# 应能看到 /swapfile 及其大小
```

### 2.3 第 3 步：安装 Docker 与 nginx

**做什么**：装容器运行时和 Web 服务器。

```bash
# 更新软件源索引
sudo apt update && sudo apt upgrade -y

# 用官方脚本安装 Docker（含 compose 插件）
curl -fsSL https://get.docker.com | sudo sh

# 把当前用户加入 docker 组（之后不用每条命令都 sudo docker）
sudo usermod -aG docker $USER

# 让当前 shell 立即生效（否则要重新登录）
newgrp docker

# 安装 nginx
sudo apt install -y nginx
```

**为什么用官方脚本装 Docker**：Ubuntu 自带源的 `docker.io` 版本较旧，
且可能不含 compose 插件。官方脚本会装最新稳定版并自动配置 systemd 服务。

**怎么判断成功**：

```bash
docker --version          # 预期：Docker version 2x.x.x
docker compose version    # 预期：Docker Compose version v2.x.x
docker info | head -5     # 能输出信息说明守护进程在跑
nginx -v                  # 预期：nginx version: nginx/1.18.0 或 1.24.0
```

**如果 `docker info` 报错 `Cannot connect to the Docker daemon`**：

```bash
sudo systemctl start docker
sudo systemctl enable docker
```

**注意**：如果 `newgrp docker` 后仍然提示权限不足，直接**退出 SSH 重新登录**即可。

### 2.4 第 4 步：上传项目文件

**做什么**：把本地构建好的 jar、前端 dist、以及部署文件传到服务器。

先在**本地 PowerShell** 里创建目标目录并上传：

```powershell
# 定义服务器地址（把 <IP> 和密钥路径换成你的）
$IP = "<ECS公网IP>"
$KEY = "D:\你的路径\你的密钥.pem"

# 在服务器上创建目录结构
ssh -i $KEY ubuntu@$IP "sudo mkdir -p /opt/tickethub && sudo chown -R ubuntu:ubuntu /opt/tickethub"

# 上传项目文件（排除本地开发产物，减小传输量）
scp -i $KEY -r `
  docker-compose.yml `
  Dockerfile.runtime `
  deploy `
  src `
  pom.xml `
  ubuntu@$IP`:/opt/tickethub/

# 上传后端 jar
scp -i $KEY -r target ubuntu@$IP:/opt/tickethub/

# 上传前端构建产物
scp -i $KEY -r frontend/dist ubuntu@$IP:/opt/tickethub/frontend/
```

**为什么要传 `src`**：compose 里把 `src/main/resources/db/tickethub.sql` 挂载给 MySQL
做首次初始化，所以这个文件必须在服务器上。整个 `src` 只有几百 KB，一起传最省事。

**为什么传 `pom.xml`**：`Dockerfile.runtime` 不需要它，但保留它便于你在服务器上
用附录 A 的方式重新构建，也方便排查「代码版本对不对」。

**怎么判断成功**：

```powershell
ssh -i $KEY ubuntu@$IP "ls -la /opt/tickethub/ && ls -la /opt/tickethub/frontend/dist/ && ls -la /opt/tickethub/target/*.jar"
```

应该看到 `docker-compose.yml`、`deploy/`、`frontend/dist/index.html`、`target/ticket-hub-*.jar`。

> **提示**：如果服务器上没有 `scp` 命令，说明没装 OpenSSH 客户端，见 1.4。

### 2.5 第 5 步：配置环境变量

**做什么**：创建 `.env` 文件，填写密码和 OSS 配置。

> **重要**：这个文件必须放在**项目根目录** `/opt/tickethub/.env`，
> 不能放在 `deploy/` 子目录。原因是 `docker compose` 只会自动读取
> **compose 文件所在目录**下的 `.env`；放错位置会导致
> `${MYSQL_PASSWORD:?请在 .env 中设置...}` 直接报错，容器起不来。

```bash
cd /opt/tickethub

# 从模板复制到项目根目录（注意目标路径）
cp deploy/.env.example .env

# 设置权限为 600（只有 owner 能读写，防止密码泄露）
chmod 600 .env

# 编辑（nano 更简单：Ctrl+O 保存，Ctrl+X 退出）
nano .env
```

**必须修改的项**（把 `change_me_*` 换成真实强密码）：

```ini
MYSQL_ROOT_PASSWORD=你的MySQL_root密码
MYSQL_PASSWORD=你的应用数据库密码
RABBITMQ_PASSWORD=你的MQ密码
```

**为什么密码要随机且强**：虽然这些端口不对公网开放，
但如果将来配置出错导致端口暴露，强密码是最后一道防线。
用下面的命令生成随机密码：

```bash
tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24; echo
```

**怎么判断成功**：

```bash
# 检查是否还有 change_me 没改
grep change_me .env
# 预期：没有任何输出

# 确认权限
ls -l .env
# 预期：-rw------- 1 ubuntu ubuntu ...
```

### 2.6 第 6 步：启动容器

**做什么**：构建应用镜像并启动全部 4 个容器。

```bash
cd /opt/tickethub

# 创建上传目录（用户上传的图片存这里）
mkdir -p uploads

# 构建并后台启动
docker compose up -d --build

# 查看启动进度
docker compose ps
```

**首次启动会比较慢**（3~8 分钟），因为要：
1. 拉取 `mysql:8.0`、`redis:7`、`rabbitmq:3.12-management`、`eclipse-temurin:8-jre` 四个镜像（约 600MB）；
2. 构建 app 镜像（只 COPY 一个 jar，几秒钟）；
3. MySQL 首次启动会执行 `tickethub.sql` 建表 + 灌演示数据（约 30 秒）。

**为什么 `-d`**：detach，让容器在后台运行，不占用你的终端。

**怎么判断成功**：

```bash
docker compose ps
```

预期看到 4 个容器，状态如下：

| 容器 | 状态 |
|---|---|
| tickethub-mysql | `Up ... (healthy)` ← 必须是 healthy |
| tickethub-redis | `Up ...` |
| tickethub-rabbitmq | `Up ...` |
| tickethub-app | `Up ...` |

**app 容器会反复重启直到 MySQL healthy**，这是正常的（`depends_on` 条件），
等 1~2 分钟再看即可。

**查看后端日志**：

```bash
docker compose logs -f app
# Ctrl+C 退出（不会停止容器）
```

看到下面这几行说明启动成功：

```
Started TicketHubApplication in x.x seconds
秒杀库存预热完成，本次写入 13 个票档
```

### 2.7 第 7 步：配置 nginx

**做什么**：把项目里的 nginx 配置装到系统，并删掉 Ubuntu 默认站点。

```bash
# 复制配置文件
sudo cp /opt/tickethub/deploy/nginx/tickethub.conf /etc/nginx/conf.d/

# 删除 Ubuntu 默认站点（它也监听 80 且是 default_server，会冲突）
sudo rm -f /etc/nginx/sites-enabled/default

# 检查语法（这一步很重要，配置写错 nginx 会启动失败）
sudo nginx -t

# 重新加载配置（不中断已有连接）
sudo systemctl reload nginx
sudo systemctl enable nginx
```

**为什么必须删默认站点**：Ubuntu 的 `/etc/nginx/sites-enabled/default` 里
有 `listen 80 default_server;`，我们的配置里也有 `listen 80 default_server;`，
**同一个端口不允许两个 default_server**，`nginx -t` 会报
`duplicate default server for 0.0.0.0:80` 直接失败。

**`nginx -t` 预期输出**：

```
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

**怎么判断成功**：

```bash
curl -I http://127.0.0.1/
# 预期：HTTP/1.1 200 OK

curl http://127.0.0.1/api/show-category/list
# 预期：返回 JSON，里面有 "success":true
```

### 2.8 第 8 步：服务器防火墙（可选）

**做什么**：开启 Ubuntu 自带的 `ufw` 防火墙。

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

**注意**：`ufw enable` 前**一定要先放行 22**，否则会把自己锁在服务器外面。

**为什么说「可选」**：阿里云安全组已经是一层防火墙，`ufw` 是第二层。
两层都配更安全，但配错了会连不上服务器。如果不熟悉，
可以只依赖安全组，先不启用 `ufw`。

---

## 第 3 章 自动部署教程（我能替你做到的）

### 3.1 分工：哪些我能做，哪些必须你做

| 工作项 | 谁做 | 说明 |
|---|---|---|
| 阿里云安全组放行 22/80 | **你** | 需要登录阿里云控制台，我无法操作你的云账号 |
| 提供 SSH 登录信息 | **你** | 公网 IP + 用户名 + 密钥路径或密码 |
| 明确授权我登录服务器 | **你** | 必须你点头，我才会连 |
| 提供 OSS 参数 | **你** | 需要时从阿里云控制台复制 |
| 本地构建 jar 与 dist | 我 | 已在本机执行过，随时可重跑 |
| 上传文件到服务器 | 我 | 用 scp |
| 加 swap | 我 | `deploy.sh` 第 2 步 |
| 装 Docker / nginx | 我 | `deploy.sh` 第 3 步 |
| 生成 .env 与随机密码 | 我 | `deploy.sh` 第 4 步 |
| 启动容器 | 我 | `docker compose up -d` |
| 配置 nginx | 我 | `deploy.sh` 第 6 步 |
| 健康检查与排错 | 我 | `deploy.sh` 第 8 步 + 看日志 |
| OSS 控制台配置（建桶/开公共读/RAM 授权） | **你** | 网页操作，我无法代做 |

### 3.2 你需要给我的信息（3 项）

```
1. ECS 公网 IP：__________
2. SSH 用户名：ubuntu（默认）
3. 登录方式：
   - 密钥：本地密钥文件完整路径，例如 D:\develop\dice\maoDice.pem
   - 或密码：__________
```

**安全提醒**：
- 我只用这些信息做部署，不会写进任何提交到 GitHub 的文件；
- `.env` 已在 `.gitignore` 里，密码不会被提交；
- 部署完成后你可以随时在阿里云控制台**更换密钥对或改密码**；
- 如果你更放心，可以先用「生成脚本 + 你自己跑」的方式（见 3.4）。

### 3.3 我会执行的完整命令序列

下面是「我登录后实际会做的事」，你可以逐条核对，也可以自己照做。

**第 1 步：本地构建**（在你这台 Windows 上）

```powershell
cd D:\develop\git\CityHub
mvn -DskipTests clean package
cd frontend
npm run build
cd ..
```

**第 2 步：上传项目到服务器**

```powershell
$IP = "<你的IP>"
$KEY = "<你的密钥路径>"

ssh -i $KEY ubuntu@$IP "sudo mkdir -p /opt/tickethub && sudo chown -R ubuntu:ubuntu /opt/tickethub"

scp -i $KEY -r docker-compose.yml Dockerfile.runtime deploy src pom.xml ubuntu@$IP:/opt/tickethub/
scp -i $KEY -r target ubuntu@$IP:/opt/tickethub/
scp -i $KEY -r frontend/dist ubuntu@$IP:/opt/tickethub/frontend/
```

**第 3 步：执行自动部署脚本**

```powershell
ssh -i $KEY ubuntu@$IP "sudo bash /opt/tickethub/deploy/deploy.sh"
```

脚本内部会依次做 8 件事（详见 3.5），全程打印带颜色的中文日志。

**第 4 步：验证**

```powershell
ssh -i $KEY ubuntu@$IP "docker compose -f /opt/tickethub/docker-compose.yml ps"

# 从本地直接测（验证安全组是否放行）
curl.exe -I "http://$IP/"
curl.exe "http://$IP/api/show-category/list"
```

### 3.4 备选：你自己跑脚本（不给我凭证）

如果你不想给 SSH 凭证，用这个方式，效果完全一样：

```powershell
# 1. 本地构建
cd D:\develop\git\CityHub
mvn -DskipTests clean package
cd frontend; npm run build; cd ..

# 2. 上传（把 <IP>、<KEY> 换成你的）
$IP = "<ECS公网IP>"; $KEY = "<密钥路径>"
ssh -i $KEY ubuntu@$IP "sudo mkdir -p /opt/tickethub && sudo chown -R ubuntu:ubuntu /opt/tickethub"
scp -i $KEY -r docker-compose.yml Dockerfile.runtime deploy src pom.xml ubuntu@$IP:/opt/tickethub/
scp -i $KEY -r target ubuntu@$IP:/opt/tickethub/
scp -i $KEY -r frontend/dist ubuntu@$IP:/opt/tickethub/frontend/

# 3. 服务器上执行一键脚本
ssh -i $KEY ubuntu@$IP
sudo bash /opt/tickethub/deploy/deploy.sh
```

### 3.5 `deploy/deploy.sh` 做了什么（逐段说明）

| 步骤 | 动作 | 幂等性说明 |
|---|---|---|
| 1/8 环境检查 | 确认 root、Ubuntu、内存、磁盘 ≥5GB、项目文件存在 | 纯检查，无副作用 |
| 2/8 swap | 内存 <3GB 时创建 2G swap 并写入 fstab | 已有 swap 则跳过；写 fstab 前先 grep 判断 |
| 3/8 安装 | 装 Docker、compose 插件、nginx，并启动服务 | 已安装则只做版本校验，不重装 |
| 4/8 生成 .env | 首次运行生成随机密码；**已存在则完全不覆盖** | 关键：重跑不会改掉你的密码 |
| 5/8 前端 | 检查 dist 与 jar 是否存在，创建 uploads 目录 | 只检查与建目录 |
| 6/8 nginx | 备份旧配置 → 复制新配置 → 删默认站点 → `nginx -t` → reload | 备份带时间戳，可回滚 |
| 7/8 启动 | `docker compose up -d --build --remove-orphans` | 只重建有变化的容器，数据卷不动 |
| 8/8 健康检查 | 轮询 `/` 与 `/api/show-category/list`，最多等 180 秒 | 失败则打印容器状态+日志+内存，退出码 1 |

**脚本的安全设计**：

- `set -Eeuo pipefail`：任何命令失败立即中止，不会带着错误继续往下跑；
- `trap` 捕获失败并打印行号，方便定位；
- **绝不执行 `docker compose down -v`**，不会删除数据库数据；
- 改 nginx 配置前先备份，`nginx -t` 失败则中止，不会把 nginx 搞挂。

### 3.6 失败时我会怎么处理

| 失败点 | 现象 | 处理 |
|---|---|---|
| 安全组没放行 | 本地 curl 超时，但服务器内 curl 正常 | 让你去控制台放行，我不需要改服务器 |
| Docker 安装失败 | `get.docker.com` 拉不下来 | 改用阿里云镜像源安装，或换 `apt install docker.io` |
| 镜像拉取慢/失败 | `docker compose up` 卡在 Pulling | 配置阿里云容器镜像加速器后重试 |
| MySQL 未 healthy | app 容器反复重启 | 看 `docker compose logs mysql`，常见是磁盘不足或密码含特殊字符 |
| 后端启动失败 | 日志有 `Communications link failure` / `Access denied` | 核对 `.env` 密码与容器网络 |
| nginx 配置错误 | `nginx -t` 报错 | 脚本已中止并保留备份，修正后重跑 |
| 健康检查超时 | 打印诊断信息 | 按诊断日志定位，修好后**重跑脚本即可**（幂等） |

**回滚方式**（万一要恢复到部署前）：

```bash
# 恢复 nginx 配置
sudo ls /etc/nginx/conf.d/tickethub.conf.bak.*
sudo cp /etc/nginx/conf.d/tickethub.conf.bak.<时间戳> /etc/nginx/conf.d/tickethub.conf
sudo nginx -t && sudo systemctl reload nginx

# 停止容器但保留数据
cd /opt/tickethub && docker compose down
```

---

## 第 4 章 上线验证

### 4.1 容器状态

```bash
cd /opt/tickethub
docker compose ps
```

**预期**：4 个容器全部 `Up`，mysql 显示 `(healthy)`。

如果 app 显示 `Restarting`，看日志：

```bash
docker compose logs --tail=100 app
```

### 4.2 服务探活

```bash
# nginx 本身（只说明 nginx 活着）
curl -s http://127.0.0.1/health
# 预期：ok

# 前端页面
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1/
# 预期：200

# 后端 API（走 nginx 反代）
curl -s http://127.0.0.1/api/show-category/list
# 预期：{"success":true,"data":[...6个分类...]}
```

### 4.3 从外网验证（最重要）

在**你自己电脑**的 PowerShell 里：

```powershell
$IP = "<ECS公网IP>"

curl.exe -I "http://$IP/"
curl.exe "http://$IP/api/show-category/list"
```

**预期**：第一条返回 `HTTP/1.1 200 OK`，第二条返回 JSON 数据。

**如果超时**：安全组没放行 80，回 1.2。
**如果 502 Bad Gateway**：nginx 通了但后端没起来，看 `docker compose logs app`。
**如果 404**：前端 dist 没传对位置，检查 `/opt/tickethub/frontend/dist/index.html`。

### 4.4 完整业务链路验证（答辩演示流程）

**方式一：浏览器操作**（推荐，答辩就用这个）

1. 打开 `http://<IP>/`
2. 首页应显示 6 个演出分类和演出列表（图片是本地生成的演示图）
3. 点任一演出 → 详情页显示票档与余票
4. 右上角登录 → 输入手机号 `13800000001` → **验证码会直接显示在页面上** → 登录
5. 选票档 → 抢票 → 提示抢票成功并返回订单号
6. 进「我的订单」→ 看到刚抢到的订单 → 点支付
7. 个人中心 → 签到 → 连续签到天数 +1

**方式二：命令行验证**（适合快速确认后端）

```bash
BASE=http://127.0.0.1

# 1. 发验证码（演示环境直接返回验证码）
CODE=$(curl -s -X POST "$BASE/api/user/code?phone=13800000001" | sed 's/.*"data":"\([0-9]*\)".*/\1/')
echo "验证码: $CODE"

# 2. 登录拿 token
TOKEN=$(curl -s -X POST "$BASE/api/user/login" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"13800000001\",\"code\":\"$CODE\"}" \
  | sed 's/.*"data":"\([^"]*\)".*/\1/')
echo "token: $TOKEN"

# 3. 查看票档
curl -s "$BASE/api/tier/list/1"

# 4. 抢票（tierId=1）
curl -s -X POST "$BASE/api/ticket-order/seckill/1" -H "authorization: $TOKEN"

# 5. 等 2 秒（MQ 异步落库），查看订单
sleep 2
curl -s "$BASE/api/ticket-order/my" -H "authorization: $TOKEN"
```

**预期**：第 4 步返回 `{"success":true,"data":<订单号>}`，
第 5 步的订单列表里能看到这笔订单。

> **如果第 5 步查不到订单**：说明 MQ 消费有问题，执行
> `docker compose logs --tail=50 app | grep -i -E "rabbit|queue|error"` 排查。

### 4.5 验证 RabbitMQ 队列

**做什么**：确认抢票消息真的进了队列、被消费。

**推荐方式：命令行查队列**（最快，不用开端口）

```bash
cd /opt/tickethub
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

**预期输出**（大致如下）：

```
Timeout: 60.0 seconds ...
Listing queues for vhost / ...
name    messages        consumers
QD      0               0
QA      0               1
```

- `QA`：正常出票队列，`consumers` 应该是 **1**（后端消费者已连上）；
  `messages` 为 0 表示消息已被及时消费。
- `QD`：死信兜底队列，正常情况下没有消息。

**抢票时观察消息流动**（可选，很直观）：

```bash
# 开一个终端持续观察
watch -n 1 'docker compose exec -T rabbitmq rabbitmqctl list_queues name messages consumers'
```

在另一个终端触发抢票，会看到 `QA` 的 `messages` 短暂 +1 后立刻回到 0，
说明「投递 → 消费 → 落库」整条链路是通的。

**看交换机与绑定关系**：

```bash
docker compose exec rabbitmq rabbitmqctl list_exchanges name type | grep -E '^X|^Y'
docker compose exec rabbitmq rabbitmqctl list_bindings | grep -E 'QA|QD'
```

**如果你确实想要 Web 管理页**：

`rabbitmq:3.12-management` 镜像已自带管理插件，但 compose 里**故意没有**
把 15672 映射到宿主机（避免暴露到公网）。临时查看的做法：

```bash
# 在服务器上临时映射端口（仅监听回环地址，外网仍访问不到）
docker compose -f /opt/tickethub/docker-compose.yml stop rabbitmq
docker run -d --name mq-admin --network tickethub \
  -p 127.0.0.1:15672:15672 rabbitmq:3.12-management

# 本地另开一个终端建立 SSH 隧道
# ssh -i <密钥> -L 15672:127.0.0.1:15672 ubuntu@<IP>
# 然后浏览器打开 http://localhost:15672

# 用完清理
docker rm -f mq-admin
```

> 日常运维用 `rabbitmqctl` 就够了，不必折腾管理页。

### 4.6 验证 nginx 限流生效

抢票接口限速是 10 次/秒、突发 20，连续快速请求应该出现 429：

```bash
for i in $(seq 1 60); do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://127.0.0.1/api/ticket-order/seckill/1
done
echo
```

**预期**：前面若干次是 `200` 或 `401`（未带 token 会被拦截器拦），
后面出现 `429`。看到 429 就说明限流生效。

> 注意：这个测试会消耗票档 1 的库存（`seckill.lua` 在限流之前已经扣减），
> 建议在演示**之前**做，或测试完重新初始化数据库：
> ```bash
> cd /opt/tickethub
> docker compose down -v && docker compose up -d
> ```
> （`-v` 会删除数据卷，所有订单和库存重置为初始状态）

---

## 第 5 章 OSS 图片配置

> **这一章是可选的**。不配 OSS 时，上传的图片会存到服务器
> `/opt/tickethub/uploads/`，由 nginx 的 `/imgs/` 直接托管，功能完全正常。
> 配 OSS 的好处是：图片不占服务器磁盘和带宽，更接近生产架构。

### 5.1 为什么用香港桶

- 香港地域的 OSS **不需要 ICP 备案**，可以立即使用；
- OSS 默认域名访问图片（`.jpg`/`.png`）不受「强制下载」限制，可以直接展示；
- 但 **HTML 文件**会被强制下载，所以前端仍由 nginx 托管（见 0.2）。

### 5.2 创建 Bucket（你操作）

1. 阿里云控制台 → 对象存储 OSS → Bucket 列表 → 创建 Bucket
2. **地域**：选择「中国香港」
3. **Bucket 名称**：例如 `tickethub-images`（全局唯一，小写字母数字和短横线）
4. **读写权限**：选择「**公共读**」（否则前端无法直接显示图片）
   - 更安全的做法是「私有」+ 签名 URL，但需要改代码，本课程项目用公共读即可
5. 其他保持默认，创建

### 5.3 创建 RAM 子账号（你操作，强烈建议）

**不要用主账号 AccessKey**，一旦泄露等于整个阿里云账号失守。

1. 阿里云控制台 → RAM 访问控制 → 用户 → 创建用户
2. 勾选「OpenAPI 调用访问」，创建后**立刻保存 AccessKey ID 和 Secret**
3. 给这个用户授权：选择「自定义策略」，内容如下（只允许操作这一个 bucket）：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject", "oss:GetObject", "oss:DeleteObject"],
      "Resource": ["acs:oss:*:*:tickethub-images/*"]
    }
  ]
}
```

把 `tickethub-images` 换成你的 bucket 名。

### 5.4 在服务器上启用 OSS

```bash
cd /opt/tickethub
nano .env
```

修改这几行：

```ini
OSS_ENABLED=true
OSS_ENDPOINT=oss-cn-hongkong.aliyuncs.com
OSS_BUCKET=tickethub-images
OSS_ACCESS_KEY_ID=你的AccessKeyID
OSS_ACCESS_KEY_SECRET=你的AccessKeySecret
OSS_URL_PREFIX=https://tickethub-images.oss-cn-hongkong.aliyuncs.com
```

重启应用让配置生效：

```bash
docker compose up -d app
docker compose logs --tail=20 app | grep OSS
# 预期：OSS 已启用: bucket=tickethub-images
```

### 5.5 验证上传

```bash
# 造一个 1x1 的 PNG 测试文件
printf '\x89PNG\r\n\x1a\n' > /tmp/test.png

# 调上传接口
curl -s -X POST http://127.0.0.1/api/upload/blog -F "file=@/tmp/test.png"
```

**预期**：返回 `{"success":true,"data":"https://tickethub-images.oss-cn-hongkong.aliyuncs.com/posts/xxx.png"}`。

把返回的 URL 粘贴到浏览器，能显示图片即成功。

**常见问题**：

| 现象 | 原因 | 解决 |
|---|---|---|
| 返回 `文件上传失败` | AccessKey 错误或权限不足 | 检查 RAM 策略里的 bucket 名是否正确 |
| URL 能打开但 403 | bucket 权限不是「公共读」 | 控制台改成公共读 |
| 返回的是 `/imgs/posts/xxx.png` | `OSS_ENABLED` 还是 false | 检查 `.env` 并 `docker compose up -d app` |

---

## 第 6 章 日常运维与故障排查

### 6.1 常用命令速查

```bash
cd /opt/tickethub

# 查看容器状态
docker compose ps

# 查看所有日志（实时）
docker compose logs -f

# 只看后端日志
docker compose logs -f app

# 只看 MySQL 日志
docker compose logs --tail=100 mysql

# 重启后端（改配置后）
docker compose up -d app

# 重启全部
docker compose restart

# 停止全部（保留数据）
docker compose down

# 停止并删除数据（重置为初始状态，慎用！）
docker compose down -v

# 查看资源占用（内存/CPU）
docker stats --no-stream

# 查看磁盘占用
df -h /
docker system df
```

### 6.2 更新代码后的发布流程

```powershell
# 本地：重新构建
cd D:\develop\git\CityHub
mvn -DskipTests clean package
cd frontend; npm run build; cd ..

# 本地：上传新产物
$IP = "<IP>"; $KEY = "<密钥路径>"
scp -i $KEY target/ticket-hub-*.jar ubuntu@$IP:/opt/tickethub/target/
scp -i $KEY -r frontend/dist/* ubuntu@$IP:/opt/tickethub/frontend/dist/
scp -i $KEY deploy/nginx/tickethub.conf ubuntu@$IP:/tmp/

# 服务器：重建并重启
ssh -i $KEY ubuntu@$IP
cd /opt/tickethub
docker compose up -d --build app
sudo cp /tmp/tickethub.conf /etc/nginx/conf.d/ && sudo nginx -t && sudo systemctl reload nginx
```

> 前端更新后如果浏览器还显示旧页面，是浏览器缓存。用 `Ctrl+Shift+R` 强制刷新。

### 6.3 数据备份与恢复

**备份 MySQL**（建议定期做，答辩前一定做一次）：

```bash
cd /opt/tickethub

# 导出到宿主机
docker compose exec -T mysql mysqldump -uroot -p"$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2)" \
  --single-transaction --routines tickethub > ~/tickethub-backup-$(date +%Y%m%d).sql

# 确认文件大小
ls -lh ~/tickethub-backup-*.sql
```

**恢复**：

```bash
docker compose exec -T mysql mysql -uroot -p"$(grep '^MYSQL_ROOT_PASSWORD=' .env | cut -d= -f2)" \
  tickethub < ~/tickethub-backup-20261016.sql
```

**备份上传的图片**：

```bash
tar czf ~/tickethub-uploads-$(date +%Y%m%d).tar.gz -C /opt/tickethub uploads
```

### 6.4 常见故障对照表

| 现象 | 可能原因 | 排查命令 | 解决 |
|---|---|---|---|
| 浏览器打不开，超时 | 安全组没放行 80 | 服务器上 `curl -I http://127.0.0.1/` | 若服务器内正常，去控制台放行 80 |
| 502 Bad Gateway | 后端容器没起来 | `docker compose ps`、`logs app` | 看日志定位；常见是数据库连不上 |
| 404 Not Found | 前端文件没传到位置 | `ls /opt/tickethub/frontend/dist/index.html` | 重新上传 dist |
| 页面能开但接口 404 | nginx rewrite 或后端路由问题 | `curl http://127.0.0.1/api/show-category/list` | 检查 nginx 配置里的 rewrite |
| 图片全是空白 | dist 里没有 imgs 目录 | `ls /opt/tickethub/frontend/dist/imgs/` | 重新 `npm run build`（图片在 `frontend/public/imgs/`） |
| 容器反复重启，日志有 `Killed` | 内存不足被 OOM Killer 杀 | `free -h`、`dmesg \| tail -20` | 确认 swap 已建；确认 `mem_limit` 生效 |
| 登录提示「验证码不一致」 | 验证码 2 分钟过期 | — | 重新获取验证码 |
| 抢票提示「库存未预热」 | Redis 里库存 key 被淘汰 | `docker compose exec redis redis-cli GET seckill:stock:1` | 重启 app 触发预热：`docker compose restart app` |
| 抢票成功但订单列表没有 | MQ 消费失败 | `logs app \| grep -i rabbit` | 检查 rabbitmq 容器状态 |
| `docker compose` 报权限错误 | 用户不在 docker 组 | `groups` | `sudo usermod -aG docker $USER` 后重新登录 |
| `no space left on device` | 磁盘满 | `df -h`、`docker system df` | `docker system prune -a` 清理无用镜像 |

### 6.5 内存监控

2G 机器的日常监控：

```bash
# 实时看内存
free -h

# 看各容器内存占用
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}"

# 看是否发生过 OOM
dmesg | grep -i -E "killed process|out of memory" | tail -10
```

**判断标准**：

- `free -h` 的 `available` 长期低于 100M → 需要优化或升配；
- `Swap` 使用量持续增长 → 物理内存确实不够，考虑升到 4G；
- `docker stats` 里某个容器接近其 `mem_limit` → 该服务需要调参。

### 6.6 磁盘清理

```bash
# 查看 Docker 占用
docker system df

# 清理未使用的镜像、容器、网络（不影响运行中的容器和数据卷）
docker system prune -a -f

# 清理旧的 nginx 日志（保留最近 7 天）
sudo find /var/log/nginx -name "*.log" -mtime +7 -delete
```

> **注意**：`docker system prune` 不会删除数据卷（volumes），
> 所以 MySQL 数据是安全的。但不要加 `--volumes` 参数。

---

## 第 7 章 项目目录与文件职责

### 7.1 部署相关目录树

```
CityHub/                                  ← 项目根目录
│
├── docker-compose.yml                    ★ 核心：4 个服务的编排定义
│                                            （app / mysql / redis / rabbitmq）
├── Dockerfile                              完整构建版（服务器内 mvn 编译，慢，备用）
├── Dockerfile.runtime                    ★ 服务器实际使用：只 COPY 本地构建好的 jar
├── .dockerignore                           构建上下文过滤（排除 node_modules/.git 等）
├── .gitignore                              忽略 target/、node_modules/、.env 等
├── pom.xml                                 Maven 依赖与构建配置
├── README.md                               项目总览与快速开始
│
├── deploy/                                 ★ 部署资产目录
│   ├── deploy.sh                         ★ 一键部署脚本（幂等，可重复执行）
│   ├── .env.example                        环境变量模板（提交到仓库，不含真实密码）
│   ├── .env                                真实密码（gitignore，**不提交**）
│   └── nginx/
│       └── tickethub.conf                ★ nginx 站点配置
│                                            静态托管 + API 反代 + 限流 + gzip
│
├── docs/
│   ├── PROJECT_GUIDE.md                    项目讲解、核心链路、技术点核对、风险清单
│   └── DEPLOY.md                           本文档
│
├── frontend/                               前端工程（Vue 3 + Vite + Element Plus）
│   ├── .env.production                     ★ 生产环境 API 基址（/api，同源）
│   ├── package.json                        npm 依赖与脚本
│   ├── vite.config.js                      开发服务器配置（含本地 /api 代理）
│   ├── public/
│   │   └── imgs/                          ★ 演示图片（演出海报/分类图标/头像）
│   │                                         构建时原样复制到 dist/imgs/
│   ├── src/                                源码（views / api / router / components）
│   └── dist/                               构建产物（gitignore，部署时上传）
│       ├── index.html
│       ├── assets/                         带 hash 的 JS/CSS
│       └── imgs/                           演示图片
│
├── src/main/                               后端源码
│   ├── java/com/tickethub/
│   │   ├── config/
│   │   │   ├── SeckillStockPreloader.java  启动时把票档库存预热进 Redis
│   │   │   ├── MvcConfig.java              拦截器注册 + CORS
│   │   │   ├── QueueConfig.java            RabbitMQ 队列拓扑（QA/QD）
│   │   │   └── ...
│   │   ├── controller/                     REST 接口
│   │   ├── service/impl/                   业务逻辑
│   │   ├── listener/TicketOrderListener.java  抢票消息消费者（异步出票）
│   │   └── utils/
│   │       ├── OssUtil.java                图片上传（OSS 或本地降级）
│   │       └── ...
│   └── resources/
│       ├── application.yaml              ★ 配置（全部支持环境变量覆盖）
│       ├── seckill.lua                   ★ 抢票 Lua 脚本（原子校验库存与一人一单）
│       └── db/tickethub.sql              ★ 建库建表 + 演示数据（MySQL 首次启动执行）
│
└── target/                                 Maven 构建产物（gitignore）
    └── ticket-hub-0.0.1-SNAPSHOT.jar     ★ 部署时上传的 jar
```

带 ★ 的是部署时必须理解的 9 个文件。

### 7.2 服务器上的目录结构

```
/opt/tickethub/                           ← 项目部署目录
├── docker-compose.yml
├── Dockerfile.runtime
├── pom.xml
├── deploy/
│   ├── deploy.sh
│   ├── .env                              ★ 真实密码（chmod 600）
│   └── nginx/tickethub.conf
├── src/main/resources/db/tickethub.sql    MySQL 初始化脚本（挂载给容器）
├── frontend/dist/                        ★ nginx 托管的静态文件
│   ├── index.html
│   ├── assets/
│   └── imgs/
├── target/ticket-hub-*.jar               ★ app 镜像的来源
└── uploads/                              ★ 用户上传的图片（持久化）
    └── posts/

/etc/nginx/conf.d/tickethub.conf          nginx 站点配置（从 deploy/ 复制过来）

Docker 数据卷（由 Docker 管理，不在 /opt/tickethub 下）：
  mysql-data       MySQL 数据文件
  redis-data       Redis AOF 持久化文件
  rabbitmq-data    RabbitMQ 消息与队列定义
```

### 7.3 关键文件的作用与修改场景

| 文件 | 作用 | 什么情况下改 |
|---|---|---|
| `docker-compose.yml` | 定义 4 个容器、网络、数据卷、环境变量 | 加服务、调内存限制、改端口 |
| `Dockerfile.runtime` | 把本地 jar 打成运行镜像 | 改 JVM 参数、换基础镜像 |
| `.env`（项目根目录） | 注入密码、OSS 配置 | 改密码、启用 OSS |
| `deploy/nginx/tickethub.conf` | 静态托管 + 反代 + 限流 | 改限流阈值、加域名、上 HTTPS |
| `frontend/.env.production` | 前端请求的 API 地址 | 前后端分离部署时改成完整 URL |
| `application.yaml` | 后端配置默认值 | 加新配置项（一律用 `${ENV:默认值}` 形式） |
| `tickethub.sql` | 建表与演示数据 | 改表结构、改演示数据（改后需 `down -v` 重新初始化） |

### 7.4 环境变量是怎么从 `.env` 流到 Java 代码的

这是理解部署的关键链路，务必搞清楚：

```
1. /opt/tickethub/.env 里的 MYSQL_PASSWORD=abc123
        ↓  docker compose 自动读取同目录（或 --env-file 指定）的 .env
2. docker-compose.yml 里写：MYSQL_PASSWORD: ${MYSQL_PASSWORD}
        ↓  Compose 把变量插值后写进容器的环境变量
3. 容器内进程的环境变量 MYSQL_PASSWORD=abc123
        ↓  Spring Boot 读取 application.yaml
4. application.yaml 里写：password: ${MYSQL_PASSWORD:root}
        ↓  Spring 的占位符解析：优先用环境变量，没有才用默认值 root
5. Java 代码拿到最终值
```

**为什么要绕这么一圈**：密码不进代码、不进镜像、不进 Git，
只存在于服务器的 `.env` 文件里。这样代码可以公开托管在 GitHub 上。

---

## 附录 A：服务器内构建（备选方案）

**适用场景**：你没有本地 Java 环境，或想让服务器直接从 Git 拉代码构建。

**风险提示**：2核2G 的机器上构建 Maven 项目**很可能 OOM**，
如果一定要用，先建好 2G swap，并在构建时停掉其他容器：

```bash
cd /opt/tickethub

# 1. 先停掉占内存的服务，腾出空间
docker compose stop app mysql redis rabbitmq

# 2. 装 Maven 和 JDK
sudo apt install -y maven openjdk-8-jdk

# 3. 构建（这一步最吃内存）
mvn -DskipTests clean package

# 4. 用完整版 Dockerfile 构建镜像（镜像内含编译过程，更慢）
#    或者继续用 Dockerfile.runtime（推荐，因为 jar 已经构建好了）
docker compose up -d --build
```

**国内下载依赖慢**，建议配置阿里云 Maven 镜像：

```bash
mkdir -p ~/.m2
cat > ~/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF
```

---

## 附录 B：升级到域名 + HTTPS + OSS/CDN

等你买了域名（并完成 ICP 备案），可以按下面步骤升级到生产级架构。

### B.1 目标架构

```
用户浏览器
    │
    ├── https://www.yourdomain.com   → OSS + CDN（静态资源）
    └── https://api.yourdomain.com   → ECS nginx（API 网关）
```

### B.2 步骤

**1. 前端改回独立域名构建**

```bash
# 修改 frontend/.env.production
VITE_API_BASE=https://api.yourdomain.com/api
```

重新 `npm run build`，把 `dist/` 上传到 OSS，开启静态网站托管
（默认首页 `index.html`，默认 404 页也填 `index.html`）。

**2. nginx 配置改为 HTTPS**

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.yourdomain.com
sudo certbot renew --dry-run
```

把 `tickethub.conf` 里的 `server_name _;` 改成 `server_name api.yourdomain.com;`，
并把 `listen 80 default_server;` 改成 `listen 80;`，再执行
`sudo nginx -t && sudo systemctl reload nginx`。

**3. OSS 绑定 CDN 域名**

在 OSS 控制台绑定 `www.yourdomain.com`，在 DNS 处按提示添加 CNAME；
CDN 控制台绑定证书，开启 HTTPS。

**4. 后端 CORS 收敛**

`MvcConfig.java` 里的 `allowedOrigins("*")` 改成具体域名：

```java
registry.addMapping("/**")
        .allowedOrigins("https://www.yourdomain.com")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .maxAge(3600);
```

### B.3 为什么要升级

| 维度 | 当前方案 | 升级后 |
|---|---|---|
| 访问方式 | `http://IP` | `https://域名` |
| 静态资源 | ECS 带宽（几 Mbps） | CDN 边缘节点，首屏快 |
| 安全性 | HTTP 明文 | HTTPS 加密 |
| 面试观感 | 够用 | 接近生产标准 |

---

## 附：一键部署速查（TL;DR）

**本地（Windows PowerShell）**：

```powershell
cd D:\develop\git\CityHub
mvn -DskipTests clean package
cd frontend; npm run build; cd ..

$IP = "<ECS公网IP>"; $KEY = "<密钥路径>"
ssh -i $KEY ubuntu@$IP "sudo mkdir -p /opt/tickethub && sudo chown -R ubuntu:ubuntu /opt/tickethub"
scp -i $KEY -r docker-compose.yml Dockerfile.runtime deploy src pom.xml ubuntu@$IP:/opt/tickethub/
scp -i $KEY -r target ubuntu@$IP:/opt/tickethub/
scp -i $KEY -r frontend/dist ubuntu@$IP:/opt/tickethub/frontend/
ssh -i $KEY ubuntu@$IP "sudo bash /opt/tickethub/deploy/deploy.sh"
```

**验证**：

```powershell
curl.exe -I "http://$IP/"
curl.exe "http://$IP/api/show-category/list"
```

**忘记 MySQL 密码**：

```bash
ssh -i $KEY ubuntu@$IP "sudo grep MYSQL_ROOT_PASSWORD /opt/tickethub/.env"
```
