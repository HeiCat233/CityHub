# TicketHub · 演出票务抢票平台

一个面向「开票抢票」高并发场景的演出票务平台，覆盖演出浏览、票档选择、抢票下单、支付、
演出动态、关注、签到等完整链路。

> 技术底座由本地生活服务项目重构成票务业务：秒杀从附加功能变为核心业务，
> 主存储 MySQL，Redis 承担缓存与高并发数据结构，RabbitMQ 负责异步出票。

## 技术栈

| 层次 | 技术 | 说明 |
|---|---|---|
| 语言/框架 | Java 8 · Spring Boot 2.3.12 | 后端服务 |
| 主存储 | MySQL 8.0 · MyBatis-Plus 3.4.3 | 演出 / 票档 / 订单 / 用户 |
| 缓存与高并发 | Redis 7（Lettuce） | 缓存、秒杀库存、点赞榜、关注、Feed、GEO、签到 |
| 消息队列 | RabbitMQ | 抢票异步出票 + 死信兜底 |
| 分布式锁 | Redisson 3.13.6 | 订单防重 |
| 对象存储 | 阿里云 OSS（可选） | 图片上传，未配置自动降级本地目录 |
| 前端 | Vue 3 · Vite · Element Plus · Pinia | 桌面/移动自适应 SPA |
| 部署 | Docker · Docker Compose · nginx · 阿里云 ECS | 见 [`docs/DEPLOY.md`](docs/DEPLOY.md) |

## 核心技术点

### 1. 抢票（秒杀）链路

```
用户点击抢票
   ↓
Redis + Lua 原子校验：库存是否充足 → 是否重复抢票 → 扣库存 → 记录用户
   ↓ 校验通过
投递 RabbitMQ（异步出票），立即返回订单号
   ↓
消费者落库 + 数据库乐观锁扣库存（stock > 0）防超卖
```

- **Lua 脚本原子性**：`src/main/resources/seckill.lua`，把「查库存 / 查已购 / 扣库存 / 记用户」合并为一次 Redis 调用，杜绝并发穿插
- **一人一单**：Redis Set 记录已抢用户，配合 Lua 的 `SISMEMBER` 判断
- **异步出票**：RabbitMQ 削峰，接口响应时间与数据库写入解耦
- **乐观锁防超卖**：`UPDATE ... SET stock = stock - 1 WHERE tier_id = ? AND stock > 0`
- **消费幂等**：消费者按订单号判重，重复投递不会产生重复订单
- **投递失败回滚**：MQ 发送异常时归还 Redis 库存与已购标记，避免名额空占

### 2. 缓存三件套

| 问题 | 方案 | 实现 |
|---|---|---|
| 缓存穿透 | 空值缓存（TTL 2 分钟） | `CacheClient#queryWithPassThrough` |
| 缓存击穿 | 逻辑过期 + 互斥锁 + 异步重建 | `CacheClient#queryWithLogicalExpire` |
| 缓存一致性 | 先更新数据库、再删除缓存 | `ShowServiceImpl#update` |

> 演出详情是典型热点数据，可容忍短暂不一致，因此选逻辑过期（AP）；缓存重建使用独立线程池，
> 未抢到锁的请求直接返回旧数据，不阻塞。

### 3. Redis 数据结构应用

| 业务 | 结构 | Key |
|---|---|---|
| 秒杀库存 | String | `seckill:stock:{tierId}` |
| 一人一单 | Set | `seckill:order:{tierId}` |
| 动态点赞榜 | ZSet | `post:liked:{postId}` |
| 关注关系 | Set | `follows:{userId}` |
| Feed 流收件箱 | ZSet | `feed:{userId}` |
| 附近演出 | GEO | `show:geo:{categoryId}` |
| 每日签到 | BitMap | `sign:{userId}:{yyyyMM}` |
| 分类缓存 | List | `show:category:` |
| 登录态 | Hash | `login:token:{token}` |
| 全局订单号 | String 自增 | `icr:order:{yyyy:MM:dd}` |

### 4. 其他

- **全局唯一订单号**：时间戳左移 32 位 + Redis 按天自增（`RedisIdWorker`）
- **登录鉴权**：双拦截器 + Redis Token 续期 + ThreadLocal 传递用户
- **动态 Feed 流**：推模式写入粉丝收件箱 ZSet，按 score 游标滚动分页
- **共同关注**：两个 Set 求交集（SINTER）
- **接口文档**：可通过 Knife4j/Postman 按 `docs/API.md` 调试

## 快速开始（本地）

### 依赖

JDK 8+、Maven 3.6+、Node 18+、MySQL 8、Redis 6+、RabbitMQ 3.x。

### 1. 初始化数据库

```bash
mysql -uroot -p < src/main/resources/db/tickethub.sql
```

### 2. 启动依赖服务

```bash
redis-server            # 6379
rabbitmq-server         # 5672（含 management 插件更好）
```

### 3. 启动后端

```bash
# 按需覆盖默认配置
export MYSQL_PASSWORD=你的密码
export REDIS_HOST=127.0.0.1
export RABBITMQ_HOST=127.0.0.1

mvn spring-boot:run     # http://localhost:8081
```

