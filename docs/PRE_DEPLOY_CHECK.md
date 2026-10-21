# TicketHub 部署前体检清单（海豹骰娘同机部署）

> 目标机器：阿里云 ECS `8.148.238.219`（广州 / Ubuntu / 2核2G / 40G 系统盘）
> 现状：该机已运行 **2 套海豹骰 + 2 个 NapCat**（共 4 个容器，开机 8 天以上）
> 体检日期：2026-10-21
> 关联文档：[DEPLOY.md](./DEPLOY.md)（部署步骤）、[PROJECT_GUIDE.md](./PROJECT_GUIDE.md)（代码讲解）

---

## 〇、一句话结论

**这台机器现在不能直接部署 TicketHub。**

不是端口问题，也不是 Docker 问题——这两项都很干净。**卡在内存**：
当前可用 912MB，TicketHub 稳态需要 1200–1500MB，缺口约 300–600MB。
硬上不会立刻报错，但会在抢票演示的瞬间被 OOM Killer 杀掉 MySQL 或后端进程，
现场表现为「页面能开、点抢票就 502」——这是答辩最不能接受的一种失败。

在动手之前，**必须先完成本清单第 1 节的 P0 三项**，并做出「扩容 / 腾机器 / 换机器」的选择。

---

## 一、实测数据快照（2026-10-21 09:22 采集）

以下全部为真机执行结果，不是估算：

| 项目 | 实测值 | 判定 |
|---|---|---|
| 操作系统 | Linux 5.15.0-191-generic x86_64（Ubuntu 22.04/24.04 内核） | ✅ |
| CPU | 2 核，load average 0.04 / 0.05 / 0.01 | ✅ 空闲 |
| 物理内存 | 总 1736MB（2G 机型实际可用值） | — |
| 已用内存 | 622MB | — |
| **可用内存** | **912MB** | ❌ **不足** |
| Swap | 4GB，**已用 727MB** | ⚠️ 说明长期存在内存压力 |
| vm.swappiness | 20 | ✅ 合理 |
| 磁盘 | 40G，已用 13G，剩 26G（33%） | ✅ 充足 |
| inode | 使用率 6%，剩 246 万 | ✅ |
| Docker | 29.9.0 | ✅ |
| Docker Compose | v5.6.0 | ✅ |
| cgroup | v2 | ✅ |
| nginx | **未安装** | ✅ 无冲突 |
| 80 端口 | 空闲 | ✅ |
| 8081 / 3306 / 6379 / 5672 | 全部空闲 | ✅ |
| 3211 / 3212 / 6099 / 6100 | 被骰娘占用 | — 与 TicketHub 不冲突 |
| ufw | inactive | ⚠️ 全靠安全组 |
| OOM 历史 | 内核日志中无记录 | ✅ |
| Java | 未安装 | ✅ 不需要（跑在容器里） |

### 现有 4 个容器的实际占用

| 容器 | 内存占用 | cgroup 上限 |
|---|---|---|
| sealdice（骰娘 1） | 68.9 MiB | 512 MiB |
| napcat（QQ 端 1） | 191.4 MiB | 768 MiB |
| sealdice-2（骰娘 2） | 70.5 MiB | 512 MiB |
| napcat-2（QQ 端 2） | 274.8 MiB | 768 MiB |
| **合计** | **约 606 MiB** | **2560 MiB** |

> 注意最后一列：现有容器的内存**上限之和 2560MB 已经超过物理内存 1736MB**。
> 也就是说这台机器从配置上就已经在超卖，平时靠 swap 和「实际用不满」维持。

### 外网端口探测（从本地 Windows 发起）

| 端口 | 结果 | 推断 |
|---|---|---|
| 22 | 通 | 安全组放行，SSH 正常 |
| 80 | 快速拒绝（RST） | **安全组已放行**，只是当前没有服务监听 |
| 8081 | 超时（DROP） | 安全组**未放行**，符合预期（后端不该暴露公网） |

> 结论：**80 端口不需要再去控制台加规则**，装好 nginx 就能直接被访问。
> 这一点很多教程会让人白折腾一轮。

---

