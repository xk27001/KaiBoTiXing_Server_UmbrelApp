package com.kaibotixing.crawler;

import com.kaibotixing.model.LiveStatus;

/**
 * 单次爬取结果。
 *
 * @param status            本次爬取判定出的直播状态
 * @param roomId            直播间动态 ID（room.id_str），用于数据库展示
 * @param errorMsg          失败原因；非空时 status 应为 UNKNOWN
 * @param discoveredWebRid  本次从主播主页反查到的直播间房间号（web_rid），
 *                          若非空，调度器应写回数据库，下次爬取将直接走直播间页面，避开主页反爬
 */
public record CrawlResult(LiveStatus status, String roomId, String errorMsg, String discoveredWebRid) {

    public static CrawlResult of(LiveStatus status, String roomId) {
        return new CrawlResult(status, roomId, null, null);
    }

    public static CrawlResult error(String errorMsg) {
        return new CrawlResult(LiveStatus.UNKNOWN, null, errorMsg, null);
    }

    /**
     * 携带本次从主页反查到的房间号 web_rid，供调度器写回数据库。
     */
    public static CrawlResult ofWithDiscoveredWebRid(LiveStatus status, String roomId, String discoveredWebRid) {
        return new CrawlResult(status, roomId, null, discoveredWebRid);
    }
}
