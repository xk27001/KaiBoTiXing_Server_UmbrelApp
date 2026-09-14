-- 抖音主播开播监控 - 建表脚本（首次启动自动执行，幂等）

CREATE TABLE IF NOT EXISTS anchor (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    nickname      VARCHAR(100)  NOT NULL COMMENT '主播昵称',
    douyin_id     VARCHAR(100)  NOT NULL COMMENT '抖音号/主页标识',
    web_rid       VARCHAR(100)  DEFAULT NULL COMMENT '直播间房间号(web_rid)',
    home_url      VARCHAR(500)  DEFAULT NULL COMMENT '主页URL',
    remark        VARCHAR(500)  DEFAULT NULL COMMENT '备注',
    enabled       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用监控 1启用 0停用',
    last_status   VARCHAR(20)   DEFAULT 'UNKNOWN' COMMENT '最新直播状态 LIVE/OFFLINE/UNKNOWN',
    last_check_time DATETIME    DEFAULT NULL COMMENT '最近检测时间',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_douyin_id (douyin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主播表';

CREATE TABLE IF NOT EXISTS monitor_config (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    cfg_key       VARCHAR(100)  NOT NULL COMMENT '配置键',
    cfg_value     VARCHAR(500)  DEFAULT NULL COMMENT '配置值',
    description   VARCHAR(500)  DEFAULT NULL COMMENT '配置说明',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cfg_key (cfg_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控配置表';

CREATE TABLE IF NOT EXISTS anchor_live_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    anchor_id       BIGINT        NOT NULL COMMENT '主播ID',
    nickname        VARCHAR(100)  DEFAULT NULL COMMENT '主播昵称快照',
    start_time      DATETIME      NOT NULL COMMENT '开播时间',
    end_time        DATETIME      DEFAULT NULL COMMENT '关播时间',
    duration_seconds BIGINT       DEFAULT NULL COMMENT '开播时长(秒)',
    KEY idx_anchor_start (anchor_id, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主播开播会话记录表';

CREATE TABLE IF NOT EXISTS monitor_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    level         VARCHAR(20)   NOT NULL DEFAULT 'INFO' COMMENT '日志级别 INFO/WARN/ERROR',
    source        VARCHAR(100)  DEFAULT NULL COMMENT '来源模块',
    message       TEXT          COMMENT '日志内容',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_level_time (level, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务日志表';

-- 初始配置
INSERT IGNORE INTO monitor_config (cfg_key, cfg_value, description) VALUES
('monitor.interval.seconds', '30', '爬取间隔（秒）'),
('monitor.thread.pool.size', '5', '并发爬取线程数'),
('monitor.enabled', '0', '监控总开关 1启用 0停止'),
('alert.enabled', '1', '弹窗提醒开关 1开启 0关闭'),
('log.enabled', '1', '数据库日志记录开关 1开启 0关闭');

-- 删除已废弃的爬取结果历史表（不再使用）
DROP TABLE IF EXISTS monitor_record;