## 二、为什么放不下：内存账

TicketHub 是 4 个容器，`docker-compose.yml` 里已经做了内存限制：

| 容器 | cgroup 上限 | 稳态实际占用（估算） | 说明 |
|---|---|---|---|
| app（Spring Boot） | 800m | 650–750MB | JVM `-Xmx512m` + Metaspace 160m + 堆外 |
| mysql | 500m | 350–450MB | buffer pool 128M，已关 performance_schema |
| redis | 256m | 30–60MB | maxmemory 128mb，实际很小 |
| rabbitmq | 400m | 200–300MB | Erlang VM，已设 watermark 256MB |
| **合计** | **1956m** | **1230–1560MB** | |

再加上系统本身（dockerd、systemd、SSH、日志）约 250MB。

```
当前可用                     912 MB
TicketHub 需要            -1500 MB
──────────────────────────────────
缺口                        -588 MB

如果停掉第 2 套骰娘（sealdice-2 + napcat-2）
可释放                      +345 MB
──────────────────────────────────
仍然缺口                    -243 MB   ← 还是不够稳
```

**关键判断**：不是「能不能启动」，而是「演示时会不会卡」。
MySQL 的 buffer pool 一旦被换到 swap，一次订单查询从 2ms 变成 200ms 以上；
抢票接口是 Redis + Lua 打头，但落库要靠 MySQL，演示时点下去转圈 3 秒就废了。

---

## 三、P0：必须先在阿里云控制台完成（我做不了，只能你点）

这三项是**外部状态**，服务器内部命令查不到，必须人工确认。

### P0-1　确认实例规格与计费方式

- 位置：ECS 控制台 → 实例 → `iZ7xv4h5p7wvjx4i6qlewyZ` → 基本信息
- 要看：**实例规格**（是否为 `ecs.e-c1m1.large` 这类 2核2G）、**付费类型**（包年包月 / 按量）
- 为什么：决定后面能不能「升配」。包年包月支持变配补差价（需停机 3–5 分钟）；按量付费可以直接改规格。
- 记录：规格型号 = ＿＿＿＿，到期时间 = ＿＿＿＿

### P0-2　确认安全组规则（重点看有没有「全端口 0.0.0.0/0」）

- 位置：ECS 控制台 → 安全组 → 入方向
- 历史资料显示该机安全组 `sg-7xvi1a1vr6qt517civ5f` 曾有一条 `0.0.0.0/0` 全端口规则
- 风险：等于把 3306 / 6379 / 5672 / 3211 / 6099 全部暴露在公网。Redis 未设密码时可在几小时内被挖矿程序写入
- 要做：
  1. 删除「全部端口 / 0.0.0.0/0」这条
  2. 只保留：`22`（建议限制为你的家庭/学校出口 IP）、`80`（0.0.0.0/0）
  3. 骰娘要继续用的话，保留 `3211`、`3212`、`6099`、`6100`，但建议也限制来源 IP
- 判定：改完后 `http://8.148.238.219:3211` 仍能打开（说明没误伤），`telnet 8.148.238.219 6379` 应超时

### P0-3　决定内存怎么解决（三选一，见第五节）

不解决这一项，后面的测试做完也没意义。

---

## 四、P1：服务器侧体检（可重复执行）

我已经把下面所有检查写成了一个脚本：

```
D:\develop\git\CityHub\deploy\preflight.sh
```

**在服务器上执行**：

```bash
bash /opt/tickethub/deploy/preflight.sh
```

**在本地 Windows 直接远程执行**（不用登录进去）：

```powershell
ssh -i D:\develop\dice\maoDice.pem root@8.148.238.219 "bash -s" < D:\develop\git\CityHub\deploy\preflight.sh
```

退出码含义：`0` 全通过 / `1` 有阻断项别部署 / `2` 可部署但有风险。

下面是脚本里每一项**为什么测、怎么判、不过怎么办**，方便你脱离脚本也能手工查。

### 4.1　内存余量（唯一的一票否决项）

```bash
free -m
```

看 **`available` 那一列**，不是 `free`。

