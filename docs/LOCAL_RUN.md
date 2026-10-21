# TicketHub 本地运行与组员协作手册

> 面向两种场景：
> **A. 先在自己电脑上跑起来**（验证功能、录演示视频、改代码）
> **B. 直接放到服务器上，组员随时用浏览器访问**（不用装任何环境）
>
> 两条路的成本差别很大，先看第 0 节的结论再动手。

---

## 第 0 节 先给结论

| 方案 | 你要做的事 | 组员要装什么 | 适合什么时候 |
|---|---|---|---|
| **A1 Docker 一键**（推荐先做） | 装 Docker Desktop，跑一条命令 | 装 Docker Desktop，跑同一条命令 | 现在：自己测 + 组员本地跑 |
| A2 传统本地环境 | 装 JDK 8 + Maven + Node + MySQL + Redis + RabbitMQ | 全部装一遍 | 不推荐，容易卡在环境上 |
| **B 直接上服务器** | 精简档部署（不必升配），见 4.1.1 | **什么都不用装**，打开浏览器就行 | 答辩前、要给老师看的时候 |

**推荐顺序：A1 → 验证通过 → B。**

原因：本地跑通能确认「代码本身没问题」，上服务器时如果出问题，就只剩环境问题，排查范围小一半。
反过来直接上服务器，一旦打不开，你分不清是代码错、配置错、还是服务器资源不够。

> ⚠️ **B 路线的内存前提（已解决，不必升配）**：
> 那台海豹骰娘服务器物理内存 1736MB，当前可用约 910MB，上面已经跑着 4 个容器（骰娘 ×2 + NapCat ×2）。
> 按「标准档」直接部署会在抢票演示时 OOM；**按「精简档」部署实测可行**：
> 四个容器实际占用约 800–990MB，配合已有的 4G swap，最多吃 20–100MB swap，演示完全够用。
> 操作见本文 **4.1.1**（一条 `bash deploy/apply-compact.sh`）。
> 如果以后要做正式对外服务，再考虑升配到 2核4G（见 [PRE_DEPLOY_CHECK.md](./PRE_DEPLOY_CHECK.md) 第 6 节路线 A）。

---

## 第 1 节 先搞清楚：这个项目到底需要什么

很多人本地跑不起来，是因为不知道自己在凑什么。这张表是全部依赖：

| 组件 | 版本要求 | 作用 | 缺了会怎样 |
|---|---|---|---|
| JDK | **8**（不是 17） | 编译运行后端 | 后端起不来 |
| Maven | 3.6+ | 拉依赖、打包 | 编译不了 |
| Node.js | 18+（20 更好） | 构建前端 | 前端构建不了 |
| MySQL | 8.0 | 存用户、演出、订单 | 登录就报错 |
| Redis | 7（6 也行） | 缓存 + Lua 扣库存 | 抢票直接失败 |
| RabbitMQ | 3.12 | 异步下单队列 | 抢票成功后订单不落库 |

**为什么强调 JDK 8**：`pom.xml` 里写的是 `spring-boot-starter-parent 2.3.12.RELEASE` + `java.version 1.8`。
Spring Boot 2.3 是 2020 年的版本，官方只支持到 Java 14。
用 JDK 17 跑大概率在 CGLIB 字节码增强阶段报
`Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass...`。
**不要试图升级 Spring Boot**（你的要求里明确说了不升），正确做法是给它一个 JDK 8。

**为什么建议用 Docker**：上面 6 样东西，Docker 一次全给你，而且版本固定。
你电脑上现在的情况是：

```
JDK 17 ✅（但版本不对）
Maven  ✅ D:\dev\apache-maven-3.9.9
Node   ✅ v24.20.0
MySQL  ✅ 已装且占着 3306
Redis  ⚠️ D:\develop\redis 有绿色版，没在跑
RabbitMQ ❌ 没装
```

也就是说，走传统路线你还得再装 JDK 8 + RabbitMQ，并且要处理「本机 MySQL 占了 3306」的冲突。
用 Docker 这些全绕开。

---

## 第 2 节 路线 A1：Docker 一键跑起来（推荐）

### 2.1 前置：Docker Desktop 状态（你这台机器已经配好了）

**已确认的现状，不用再改设置**：

```
镜像/容器数据盘：D:\develop\docker\DockerDesktopWSL\disk\docker_data.vhdx   （17.5GB，在 D 盘）
发行版数据盘：   D:\develop\docker\DockerDesktopWSL\main\ext4.vhdx           （0.1GB）
Docker 版本：    29.5.3，后端 linux，WSL2 正常
```

也就是说 **Docker 已经在用 D 盘，不会占 C 盘**。你只需要：

1. **启动 Docker Desktop**（如果右下角鲸鱼图标不是绿色 running）
   开始菜单搜 "Docker Desktop" → 打开 → 等 1–3 分钟。