启动时会自动把票档库存预热到 Redis（仅当 Redis 中不存在时写入）。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev             # http://localhost:5173（/api 自动代理到 8081）
```

### 5. 登录演示

验证码接口会直接返回验证码，前端会自动填入，无需短信服务。
演示账号：`13800000001` ~ `13800000006`（预置数据）。

## 目录结构

```
CityHub/
├── src/main/java/com/tickethub/
│   ├── config/        # MVC/拦截器、MyBatis、RabbitMQ 队列、Redisson、库存预热
│   ├── controller/    # REST 接口（演出/票档/订单/动态/关注/用户/上传）
│   ├── entity/        # 实体（Show/TicketTier/TierStock/TicketOrder/Post…）
│   ├── listener/      # 抢票 MQ 消费者（正常队列 + 死信兜底）
│   ├── mapper/        # MyBatis-Plus Mapper
│   ├── service/       # 业务接口与实现
│   └── utils/         # CacheClient / RedisIdWorker / OssUtil / 常量
├── src/main/resources/
│   ├── application.yaml    # 配置（全部支持环境变量覆盖）
│   ├── seckill.lua         # 抢票资格原子校验脚本
│   └── db/tickethub.sql    # 建库建表 + 演示数据
├── frontend/          # Vue 3 + Vite + Element Plus
├── deploy/            # 一键部署脚本、nginx 配置、.env 模板
├── Dockerfile         # 完整构建版（服务器内编译，备用）
├── Dockerfile.runtime # 服务器实际使用：只运行本地构建好的 jar
├── docker-compose.yml       # 生产编排（精简档参数可由 .env 覆盖）
├── docker-compose.local.yml # 本地快速启动（复用已构建的 jar/dist，30 秒起）
├── docker-compose.build.yml # 本地完整构建（组员用，容器内编译）
└── docs/              # LOCAL_RUN / DEPLOY / PRE_DEPLOY_CHECK / PROJECT_GUIDE
```

## 部署到服务器

**完整教程见 [`docs/DEPLOY.md`](docs/DEPLOY.md)**，包含手动部署（8 步详解）与自动部署脚本两套流程。

架构：**nginx 同时托管前端静态资源与反向代理 API，后端 + MySQL + Redis + RabbitMQ
由 Docker Compose 编排在同一台 ECS 上**。OSS（可选）只用于存放用户上传的图片。

> 为什么前端不放 OSS：阿里云 OSS 默认域名访问 HTML 会被强制下载，必须绑定自定义域名。
> 课程阶段无域名，因此静态资源交给 nginx，OSS 只存图片（详见 DEPLOY.md 第 0 章）。

**快速部署（本地构建 → 上传 → 一键脚本）**：

```powershell
# 1. 本地构建（服务器是 2核2G，不在服务器上编译，否则必 OOM）
mvn -DskipTests clean package
cd frontend; npm run build; cd ..

# 2. 上传到服务器（jar 必须落在 target/ 下）
$IP = "8.148.238.219"; $KEY = "D:\develop\dice\maoDice.pem"
ssh -i $KEY root@$IP "mkdir -p /opt/tickethub/target /opt/tickethub/frontend"
scp -i $KEY docker-compose.yml Dockerfile.runtime root@$IP:/opt/tickethub/
scp -i $KEY target\ticket-hub-*.jar root@$IP:/opt/tickethub/target/
scp -i $KEY -r frontend\dist root@$IP:/opt/tickethub/frontend/
scp -i $KEY -r deploy root@$IP:/opt/tickethub/

# 3. 服务器上一键部署（装 Docker/nginx、建 swap、生成密码、起容器、健康检查）
ssh -i $KEY root@$IP "cd /opt/tickethub && bash deploy/deploy.sh"
```

访问 `http://<ECS公网IP>/` 即可。前置条件：阿里云安全组放行 **22、80** 端口。

**2核2G / 同机已有其他服务**：`deploy.sh` 检测到内存 < 3GB 会自动切「精简档」
（四容器实际占用约 800–990MB）。也可手动执行 `bash deploy/apply-compact.sh`。
详见 [`docs/DEPLOY.md`](docs/DEPLOY.md) 第 0.4.1 节。

## 演示脚本（答辩用）

1. **首页**：演出分类切换、附近演出（GEO 查询）
2. **演出详情**：票档列表、实时余票、开票倒计时
3. **抢票**：双浏览器同时抢同一票档 → 展示库存不超卖、一人一单拦截
4. **订单**：订单号生成 → 支付 → 状态流转
5. **动态**：点赞（ZSet 排行榜）、Feed 流、共同关注（Set 交集）
6. **个人中心**：BitMap 签到日历
7. **压测收尾**：`TicketOrderConcurrencyTest` 并发验证（200 并发抢 50 张票 → 恰好 50 单成功）

## 文档

| 文档 | 内容 | 什么时候看 |
|---|---|---|
| [`docs/LOCAL_RUN.md`](docs/LOCAL_RUN.md) | 本地一键启动、组员协作、Docker 镜像盘、手动清单 | **第一次跑起来** |
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | 架构原理、手动部署 8 步、自动部署、运维排错 | 要上服务器时 |
| [`docs/PRE_DEPLOY_CHECK.md`](docs/PRE_DEPLOY_CHECK.md) | 部署前体检清单、内存账、回滚预案 | 上服务器前 |
| [`docs/PROJECT_GUIDE.md`](docs/PROJECT_GUIDE.md) | 项目讲解、核心链路逐行分析、技术点核对、风险清单 | 答辩 / 简历 |

## License

MIT