| available | 判定 | 处置 |
|---|---|---|
| ≥ 1500MB | ✅ 直接部署 | — |
| 1050–1500MB | ⚠️ 可部署但会抖 | 演示前关掉不用的容器，接受延迟波动 |
| < 1050MB | ❌ 禁止部署 | 必须先扩容或换机器 |

**当前值 912MB → ❌**

### 4.2　Swap 是否够、是否已经被吃满

```bash
free -m | grep Swap
swapon --show
cat /proc/sys/vm/swappiness
```

| 检查点 | 合格线 | 当前 |
|---|---|---|
| swap 总量 | ≥ 2048MB | 4095MB ✅ |
| swap 已用 | < 总量 50% | 727MB / 4095MB = 18% ✅ |
| swappiness | 10–20 | 20 ✅ |

> 本机已有 4G swap，**不需要再执行 DEPLOY.md 第 2.2 节建 swap**。
> 这一点要特别注意：重复执行 `mkswap` 会覆盖已有 swap 文件，虽然不致命但会打断骰娘。

### 4.3　磁盘与 inode

```bash
df -h /
df -i /
docker system df
```

| 检查点 | 合格线 | 当前 |
|---|---|---|
| 根分区剩余 | ≥ 10GB | 26GB ✅ |
| inode 使用率 | < 80% | 6% ✅ |
| Docker 可回收空间 | < 5GB | 需要跑 `docker system df` 确认 |

如果 `docker system df` 里 `RECLAIMABLE` 很大，部署前先清：

```bash
docker image prune -a -f     # 删掉没被任何容器使用的镜像
docker builder prune -f      # 删掉构建缓存
```

### 4.4　五个端口是否被占

```bash
ss -lntp | grep -E ':(80|8081|3306|6379|5672)\b'
```

**预期：没有任何输出。**

当前实测全部空闲 ✅。如果以后有输出，处理方式：

| 端口 | 常见占用者 | 处理 |
|---|---|---|
| 80 | 系统自带 apache2 / 旧 nginx | `systemctl stop apache2 && systemctl disable apache2` |
| 3306 | 宿主机装过 MySQL | 停掉宿主 MySQL，TicketHub 用容器内的 |
| 6379 | 宿主机装过 Redis | 同上 |
| 5672 | 宿主机装过 RabbitMQ | 同上 |

### 4.5　Docker 与 Compose 是否可用

```bash
docker --version
docker compose version
docker info --format '{{.CgroupVersion}} {{.MemTotal}} {{.NCPU}}'
```

当前：Docker 29.9.0 / Compose v5.6.0 / cgroup v2 / 2 核 ✅

**不需要重装 Docker。** 很多人照着教程会再跑一遍 `curl get.docker.com | sh`，
在这台机器上没必要，而且有版本回退风险。

### 4.6　nginx 是否已装

```bash
nginx -v 2>&1 || echo "NO_NGINX"
ss -lntp | grep ':80'
```

当前：**未安装，80 空闲** ✅ → 按 DEPLOY.md 第 2.3 节安装即可，不会与任何东西冲突。

### 4.7　现有服务的稳定性基线（部署前后对比用）

部署前先存一份快照，出问题时才能证明「不是我搞坏的」：

```bash
mkdir -p /root/preflight-$(date +%F)
docker ps --format '{{.Names}}|{{.Status}}|{{.Image}}' > /root/preflight-$(date +%F)/docker-ps.txt
docker stats --no-stream > /root/preflight-$(date +%F)/docker-stats.txt
free -m > /root/preflight-$(date +%F)/free.txt
ss -lntp > /root/preflight-$(date +%F)/ports.txt
```

部署完成后再跑一遍，`diff` 两个目录，就能看出：
- 有没有容器意外重启（Status 里的 Up 时长归零）
- 内存涨了多少
- 有没有多监听端口

### 4.8　骰娘数据备份（部署前的强制项）

```bash
bash /opt/dice/backup-dice.sh
ls -lh /opt/dice-backup/
```

如果脚本不存在：

```bash
tar czf /opt/dice-backup/manual-$(date +%F-%H%M).tar.gz -C /opt dice dice-2
```