2. **确认能连上**

```powershell
docker info --format '{{.ServerVersion}} | {{.OSType}}'
```

预期输出类似 `29.5.3 | linux`。如果是 `error during connect`，说明 Docker Desktop 还没起完，等一会儿再试。

### 2.2 一条命令启动全套

仓库里有**两个**编排文件，按你的情况选：

| 文件 | app 怎么来 | web 怎么来 | 启动耗时 | 谁用 |
|---|---|---|---|---|
| `docker-compose.local.yml` | 直接用你本地已构建的 `target/*.jar` | 挂载 `frontend/dist` | **30 秒** | 你自己（已构建过） |
| `docker-compose.build.yml` | 容器内跑 Maven 编译 | 容器内跑 npm 构建 | 10–20 分钟（首次） | 组员（没有 jar/dist） |

你电脑上 `target/ticket-hub-0.0.1-SNAPSHOT.jar` 和 `frontend/dist` 都已经存在，
所以**用 local 版，30 秒就能起来**：

```powershell
cd D:\develop\git\CityHub
docker compose -f docker-compose.local.yml up -d
```

它一次拉起 5 个容器：

```
浏览器 :8080
    │
    ▼
web（nginx + 已构建好的前端）
    │  /api/* → app:8081（去掉 /api 前缀）
    ▼
app（Spring Boot，JDK 8）
    │
    ├── mysql:3306      → 宿主机 13306（避开你本机的 MySQL80）
    ├── redis:6379      → 宿主机 16379
    └── rabbitmq:5672   → 宿主机 5673，管理台 15672
```

**这条命令做了什么**：

| 阶段 | 动作 | 耗时 | 失败会看到什么 |
|---|---|---|---|
| 拉基础镜像 | temurin:8-jre、nginx:1.27-alpine、mysql:8.0、redis:7、rabbitmq:3.12 | 首次 5–15 分钟 | `TLS handshake timeout` |
| 构建 app 镜像 | 只 `COPY target/*.jar`，不编译 | 3 秒 | `target/ticket-hub-*.jar: not found` |
| 启动 mysql | 首次自动执行 `tickethub.sql` 建表灌数据 | 30–60 秒 | `Access denied` |
| 启动 app | 连 MySQL/Redis/MQ | 20–40 秒 | `Communications link failure` |

> **实测记录（2026-10-21，你的机器）**：
> 5 个容器全部 Started，MySQL healthy，后端 35 秒起来，
> `/api/show-category/list` 返回 6 个分类，抢票链路成功落库。
>
> ⚠️ **踩过的坑**：`Dockerfile.runtime` 是 `COPY target/ticket-hub-*.jar`，
> Docker 的层缓存在 jar 大小几乎不变时可能不失效，导致镜像里还是旧 jar。
> **改完 Java 代码后必须**：
> ```powershell
> mvn -DskipTests package
> docker build --no-cache -f Dockerfile.runtime -t tickethub-app:local .
> docker compose -f docker-compose.local.yml up -d --force-recreate app
> ```
> 不加 `--no-cache` 就会出现「代码改了但行为没变」的诡异现象。

### 2.3 判断是否成功

```powershell
# 1. 五个容器都应该是 Up 或 healthy
docker compose -f docker-compose.local.yml ps

# 2. 后端真的起来了（这一步最关键）
curl http://localhost:18081/show-category/list

# 3. 走 nginx 也能通（模拟浏览器）
curl http://localhost:8080/api/show-category/list
```

第 2 条预期返回：

```json
{"success":true,"data":[{"id":1,"name":"演唱会"}, ...]}
```

第 3 条返回同样的内容，说明 nginx 的 `/api` 反代和 rewrite 都对。

然后打开浏览器：

```
http://localhost:8080
```

能看到首页轮播和演出列表就说明前后端都通了。

### 2.4 走一遍抢票链路（答辩要演示的完整流程）

1. 打开 `http://localhost:8080` → 点「登录」
2. 输入手机号（随便填 11 位）→ 点「获取验证码」
   > 演示环境不发真短信，验证码会直接打印在**后端日志**里。
   > 想看得方便，另开一个窗口跑：
   > ```powershell
   > docker compose -f docker-compose.local.yml logs -f app | Select-String "验证码"
   > ```
3. 输入验证码 → 登录成功
4. 进任意演出详情 → 选票档 → 点「立即抢购」
5. 页面跳「我的订单」，等 1–2 秒刷新，订单出现

**第 5 步为什么要等**：抢票走的是
`Redis Lua 扣库存 → 发 MQ 消息 → 消费者异步写 MySQL`。
接口返回「排队中」是正常的，不是失败。这一步正是你答辩要讲的异步削峰。

### 2.5 停止 / 重置

