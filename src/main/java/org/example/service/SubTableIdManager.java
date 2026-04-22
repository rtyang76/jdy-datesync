package org.example.service;

import org.example.util.LogUtil;

import java.util.*;

/**
 * 子表ID管理器
 * 用于管理简道云子表数据的ID，确保更新时能正确传递子表ID
 */
public class SubTableIdManager {

    /**
     * 子表匹配配置
     */
    public static class SubTableMatchConfig {
        private final String subTableKey;      // 子表标识（如"requireComponentList"）
        private final String widgetId;         // 子表控件ID
        private final String matchField;       // 匹配字段名（如"require_item_number"）
        private final String matchWidgetId;    // 匹配字段的widget ID

        public SubTableMatchConfig(String subTableKey, String widgetId, String matchField, String matchWidgetId) {
            this.subTableKey = subTableKey;
            this.widgetId = widgetId;
            this.matchField = matchField;
            this.matchWidgetId = matchWidgetId;
        }

        public String getSubTableKey() { return subTableKey; }
        public String getWidgetId() { return widgetId; }
        public String getMatchField() { return matchField; }
        public String getMatchWidgetId() { return matchWidgetId; }
    }

    /**
     * 从简道云查询结果中提取子表ID映射
     * @param jdyRecord 简道云查询返回的完整记录
     * @param configs 子表匹配配置列表
     * @return 子表ID映射：{子表key: {匹配值: 子表行ID}}
     */
    public static Map<String, Map<String, String>> extractSubTableIds(
            Map<String, Object> jdyRecord,
            List<SubTableMatchConfig> configs) {

        Map<String, Map<String, String>> result = new HashMap<>();

        for (SubTableMatchConfig config : configs) {
            String subTableKey = config.getSubTableKey();
            String widgetId = config.getWidgetId();
            String matchWidgetId = config.getMatchWidgetId();

            // 从简道云记录中获取子表数据
            Object subTableObj = jdyRecord.get(widgetId);
            if (!(subTableObj instanceof Map)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> subTableMap = (Map<String, Object>) subTableObj;
            Object valueObj = subTableMap.get("value");
            if (!(valueObj instanceof List)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subTableRows = (List<Map<String, Object>>) valueObj;

            // 构建匹配值到子表ID的映射
            Map<String, String> idMap = new HashMap<>();
            for (Map<String, Object> row : subTableRows) {
                String rowId = (String) row.get("_id");
                if (rowId == null) continue;

                // 获取匹配字段的值
                Object matchFieldObj = row.get(matchWidgetId);
                String matchValue = extractValue(matchFieldObj);

                if (matchValue != null && !matchValue.isEmpty()) {
                    idMap.put(matchValue, rowId);
                }
            }

            if (!idMap.isEmpty()) {
                result.put(subTableKey, idMap);
            }
        }

        return result;
    }

    /**
     * 将子表ID合并到更新数据中
     * @param updateData 待更新的数据
     * @param subTableIdMap 子表ID映射
     * @param configs 子表匹配配置列表
     * @param subTablesConfig 子表字段映射配置
     */
    public static void mergeSubTableIds(
            Map<String, Object> updateData,
            Map<String, Map<String, String>> subTableIdMap,
            List<SubTableMatchConfig> configs,
            Map<String, Map<String, String>> subTablesConfig) {

        for (SubTableMatchConfig config : configs) {
            String subTableKey = config.getSubTableKey();
            String widgetId = config.getWidgetId();
            String matchField = config.getMatchField();
            String matchWidgetId = config.getMatchWidgetId();

            // 获取该子表的ID映射
            Map<String, String> idMap = subTableIdMap.get(subTableKey);
            if (idMap == null || idMap.isEmpty()) {
                continue;
            }

            // 获取更新数据中的子表
            Object subTableObj = updateData.get(widgetId);
            if (!(subTableObj instanceof Map)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> subTableMap = (Map<String, Object>) subTableObj;
            Object valueObj = subTableMap.get("value");
            if (!(valueObj instanceof List)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subTableRows = (List<Map<String, Object>>) valueObj;

            // 为每行数据添加_id
            int matchedCount = 0;
            for (Map<String, Object> row : subTableRows) {
                // 获取匹配字段的值
                Object matchFieldObj = row.get(matchWidgetId);
                String matchValue = extractValue(matchFieldObj);

                if (matchValue != null && idMap.containsKey(matchValue)) {
                    row.put("_id", idMap.get(matchValue));
                    matchedCount++;
                }
            }

            if (matchedCount > 0) {
                LogUtil.logInfo(String.format("子表[%s]匹配成功: %d/%d 行",
                        subTableKey, matchedCount, subTableRows.size()));
            }
        }
    }

    /**
     * 从简道云格式的字段值中提取实际值
     */
    private static String extractValue(Object fieldObj) {
        if (fieldObj == null) {
            return null;
        }

        if (fieldObj instanceof String) {
            return (String) fieldObj;
        }

        if (fieldObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fieldMap = (Map<String, Object>) fieldObj;
            Object value = fieldMap.get("value");
            if (value != null) {
                return value.toString().trim();
            }
        }

        return fieldObj.toString().trim();
    }
}