**判定：备份文件存在且大小合理（几十 MB 以上）。**

理由：部署过程会装 Docker 镜像、改 nginx、动内存参数。虽然不动 `/opt/dice`，
但一旦 OOM Killer 触发，它可能随机杀进程——包括 NapCat。有备份才敢动手。

### 4.9　内核有没有 OOM 前科

```bash
dmesg -T | grep -iE 'out of memory|oom-kill|killed process' | tail -20
```

当前：**无输出** ✅（说明这 8 天没被打爆过，2 套骰娘的资源规划是合理的）

如果有输出，说明这台机器已经在临界点，叠加部署必然出事。

### 4.10　防火墙状态

```bash
ufw status
```

当前：`inactive`。

**建议保持关闭**，只靠阿里云安全组。理由：
- 两层防火墙容易漏配，最常见的事故是「安全组开了，ufw 没开，怎么都连不上」
- 本机只对公网开 22 和 80，安全组足够
- 如果坚持开 ufw，**必须先 `ufw allow 22` 再 `ufw enable`**，否则 SSH 会把自己锁在外面

### 4.11　时间同步

```bash
timedatectl | grep -E 'Time zone|synchronized'
```

**要求：`System clock synchronized: yes`，时区 `Asia/Shanghai`。**

为什么重要：JWT token 有过期时间、RabbitMQ 消息有 TTL、MySQL 有 `CURRENT_TIMESTAMP`。
时钟漂移几分钟就会出现「刚登录就 401」「订单时间对不上」这类玄学问题。

### 4.12　是否能拉到镜像

这是最容易被忽略、又最致命的一项。阿里云国内机器拉 Docker Hub 经常超时。

```bash
docker pull hello-world && docker run --rm hello-world
docker pull mysql:8.0        # 约 600MB，最慢的一个
docker pull redis:7
docker pull rabbitmq:3.12-management
```

| 结果 | 处置 |
|---|---|
| 全部成功 | ✅ 可以继续 |
| 超时 / `TLS handshake timeout` | 配置阿里云镜像加速器（控制台 → 容器镜像服务 → 镜像加速器，拿到专属地址写进 `/etc/docker/daemon.json`） |
| 提示 `no space left on device` | 回 4.3 清磁盘 |

> 这一步必须在**部署前**做。等 `docker compose up` 时才发现拉不下来，
> 会看到一堆容器处于 `Created` 状态，排查起来很浪费时间。

---

## 五、P2：应用侧体检（在本地 Windows 做）

服务器只是运行环境，产物本身有问题照样白搭。这四项在**上传之前**做完。

### 5.1　后端能打包

```powershell
cd D:\develop\git\CityHub
.\mvnw.cmd -DskipTests clean package
```

**预期**：`target/` 下出现 `*.jar`，大小约 40–60MB。

**失败处置**：先 `.\mvnw.cmd -DskipTests package` 单独跑，看是不是测试依赖缺失；
如果是网络问题，检查 `~/.m2/settings.xml` 的阿里云镜像。

### 5.2　前端能构建

```powershell
cd D:\develop\git\CityHub\frontend
npm ci
npm run build
```

**预期**：`frontend/dist/index.html` 存在，`dist/assets/` 下有 js/css。

**关键检查**：`frontend/.env.production` 里的 `VITE_API_BASE` 必须是 `/api`（同源）。
如果还是 `https://api.example.com/api`，部署后前端所有请求都会打到一个不存在的域名。

### 5.3　jar 能独立启动（冒烟测试）

不要等上了服务器才发现配置读不到。本地先起一次：

```powershell
java -jar target\*.jar --spring.profiles.active=dev
```

**预期**：日志出现 `Started ... in x seconds`，访问 `http://localhost:8081/api/show-category/list` 有 JSON 返回。

> 需要本地有 MySQL/Redis/RabbitMQ。没有的话跳过这一项，
> 但至少确认 `java -jar` 不报「找不到主类」「jar 损坏」。

### 5.4　数据库脚本能被 MySQL 8 接受

`src/main/resources/db/tickethub.sql` 会在 MySQL 容器**首次启动**时自动执行。