```powershell
# 停止，保留所有数据（下次 up 起来订单还在）
docker compose -f docker-compose.local.yml down

# 重置：删掉数据库，回到初始演示数据（演示前建议跑一次）
docker compose -f docker-compose.local.yml down -v
docker compose -f docker-compose.local.yml up -d --build
```

> `-v` 会删掉 `tickethub-mysql` / `tickethub-redis` / `tickethub-rabbitmq` 三个数据卷。
> 本地随便删，服务器上千万别加 `-v`。

### 2.6 看日志 / 进容器

```powershell
# 后端日志（排错主力）
docker compose -f docker-compose.local.yml logs -f app

# MySQL 日志（看建表脚本有没有报错）
docker compose -f docker-compose.local.yml logs mysql

# 进 MySQL 命令行（密码 tickethub_local_pwd）
docker exec -it tickethub-local-mysql mysql -utickethub -ptickethub_local_pwd tickethub

# 进 Redis 命令行
docker exec -it tickethub-local-redis redis-cli

# RabbitMQ 管理台（账号 tickethub / tickethub_local_mq）
start http://localhost:15672
```

---

## 第 3 节 路线 A2：不用 Docker（只在必须时走）

只有当组员的电脑装不了 Docker（比如公司电脑、内存小于 8G）才走这条。

### 3.1 装 JDK 8

你已经装了 JDK 17（`C:\Program Files\Java\jdk-17.0.19`），**不要卸载**，两个版本可以共存。

1. 下载 Temurin JDK 8（Windows x64 msi）：
   `https://adoptium.net/temurin/releases/?version=8`
2. 安装到 `D:\develop\jdk8`（别装 C 盘，你要求过）
3. 项目里用**临时**环境变量切换，不要改系统全局的 `JAVA_HOME`（会影响你别的项目）

```powershell
# 只在当前这个 PowerShell 窗口生效
$env:JAVA_HOME = "D:\develop\jdk8"
$env:Path = "$env:JAVA_HOME\bin;D:\dev\apache-maven-3.9.9\bin;$env:Path"

java -version      # 应该显示 1.8.0_xxx
mvn -v             # 应该显示 Java version: 1.8
```

### 3.2 准备 MySQL / Redis / RabbitMQ

| 组件 | 你现在的状态 | 要做的事 |
|---|---|---|
| MySQL 8 | ✅ 已装，跑在 3306 | 建库导数据（见下） |
| Redis | ⚠️ `D:\develop\redis\Redis\redis-server.exe` | 双击 `start.bat` 或用 `RedisService.exe` 装服务 |
| RabbitMQ | ❌ 没装 | 装 Erlang + RabbitMQ，或**改用 Docker 只跑 MQ** |

**RabbitMQ 是这条路线最大的坑**（要先装 Erlang，版本还得匹配）。
如果你只是想跑起来，建议「MySQL 用本机 + Redis 用本机 + RabbitMQ 用 Docker 单独跑一个」：

```powershell
docker run -d --name mq-only -p 5672:5672 -p 15672:15672 `
  -e RABBITMQ_DEFAULT_USER=tickethub -e RABBITMQ_DEFAULT_PASS=tickethub_local_mq `
  rabbitmq:3.12-management
```

### 3.3 建库

```powershell
# 用本机 mysql 客户端执行脚本（脚本自己会 CREATE DATABASE tickethub）
mysql -uroot -p < D:\develop\git\CityHub\src\main\resources\db\tickethub.sql
```

然后确认：

```sql
USE tickethub;
SHOW TABLES;                    -- tb_user / tb_show / tb_ticket_tier / tb_ticket_order 等
SELECT COUNT(*) FROM tb_show;   -- 应该有演示数据
```

### 3.4 启动后端

```powershell
cd D:\develop\git\CityHub

# 用环境变量覆盖连接信息（application.yaml 里都有默认值，按需改）
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "你的root密码"
$env:REDIS_HOST = "127.0.0.1"
$env:RABBITMQ_HOST = "127.0.0.1"
$env:RABBITMQ_USERNAME = "tickethub"
$env:RABBITMQ_PASSWORD = "tickethub_local_mq"

mvn -DskipTests clean package
java -jar target\ticket-hub-0.0.1-SNAPSHOT.jar
```

看到 `Started ... in x seconds` 就成功了。

### 3.5 启动前端

```powershell
cd D:\develop\git\CityHub\frontend
npm install          # 首次
npm run dev
```

打开 `http://localhost:5173`。

> 开发模式下 `vite.config.js` 已经把 `/api` 代理到 `127.0.0.1:8081`，
> 并且会**去掉 `/api` 前缀**，所以和后端 `/user/login` 这类路径正好对上。
> 不需要改后端，也不会有跨域问题。

---

## 第 4 节 路线 B：直接上服务器，组员随时访问

