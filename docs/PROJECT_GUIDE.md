# CityHub / TicketHub 项目讲解文档

> 本文档分两部分：**第 1~5 章是改造前的基线说明**（原黑马点评项目），
> **第 6 章起是 TicketHub 票务改造方案与实施记录**。
> 编写日期：2026-10-01；改造完成：2026-10-09。
> 配套阅读：`README.md`（TicketHub 项目说明与部署指南）。

---

## 目录

1. [项目是什么](#1-项目是什么)
2. [整体架构与核心链路逐行讲解](#2-整体架构与核心链路逐行讲解)
3. [项目目录结构详解](#3-项目目录结构详解)
4. [技术点核对表（对照 Xiaoc7r/CityHub README）](#4-技术点核对表)
5. [风险点与已知问题清单](#5-风险点与已知问题清单)
6. [改造方案草稿（待确认后实施）](#6-改造方案草稿)
7. [本地运行指南](#7-本地运行指南)
8. [部署方案（生产环境）](#8-部署方案生产环境)

---

## 1. 项目是什么

### 1.1 业务场景（当前）

一个**类大众点评的本地生活服务平台**（黑马程序员点评项目二开），核心业务是：

- 浏览商铺（按分类、按名称、按附近距离）、查看商铺缓存详情
- 领取/购买优惠券，**优惠券秒杀**（项目的高并发核心场景）
- 发布探店笔记、点赞（点赞排行榜）、关注别人、看关注人的 Feed 流、共同关注
- 每日签到（BitMap 日历）

### 1.2 实际技术栈（以 pom.xml / 代码为准）

| 组件 | 版本 | 在项目里的角色 |
|---|---|---|
| Java | 8 | 语言 |
| Spring Boot | 2.3.12.RELEASE | 框架（注意：不是 README 徽章写的 3.0） |
| MySQL | 8.0.28 驱动 | 主存储，库名 `dingping`，11 张表 |
| Redis | Lettuce 6.1.6 + 连接池 | 缓存 / 秒杀资格判断 / 点赞 / 关注 / Feed / GEO / 签到 / 全局ID / 会话 |
| RabbitMQ | spring-boot-starter-amqp | 秒杀异步下单（README 写的 RocketMQ 并不存在） |
| Redisson | 3.13.6 | 分布式锁 `lock:order:{userId}` |
| MyBatis-Plus | 3.4.3 | ORM（BaseMapper + QueryWrapper，分页插件） |
| Hutool | 5.7.17 | JSON / Bean 拷贝 / 随机数工具 |

### 1.3 README 声称 vs 代码实际（重要）

| README 说 | 代码实际 |
|---|---|
| Spring Boot 3.0 | 2.3.12（2020 年版本） |
| RocketMQ 5.0 异步下单 | RabbitMQ |
| Caffeine 3.1 二级缓存 | pom 无此依赖，代码中 0 处引用 |
| JMeter 1000 线程压测验证 | 仓库里只有 MockMvc 写的多线程测试类，无 JMeter 工程文件 |
| 滑动窗口限流 / HyperLogLog UV | 代码中完全没有 |

**结论**：README 的技术点部分是"学习笔记 + 规划"，不能当作代码事实来答辩。改造时 README 需要重写（见第 5 节风险 1）。

---

## 2. 整体架构与核心链路逐行讲解

### 2.1 架构总览

```
前端(独立Nginx托管,不在本仓库) ──HTTP──> Spring Boot(8081)
                                            │
                     ┌──────────────────────┼───────────────────────┐
                     │                      │                       │
              拦截器(登录鉴权)          Controller(10个REST)      RabbitMQ(异步)
                     │                      │                       │
                     │                Service(业务+Redis读写)     QA队列 ──> 消费者落库
                     │                      │                       │
              MySQL(主存储) <──MyBatis-Plus──┤                 (TTL 10s 死信 → QD)
                                            │
                                          Redis(缓存/秒杀/社交数据)
```

### 2.2 核心链路①：秒杀下单（项目最核心的一段）

入口：`POST /voucher-order/seckill/{voucherId}` → `VoucherOrderController` → `VoucherOrderServiceImpl.seckillVoucher()`

**第 1 步：准备**（`VoucherOrderServiceImpl.java:204-213`）

```java
Long userId = UserHolder.getUser().getId();      // 拦截器已把用户放进 ThreadLocal
long orderId = redisIdWorker.nextId("order");    // 全局唯一订单号
```

`RedisIdWorker.nextId()`（`utils/RedisIdWorker.java`）：`时间戳(秒,从2022-01-01起) << 32 | Redis自增序列`。
自增 key 按天分片 `icr:order:yyyy:MM:dd`——避免单 key 涨太大，也方便按天统计订单量。

**第 2 步：Lua 脚本原子判断资格**（`resources/seckill.lua`）

Lua 在 Redis 端一次性执行"查库存 → 查已购 → 扣库存 → 记录用户"，中间不会被并发请求穿插，这就是"原子性"：

```lua
local stockKey='seckill:stock:' .. voucherId   -- String：库存
local orderKey='seckill:order:' .. voucherId   -- Set：已购用户
if stock == nil then return -1 end             -- 预热数据不存在
if (stock<= 0) then return 1 end               -- 库存不足
if(redis.call('sismember',orderKey,userId)==1) then return 2 end  -- 一人一单
redis.call('incrby',stockKey,-1)               -- 扣库存
redis.call('sadd',orderKey,userId)             -- 记录该用户已购
```

返回 0（成功）/1（库存不足）/2（重复下单）/-1（未预热）。Java 侧：

```java
if(r!=0){ return Result.fail(r==1?"库存不足":"不能重复下单"); }
```

**第 3 步：发 RabbitMQ，立即返回订单号**（`VoucherOrderServiceImpl.java:228-244`）

```java
VoucherOrder order = new VoucherOrder();   // 只带 id/userId/voucherId
String jsonStr = JSONUtil.toJsonStr(order);
rabbitTemplate.convertAndSend("X","XA",jsonStr);  // 发到交换机X, 路由键XA
return Result.ok(orderId);                        // 用户立刻拿到订单号,落库是异步的
```

队列拓扑在 `config/QueueConfig.java` 声明：

```
交换机X --路由XA--> 队列QA(TTL 10s, 死信交换机Y, 死信路由YD)
交换机Y --路由YD--> 队列QD(死信队列)
```

**第 4 步：消费者异步落库**（`listener/SeckillVoucherListener.java`）

```java
@RabbitListener(queues = "QA")
public void receivedA(Message message, Channel channel) {
    VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
    voucherOrderService.save(voucherOrder);                 // 订单落库
    seckillVoucherService.update()
        .setSql("stock = stock - 1")                        // 数据库扣库存
        .eq("voucher_id", voucherId).gt("stock", 0)         // WHERE stock>0 乐观锁防超卖
        .update();
}
```

`gt("stock", 0)` 生成的 SQL 是 `... AND stock > 0`——并发扣减时库存一旦到 0，后续 UPDATE 影响行数为 0，这就是**乐观锁防超卖**。

**这条链路上没有走到/残留的代码**（理解时容易混淆）：
- `handleVoucherOrder()`（Redisson 锁 + 代理调用 `createVoucherOrder`）当前**没有任何调用方**，且字段 `proxy` 从未初始化，被调用即 NPE（详见风险 2）。它属于旧方案遗留。
- `createVoucherOrder()` 里数据库层的"一人一单检查 + 扣库存 + 保存"是完整事务写法，但主链路改走 MQ 后没再用它（Redis Set 已在 Lua 里做了去重）。
- Lua 脚本末尾 `XADD stream.orders ...` 是更旧方案（Redis Stream 消息队列）的残留，消息发出去没人消费（风险 3）。

### 2.3 核心链路②：店铺详情缓存（穿透 / 击穿）

入口：`GET /shop/{id}` → `ShopServiceImpl.queryById()` → 实际调用 `CacheClient.queryWithLogicalExpire()`（**逻辑过期方案**）。

`utils/CacheClient.java` 是缓存三件套的通用封装，签名都是 `(key前缀, id, 返回类型, 回调查库函数, TTL, 单位)`：

**a) 缓存穿透方案 `queryWithPassThrough`**（`:42-70`，当前主链路未启用，代码保留）

```
查Redis → 命中即返回
       → 是空串("")说明是之前缓存的空值，直接返回null（挡住恶意id刷库）
       → 查数据库，查不到就写一个TTL 2分钟的空值进Redis
```

**b) 缓存击穿方案 `queryWithLogicalExpire`**（`:74-122`，当前启用）

核心思想：**热点数据永不物理过期，把过期时间包在 value 里（逻辑过期）**，过期了也不阻塞请求：

```
查Redis → 没数据直接返回null（所以必须先用测试类预热缓存!）
       → 有数据,解析出 expireTime
       → 未到期：直接返回旧数据（性能最好,AP思路）
       → 已到期：tryLock("lock:shop:"+id) 抢到锁的线程,丢进独立线程池异步查库重建缓存
                 没抢到锁的线程：直接返回旧数据,绝不等待
```

Redis 里存的结构是 `RedisData{ expireTime, data(店铺JSON) }`。

**c) 更新一致性**：`ShopServiceImpl.update()`（`:232-244`）——先更新数据库、再删除缓存（Cache-Aside 标准写法）。

**d) 附近商铺 GEO**：`queryShopByType()`（`:246-298`）——带坐标时用 `opsForGeo().search(key, 坐标, 5000米)` 拿"shopId+距离"，内存里 skip/limit 分页，再用 `order by field(id,...)` 按距离顺序回表查店铺，最后回填 distance 字段。

### 2.4 核心链路③：登录鉴权（双拦截器 + Redis 会话）

1. `POST /user/code`：`UserServiceImpl.sendCode()` 生成 6 位随机码，存 `login:code:{phone}`（TTL 2 分钟），**验证码只在日志里打印**（没有接真实短信，演示时看后端日志拿验证码）。
2. `POST /user/login`：校验验证码 → 按手机号查用户（不存在则注册）→ 生成随机 token → `login:token:{token}` 存用户 JSON（TTL 36000 秒 = 10 小时）→ token 返回给前端。
3. 前端后续请求带 `authorization: {token}` 头。两个拦截器（`config/MvcConfig.java` 注册）：
   - `RefreshTokenInterceptor`（order 0，拦所有请求）：有 token 就查 Redis → 刷新 TTL → 把 `UserDTO` 放进 ThreadLocal（`utils/UserHolder`）；
   - `LoginInterceptor`（order 1，只拦需要登录的路径）：ThreadLocal 里没用户就返回 401。
   - 放行路径：登录、发验证码、商铺/分类查询、优惠券浏览、热门笔记、图片上传。

### 2.5 社交功能用的 Redis 结构（对照代码）

| 功能 | 代码位置 | Redis 结构 |
|---|---|---|
| 点赞/取消点赞 + 点赞榜 | `BlogServiceImpl.updateLike / queryBlogLikes` | `blog:liked:{blogId}` **ZSet**，score=点赞时间戳；top5 用 `range(0,4)` |
| 关注 | `FollowServiceImpl.follow` | MySQL `tb_follow` + Redis `follows:{userId}` **Set** 双写 |
| 共同关注 | `followCommons` | 两个 Set 求交集（SINTER） |
| Feed 流（推模式） | `BlogServiceImpl` 查关注人笔记 | 发笔记时写入粉丝收件箱 `feed:{粉丝id}` **ZSet**（score=时间戳），滚动分页用 `ScrollResult` 记录 lastId/lastScore |
| 签到 | `UserServiceImpl` sign | `sign:{userId}:{yyyyMM}` **BitMap**，offset=第几天；连续签到从月底往回数 |
| 分类缓存 | `ShopTypeServiceImpl` | `shop_type:` **List** 缓存整个分类表 |
| 秒杀 | 上文 | **String**(库存) + **Set**(已购) |

---

## 3. 项目目录结构详解

### 3.1 完整目录树

```
CityHub/
├── pom.xml                                  # Maven 依赖（版本见 1.2 节）
├── README.md                                # 原作者设计取舍笔记（与代码有出入,见风险1）
├── docs/
│   └── PROJECT_GUIDE.md                     # 本文档
└── src/
    ├── main/
    │   ├── java/com/hmdp/
    │   │   ├── HmDianPingApplication.java   # 启动类：@MapperScan + @EnableRabbit
    │   │   ├── config/                      # 配置类(5个)
    │   │   ├── controller/                  # REST 接口层(10个)
    │   │   ├── dto/                         # 出入参对象(4个)
    │   │   ├── entity/                      # 数据库实体(10个,对应11张表)
    │   │   ├── interceptor/                 # 登录拦截器(2个)
    │   │   ├── listener/                    # MQ 消费者(1个)
    │   │   ├── mapper/                      # MyBatis-Plus Mapper(10个)
    │   │   ├── service/                     # 业务接口(10个) + impl/(10个实现)
    │   │   └── utils/                       # 工具类(11个)
    │   └── resources/
    │       ├── application.yaml             # 配置(端口8081/MySQL/Redis/RabbitMQ)
    │       ├── seckill.lua                  # 秒杀资格判断脚本
    │       ├── unLock.lua                   # 分布式锁释放脚本
    │       ├── db/hmdp.sql                  # 建库建表+数据(11张表)
    │       └── mapper/VoucherMapper.xml     # 唯一的手写SQL:店铺券列表联表查询
    └── test/java/com/hmdp/
        ├── HmDianPingApplicationTests.java  # 默认上下文测试
        ├── ShopCacheTest.java               # 店铺缓存预热(逻辑过期方案的必要前置!)
        ├── VoucherOrderControllerTest.java  # 秒杀多线程压测(MockMvc+CountDownLatch)
        └── tokens.txt                       # 压测时写入用户token
```

### 3.2 每个类的职责

**config/**（5 个）

| 类 | 职责 |
|---|---|
| `MvcConfig` | 注册两个登录拦截器，配置放行路径 |
| `MybatisConfig` | MyBatis-Plus 分页插件 |
| `QueueConfig` | RabbitMQ 拓扑：交换机X/Y、队列QA(TTL 10s)/QD、绑定关系 |
| `RedissonConfig` | Redisson 客户端 Bean（分布式锁用） |
| `WebExceptionAdvice` | 全局异常处理 |

**controller/**（10 个，全部返回统一包装 `dto/Result`）

| 类 | 路由前缀 | 主要接口 |
|---|---|---|
| `UserController` | /user | 发验证码、登录/登出、查我、签到、连续签到数 |
| `ShopController` | /shop | 按id查(走缓存)、新增、更新(删缓存)、按类型+坐标分页(GEO)、按名称查 |
| `ShopTypeController` | /shop-type | 分类列表(Redis List 缓存) |
| `VoucherController` | /voucher | 新增普通/秒杀券、查店铺券列表(联表) |
| `VoucherOrderController` | /voucher-order | **秒杀下单(核心)** |
| `BlogController` | /blog | 发笔记、点赞、热门/详情/点赞榜/我的笔记/关注人Feed流 |
| `FollowController` | /follow | 关注/取关、是否关注、共同关注 |
| `BlogCommentsController` | /blog-comments | 空实现 |
| `UploadController` | /upload | 图片上传(硬编码本机磁盘路径,见风险8) |

**service/impl/**（10 个实现，重点 5 个）

| 类 | 职责与亮点 |
|---|---|
| `VoucherOrderServiceImpl` | 秒杀主链路：Lua 资格判断 + 发MQ；含旧方案遗留代码(风险2) |
| `SeckillVoucherServiceImpl` | 秒杀券库存的通用 service（消费者扣库存用它） |
| `ShopServiceImpl` | 缓存三件套应用、GEO 附近商铺、缓存一致性 |
| `BlogServiceImpl` | 点赞(ZSet)、Feed流推模式、热门笔记 |
| `UserServiceImpl` | 验证码登录、BitMap 签到/连续签到 |
| `FollowServiceImpl` | 关注双写(MySQL+Set)、共同关注(SINTER) |
| `VoucherServiceImpl` | 券管理，查店铺券列表走 `VoucherMapper.xml` 联表 |

**utils/**（11 个）

| 类 | 职责 |
|---|---|
| `CacheClient` | 缓存三件套通用封装(穿透空值/击穿逻辑过期+互斥锁) |
| `RedisIdWorker` | 全局唯一ID：时间戳<<32 \| Redis自增 |
| `SimpleRedisLock` | 手写 SETNX+Lua 释放锁(教学对照,主链路用的是Redisson) |
| `ILock` | 上者的接口 |
| `RedisConstants` | 所有 Redis key 前缀与 TTL 常量(见 3.4) |
| `RedisData` | 逻辑过期包装类{expireTime, data} |
| `UserHolder` | ThreadLocal 存当前登录用户 |
| `SystemConstants` | 分页大小等常量 |
| `RegexUtils` / `RegexPatterns` | 手机号校验 |
| `PasswordEncoder` | 密码加密工具 |

### 3.3 resources 详解

- **application.yaml**：端口 8081；MySQL `127.0.0.1:3306/dingping`(root/明文密码,风险7)；Redis 连接池(max-active 30)；RabbitMQ guest/guest；Jackson 忽略 null；com.hmdp 日志 debug（验证码就在 debug 日志里）。
- **seckill.lua / unLock.lua**：逐行讲解见 2.2 与 5.4。注意 `SimpleRedisLock` 代码里加载的是 `"unlock.lua"`（全小写），实际文件叫 `unLock.lua`——Windows 不区分大小写能跑，**Linux 部署会直接找不到资源**（风险 9）。
- **db/hmdp.sql**：11 张表（见 3.5），带演示数据（约 1000 用户、若干商铺/券/笔记）。
- **VoucherMapper.xml**：唯一手写 SQL，`tb_voucher LEFT JOIN tb_seckill_voucher` 查店铺券列表。

### 3.4 Redis key 一览（排查数据时对照用）

| Key 前缀 | 类型 | 内容 | TTL |
|---|---|---|---|
| `login:code:{phone}` | String | 短信验证码 | 2 分钟 |
| `login:token:{token}` | String(JSON) | 登录会话 | 10 小时 |
| `cache:shop:{id}` | String(JSON) | 店铺缓存(逻辑过期包裹) | 无(逻辑过期) |
| `lock:shop:{id}` | String | 缓存重建互斥锁 | 10 秒 |
| `seckill:stock:{voucherId}` | String | 秒杀库存 | 无 |
| `seckill:order:{voucherId}` | Set | 已购用户(一人一单) | 无 |
| `icr:order:{yyyy:MM:dd}` | String(自增) | 订单号序列 | 无 |
| `blog:liked:{blogId}` | ZSet | 点赞用户(score=时间) | 无 |
| `follows:{userId}` | Set | 关注列表 | 无 |
| `feed:{userId}` | ZSet | 收件箱Feed流 | 无 |
| `shop:geo:{typeId}` | GEO | 商铺坐标 | 无 |
| `sign:{userId}:{yyyyMM}` | BitMap | 每日签到 | 无 |
| `shop_type:` | List | 分类缓存 | 10 分钟 |
| `stream.orders` | Stream | **残留无消费**(风险3) | 无 |

### 3.5 MySQL 表一览（`db/hmdp.sql`）

| 表 | 存什么 | 备注 |
|---|---|---|
| `tb_user` | 用户(手机号/昵称/头像) | 登录主体 |
| `tb_user_info` | 用户详细信息 | 基本没用上 |
| `tb_shop` | 商铺(名称/图片/坐标x,y/评分) | 缓存+GEO 的数据源 |
| `tb_shop_type` | 商铺分类(图标/排序) | |
| `tb_voucher` | 优惠券(普通/秒杀共用) | |
| `tb_seckill_voucher` | 秒杀信息(库存/起止时间) | 与 voucher 1:1 |
| `tb_voucher_order` | 秒杀订单 | MQ 消费者写入 |
| `tb_blog` | 探店笔记(标题/图片/点赞数) | liked 字段与 Redis 双写 |
| `tb_blog_comments` | 笔记评论 | 空实现 |
| `tb_follow` | 关注关系 | 与 Redis Set 双写 |
| `tb_sign` | 签到记录 | 代码实际用 BitMap,这张表基本闲置 |

### 3.6 test 目录

- `ShopCacheTest.testSaveShopCache`：把全部商铺写入 Redis（逻辑过期格式）——**每次重启/清空 Redis 后必须先跑它**，否则查店铺一律"不存在"。
- `VoucherOrderControllerTest.testMultiThreadSeckill`：模拟 1000 并发秒杀（MockMvc + CountDownLatch），先批量注册用户拿 token 写进 `tokens.txt`，再放行并发——验证"不超卖、一人一单"。

---

## 4. 技术点核对表

对照对象：Xiaoc7r/CityHub 仓库 README 声称的技术点。**✅=代码已实现 ❌=未实现 ⚠️=部分实现**

| # | 技术点 | 状态 | 实现位置 / 说明 |
|---|---|---|---|
| 1 | 缓存穿透-空值缓存 | ✅ | `CacheClient.queryWithPassThrough`（注意：当前主链路未启用,属保留能力） |
| 2 | 缓存击穿-逻辑过期+互斥锁 | ✅ | `CacheClient.queryWithLogicalExpire`,`ShopServiceImpl.queryById` 启用中；互斥锁版在注释里 |
| 3 | 缓存一致性(先更库后删缓存) | ✅ | `ShopServiceImpl.update` |
| 4 | Redis+Lua 秒杀(库存String+已购Set) | ✅ | `seckill.lua` + `VoucherOrderServiceImpl.seckillVoucher` |
| 5 | MQ 异步下单 | ✅ | RabbitMQ:`QueueConfig`+`SeckillVoucherListener`（README 写 RocketMQ,不符） |
| 6 | 乐观锁防超卖 | ⚠️ | 库存扣减 `gt("stock",0)` 有；无支付/关单状态机 |
| 7 | 延迟队列/延迟关单 | ⚠️ | 只有 QA 的 TTL 10s→死信管道,且 QD 消费者是"重复落库"而非关单（风险4/5） |
| 8 | Redisson 分布式锁 | ✅ | `handleVoucherOrder` `lock:order:{userId}`（但主链路当前未走到,且该方法有 NPE 隐患,风险2） |
| 9 | 手写 SETNX+Lua 释放锁 | ✅ | `SimpleRedisLock`+`unLock.lua`（教学对照,未在主链路使用） |
| 10 | ZSet 点赞排行榜 | ✅ | `BlogServiceImpl.updateLike/queryBlogLikes` |
| 11 | Set 共同关注 | ✅ | `FollowServiceImpl.followCommons` |
| 12 | Feed 流推模式 | ✅ | `BlogServiceImpl` + `feed:` ZSet + ScrollResult 滚动分页 |
| 13 | GEO 附近商铺 | ✅ | `ShopServiceImpl.queryShopByType` |
| 14 | BitMap 签到 | ✅ | `UserServiceImpl` sign / 连续签到 |
| 15 | 全局唯一 ID(自增+时间戳) | ✅ | `RedisIdWorker` |
| 16 | Caffeine 本地二级缓存 | ❌ | 无依赖无代码（README 声称有） |
| 17 | 滑动窗口限流(ZSet+Lua+注解AOP) | ❌ | 完全没有 |
| 18 | HyperLogLog UV 统计 | ❌ | 完全没有 |
| 19 | RocketMQ | ❌ | 实际是 RabbitMQ |
| 20 | 定时对账/支付回调补单 | ❌ | README 提及,代码无 |

> 结论：**真正的核心资产是 1/2/3/4/5/10/11/12/13/14/15 这些已在主链路跑通的点**。16/17/18 按当前决定暂不实现，作为"后续可扩展项"记录在案。

---

## 5. 风险点与已知问题清单

改造前必须心里有数的问题（按严重程度排序）：

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | **README 与代码严重不符**（Boot 3.0/RocketMQ/Caffeine/压测数据均为声称，代码无） | README.md | 答辩/面试被追问会露馅，改造时重写 |
| 2 | **`proxy` 字段从未初始化**，`handleVoucherOrder()` 一旦被调用立刻 NPE（当前恰好无人调用它） | `VoucherOrderServiceImpl.java:178-202` | 定时炸弹；改造时应清理 |
| 3 | **seckill.lua 末尾 `XADD stream.orders` 残留**：每次成功秒杀都往 Redis Stream 写一条无人消费的消息 | `seckill.lua:37` | Redis 内存缓慢增长，且是旧方案痕迹 |
| 4 | **逻辑过期重建后删错 key**：重建线程 finally 里 `unLock(key)` 删除的是缓存 key 而不是锁 key（应为 `unLock(lockKey)`），导致刚重建的缓存被删掉、锁要等 10s 自然过期 | `CacheClient.java:113` | 缓存命中率异常，属于真 Bug |
| 5 | **死信队列消费者会重复落库**：`receivedD` 与 `receivedA` 做同样的 save+扣库存，若消息真进入 QD（消费者宕机超 10s）会重复下单、重复扣库存 | `SeckillVoucherListener.java:52-66` | 消费端无幂等保障 |
| 6 | **秒杀时间窗口校验缺失**：Lua 只判断库存/已购，起售/截止时间校验在注释掉的旧代码里 | `VoucherOrderServiceImpl.java:248-291` | 未开票也能抢 |
| 7 | **MySQL root 密码明文提交**（290390） | `application.yaml:15` | 安全问题；仓库公开可见 |
| 8 | **图片上传路径硬编码** `D:\tlias\nginx-1.18.0 dianping\html\hmdp\imgs` | `UploadController` | 换机器/部署必挂 |
| 9 | **Lua 资源名大小写不一致**：代码加载 `unlock.lua`，文件名是 `unLock.lua` | `SimpleRedisLock.java:26` | Windows 能跑，**Linux/Docker 部署找不到文件直接报错** |
| 10 | 驱动类名过旧 `com.mysql.jdbc.Driver` | `application.yaml:12` | 能跑但 8.x 正确写法是 `com.mysql.cj.jdbc.Driver` |
| 11 | 无前端、无任何部署文件（Dockerfile/compose/nginx 配置） | 全仓库 | 无法独立演示/部署，改造范围的一部分 |
| 12 | `VoucherOrderServiceImpl` 有无用/错误 import（`BasicBlock`、`Message` 等） | 同上 | 代码卫生 |

---

## 6. 改造方案草稿

> **状态：✅ 已实施完成（2026-10-09）。** 以下为最终落地的方案，实施记录见 6.7。

### 6.1 目标

- 新业务场景：**TicketHub 演出票务抢票平台**（类大麦/猫眼）——"秒杀"从附加功能变成核心业务（开票抢票），与简历高并发卖点最契合
- 当场可演示（新写简易前端）+ 可部署到服务器（Docker Compose 一键起）
- 交付到 https://github.com/HeiCat233/CityHub.git

### 6.2 存储方案

- 主存储保持 **MySQL**（你熟悉，迁移成本最低）
- 课程要求的**非关系型数据库由 Redis 满足**：本项目 Redis 承担缓存、秒杀资格判断、点赞榜、关注、Feed、GEO、签到、全局 ID 等 14 类数据结构，是典型 NoSQL 键值型数据库的深度应用，答辩话术现成

### 6.3 业务映射表（点评 → 票务）

| 原（点评） | 新（TicketHub） |
|---|---|
| tb_shop 商铺 | tb_show 演出（演唱会/话剧/音乐会/体育） |
| tb_shop_type 分类 | tb_show_category 演出分类 |
| tb_voucher + tb_seckill_voucher 优惠券秒杀 | tb_ticket_tier 票档(内场/看台) + tb_seckill_show 开票场次 |
| tb_voucher_order 券订单 | tb_ticket_order 购票订单(待支付/已支付/已关闭状态机) |
| tb_blog 探店笔记 | tb_post 演出动态/资讯（保留点赞/Feed 落点） |
| 附近商铺 GEO | 附近演出/同城场馆 GEO |
| tb_user / tb_follow / tb_sign | 保留（登录、关注艺人、观演日历签到） |

秒杀链路原样保留换皮：Lua 资格判断 → RabbitMQ 异步出票 → 乐观锁落库防超卖。

### 6.4 阶段计划（实施顺序）

1. **业务重构**：建 `db/tickethub.sql`（新表+演示数据）→ 实体/Mapper/Service/Controller/Lua/拦截器按映射表改写 → 清理第 5 节风险点中影响改造的部分（2/3/5/6/9/10）
2. **前端**：`frontend/` 新建 Vue3 + Vite + Element Plus（首页/演出详情/抢票页/订单页/个人中心/登录页 6 个页面；验证码直接显示在页面上方便演示）
3. **接口文档**：集成 Knife4j，答辩兜底
4. **部署**：后端 Dockerfile + `docker-compose.yml`（app + mysql + redis + rabbitmq）；前端 `npm run build` 产物上传 **OSS**；服务器用 **nginx 做 API 网关**（反向代理 / 限流 / SSL / 负载均衡），详见第 8 章
5. **推送**：分阶段 commit 到 main

### 6.5 答辩演示流程（草稿）

1. 首页浏览演出列表/分类/附近演出 → 2. 管理端"一键开票" → 3. 抢票页倒计时归零，双浏览器同时抢票，展示订单号生成且库存不超卖 → 4. 15 分钟（演示可调短）不支付票自动回流 → 5. 签到日历/关注 Feed → 6. Knife4j 文档 + 压测脚本收尾

### 6.6 后续可扩展项（本轮不实现，仅记录）

- Caffeine + Redis 二级缓存（演出详情热点）
- 滑动窗口限流（抢票接口 IP 防刷）
- HyperLogLog UV 统计
- 完整延迟关单（15 分钟未支付自动释放库存）

> 其中「消费幂等」已在本次改造中落地（消费者按订单号判重），见 6.7。

### 6.7 实施记录（2026-10-09 完成）

#### 已完成

| 项 | 内容 |
|---|---|
| 业务重构 | 点评 → 票务：演出/票档/库存/订单/动态/关注全部换皮，URL 前缀改为 `/show`、`/tier`、`/ticket-order`、`/post`、`/show-category` |
| 包名与坐标 | `com.hmdp` → `com.tickethub`，`hm-dianping` → `ticket-hub`（74 个源文件） |
| 数据库 | 新建 `src/main/resources/db/tickethub.sql`：11 张票务表 + 演示数据（6 场演出、13 个票档、6 个用户、5 条动态） |
| 抢票链路 | Lua 重写（移除旧方案的 `XADD stream.orders` 残留）；MQ 异步出票；乐观锁防超卖；**新增消费幂等**；**新增投递失败回滚 Redis 库存** |
| 缓存 | `CacheClient` 修复原「重建后删错 key」缺陷（释放锁改为 `unLock(lockKey)`） |
| 图片上传 | 新增 `OssUtil`：配置 OSS 走对象存储，未配置降级本地目录，**修复原硬编码本机路径问题** |
| 配置外置 | `application.yaml` 全部改为环境变量注入，**移除明文密码** |
| 拦截器 | 修复 TTL 不一致（30 分钟 → 10 小时）；**补充 ThreadLocal 清理**，避免线程复用串号 |
| 跨域 | `MvcConfig` 增加 CORS 配置，支持 OSS 静态站点访问后端 API |
| 库存预热 | 新增 `SeckillStockPreloader`，启动时把数据库库存同步到 Redis（仅在 Redis 无该 key 时写入） |
| 前端 | `frontend/` 全新 Vue 3 + Vite + Element Plus：首页 / 演出详情 / 抢票 / 订单 / 个人中心 / 登录 6 个页面 |
| 部署 | `Dockerfile`、`docker-compose.yml`、`deploy/nginx/tickethub.conf`、`deploy/.env.example` |
| 验证 | 后端编译打包通过；前端构建通过；接口链路（分类/票档/登录）验证通过；**并发测试 200 请求抢 50 张票 → 恰好 50 单成功、库存归零无超卖** |

#### 待办

- **RabbitMQ 环境验证**：本机未安装 RabbitMQ 且 Docker 守护进程未能启动，抢票接口的 MQ 投递/消费链路尚未在本机端到端跑通（代码已通过编译，逻辑与压测已验证）
- 缺失的 4 个技术点按约定暂不实现，列在 6.6

---

## 7. 本地运行指南

**环境**：JDK 8、Maven 3.6+、MySQL 8、Redis 6+、RabbitMQ（含 management）。

### 7.0 前端部署记录（✅ 已完成，2026-10-03）

| 项目 | 内容 |
|---|---|
| nginx 目录 | `D:\develop\nginx-1.18.0`（课程原版包，自带 hmdp 前端页面 + 已配好的 conf） |
| 备用官方版 | `D:\develop\nginx-1.24.0`（干净版，未配置，后续备用） |
| 前端页面 | `D:\develop\nginx-1.18.0\html\hmdp\`（13 个页面：首页/商铺详情/笔记/登录等） |
| 访问地址 | http://localhost:8080 （**需后端 8081 已启动**，否则页面打得开但接口 502） |
| 代理配置 | conf/nginx.conf：`location /` 托管静态页；`location /api` 去掉 /api 前缀后转发到 `127.0.0.1:8081` |
| 图片上传 | 后端硬编码写 `D:\tlias\nginx-1.18.0 dianping\html\hmdp\imgs`，已建 **目录联接**（junction）指向 `D:\develop\nginx-1.18.0\html\hmdp\imgs`——不改代码、不占空间，上传的图片前端立即可见 |

nginx 常用命令（在 `D:\develop\nginx-1.18.0` 下执行）：

```bash
./nginx.exe            # 启动（建议: cmd //c "start /min nginx.exe" 脱离终端运行）
./nginx.exe -s stop    # 停止
./nginx.exe -s reload  # 改 conf 后热重载
./nginx.exe -t         # 检查配置语法
```

> 注意：不要直接在前台终端 Ctrl+C 掉 nginx 进程；改了 `nginx.conf` 必须 `-s reload` 才生效。

```bash
# 1. MySQL：建库并导入（库名必须是 dingping）
mysql -uroot -p < src/main/resources/db/hmdp.sql

# 2. 修改 application.yaml 中你的 MySQL 密码

# 3. 启动 Redis(6379) 和 RabbitMQ(5672)

# 4. 启动应用
mvn spring-boot:run        # 端口 8081

# 5. 预热店铺缓存（重要!逻辑过期方案没有预热查不到数据）
#    跑 src/test/java/com/hmdp/ShopCacheTest.java

# 6. 接口调试（仓库无前端页面）
#    Postman/Apifox 手动调；验证码看后端 debug 日志：
#    log.info("短信验证码发送成功：{}", code)

# 7. 并发压测（验证不超卖/一人一单）
#    跑 VoucherOrderControllerTest.testMultiThreadSeckill
```

**常用演示命令**（redis-cli 观察）：

```bash
GET seckill:stock:10       # 某场秒杀的剩余库存
SMEMBERS seckill:order:10  # 已购用户集合
ZREVRANGE blog:liked:1 0 4 # 笔记点赞榜前5
GEOSEARCH shop:geo:1 FROMLONLAT 116.4 39.9 BYRADIUS 5000 m WITHDIST
```

---

## 8. 部署方案（生产环境）

> 目标：**静态资源走 OSS + CDN，后端走 API 服务器（nginx 做网关）**。
> 本机已有环境：阿里云服务器（**Ubuntu**，已购买）、阿里云 OSS Bucket（已有）。
> 本地演示环境（Windows + nginx 1.18.0）保持不动，断网也能答辩。

### 8.1 总体架构

```
                        用户浏览器
                            │
          ┌─────────────────┴──────────────────┐
          │                                    │
   静态资源请求                           API 请求
   (HTML/JS/CSS/图片)                    (/api/*)
          │                                    │
          ▼                                    ▼
   阿里云 OSS + CDN                     云服务器(Ubuntu)
   (静态网站托管)                            │
          │                          ┌───────┴────────┐
          │                          │  nginx :443    │  ← API 网关
          │                          │  反向代理/限流  │
          │                          │  SSL/负载均衡   │
          │                          └───────┬────────┘
          │                                  ▼
          │                          Spring Boot :8081
          │                                  │
          │                    ┌─────────────┼─────────────┐
          │                    ▼             ▼             ▼
          │                 MySQL 8       Redis 7      RabbitMQ
          │
          └── 上传图片也直传 OSS（替代原硬编码磁盘路径，解决风险 8）
```

**为什么这样分层**

| 层次 | 组件 | 理由 |
|---|---|---|
| 静态资源 | OSS + CDN | 学生机带宽通常仅 3–5Mbps，前端包 1–3MB 首屏要 3–8 秒；CDN 边缘节点秒开，答辩不翻车 |
| API 入口 | nginx | 反向代理、限流、SSL、负载均衡，练手点全保留，也是生产标准做法 |
| 应用 | Spring Boot | 抢票核心链路（Lua 秒杀 + MQ 异步 + 乐观锁） |
| 数据 | MySQL/Redis/RabbitMQ | 主存储 + 缓存/秒杀 + 异步队列 |
| 图片 | OSS | 顺带修掉 `UploadController` 硬编码本机路径的问题（风险 8） |

### 8.2 服务器准备（Ubuntu）

```bash
# 1. 更新系统
sudo apt update && sudo apt upgrade -y

# 2. 安装 Docker 与 Compose 插件
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER      # 重新登录后生效
docker --version && docker compose version

# 3. 安装 nginx（作为 API 网关，不用 Docker 跑，便于练手）
sudo apt install -y nginx
nginx -v

# 4. 防火墙：只放行 22 / 80 / 443（8081/3306/6379/5672 一律不对外）
sudo ufw allow 22 && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable
```

> 同时要在**阿里云控制台的安全组**里放行 22 / 80 / 443，两处都要开。

### 8.3 后端部署（Docker Compose）

后端连同 MySQL / Redis / RabbitMQ 用 Compose 一键起，`docker-compose.yml` 的编排要点：

```yaml
services:
  app:
    build: .
    ports: ["8081:8081"]        # 只给宿主机 nginx 用，不对外暴露
    depends_on: [mysql, redis, rabbitmq]
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/tickethub
      SPRING_REDIS_HOST: redis
      SPRING_RABBITMQ_HOST: rabbitmq
  mysql:  { image: mysql:8.0,  volumes: ["mysql-data:/var/lib/mysql"] }
  redis:  { image: redis:7,    volumes: ["redis-data:/data"] }
  rabbitmq:
    image: rabbitmq:3-management
    volumes: ["rabbitmq-data:/var/lib/rabbitmq"]
volumes: { mysql-data: {}, redis-data: {}, rabbitmq-data: {} }
```

关键点：

- **配置外置**：数据库密码、Redis 地址、OSS 密钥全部走环境变量，不要写进镜像（修掉风险 7 的明文密码）
- **数据卷持久化**：三个 volume 保证容器重建数据不丢
- **只暴露 8081 给宿主机**，由 nginx 转发；3306/6379/5672 只在 Docker 内网互通
- 启动：`docker compose up -d --build`，日志 `docker compose logs -f app`

### 8.4 nginx API 网关配置（重点练手部分）

`/etc/nginx/conf.d/tickethub.conf`：

```nginx
# 限流区：抢票接口按 IP 限速，10 次/秒，突发 20
limit_req_zone $binary_remote_addr zone=seckill:10m rate=10r/s;
# 全局接口限流：50 次/秒
limit_req_zone $binary_remote_addr zone=api:10m rate=50r/s;

upstream backend {
    server 127.0.0.1:8081 weight=1 max_fails=3 fail_timeout=10s;
    # server 127.0.0.1:8082 weight=1;   # 将来扩实例，负载均衡立即生效
    keepalive 32;
}

server {
    listen 80;
    server_name api.yourdomain.com;
    return 301 https://$host$request_uri;      # 强制 HTTPS
}

server {
    listen 443 ssl http2;
    server_name api.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/api.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.yourdomain.com/privkey.pem;

    client_max_body_size 10m;
    gzip on;
    gzip_types application/json text/css application/javascript;

    # 抢票接口：严格限流
    location /api/seckill/ {
        limit_req zone=seckill burst=20 nodelay;
        limit_req_status 429;
        rewrite ^/api(/.*)$ $1 break;
        proxy_pass http://backend;
        include /etc/nginx/proxy_params;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 其他接口：宽松限流
    location /api/ {
        limit_req zone=api burst=100 nodelay;
        rewrite ^/api(/.*)$ $1 break;
        proxy_pass http://backend;
        include /etc/nginx/proxy_params;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo nginx -t          # 改完必查语法
sudo systemctl reload nginx
```

**这一层能练到的 nginx 能力**

| 能力 | 对应配置 | 项目场景 |
|---|---|---|
| 反向代理 | `proxy_pass` + header 透传 | `/api` → 8081 |
| 负载均衡 | `upstream` + `weight` + `max_fails` | 起两个后端实例 |
| 限流 | `limit_req_zone` + `burst` + `nodelay` | 抢票接口防刷（与 Redis 滑动窗口形成两层防护） |
| HTTPS | 证书 + 80→443 跳转 | OSS 是 HTTPS，后端必须跟，否则混合内容被拦 |
| gzip / 超时 | `gzip`、`proxy_read_timeout` | 接口性能 |
| 日志 | `access_log` 自定义格式 | 喂给大数据模块做埋点分析 |

> `X-Real-IP` 这几行很关键：限流和访问日志要拿到**真实客户端 IP**，否则统计到的全是 nginx 的地址。

### 8.5 前端部署（OSS + CDN）

```bash
# 1. 构建（API 地址通过环境变量注入）
cd frontend
echo "VITE_API_BASE=https://api.yourdomain.com/api" > .env.production
npm run build                      # 产出 dist/

# 2. 上传到 OSS（ossutil 示例）
ossutil cp -r dist/ oss://your-bucket/ --update
```

OSS 控制台配置：

1. **静态网站托管**：默认首页 `index.html`；默认 404 页也填 `index.html`（Vue Router history 模式必需）
2. **跨域 CORS**：来源填 `https://your-bucket.oss-cn-xxx.aliyuncs.com`（或你的自定义域名），允许 `GET/POST/PUT/DELETE` 与 `authorization` 头
3. **CDN 加速**：绑定 CDN 域名 + 证书，回源指向 bucket
4. **图片目录**：新建 `imgs/` 前缀，开启公共读（或用签名 URL）

后端对应改造：

- 加 CORS 配置（`WebMvcConfigurer#addCorsMappings` 或 `@CrossOrigin`），否则浏览器直接拦截
- `UploadController` 改为**上传到 OSS**（引入 `aliyun-sdk-oss`），返回 OSS 图片 URL——彻底解决风险 8 的硬编码路径
- 前端 axios `baseURL` 读 `import.meta.env.VITE_API_BASE`

### 8.6 HTTPS 证书

用 Let's Encrypt 免费证书（certbot）：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.yourdomain.com
sudo certbot renew --dry-run     # 验证自动续期
```

> 若暂时不想买域名，可先用自签证书或直接 http 调试；但**OSS 页面是 HTTPS，正式演示前后端必须上 HTTPS**。

### 8.7 上线检查清单

- [ ] 安全组 / ufw 只开 22、80、443
- [ ] MySQL / Redis / RabbitMQ 端口未对公网暴露
- [ ] 数据库密码、OSS 密钥走环境变量，未提交到 Git
- [ ] `nginx -t` 通过，80 自动跳 443
- [ ] 后端 CORS 放行 OSS 域名
- [ ] 前端 `VITE_API_BASE` 指向 `https://api.域名/api`
- [ ] OSS 静态托管 + 404 回退 `index.html` 已配
- [ ] 秒杀接口 nginx 限流已生效（`ab`/`wrk` 压测验证 429）
- [ ] 全链路演示：OSS 打开首页 → 抢票 → 订单 → 支付

### 8.8 本地与线上双环境对照

| 能力 | 本地（演示/练手） | 线上（生产） |
|---|---|---|
| 静态资源 | nginx 直接托管 `html/` | OSS + CDN |
| API 入口 | nginx `proxy_pass` 8081 | nginx 网关 + SSL + 限流 |
| 后端 | `mvn spring-boot:run` | Docker Compose |
| 数据 | 本机 MySQL/Redis/RabbitMQ | 容器 + 数据卷 |
| 图片 | 本机磁盘（目录联接） | OSS |
| HTTPS | 无 | Let's Encrypt |

> 本地环境保留全套 nginx 练手（反向代理、负载均衡、限流、静态托管），线上用 OSS 剥离静态资源、nginx 只做网关——两边都不浪费。