**检查点**：
- 文件编码是 UTF-8 无 BOM（有 BOM 时 MySQL 会在第一行报语法错误）
- 没有 `CREATE DATABASE`（compose 已经通过 `MYSQL_DATABASE` 建好了）
- 图片字段指向的 `/imgs/xxx.jpg` 在 `frontend/public/imgs/` 里真实存在

---

## 六、三条处置路线（P0-3 的答案）

### 路线 A：ECS 升配到 2核4G（推荐）

| 项目 | 内容 |
|---|---|
| 操作 | ECS 控制台 → 实例 → 更多 → 实例状态 → 停止；变配 → 2核4G；启动 |
| 停机 | 3–5 分钟（骰娘会掉线，QQ 端需重新连接，通常自动重连） |
| 费用 | 按差价补，广州地域 2核2G→2核4G 大约每月多几十元（以控制台报价为准） |
| 结果 | 可用内存约 3.2GB，TicketHub 1.5GB + 骰娘 0.6GB，余量充足 |
| 风险 | 包年包月实例只能升不能降；按量付费可随时改回 |

**升配后必须重跑 `preflight.sh`，确认 available ≥ 2500MB。**

### 路线 B：迁走第 2 套骰娘

| 项目 | 内容 |
|---|---|
| 操作 | 把 `sealdice-2` + `napcat-2` 迁到另一台机器（或直接停掉不再用） |
| 释放 | 约 345MB |
| 结果 | available ≈ 1257MB，**仍然低于 1500MB 建议线**，只能算「勉强能跑」 |
| 适用 | 第 2 套本来就没人用、且你接受演示时可能卡一下 |
| 风险 | 内存依然紧张，MySQL 有较大概率被换出 |

**结论：单独用这条路不够，必须配合「降低 TicketHub 资源占用」一起做**（见下方「压缩方案」）。

### 路线 C：另买一台轻量应用服务器

| 项目 | 内容 |
|---|---|
| 操作 | 阿里云轻量应用服务器 2核4G，学生优惠或新用户首年通常 100 元以内 |
| 优点 | 与骰娘完全隔离，故障域分开；骰娘继续跑，答辩机器独立 |
| 缺点 | 多一台机器要维护，多一个公网 IP |
| 适用 | 骰娘是长期服务、不能接受停机升配 |

### 附：压缩方案（三条路线都可叠加）

如果暂时不想花钱，可以把 TicketHub 的内存需求压到 950MB 左右：

| 改动 | 释放 | 代价 |
|---|---|---|
| JVM `-Xmx512m` → `-Xmx320m` | 约 200MB | 并发稍差，演示够用 |
| MySQL `buffer_pool 128M` → `64M` | 约 80MB | 查询变慢，但演示数据量小 |
| 去掉 RabbitMQ，下单改同步落库 | 约 250MB | **要改代码**，违背「不改核心业务」的原则 |
| Redis `maxmemory 128mb` → `64mb` | 约 30MB | 缓存命中率下降 |

前三项里只有前两项是纯配置改动。**我建议不要为了省几十块钱去改业务代码**，
答辩时被问到「为什么下单是同步的」反而不好解释。

---

## 七、部署当天的执行顺序

```
① 备份骰娘数据            bash /opt/dice/backup-dice.sh
② 存稳定性快照            见 4.7
③ 跑体检                  bash deploy/preflight.sh   → 必须 exit 0 或 2
④ 预拉镜像                见 4.12
⑤ 本地构建 jar + dist      见 5.1 / 5.2
⑥ 上传产物                scp
⑦ docker compose up -d
⑧ 装 nginx 配置            nginx -t && systemctl reload nginx
⑨ 外网验证                curl http://8.148.238.219/api/show-category/list
⑩ 业务链路验证            验证码登录 → 抢票 → 查订单
⑪ 压力观察                docker stats 连续看 5 分钟，内存是否稳定
```

第 ⑪ 步是很多人会跳过的：**部署完不算完，要看着它跑 5 分钟**。
如果 `docker stats` 里 app 的内存一路上涨不回落，说明 JVM 堆设小了或者有泄漏，
演示到一半就会 OOM。