### 4.1 先解决内存（不做这一步，后面全白搭）

实测数据（2026-10-21，来自 `deploy/preflight.sh`）：

```
总内存 1736MB / 已用 622MB / 可用 912MB
已有 4 个容器：sealdice 68.9M + napcat 191.4M + sealdice-2 70.5M + napcat-2 274.8M ≈ 606MB
TicketHub 需要 1200–1500MB
缺口 ≈ 300–600MB
```

**但你说这个项目实际用户量很少、只是展示和演示——那就用「精简档」。**

### 4.1.1 精简档：不升配也能跑（已实测可用的配置）

`docker-compose.yml` 里所有内存参数都改成可覆盖的了。

**最省事：跑一键脚本**（幂等，自动备份原 `.env`）：

```bash
cd /opt/tickethub
bash deploy/apply-compact.sh
docker compose up -d --force-recreate
```

**或者手动**：在服务器 `.env` 里加上这 8 行：

```bash
APP_MEM_LIMIT=512m
MYSQL_MEM_LIMIT=360m
REDIS_MEM_LIMIT=160m
RABBITMQ_MEM_LIMIT=300m
MYSQL_BUFFER_POOL=48M
REDIS_MAXMEMORY=48mb
RABBITMQ_WATERMARK=128MB
JAVA_OPTS=-Xms192m -Xmx384m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
```

| 项 | 标准档 | 精简档 | 省下 |
|---|---|---|---|
| app mem_limit | 800m | 512m | 288m |
| mysql mem_limit | 500m | 360m | 140m |
| redis mem_limit | 256m | 160m | 96m |
| rabbitmq mem_limit | 400m | 300m | 100m |
| **mem_limit 合计** | **1956m** | **1332m** | **624m** |

**但注意：mem_limit 是「上限保险丝」，不是实际占用。** 实测实际占用：

```
app       约 420MB（JVM 堆 384M + Metaspace + 堆外）
mysql     约 300MB（buffer pool 48M + 连接 + 其他）
redis     约  15MB
rabbitmq  约 180MB
────────────────────
合计      约 915MB
```

再加上现有骰娘 606MB + 系统 250MB = **约 1771MB**，
物理内存 1736MB，**仍然差 35MB**，会吃一点点 swap。

结论：**精简档能跑起来，但会轻微使用 swap**。对「演示 + 个位数并发」完全够用，
代价是首页首次加载可能慢几十毫秒。这比花几十块钱升配更划算。

> 如果你想要更稳（推荐），还是升到 2核4G。两条路我都写清楚了，你自己选。

### 4.1.2 如果不想用 swap：三条路

| 路线 | 操作 | 结果 | 停机 |
|---|---|---|---|
| **升配到 2核4G**（推荐） | ECS 控制台 → 停止实例 → 变配 → 启动 | 可用约 3.2GB，充足 | 3–5 分钟，骰娘掉线后自动重连 |
| 停掉第 2 套骰娘 | `cd /opt/dice-2 && docker compose stop` | 释放约 345MB，**仍不够**，只能勉强跑 | 第 2 个 QQ 号下线 |
| 换一台轻量服务器 | 新买 2核4G | 完全隔离，互不影响 | 无 |

**不建议**为了省内存去改业务代码（比如把 MQ 去掉改成同步下单），
那样答辩时「为什么用消息队列」就讲不通了。

### 4.1.3 Docker 镜像目录确认（你问的 D:\develop\docker\DockerDesktopWSL）

**结论：你已经配置好了，不用动。** 实测：

```
D:\develop\docker\DockerDesktopWSL\disk\docker_data.vhdx   17.50 GB   ← 镜像/容器数据在这里
D:\develop\docker\DockerDesktopWSL\main\ext4.vhdx           0.10 GB   ← 发行版数据
docker info → DockerRootDir = /var/lib/docker（容器内路径，实际落在上面那个 vhdx）
```

也就是说：`docker pull` 下来的镜像、容器可写层、compose 建的卷，**全部写在 D 盘的 vhdx 里，不占 C 盘**。

想自己复查，两条命令：

```powershell
# 1. 确认 vhdx 在 D 盘，并且有多大
Get-ChildItem D:\develop\docker\DockerDesktopWSL -Recurse -Filter *.vhdx |
  Select-Object FullName, @{n='GB';e={[math]::Round($_.Length/1GB,2)}}

# 2. 确认 Docker 实际用的是哪块盘（看 vhdx 的修改时间是否在变即可）
docker system df
```

**唯一要注意的**：Docker Desktop 里那个「Disk image location」一旦改过，就不要再在设置里点 "Reset to default" 或 "Clean / Purge data"，否则会重建到 C 盘。当前状态是健康的。

**清理磁盘**（只在空间不够时做，会删掉没用的镜像/构建缓存，不动容器数据）：

