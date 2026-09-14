package com.kaibotixing.util;

import com.kaibotixing.dao.MonitorConfigDao;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 表格列宽持久化工具：将用户手动调整的列宽保存到数据库（monitor_config 表），
 * 下次启动时恢复，避免重复调整。
 * <p>
 * 通过监听每列 widthProperty 的变化，防抖（延迟 500ms）后批量写入数据库。
 * </p>
 */
public final class ColumnWidthStore {

    private static final Logger log = LoggerFactory.getLogger(ColumnWidthStore.class);
    private static final MonitorConfigDao CONFIG_DAO = new MonitorConfigDao();

    /** 防抖写入调度器（单线程） */
    private static final ScheduledExecutorService SAVE_POOL =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "column-width-saver");
                t.setDaemon(true);
                return t;
            });

    private ColumnWidthStore() {
    }

    /**
     * 恢复表格各列宽度。
     *
     * @param table    目标表格
     * @param tableKey 表格唯一标识（用于区分不同表格的配置 key）
     */
    public static void restore(TableView<?> table, String tableKey) {
        int index = 0;
        for (TableColumn<?, ?> col : table.getColumns()) {
            String key = configKey(tableKey, index);
            try {
                String value = CONFIG_DAO.get(key, null);
                if (value != null) {
                    double width = Double.parseDouble(value);
                    if (width > 0) {
                        col.setPrefWidth(width);
                    }
                }
            } catch (SQLException | NumberFormatException e) {
                log.debug("恢复列宽失败 key={}: {}", key, e.getMessage());
            }
            index++;
        }
    }

    /**
     * 绑定列宽保存监听（防抖），用户调整列宽后自动保存。
     *
     * @param table    目标表格
     * @param tableKey 表格唯一标识
     */
    public static void bindSave(TableView<?> table, String tableKey) {
        int index = 0;
        for (TableColumn<?, ?> col : table.getColumns()) {
            final int colIndex = index;
            col.widthProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == null || newVal.doubleValue() <= 0) {
                    return;
                }
                scheduleSave(tableKey, colIndex, newVal.doubleValue());
            });
            index++;
        }
    }

    private static String configKey(String tableKey, int index) {
        return "ui.col." + tableKey + "." + index;
    }

    /**
     * 防抖保存：合并近期变更，延迟后一次性写入。
     */
    private static final Map<String, Double> PENDING = new HashMap<>();

    private static synchronized void scheduleSave(String tableKey, int index, double width) {
        PENDING.put(configKey(tableKey, index), width);
        SAVE_POOL.schedule(ColumnWidthStore::flushPending, 500, TimeUnit.MILLISECONDS);
    }

    private static synchronized void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        Map<String, Double> snapshot = new HashMap<>(PENDING);
        PENDING.clear();
        for (Map.Entry<String, Double> entry : snapshot.entrySet()) {
            try {
                CONFIG_DAO.set(entry.getKey(), String.valueOf(entry.getValue().longValue()));
            } catch (SQLException e) {
                log.warn("保存列宽失败 key={}: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}