---

## 八、回滚预案

一旦出问题，按这个顺序退，**每一步都能独立止血**：

| 级别 | 动作 | 命令 | 影响 |
|---|---|---|---|
| L1 | 停 TicketHub，保留数据 | `docker compose stop` | 网站打不开，骰娘不受影响 |
| L2 | 停 TicketHub 并删容器 | `docker compose down` | 数据卷保留，可随时重启 |
| L3 | 卸载 nginx | `systemctl stop nginx && systemctl disable nginx` | 80 端口释放 |
| L4 | 恢复骰娘快照 | `docker compose -f /opt/dice/docker-compose.yml up -d` | 骰娘恢复 |
| L5 | 内存告急 | `sync && echo 3 > /proc/sys/vm/drop_caches` | 释放 page cache，应急用 |

> **永远不要**在没备份的情况下执行 `docker compose down -v`。
> 那个 `-v` 会把 MySQL、Redis、RabbitMQ 的数据卷一起删掉，订单数据直接没了。

---

## 九、目录与文件职责（本轮新增）

```
D:\develop\git\CityHub\
├── docs\
│   ├── DEPLOY.md              ← 部署步骤（架构原理 + 手动/自动两套教程）
│   ├── PRE_DEPLOY_CHECK.md    ← 本文件：部署前体检清单
│   └── PROJECT_GUIDE.md       ← 代码讲解（秒杀链路 / 缓存 / Lua / MQ）
├── deploy\
│   ├── preflight.sh           ← 新增：只读体检脚本，可重复执行
│   ├── deploy.sh              ← 一键部署脚本（幂等）
│   ├── .env.example           ← 环境变量模板
│   └── nginx\
│       └── tickethub.conf     ← nginx 站点配置（静态托管 + /api 反代 + 限流）
├── docker-compose.yml         ← 4 容器编排 + 内存限制
├── Dockerfile.runtime         ← 只 COPY jar 的轻量运行镜像
└── frontend\
    ├── .env.production        ← VITE_API_BASE=/api（同源，不能改）
    └── public\imgs\           ← 演示数据引用的海报占位图
```

### 每个文件在什么情况下要改

| 文件 | 什么时候改 |
|---|---|
| `preflight.sh` | 基本不用改；换机器时直接复制过去跑 |
| `deploy.sh` | 改了端口、目录、域名时才动 |
| `docker-compose.yml` | 调整内存上限、加服务、换镜像版本时 |
| `tickethub.conf` | 加域名 / 上 HTTPS / 改限流阈值时 |
| `.env`（服务器上） | 每次换密码、开 OSS 时 |

---

## 十、体检结果记录表（打印出来逐项打勾）

| # | 项目 | 合格线 | 实测 | 通过 |
|---|---|---|---|---|
| 1 | 可用内存 | ≥ 1500MB | 912MB | ❌ |
| 2 | swap 总量 | ≥ 2048MB | 4095MB | ✅ |
| 3 | swap 已用 | < 50% | 18% | ✅ |
| 4 | 根分区剩余 | ≥ 10GB | 26GB | ✅ |
| 5 | inode | < 80% | 6% | ✅ |
| 6 | 80 端口 | 空闲 | 空闲 | ✅ |
| 7 | 8081/3306/6379/5672 | 空闲 | 空闲 | ✅ |
| 8 | Docker | 可连接 | 29.9.0 | ✅ |
| 9 | Compose | v2 插件 | 5.6.0 | ✅ |
| 10 | nginx | 未装或已停 | 未装 | ✅ |
| 11 | OOM 历史 | 无 | 无 | ✅ |
| 12 | 骰娘备份 | 有近期备份 | 待执行 | ⬜ |
| 13 | 镜像可拉取 | 全部成功 | 待验证 | ⬜ |
| 14 | 后端 jar | 构建成功 | 待验证 | ⬜ |
| 15 | 前端 dist | 构建成功 | 待验证 | ⬜ |

**13 / 14 / 15 我可以直接替你跑完并填表；12 需要在服务器上执行一次备份。**