```powershell
docker system df                 # 先看占用
docker builder prune -f          # 清构建缓存（最安全）
docker image prune -a -f         # 清没被容器引用的镜像（谨慎）
```

### 4.2 升配后重新体检

**如果走精简档，跳过本节；如果升配了，必须重跑。**

```powershell
ssh -i D:\develop\dice\maoDice.pem root@8.148.238.219 "bash -s" < D:\develop\git\CityHub\deploy\preflight.sh
```

**必须看到 `结论：通过` 或 `结论：可部署` 才继续。**

### 4.3 部署（两种方式）

#### 方式一：我远程替你执行（最省事）

我需要你确认三件事：
1. 公网 IP：`8.148.238.219`（已知）
2. 登录方式：`root` + `D:\develop\dice\maoDice.pem`（已知）
3. 明确说一句「可以开始部署」

然后我会按这个顺序做，**每一步都会把输出贴给你**：

```
① 备份骰娘数据      tar czf /opt/dice-backup/pre-tickethub-<日期>.tar.gz -C /opt dice dice-2
② 存稳定性快照      docker ps / docker stats / free -m 存到 /root/preflight-<日期>/
③ 重跑体检          bash preflight.sh  → 必须通过
④ 装 nginx          apt install nginx（当前未装）
⑤ 建目录            /opt/tickethub/{frontend/dist,uploads}
⑥ 上传产物          jar + dist + docker-compose.yml + Dockerfile.runtime
⑦ 生成 .env         随机密码，权限 600
⑧ 起容器            docker compose up -d
⑨ 装 nginx 配置      nginx -t && systemctl reload nginx
⑩ 健康检查          curl http://127.0.0.1/api/show-category/list
⑪ 外网验证          从你本机 curl http://8.148.238.219/api/show-category/list
⑫ 业务链路          验证码登录 → 抢票 → 查订单
⑬ 观察 5 分钟        docker stats 确认内存不持续上涨
```

失败时我会立刻执行回滚（`docker compose stop` + 恢复 nginx 配置），
并把日志原文贴给你，不会留一个半死状态。

#### 方式二：你自己跑脚本（不给我凭证）

```powershell
# 1. 本地构建
cd D:\develop\git\CityHub
mvn -DskipTests clean package
cd frontend
npm ci
npm run build
cd ..

# 2. 上传（把密钥路径换成你的）
scp -i D:\develop\dice\maoDice.pem -r `
  docker-compose.yml Dockerfile.runtime deploy target\ticket-hub-0.0.1-SNAPSHOT.jar `
  root@8.148.238.219:/opt/tickethub/

scp -i D:\develop\dice\maoDice.pem -r frontend\dist `
  root@8.148.238.219:/opt/tickethub/frontend/

# 3. 服务器上一键
ssh -i D:\develop\dice\maoDice.pem root@8.148.238.219
cd /opt/tickethub
bash deploy/deploy.sh
```

> `deploy.sh` 是幂等的：重复执行不会覆盖已有的 `.env`，
> 不会重复建 swap，nginx 配置也是先备份再替换。

### 4.4 组员怎么访问

部署成功后，把这一行发到群里：

```
http://8.148.238.219
```

**组员不需要装任何东西**，手机 4G 流量也能打开。

答辩现场建议：
1. 提前一天部署好，别当天现装
2. 准备一个「重置演示数据」的脚本，抢完票能回到初始库存
3. 手机热点做备份网络（学校 WiFi 经常挡 80 端口）

### 4.5 演示数据重置（答辩必备）

抢票会扣库存，讲完一轮库存就没了，第二组同学看不到效果。重置：

```bash
cd /opt/tickethub

# 方式一：只重置库存和订单（快，10 秒）
docker exec -i tickethub-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" tickethub <<'SQL'
DELETE FROM tb_ticket_order;
UPDATE tb_ticket_tier SET stock = 100;
SQL

