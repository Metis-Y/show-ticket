-- =====================================================
-- 演出抢票平台 show_ticket
-- 表结构与实体一一对应，使用 utf8mb4
-- =====================================================
CREATE DATABASE IF NOT EXISTS show_ticket DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE show_ticket;

DROP TABLE IF EXISTS tb_follow;
DROP TABLE IF EXISTS tb_review_comments;
DROP TABLE IF EXISTS tb_review;
DROP TABLE IF EXISTS tb_ticket_order;
DROP TABLE IF EXISTS tb_show_stock;
DROP TABLE IF EXISTS tb_show;
DROP TABLE IF EXISTS tb_show_type;
DROP TABLE IF EXISTS tb_venue;
DROP TABLE IF EXISTS tb_user_info;
DROP TABLE IF EXISTS tb_user;

-- ---------------------------
-- 用户
-- ---------------------------
CREATE TABLE tb_user (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    phone       VARCHAR(11)     NOT NULL COMMENT '手机号',
    password    VARCHAR(128)             DEFAULT '' COMMENT '密码',
    nick_name   VARCHAR(32)     NOT NULL COMMENT '昵称',
    icon        VARCHAR(255)             DEFAULT '' COMMENT '头像',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) COMMENT '用户';

CREATE TABLE tb_user_info (
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '主键，用户id',
    city        VARCHAR(64)              DEFAULT '' COMMENT '城市',
    introduce   VARCHAR(128)             DEFAULT '' COMMENT '个人介绍',
    fans        INT UNSIGNED             DEFAULT 0 COMMENT '粉丝数',
    followee    INT UNSIGNED             DEFAULT 0 COMMENT '关注数',
    gender      TINYINT(1)               DEFAULT 0 COMMENT '性别 0男 1女',
    birthday    DATE                              COMMENT '生日',
    credits     INT UNSIGNED             DEFAULT 0 COMMENT '积分',
    level       TINYINT(1)               DEFAULT 0 COMMENT '等级',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) COMMENT '用户详情';

-- ---------------------------
-- 场馆 / 演出分类 / 演出 / 票档
-- ---------------------------
CREATE TABLE tb_venue (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)     NOT NULL COMMENT '场馆名称',
    type_id     BIGINT UNSIGNED NOT NULL COMMENT '演出分类id',
    images      VARCHAR(1024)            DEFAULT '' COMMENT '场馆图片，多张逗号分隔',
    area        VARCHAR(64)              DEFAULT '' COMMENT '所在商圈',
    address     VARCHAR(255)             DEFAULT '' COMMENT '详细地址',
    x           DOUBLE                   DEFAULT NULL COMMENT '经度',
    y           DOUBLE                   DEFAULT NULL COMMENT '纬度',
    avg_price   BIGINT UNSIGNED          DEFAULT 0 COMMENT '平均票价（分）',
    sold        INT UNSIGNED             DEFAULT 0 COMMENT '累计售票数',
    comments    INT UNSIGNED             DEFAULT 0 COMMENT '评论数',
    score       INT UNSIGNED             DEFAULT 40 COMMENT '评分，1~50',
    open_hours  VARCHAR(32)              DEFAULT '' COMMENT '开放时间',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_type_id (type_id)
) COMMENT '场馆';

CREATE TABLE tb_show_type (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(32)     NOT NULL COMMENT '分类名称',
    icon        VARCHAR(255)             DEFAULT '' COMMENT '图标',
    sort        INT                      DEFAULT 0 COMMENT '排序',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) COMMENT '演出分类';

CREATE TABLE tb_show (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    venue_id     BIGINT UNSIGNED NOT NULL COMMENT '场馆id',
    title        VARCHAR(128)    NOT NULL COMMENT '演出标题',
    sub_title    VARCHAR(255)             DEFAULT '' COMMENT '副标题',
    rules        VARCHAR(1024)            DEFAULT '' COMMENT '购票须知',
    pay_value    BIGINT UNSIGNED          DEFAULT 0 COMMENT '票价（分）',
    actual_value BIGINT UNSIGNED          DEFAULT 0 COMMENT '原价（分）',
    type         TINYINT UNSIGNED         DEFAULT 0 COMMENT '票档类型',
    status       TINYINT UNSIGNED         DEFAULT 1 COMMENT '状态 1上架 0下架',
    create_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_venue_id (venue_id)
) COMMENT '演出';

