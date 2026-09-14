package com.kaibotixing.crawler;

import com.kaibotixing.model.Anchor;

/**
 * 抖音直播状态爬取器接口。
 * 封装为接口便于后续替换为开放平台 API 或第三方数据源。
 */
public interface DouyinCrawler {

    /**
     * 爬取指定主播的直播状态。
     *
     * @param anchor 主播信息
     * @return 爬取结果
     */
    CrawlResult crawl(Anchor anchor);
}