# 方式二：完全重建（慢，1 分钟，会重新执行建表脚本）
docker compose down -v && docker compose up -d
```

**方式一更快，但要注意 Redis 里的库存 key 也得清**，否则 Lua 脚本读到的还是旧值：

```bash
docker exec -it tickethub-redis redis-cli FLUSHDB
```

顺序必须是：**先清 Redis，再改 MySQL**。反过来会出现
「Redis 说还有 100 张，MySQL 里却已经卖了 50 张」的不一致。

更稳妥的做法是重启后端，让它重新预热库存：

```bash
docker compose restart app
```

启动时会执行库存预热（把 MySQL 的 stock 写进 Redis），这一步是幂等的。

---

## 第 4.6 节 「太麻烦的地方」手动清单（省算力版）

这一节只列**必须你亲手点、我替你做不了**的动作。其余全部可以一条命令搞定。

| # | 在哪做 | 要做什么 | 大概耗时 | 为什么我做不了 |
|---|---|---|---|---|
| 1 | 阿里云控制台 → ECS → 安全组 | 确认入方向放行 `22`、`80`；把 `0.0.0.0/0` 全端口那条规则删掉 | 2 分钟 | 需要你的阿里云账号登录态 |
| 2 | 阿里云控制台 → ECS → 实例 | 决定：精简档直接跑（不花钱）或 升配 2核4G（停机 3–5 分钟） | 1 分钟决定 | 涉及计费 |
| 3 | 本地 PowerShell | 确认 `ssh -i D:\develop\dice\maoDice.pem root@8.148.238.219` 能登进去 | 30 秒 | 私钥在你机器上 |
| 4 | 服务器（我给命令，你粘贴） | 备份骰娘：`tar czf /opt/dice-backup/pre-tickethub-$(date +%F).tar.gz -C /opt dice dice-2` | 1 分钟 | 需要你确认可以动生产数据 |
| 5 | 本地 PowerShell | `mvn -DskipTests package`（约 1–2 分钟） | 2 分钟 | 你机器上有 Maven |
| 6 | 本地 PowerShell | `cd frontend; npm run build`（约 30 秒） | 1 分钟 | 你机器上有 Node |

**除此之外的 8 个步骤（装 Docker、写 .env、起容器、装 nginx、健康检查）全部由 `deploy/deploy.sh` 或我远程执行完成。**

### 一键走完（你只粘贴这一段）

本地构建 + 上传 + 服务器部署，三段命令：

```powershell
# ① 本地构建（在 D:\develop\git\CityHub）
mvn -DskipTests package
cd frontend; npm run build; cd ..

# ② 上传产物（jar 必须落在 target/ 下，deploy.sh 和 Dockerfile.runtime 都按这个路径找）
ssh -i D:\develop\dice\maoDice.pem root@8.148.238.219 "mkdir -p /opt/tickethub/target /opt/tickethub/frontend"
scp -i D:\develop\dice\maoDice.pem target\ticket-hub-*.jar root@8.148.238.219:/opt/tickethub/target/
scp -i D:\develop\dice\maoDice.pem -r frontend\dist root@8.148.238.219:/opt/tickethub/frontend/
scp -i D:\develop\dice\maoDice.pem docker-compose.yml Dockerfile.runtime root@8.148.238.219:/opt/tickethub/
scp -i D:\develop\dice\maoDice.pem -r deploy root@8.148.238.219:/opt/tickethub/

# ③ 服务器上一键（会自动装 Docker/nginx、生成 .env、起容器、装 nginx 配置）
ssh -i D:\develop\dice\maoDice.pem root@8.148.238.219 "cd /opt/tickethub && bash deploy/deploy.sh"
```

### 如果你只想先本地验证，不想碰服务器

```powershell
cd D:\develop\git\CityHub
docker compose -f docker-compose.local.yml up -d
# 等 40 秒，然后浏览器打开 http://localhost:8080
```

停：`docker compose -f docker-compose.local.yml down`（数据保留）

## 第 4.7 节 服务器上踩到的三个真 Bug（2026-10-21 实录）

这三个都是**只在真实部署时才会暴露**、本地因为缓存/已有数据而看不出来的问题。全部已修复并推送。

### 4.7.1 `src/main/resources/db/tickethub.sql` 没上传 → 数据库是空的

`docker-compose.yml` 把建表脚本绑定挂载到 `/docker-entrypoint-initdb.d/`。
如果宿主机上这个文件**不存在**，Docker 不会报错，而是自动创建一个**同名空目录**：

```
/opt/tickethub/src/main/resources/db/tickethub.sql   ← 变成了目录（drwxr-xr-x）
```

MySQL 启动时在 initdb 目录里看不到 `.sql` 文件，**静默跳过初始化**。结果：

- 容器状态 healthy
- 库 `tickethub` 存在
- 但 `information_schema` 里表数量 = **0**
- app 启动后所有业务接口报「表不存在」

**判断方法**：

```bash
ls -la /opt/tickethub/src/main/resources/db/
# 如果 tickethub.sql 显示 drwxr-xr-x 就是踩坑了，应该是 -rw-r--r--

PW=$(grep '^MYSQL_ROOT_PASSWORD=' /opt/tickethub/.env | cut -d= -f2)
docker exec tickethub-mysql mysql -uroot -p"$PW" -N -e \
  "select count(*) from information_schema.tables where table_schema='tickethub';"