CREATE TABLE tb_show_stock (
    show_id     BIGINT UNSIGNED NOT NULL COMMENT '演出id',
    stock       INT                      DEFAULT 0 COMMENT '剩余票数',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    begin_time  DATETIME                 DEFAULT NULL COMMENT '开售时间',
    end_time    DATETIME                 DEFAULT NULL COMMENT '停售时间',
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (show_id)
) COMMENT '演出票档';

-- ---------------------------
-- 订单
-- ---------------------------
CREATE TABLE tb_ticket_order (
    id          BIGINT UNSIGNED NOT NULL COMMENT '订单id（Redis 全局唯一ID）',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '下单用户id',
    show_id     BIGINT UNSIGNED NOT NULL COMMENT '演出id',
    pay_type    TINYINT UNSIGNED         DEFAULT NULL COMMENT '支付方式 1余额 2支付宝 3微信',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1未支付 2已支付 3已使用 4已取消 5退款中 6已退款',
    create_time DATETIME        NOT NULL COMMENT '下单时间',
    pay_time    DATETIME                 DEFAULT NULL COMMENT '支付时间',
    use_time    DATETIME                 DEFAULT NULL COMMENT '入场时间',
    refund_time DATETIME                 DEFAULT NULL COMMENT '退款时间',
    update_time DATETIME                 DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_show_id (show_id),
    KEY idx_status_create (status, create_time)
) COMMENT '购票订单';

-- ---------------------------
-- 剧评 / 评论 / 关注
-- ---------------------------
CREATE TABLE tb_review (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    venue_id    BIGINT UNSIGNED NOT NULL COMMENT '场馆id',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '作者id',
    title       VARCHAR(255)             DEFAULT '' COMMENT '标题',
    images      VARCHAR(2048)            DEFAULT '' COMMENT '图片，多张逗号分隔',
    content     VARCHAR(2048)            DEFAULT '' COMMENT '正文',
    liked       INT UNSIGNED             DEFAULT 0 COMMENT '点赞数',
    comments    INT UNSIGNED             DEFAULT 0 COMMENT '评论数',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_venue_id (venue_id),
    KEY idx_user_id (user_id)
) COMMENT '观演剧评';

CREATE TABLE tb_review_comments (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '评论用户id',
    review_id   BIGINT UNSIGNED NOT NULL COMMENT '剧评id',
    parent_id   BIGINT UNSIGNED          DEFAULT 0 COMMENT '一级评论id',
    answer_id   BIGINT UNSIGNED          DEFAULT 0 COMMENT '回复的评论id',
    content     VARCHAR(512)    NOT NULL COMMENT '内容',
    liked       INT UNSIGNED             DEFAULT 0 COMMENT '点赞数',
    status      TINYINT(1)               DEFAULT 0 COMMENT '0正常 1被举报 2禁止查看',
    create_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_review_id (review_id)
) COMMENT '剧评评论';

CREATE TABLE tb_follow (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id        BIGINT UNSIGNED NOT NULL COMMENT '关注者id',
    follow_user_id BIGINT UNSIGNED NOT NULL COMMENT '被关注者id',
    create_time    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_follow (user_id, follow_user_id)
) COMMENT '关注关系';

-- =====================================================
-- 种子数据
-- =====================================================
INSERT INTO tb_user (id, phone, nick_name, icon) VALUES
(1, '13800000001', '阿云', '/imgs/icons/1.jpg'),
(2, '13800000002', '小满', '/imgs/icons/2.jpg'),
(3, '13800000003', '老陈', '/imgs/icons/3.jpg'),
(4, '13800000004', '木木', '/imgs/icons/4.jpg'),
(5, '13800000005', '大鱼', '/imgs/icons/5.jpg'),
(6, '13800000006', '阿KEN', '/imgs/icons/6.jpg');

