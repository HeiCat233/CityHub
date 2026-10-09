-- ============================================================
-- TicketHub 演出票务抢票平台 - 数据库脚本
-- MySQL 8.0 / utf8mb4
-- 包含：建库、建表、演示数据
-- ============================================================

CREATE DATABASE IF NOT EXISTS `tickethub` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `tickethub`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 用户表
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone`       varchar(11)  NOT NULL COMMENT '手机号码',
  `password`    varchar(128) NULL     COMMENT '密码，加密存储',
  `nick_name`   varchar(32)  NOT NULL COMMENT '昵称',
  `icon`        varchar(255) NOT NULL DEFAULT '' COMMENT '头像',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniqe_phone` (`phone`)
) ENGINE = InnoDB COMMENT = '用户表';

-- ----------------------------
-- 用户详情表
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info` (
  `user_id`     bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city`        varchar(64)  NOT NULL DEFAULT '' COMMENT '城市名称',
  `introduce`   varchar(128) NOT NULL DEFAULT '' COMMENT '个人介绍',
  `fans`        int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '粉丝数量',
  `followee`    int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '关注的人的数量',
  `gender`      tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday`    date         NULL COMMENT '生日',
  `credits`     int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '积分',
  `level`       tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '会员级别',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE = InnoDB COMMENT = '用户详情表';

-- ----------------------------
-- 演出分类表
-- ----------------------------
DROP TABLE IF EXISTS `tb_show_category`;
CREATE TABLE `tb_show_category` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        varchar(32)  NOT NULL COMMENT '分类名称',
  `icon`        varchar(255) NOT NULL DEFAULT '' COMMENT '图标',
  `sort`        int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '顺序',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '演出分类表';

-- ----------------------------
-- 演出表
-- ----------------------------
DROP TABLE IF EXISTS `tb_show`;
CREATE TABLE `tb_show` (
  `id`              bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`            varchar(128) NOT NULL COMMENT '演出名称',
  `category_id`     bigint(20) UNSIGNED NOT NULL COMMENT '演出分类id',
  `venue_name`      varchar(128) NOT NULL COMMENT '场馆名称',
  `venue_address`   varchar(255) NOT NULL DEFAULT '' COMMENT '场馆地址',
  `city`            varchar(32)  NOT NULL DEFAULT '' COMMENT '城市',
  `x`               double       NOT NULL COMMENT '经度',
  `y`               double       NOT NULL COMMENT '纬度',
  `images`          varchar(1024) NOT NULL DEFAULT '' COMMENT '演出海报，多张以逗号分隔',
  `description`     varchar(2048) NOT NULL DEFAULT '' COMMENT '演出介绍',
  `duration`        int(8) UNSIGNED NOT NULL DEFAULT 120 COMMENT '演出时长（分钟）',
  `begin_time`      datetime     NOT NULL COMMENT '演出开始时间',
  `sale_begin_time` datetime     NOT NULL COMMENT '开票时间',
  `sale_end_time`   datetime     NOT NULL COMMENT '停售时间',
  `min_price`       bigint(10) UNSIGNED NOT NULL DEFAULT 0 COMMENT '最低票价（分）',
  `sold`            int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '已售数量',
  `score`           int(4) UNSIGNED NOT NULL DEFAULT 50 COMMENT '评分，乘10保存',
  `comments`        int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量',
  `status`          tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：1 未开票，2 售票中，3 已售罄，4 已结束',
  `create_time`     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_city` (`city`)
) ENGINE = InnoDB COMMENT = '演出表';

-- ----------------------------
-- 票档表
-- ----------------------------
DROP TABLE IF EXISTS `tb_ticket_tier`;
CREATE TABLE `tb_ticket_tier` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `show_id`     bigint(20) UNSIGNED NOT NULL COMMENT '演出id',
  `name`        varchar(64)  NOT NULL COMMENT '票档名称，如 内场VIP / 看台A区',
  `price`       bigint(10) UNSIGNED NOT NULL COMMENT '票价（分）',
  `rules`       varchar(512) NOT NULL DEFAULT '' COMMENT '购票须知',
  `type`        tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '类型：1 普通票档，2 秒杀票档',
  `status`      tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：1 正常，2 下架',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_show` (`show_id`)
) ENGINE = InnoDB COMMENT = '票档表';