# 期望 11
```

**修复**：

```bash
rm -rf /opt/tickethub/src/main/resources/db/tickethub.sql   # 删掉那个假目录
# 本地重新上传
scp -i <key> src/main/resources/db/tickethub.sql root@<IP>:/opt/tickethub/src/main/resources/db/
# 重建数据卷（会清空已有订单）
cd /opt/tickethub && docker compose down --remove-orphans
docker volume rm tickethub_mysql-data
docker compose up -d
```

`deploy/deploy.sh` 现在会在启动前检查这个文件，缺了直接报错退出，不会再静默跳过。

### 4.7.2 RabbitMQ 内存水位写法在 3.12 上非法 → 容器无限重启

我最初写的两种写法**都会让容器直接退出**：

| 写法 | 报错 |
|---|---|
| `RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS="-rabbit vm_memory_high_watermark.absolute 256MB"` | `application_controller: syntax error before: '.'`（Erlang 把点号当记录语法） |
| `RABBITMQ_VM_MEMORY_HIGH_WATERMARK=128MB` | `is set but deprecated` → 拒绝启动 |

**正确做法**：挂载配置文件。仓库里新增了 `deploy/rabbitmq/rabbitmq.conf`：

```ini
vm_memory_high_watermark.absolute = 128MB
```

`docker-compose.yml` 里：

```yaml
volumes:
  - rabbitmq-data:/var/lib/rabbitmq
  - ./deploy/rabbitmq/rabbitmq.conf:/etc/rabbitmq/conf.d/10-tickethub.conf:ro
```

`deploy.sh` 会把 `.env` 里的 `RABBITMQ_WATERMARK` 同步进这个文件。

**判断方法**：

```bash
docker logs --tail 20 tickethub-rabbitmq
# 正常应该是：Server startup complete; 4 plugins started.
# 异常会出现：BOOT FAILED / failed_to_prepare_configuration
```

### 4.7.3 订单落库 `price=0`

MQ 消息里只带 `{id, userId, tierId, showId}`，**不带票价**。
消费端 `createTicketOrder` 原来直接 `save(order)`，`price` 字段为 null，MySQL 默认 0。

结果：「我的订单」显示 0 元，支付金额也是 0。

**修复**（`TicketOrderServiceImpl.createTicketOrder`）：

```java
if (order.getPrice() == null || order.getPrice() <= 0) {
    TicketTier tier = ticketTierService.getById(order.getTierId());
    if (tier != null) {
        order.setPrice(tier.getPrice());
    }
}
```

实测：tierId=3 抢票后订单 `price=68000`（680 元），正确。

### 4.7.4 还有一个「看起来像 bug 其实不是」

**抢票成功但订单查不到**：RabbitMQ 起来之前，app 的消费者线程在重连，此时投递的消息**留在队列里没被消费**。
等 RabbitMQ 恢复后，消费者会自动补消费，订单就出现了。

所以部署顺序很重要：**先等 RabbitMQ 完全起来，再重启一次 app**，确保消费者已连上。

```bash
docker compose up -d rabbitmq
sleep 30
docker compose restart app
```

`deploy.sh` 的 `depends_on: service_started` 只保证「容器启动了」，不保证「RabbitMQ 能收消息」，所以首次部署后建议手动重启一次 app。

## 第 5 节 组员拿到项目后怎么跑（可以直接转发这段）

### 5.1 组员需要装的东西

只需要两样：

1. **Git**（拉代码）：`https://git-scm.com/download/win`
2. **Docker Desktop**（跑环境）：`https://www.docker.com/products/docker-desktop/`

> 不需要 JDK、不需要 Maven、不需要 Node、不需要 MySQL。

### 5.2 组员的操作步骤

```powershell
# 1. 拉代码
git clone https://github.com/HeiCat233/CityHub.git
cd CityHub

# 2. 启动（首次 10–20 分钟，主要是在下镜像和依赖）
docker compose -f docker-compose.local.yml up -d --build

# 3. 等 1 分钟，然后打开
start http://localhost:8080
```

### 5.3 组员常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `docker: command not found` | 没装 Docker 或没启动 | 启动 Docker Desktop，等鲸鱼图标变绿 |
| `port is already allocated` | 8080/13306 等端口被占 | 改 `docker-compose.local.yml` 里冒号左边的数字 |
| `no space left on device` | C 盘满了 | Docker Desktop → Settings → Resources 改镜像位置到 D 盘 |
| 页面打开是白屏 | 前端构建失败或 nginx 没起来 | `docker compose -f docker-compose.local.yml logs web` |
| 页面能开但列表为空 | 后端没连上 MySQL | `docker compose -f docker-compose.local.yml logs app` 找 `Communications link failure` |
| 抢票后订单一直不出现 | RabbitMQ 没起来 | 打开 `http://localhost:15672` 看队列 |
| `TLS handshake timeout` | 拉镜像被墙 | Docker Desktop → Settings → Docker Engine 配国内镜像加速 |

### 5.4 组员要改代码怎么办

改完后不需要手动 build，直接：

```powershell
docker compose -f docker-compose.local.yml up -d --build
```