INSERT INTO tb_user_info (user_id, city, introduce, fans, followee, gender, credits, level) VALUES
(1, '上海', '一年看 50 场演出的非职业观众', 4, 0, 0, 1200, 1),
(2, '上海', '音乐剧入坑三年', 0, 1, 1, 600, 1),
(3, '上海', '话剧、舞剧都看', 0, 1, 0, 300, 0),
(4, '苏州', '周末特种兵，专程来沪看演出', 0, 1, 1, 150, 0),
(5, '上海', '音乐节选手', 1, 1, 0, 800, 1),
(6, '杭州', '看展散步爱好者', 0, 0, 1, 100, 0);

INSERT INTO tb_show_type (id, name, icon, sort) VALUES
(1, '演唱会', '/imgs/types/concert.png', 1),
(2, '话剧歌剧', '/imgs/types/drama.png', 2),
(3, '音乐节', '/imgs/types/festival.png', 3),
(4, '展览', '/imgs/types/exhibition.png', 4),
(5, '体育赛事', '/imgs/types/sports.png', 5);

INSERT INTO tb_venue (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours) VALUES
(1, '梅赛德斯-奔驰文化中心', 1, '/imgs/venues/1.jpg', '世博园区', '浦东新区世博大道1200号', 121.487, 31.187, 62000, 128000, 8600, 48, '10:00-22:00'),
(2, '上海体育场', 1, '/imgs/venues/2.jpg', '徐家汇', '徐汇区天钥桥路666号', 121.437, 31.183, 58000, 215000, 12000, 47, '09:00-23:00'),
(3, '上海大剧院', 2, '/imgs/venues/3.jpg', '人民广场', '黄浦区人民大道300号', 121.473, 31.232, 38000, 96000, 5200, 49, '10:00-21:30'),
(4, '上海文化广场', 2, '/imgs/venues/4.jpg', '陕西南路', '黄浦区复兴中路597号', 121.466, 31.221, 42000, 88000, 6100, 48, '10:00-22:00'),
(5, '上海东方艺术中心', 2, '/imgs/venues/5.jpg', '浦东花木', '浦东新区丁香路425号', 121.535, 31.226, 35000, 72000, 4300, 47, '10:00-21:00'),
(6, '森兰绿地', 3, '/imgs/venues/6.jpg', '外高桥', '浦东新区洲海路森兰绿地', 121.607, 31.315, 38000, 156000, 9800, 45, '12:00-23:00'),
(7, '世博公园', 3, '/imgs/venues/7.jpg', '世博园区', '浦东新区世博大道', 121.490, 31.186, 32000, 132000, 7600, 46, '12:00-22:30'),
(8, '上海博物馆东馆', 4, '/imgs/venues/8.jpg', '浦东花木', '浦东新区世纪大道1952号', 121.556, 31.226, 12800, 240000, 15000, 49, '09:00-17:00'),
(9, '西岸美术馆', 4, '/imgs/venues/9.jpg', '徐汇滨江', '徐汇区龙腾大道2600号', 121.464, 31.180, 15000, 92000, 4800, 47, '10:00-18:00'),
(10, '上海体育馆', 5, '/imgs/venues/10.jpg', '徐家汇', '徐汇区漕溪北路1111号', 121.437, 31.183, 22000, 168000, 8900, 46, '09:00-22:00'),
(11, '虹口足球场', 5, '/imgs/venues/11.jpg', '虹口足球场', '虹口区东江湾路444号', 121.478, 31.270, 18000, 198000, 10500, 45, '09:00-22:30'),
(12, '上海交响音乐厅', 2, '/imgs/venues/12.jpg', '复兴中路', '徐汇区复兴中路1380号', 121.457, 31.213, 46000, 64000, 3900, 48, '10:00-21:30');