-- ----------------------------
-- 票档库存表（开票场次，一对一挂票档）
-- ----------------------------
DROP TABLE IF EXISTS `tb_tier_stock`;
CREATE TABLE `tb_tier_stock` (
  `tier_id`     bigint(20) UNSIGNED NOT NULL COMMENT '票档id',
  `show_id`     bigint(20) UNSIGNED NOT NULL COMMENT '演出id',
  `stock`       int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '剩余库存',
  `begin_time`  datetime     NOT NULL COMMENT '开票开始时间',
  `end_time`    datetime     NOT NULL COMMENT '开票结束时间',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tier_id`),
  KEY `idx_show` (`show_id`)
) ENGINE = InnoDB COMMENT = '票档库存表';

-- ----------------------------
-- 购票订单表
-- ----------------------------
DROP TABLE IF EXISTS `tb_ticket_order`;
CREATE TABLE `tb_ticket_order` (
  `id`          bigint(20) UNSIGNED NOT NULL COMMENT '订单id（全局唯一ID）',
  `user_id`     bigint(20) UNSIGNED NOT NULL COMMENT '下单用户id',
  `show_id`     bigint(20) UNSIGNED NOT NULL COMMENT '演出id',
  `tier_id`     bigint(20) UNSIGNED NOT NULL COMMENT '票档id',
  `price`       bigint(10) UNSIGNED NOT NULL DEFAULT 0 COMMENT '成交单价（分）',
  `quantity`    int(4) UNSIGNED NOT NULL DEFAULT 1 COMMENT '购买数量',
  `pay_type`    tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '支付方式：0 未支付，1 余额，2 支付宝，3 微信',
  `status`      tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态：1 待支付，2 已支付，3 已出票，4 已取消，5 退款中，6 已退款',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time`    timestamp    NULL COMMENT '支付时间',
  `close_time`  timestamp    NULL COMMENT '关单时间',
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_tier` (`tier_id`)
) ENGINE = InnoDB COMMENT = '购票订单表';

-- ----------------------------
-- 演出动态表
-- ----------------------------
DROP TABLE IF EXISTS `tb_post`;
CREATE TABLE `tb_post` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `show_id`     bigint(20) UNSIGNED NOT NULL COMMENT '演出id',
  `user_id`     bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title`       varchar(255) NOT NULL DEFAULT '' COMMENT '标题',
  `images`      varchar(2048) NOT NULL DEFAULT '' COMMENT '图片，多张以逗号分隔',
  `content`     varchar(2048) NOT NULL DEFAULT '' COMMENT '正文',
  `liked`       int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数量',
  `comments`    int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_show` (`show_id`),
  KEY `idx_user` (`user_id`)
) ENGINE = InnoDB COMMENT = '演出动态表';

-- ----------------------------
-- 动态评论表
-- ----------------------------
DROP TABLE IF EXISTS `tb_post_comment`;
CREATE TABLE `tb_post_comment` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `post_id`     bigint(20) UNSIGNED NOT NULL COMMENT '动态id',
  `parent_id`   bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '一级评论id，0 表示一级评论',
  `answer_id`   bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '回复的评论id',
  `content`     varchar(2048) NOT NULL DEFAULT '' COMMENT '回复内容',
  `liked`       int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数',
  `status`      tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0 正常，1 被举报，2 禁止查看',
  `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_post` (`post_id`)
) ENGINE = InnoDB COMMENT = '动态评论表';

-- ----------------------------
-- 关注表
-- ----------------------------
DROP TABLE IF EXISTS `tb_follow`;
CREATE TABLE `tb_follow` (
  `id`             bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time`    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_follow` (`user_id`, `follow_user_id`)
) ENGINE = InnoDB COMMENT = '关注表';

-- ----------------------------
-- 签到表
-- ----------------------------
DROP TABLE IF EXISTS `tb_sign`;
CREATE TABLE `tb_sign` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `year`        year         NOT NULL COMMENT '签到的年',
  `month`       tinyint(2) UNSIGNED NOT NULL COMMENT '签到的月',
  `date`        date         NOT NULL COMMENT '签到日期',
  `is_backup`   tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否补签',
  PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '签到表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 演示数据
-- ============================================================

-- 演出分类
INSERT INTO `tb_show_category` (`id`, `name`, `icon`, `sort`) VALUES
(1, '演唱会', '/imgs/types/concert.png', 1),
(2, '音乐节', '/imgs/types/festival.png', 2),
(3, '话剧歌剧', '/imgs/types/drama.png', 3),
(4, '音乐会', '/imgs/types/orchestra.png', 4),
(5, '体育赛事', '/imgs/types/sports.png', 5),
(6, '相声曲艺', '/imgs/types/crosstalk.png', 6);

-- 演出（sale_begin_time 设为未来，演示"一键开票"；x/y 用各城市真实经纬度，供 GEO 查询）
INSERT INTO `tb_show`
(`id`, `name`, `category_id`, `venue_name`, `venue_address`, `city`, `x`, `y`, `images`, `description`, `duration`,
 `begin_time`, `sale_begin_time`, `sale_end_time`, `min_price`, `sold`, `score`, `comments`, `status`) VALUES
(1, '周杰伦「嘉年华」世界巡回演唱会·上海站', 1, '上海体育场', '上海市徐汇区天钥桥路666号', '上海',
 121.4376, 31.1835, '/imgs/shows/jay.jpg', '华语乐坛天王周杰伦的世界巡回演唱会，全新舞台设计，经典金曲全场大合唱。', 180,
 DATE_ADD(NOW(), INTERVAL 30 DAY), DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 3 DAY), 68000, 0, 49, 128, 1),
(2, '五月天「诺亚方舟」演唱会·北京站', 1, '国家体育场（鸟巢）', '北京市朝阳区国家体育场南路1号', '北京',
 116.3975, 39.9930, '/imgs/shows/mayday.jpg', '五月天时隔三年重返鸟巢，诺亚方舟十周年纪念巡演。', 200,
 DATE_ADD(NOW(), INTERVAL 45 DAY), DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 3 DAY), 55500, 0, 48, 96, 1),
(3, '草莓音乐节·杭州站', 2, '杭州白马湖国际会展中心', '杭州市滨江区长江南路336号', '杭州',
 120.2097, 30.1842, '/imgs/shows/strawberry.jpg', '两天两夜，三十组乐队，草莓音乐节首次落地杭州。', 480,
 DATE_ADD(NOW(), INTERVAL 20 DAY), DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 2 DAY), 32000, 0, 47, 54, 1),
(4, '话剧《暗恋桃花源》·广州站', 3, '广州大剧院', '广州市天河区珠江新城珠江西路1号', '广州',
 113.3230, 23.1180, '/imgs/shows/peach.jpg', '赖声川经典话剧，三十余年常演不衰，悲喜交织的舞台经典。', 150,
 DATE_ADD(NOW(), INTERVAL 15 DAY), DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 2 DAY), 18000, 0, 50, 42, 1),
(5, '维也纳爱乐乐团新年音乐会·深圳站', 4, '深圳音乐厅', '深圳市福田区福中一路2016号', '深圳',
 114.0550, 22.5470, '/imgs/shows/vienna.jpg', '世界顶级交响乐团，奏响新年序曲。', 120,
 DATE_ADD(NOW(), INTERVAL 60 DAY), DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 5 DAY), 88000, 0, 50, 31, 1),
(6, 'CBA常规赛 广东 vs 辽宁', 5, '东莞篮球中心', '东莞市南城区宏远路1号', '东莞',
 113.7890, 22.9760, '/imgs/shows/cba.jpg', 'CBA 焦点之战，广东宏远主场迎战辽宁本钢。', 120,
 DATE_ADD(NOW(), INTERVAL 10 DAY), DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 2 DAY), 28000, 0, 46, 88, 1);

-- 票档（每个演出 2~3 档）
INSERT INTO `tb_ticket_tier` (`id`, `show_id`, `name`, `price`, `rules`, `type`, `status`) VALUES
(1, 1, '内场VIP', 188000, '一人一票，凭票入场，不支持退换', 2, 1),
(2, 1, '看台A区', 98000, '一人一票，凭票入场，不支持退换', 2, 1),
(3, 1, '看台B区', 68000, '一人一票，凭票入场，不支持退换', 2, 1),
(4, 2, '内场摇滚区', 135500, '一人一票，凭票入场', 2, 1),
(5, 2, '看台', 55500, '一人一票，凭票入场', 2, 1),
(6, 3, '两日通票', 68000, '两日通票，含露营区', 2, 1),
(7, 3, '单日票', 32000, '单日票，仅限当日使用', 2, 1),
(8, 4, '一楼池座', 38000, '一人一票，演出中途不可退场', 2, 1),
(9, 4, '二楼楼座', 18000, '一人一票，演出中途不可退场', 2, 1),
(10, 5, 'VIP席', 168000, '含酒会入场资格', 2, 1),
(11, 5, '普通席', 88000, '一人一票', 2, 1),
(12, 6, '场边席', 58000, '一人一票', 2, 1),
(13, 6, '看台席', 28000, '一人一票', 2, 1);

-- 票档库存（begin_time 用 NOW() 保证开票即抢，演示方便）
INSERT INTO `tb_tier_stock` (`tier_id`, `show_id`, `stock`, `begin_time`, `end_time`) VALUES
(1, 1, 500, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY)),
(2, 1, 3000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY)),
(3, 1, 5000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY)),
(4, 2, 800, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY)),
(5, 2, 6000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY)),
(6, 3, 2000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
(7, 3, 8000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
(8, 4, 600, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
(9, 4, 1200, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
(10, 5, 300, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY)),
(11, 5, 1500, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 5 DAY)),
(12, 6, 2000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
(13, 6, 8000, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY));

-- 演示用户（密码均为 123456，明文仅用于本地演示）
INSERT INTO `tb_user` (`id`, `phone`, `password`, `nick_name`, `icon`) VALUES
(1, '13800000001', '123456', '乐迷小张', '/imgs/icons/1.png'),
(2, '13800000002', '123456', '追星少女', '/imgs/icons/2.png'),
(3, '13800000003', '123456', '古典爱好者', '/imgs/icons/3.png'),
(4, '13800000004', '123456', '话剧迷', '/imgs/icons/4.png'),
(5, '13800000005', '123456', '球迷老李', '/imgs/icons/5.png'),
(6, '13800000006', '123456', '音乐节常客', '/imgs/icons/6.png');

INSERT INTO `tb_user_info` (`user_id`, `city`, `introduce`, `fans`, `followee`, `credits`, `level`) VALUES
(1, '上海', '周杰伦十年老粉', 12, 3, 120, 1),
(2, '北京', '五月天永远的神', 30, 8, 300, 2),
(3, '深圳', '古典音乐爱好者', 5, 2, 60, 1),
(4, '广州', '话剧重度患者', 8, 4, 90, 1),
(5, '东莞', 'CBA 现场见证者', 3, 1, 40, 0),
(6, '杭州', '一年五场音乐节', 20, 6, 200, 2);

-- 演出动态
INSERT INTO `tb_post` (`id`, `show_id`, `user_id`, `title`, `images`, `content`, `liked`, `comments`) VALUES
(1, 1, 1, '周杰伦上海站抢票攻略', '/imgs/posts/1.jpg', '内场VIP 一定要卡点抢，提前把收货信息填好，实测 3 秒内下单成功。', 128, 12),
(2, 2, 2, '五月天鸟巢现场有多燃', '/imgs/posts/2.jpg', '去年去了鸟巢，全场大合唱《倔强》的时候真的泪目，今年一定要再去。', 256, 30),
(3, 3, 6, '草莓音乐节杭州站阵容分析', '/imgs/posts/3.jpg', '今年的阵容比去年强太多，两日通票性价比很高，建议直接买通票。', 88, 6),
(4, 4, 4, '暗恋桃花源观剧感受', '/imgs/posts/4.jpg', '第三次看这部话剧了，每次都能看到新的细节，赖声川真的是大师。', 64, 4),
(5, 5, 3, '维也纳爱乐新年音乐会值不值', '/imgs/posts/5.jpg', '票价确实贵，但现场听交响乐的感觉和耳机里完全是两回事，值。', 45, 3);

-- 关注关系
INSERT INTO `tb_follow` (`user_id`, `follow_user_id`) VALUES
(1, 2), (1, 6), (2, 1), (2, 6), (3, 1), (4, 1), (5, 1), (6, 1), (6, 2);

-- 预置订单（演示订单页有数据）
INSERT INTO `tb_ticket_order` (`id`, `user_id`, `show_id`, `tier_id`, `price`, `quantity`, `pay_type`, `status`, `create_time`, `pay_time`) VALUES
(1000000000000000001, 2, 2, 5, 55500, 1, 2, 2, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(1000000000000000002, 4, 4, 9, 18000, 2, 3, 2, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY));