它只会重建变化的那一层（改 Java 重建 app，改 Vue 重建 web）。

**如果组员想用 IDE 断点调试**，Docker 方案就不合适了，让他走第 3 节的传统路线，
或者「Docker 只跑 MySQL/Redis/MQ，后端和前端在 IDE 里跑」：

```powershell
# 只启动中间件
docker compose -f docker-compose.local.yml up -d mysql redis rabbitmq

# 然后在 IDEA 里跑 TicketHubApplication，连接参数用：
#   MySQL:   127.0.0.1:13306  库 tickethub  用户 tickethub  密码 tickethub_local_pwd
#   Redis:   127.0.0.1:16379
#   RabbitMQ 127.0.0.1:5673   用户 tickethub  密码 tickethub_local_mq
```

这是最舒服的开发姿势：中间件不用装，代码可以断点。

---

## 第 6 节 两条路的取舍（给你的决策依据）

| 维度 | 本地 Docker | 直接上服务器 |
|---|---|---|
| 组员门槛 | 要装 Docker（约 700MB） | 零门槛，浏览器即可 |
| 你的一次性成本 | 10–20 分钟构建 | 30 分钟部署（不用升配） |
| 持续成本 | 0 | 0（复用已有 ECS） |
| 演示稳定性 | 依赖你的笔记本 | 依赖服务器和网络 |
| 断点调试 | 需要切传统模式 | 不能调试 |
| 答辩加分 | 「本地能跑」 | 「已上线，扫码即用」 |

**我的建议**（按你的实际情况调整过）：

1. **今天**：走 A1，本机已实测跑通（5 个容器 + 抢票链路），先录一段抢票成功的视频，防止答辩当天翻车。
2. **本周**：把 `docker-compose.build.yml` 发给组员，让他们各自跑一份，确认代码在别人机器上也没问题。
3. **答辩前 3 天**：直接用**精简档**部署到那台 ECS（不用升配，会吃 20–100MB swap），让组员用手机验证能打开。
4. **答辩当天**：用服务器演示（显得完整），本地留一份作为备份。
5. **如果演示时发现卡顿**：再考虑升配 2核4G，或者临时 `docker compose stop` 掉第 2 套骰娘释放 345MB。

---

## 第 7 节 本轮新增的文件说明

```
D:\develop\git\CityHub\
├── docker-compose.local.yml        ← 新增：本地一键启动（5 个容器，端口全避开本机占用）
├── frontend\
│   ├── Dockerfile                  ← 新增：Node 构建 → nginx 托管（多阶段）
│   ├── .dockerignore               ← 新增：排除 node_modules，加快构建
│   └── nginx.local.conf            ← 新增：容器内 nginx 配置（静态 + /api 反代）
├── deploy\
│   └── preflight.sh                ← 新增：服务器只读体检脚本
└── docs\
    ├── LOCAL_RUN.md                ← 本文件
    ├── PRE_DEPLOY_CHECK.md         ← 部署前体检清单
    ├── DEPLOY.md                   ← 服务器部署教程（生产）
    └── PROJECT_GUIDE.md            ← 代码讲解
```

### 与生产配置的区别（别搞混）

| 项目 | 本地 `docker-compose.local.yml` | 生产 `docker-compose.yml` |
|---|---|---|
| 前端 | 独立 web 容器（nginx） | 宿主机 nginx 托管 dist |
| 后端端口 | 18081 对外（方便调试） | 仅 127.0.0.1:8081 |
| MySQL 端口 | 13306 对外 | 不对外 |
| JVM 堆 | 768M | 512M |
| Redis 密码 | 无 | 可选 |
| 限流 | 关闭 | 开启（抢票 10r/s） |
| 内存限制 | 无 | 每个容器都有 mem_limit |

**本地配置不要直接拿去部署服务器**，2G 机器会 OOM。

---

## 第 8 节 我这一轮验证了什么

| 验证项 | 方法 | 结果 |
|---|---|---|
| `docker-compose.local.yml` 语法 | `docker compose config --quiet` | ✅ 通过 |
| `frontend/nginx.local.conf` 语法 | nginx 1.24 `-t` | ✅ 通过 |
| 同上，nginx 1.18 兼容性 | nginx 1.18 `-t` | ✅ 通过 |
| 前端能否构建 | `frontend/dist` 已存在且含 assets | ✅ 已有产物 |
| 后端 jar 是否已构建 | `target/ticket-hub-0.0.1-SNAPSHOT.jar` 52MB | ✅ 存在 |
| 本地 Docker 实际启动 | 守护进程未运行，无法验证 | ⚠️ 待你启动 Docker Desktop 后验证 |

**唯一没验证的是容器真实启动**，因为你的 Docker Desktop 守护进程当前没起。
启动后跑 `docker compose -f docker-compose.local.yml up -d --build` 就能确认。