INSERT INTO tb_show (id, venue_id, title, sub_title, rules, pay_value, actual_value, type, status) VALUES
(1, 1, '李荣浩「纵横四海」巡回演唱会·上海站', '6 月 20 日 19:30 全场次', '一人一票，凭身份证入场；不支持退换', 48000, 68000, 1, 1),
(2, 2, '五月天「回到那一天」25周年巡回演唱会', '6 月 28 日 19:00 内场', '一人一票，实名制入场；不支持退换', 68000, 88000, 1, 1),
(3, 3, '莎士比亚经典话剧《哈姆雷特》', '6 月 15 日 19:30 一楼池座', '1.2 米以下儿童谢绝入场；演出中禁止摄影', 28000, 38000, 2, 1),
(4, 4, '百老汇音乐剧《剧院魅影》中文版', '6 月 18 日 19:15 一楼', '一人一票，凭票入场；不支持退换', 58000, 88000, 2, 1),
(5, 5, '上海交响乐团 2026 新年音乐会', '12 月 31 日 19:30 池座', '一人一票，建议正装入场', 88000, 128000, 2, 1),
(6, 6, '2026 上海草莓音乐节 单日票', '6 月 21 日 单日票', '禁止携带瓶装水及自拍杆；儿童需购买全票', 38000, 48000, 3, 1),
(7, 7, '简单生活节·城市限定', '6 月 22 日 单日票', '实名制购票，刷身份证入场', 32000, 42000, 3, 1),
(8, 8, '古埃及文明大展·上海', '全展期通票', '一人一票，展馆内禁止饮食', 12800, 16800, 4, 1),
(9, 10, 'CBA 常规赛 上海久事 vs 辽宁本钢', '7 月 3 日 19:35 东看台', '一人一票，按区入座', 18000, 28000, 5, 1),
(10, 11, '中超 上海海港 vs 山东泰山', '7 月 8 日 19:35 主看台', '一人一票，禁止携带专业摄影设备', 12000, 20000, 5, 1);

-- 票档：前 8 场正在售卖，第 9 场 3 天后才开票，用于演示「还未开票」
INSERT INTO tb_show_stock (show_id, stock, begin_time, end_time) VALUES
(1, 50, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(2, 100, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(3, 200, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(4, 300, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(5, 400, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(6, 500, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(7, 300, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(8, 800, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(9, 600, DATE_ADD(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY)),
(10, 700, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY));

INSERT INTO tb_review (id, venue_id, user_id, title, images, content, liked, comments) VALUES
(1, 1, 2, '梅奔的声场是真的顶', '/imgs/reviews/1.jpg', '内场第三排，灯牌和应援色全开的时候鸡皮疙瘩起来了。散场地铁 8 号线人有点多，建议提前 10 分钟走。', 12, 2),
(2, 4, 3, '《剧院魅影》中文版值回票价', '/imgs/reviews/2.jpg', '舞台机械和吊灯下落的瞬间全场鼓掌，译配比想象中自然。二楼前排视线无遮挡，性价比高。', 8, 1),
(3, 3, 4, '哈姆雷特三小时不觉得长', '/imgs/reviews/3.jpg', '台词功底扎实，中场休息 20 分钟。池座偏左侧会挡一部分调度，尽量选中间。', 5, 0),
(4, 6, 5, '草莓音乐节避坑指南', '/imgs/reviews/4.jpg', '下午四点后进场最舒服，记得带防晒和便携凳。散场打车排队一小时，建议坐接驳车。', 21, 3),
(5, 8, 6, '古埃及展要留足三小时', '/imgs/reviews/5.jpg', '预约制，人不多。木乃伊展厅禁止拍照，其他区域可以。文创店排队比较久。', 7, 0),
(6, 5, 2, '新年音乐会仪式感拉满', '/imgs/reviews/6.jpg', '返场加了《拉德茨基进行曲》，全场打拍子。建议提前 30 分钟到，附近不好停车。', 9, 1);

INSERT INTO tb_review_comments (user_id, review_id, parent_id, answer_id, content, liked, status) VALUES
(1, 1, 0, 0, '同场！安可那首直接哭出来', 2, 0),
(5, 1, 0, 0, '内场票价多少收的？', 0, 0),
(1, 2, 0, 0, '译配确实比预期好，二楼视野也够', 1, 0),
(3, 4, 0, 0, '接驳车排队也长，还是地铁靠谱', 3, 0),
(6, 4, 0, 0, '谢谢避坑，下周就去', 0, 0),
(1, 6, 0, 0, '拉德茨基那下太感动了', 1, 0);

INSERT INTO tb_follow (user_id, follow_user_id) VALUES
(2, 1), (3, 1), (4, 1), (5, 2);
