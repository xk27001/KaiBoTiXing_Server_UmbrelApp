package com.kaibotixing.util;

import javafx.scene.control.TableView;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 表格刷新时的选中状态保持工具。
 * <p>
 * JavaFX 中对 {@code TableView} 的 items 调用 {@code setAll} 会清空选中项，导致定时刷新
 * （客户端每秒、服务端每 5 秒）时用户刚选中的行被取消选中。本工具在替换数据前后按
 * 「业务主键」记录并恢复选中行，避免刷新打断用户操作。
 * </p>
 */
public final class TableSelectionKeeper {

    private TableSelectionKeeper() {
    }

    /**
     * 用新数据替换表格内容，并尽量保持原选中行仍处于选中状态。
     *
     * @param table        目标表格
     * @param newItems     新数据集合
     * @param keyExtractor 业务主键提取器（如 {@code Anchor::getId}），用于在新数据中定位原选中行；
     *                     返回 null 时退化为按行下标恢复
     * @param <T>          行数据类型
     * @param <K>          主键类型
     */
    public static <T, K> void refresh(TableView<T> table, List<T> newItems, Function<T, K> keyExtractor) {
        if (table == null || newItems == null) {
            return;
        }

        // 刷新前记录选中行的主键与下标
        T selected = table.getSelectionModel().getSelectedItem();
        K selectedKey = (selected == null) ? null : keyExtractor.apply(selected);
        int selectedIndex = table.getSelectionModel().getSelectedIndex();

        // 替换数据（会清空选中）
        table.getItems().setAll(newItems);

        // 在新数据中定位原选中行
        int newIndex = -1;
        if (selectedKey != null) {
            for (int i = 0; i < newItems.size(); i++) {
                if (Objects.equals(keyExtractor.apply(newItems.get(i)), selectedKey)) {
                    newIndex = i;
                    break;
                }
            }
        } else if (selectedIndex >= 0 && selectedIndex < newItems.size()) {
            // 主键为空（如尚未落库的行）：按下标尽力恢复
            newIndex = selectedIndex;
        }

        if (newIndex < 0) {
            return;
        }

        // 已是正确的选中项时不再重复 select，避免多余事件（如选中行回填表单）与闪烁
        T current = table.getSelectionModel().getSelectedItem();
        boolean alreadySelected = current != null && (selectedKey != null
                ? Objects.equals(keyExtractor.apply(current), selectedKey)
                : table.getSelectionModel().getSelectedIndex() == newIndex);
        if (!alreadySelected) {
            table.getSelectionModel().select(newIndex);
        }
    }
}
