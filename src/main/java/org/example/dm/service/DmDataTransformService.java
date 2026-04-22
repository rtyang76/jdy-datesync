package org.example.dm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ConfigManager;
import org.example.dm.dao.DmLocalDao;
import org.example.dm.model.DmOrder;
import org.example.dm.model.DmOrderDetail;
import org.example.util.LogUtil;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * DM数据转换服务
 * 负责将DM订单数据转换为简道云API格式
 */
public class DmDataTransformService {
    private static DmDataTransformService instance;
    // 简道云API要求的ISO 8601日期时间格式: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
    private static final DateTimeFormatter ISO8601_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private final ObjectMapper mapper = new ObjectMapper();
    
    private Map<String, String> mainFieldMapping;
    private Map<String, String> subTableMapping;
    private String subTableWidgetId;
    
    private DmDataTransformService() {
        loadFieldMapping();
    }
    
    public static synchronized DmDataTransformService getInstance() {
        if (instance == null) {
            instance = new DmDataTransformService();
        }
        return instance;
    }
    
    /**
     * 加载字段映射配置
     */
    private void loadFieldMapping() {
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            String mappingFile = configManager.getProperty("dm.to.jdy.field.mapping.path", "dm_to_jdy_field_mapping.json");
            
            InputStream input = getClass().getClassLoader().getResourceAsStream(mappingFile);
            if (input == null) {
                input = new java.io.FileInputStream(mappingFile);
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> config = mapper.readValue(input, Map.class);
            
            @SuppressWarnings("unchecked")
            Map<String, String> mainFields = (Map<String, String>) config.get("main_fields");
            this.mainFieldMapping = mainFields;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> subTables = (Map<String, Object>) config.get("sub_tables");
            if (subTables != null && subTables.containsKey("order_details")) {
                @SuppressWarnings("unchecked")
                Map<String, String> detailsMapping = (Map<String, String>) subTables.get("order_details");
                this.subTableMapping = detailsMapping;
                this.subTableWidgetId = mainFields.get("order_details");
            }
            
            LogUtil.logInfo("DM字段映射配置加载成功");
            
        } catch (Exception e) {
            LogUtil.logError("加载DM字段映射配置失败: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("加载DM字段映射配置失败", e);
        }
    }
    
    /**
     * 将DM订单转换为简道云格式
     * @param order DM订单对象
     * @return 简道云格式的数据
     */
    public Map<String, Object> convertToJdyFormat(DmOrder order) {
        Map<String, Object> converted = new HashMap<>();
        
        try {
            convertMainFields(order, converted);
            convertSubTableData(order.getId(), converted);
            
        } catch (Exception e) {
            LogUtil.logError("转换DM订单数据失败 (order_id=" + order.getId() + "): " + e.getMessage());
            e.printStackTrace();
            return null;
        }
        
        return converted;
    }
    
    /**
     * 转换主表字段
     */
    private void convertMainFields(DmOrder order, Map<String, Object> converted) {
        putField(converted, "order_no", order.getOrderNo());
        putField(converted, "month_settlement", order.getMonthSettlement());
        putField(converted, "factory", order.getFactory());
        putField(converted, "person_in_charge", order.getPersonInCharge());
        putField(converted, "currency", order.getCurrency());
        putField(converted, "mark", order.getMark());
        putField(converted, "tax_rate", order.getTaxRate() != null ? order.getTaxRate().toString() : "");
        putField(converted, "payment_terms", order.getPaymentTerms());
        putField(converted, "remarks", order.getRemarks());
        putField(converted, "in_warehouse", order.getInWarehouse());
        putField(converted, "material_warehouse", order.getMaterialWarehouse());
        putField(converted, "original_terms", order.getOriginalTerms());
        putField(converted, "total_quantity", order.getTotalQuantity() != null ? order.getTotalQuantity().toString() : "");
        putField(converted, "total_tax_amount", order.getTotalTaxAmount() != null ? order.getTotalTaxAmount().toString() : "");
        putField(converted, "department", order.getDepartment());
        putField(converted, "creator", order.getCreator());
        putField(converted, "auditor", order.getAuditor());
        putField(converted, "approver", order.getApprover());
        
        if (order.getSubmitTime() != null) {
            putField(converted, "submit_time", formatDateTime(order.getSubmitTime()));
        } else {
            putField(converted, "submit_time", "");
        }

        if (order.getModifyTime() != null) {
            putField(converted, "modify_time", formatDateTime(order.getModifyTime()));
        } else {
            putField(converted, "modify_time", "");
        }
        putField(converted, "order_status", String.valueOf(order.getOrderStatus()));
        
        putField(converted, "i_ord", order.getIOrd());
        
        if (order.getFillDate() != null) {
            putField(converted, "fill_date", formatDateTime(order.getFillDate()));
        } else {
            putField(converted, "fill_date", "");
        }
        
        putField(converted, "price_book", order.getPriceBook());
        putField(converted, "responsible_department", order.getResponsibleDepartment());
        
        if (order.getCurrentPaymentDate() != null) {
            putField(converted, "current_payment_date", formatDateTime(order.getCurrentPaymentDate()));
        } else {
            putField(converted, "current_payment_date", "");
        }

        if (order.getOriginalPaymentDate() != null) {
            putField(converted, "original_payment_date", formatDateTime(order.getOriginalPaymentDate()));
        } else {
            putField(converted, "original_payment_date", "");
        }
        
        putField(converted, "document_type", order.getDocumentType());
    }
    
    /**
     * 转换子表数据
     */
    private void convertSubTableData(Integer orderId, Map<String, Object> converted) {
        DmLocalDao localDao = DmLocalDao.getInstance();
        List<DmOrderDetail> details = localDao.queryOrderDetails(orderId);
        
        if (details == null || details.isEmpty()) {
            converted.put(subTableWidgetId, Collections.singletonMap("value", new ArrayList<>()));
            return;
        }
        
        List<Map<String, Object>> subTableData = new ArrayList<>();
        
        for (DmOrderDetail detail : details) {
            Map<String, Object> detailMap = new HashMap<>();

            putSubField(detailMap, "line_no", detail.getLineNo() != null ? String.valueOf(detail.getLineNo()) : "");
            putSubField(detailMap, "order_no", detail.getOrderNo());
            putSubField(detailMap, "material_code", detail.getMaterialCode());
            putSubField(detailMap, "material_desc", detail.getMaterialDesc());
            putSubField(detailMap, "quantity", detail.getQuantity() != null ? detail.getQuantity().toString() : "");
            putSubField(detailMap, "unit_price", detail.getUnitPrice() != null ? detail.getUnitPrice().toString() : "");
            putSubField(detailMap, "tax_unit_price", detail.getTaxUnitPrice() != null ? detail.getTaxUnitPrice().toString() : "");
            putSubField(detailMap, "tax_amount", detail.getTaxAmount() != null ? detail.getTaxAmount().toString() : "");
            putSubField(detailMap, "price_book", detail.getPriceBook());
            putSubField(detailMap, "suggested_quantity", detail.getSuggestedQuantity() != null ? detail.getSuggestedQuantity().toString() : "");
            putSubField(detailMap, "source_doc_no", detail.getSourceDocNo());
            
            putSubField(detailMap, "i_ord", detail.getIOrd());
            putSubField(detailMap, "same_auxiliary", detail.getSameAuxiliary());
            putSubField(detailMap, "update_mark", detail.getUpdateMark());
            putSubField(detailMap, "expand_mark", detail.getExpandMark());
            
            subTableData.add(detailMap);
        }
        
        converted.put(subTableWidgetId, Collections.singletonMap("value", subTableData));
    }
    
    /**
     * 添加主表字段（包装为简道云格式）
     */
    private void putField(Map<String, Object> converted, String fieldName, Object value) {
        String widgetId = mainFieldMapping.get(fieldName);
        if (widgetId != null && !widgetId.equals(subTableWidgetId) && !widgetId.equals("待创建")) {
            String strValue = (value != null) ? value.toString().trim() : "";
            converted.put(widgetId, Collections.singletonMap("value", strValue));
        }
    }
    
    /**
     * 添加子表字段（包装为简道云格式）
     */
    private void putSubField(Map<String, Object> detailMap, String fieldName, Object value) {
        String widgetId = subTableMapping.get(fieldName);
        if (widgetId != null && !widgetId.equals("待创建")) {
            String strValue = (value != null) ? value.toString().trim() : "";
            detailMap.put(widgetId, Collections.singletonMap("value", strValue));
        }
    }

    /**
     * 格式化日期时间字段
     * 转换为简道云API要求的ISO 8601格式: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
     * 注意：数据库中的时间是北京时间(UTC+8)，需要转换为UTC时间戳
     */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        // 将北京时间(UTC+8)转换为UTC时间，然后格式化为ISO 8601格式
        // 简道云API要求的时间格式: 2018-01-01T10:10:10.000Z
        ZonedDateTime beijingTime = dateTime.atZone(ZoneId.of("Asia/Shanghai"));
        ZonedDateTime utcTime = beijingTime.withZoneSameInstant(ZoneOffset.UTC);
        return utcTime.format(ISO8601_FORMATTER);
    }
}
