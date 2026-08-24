package com.scic.platform.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scic.platform.common.BusinessException;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DataImportService {
    private static final Set<String> TYPES = Set.of("SUPPLIER", "MATERIAL", "INVENTORY", "DEMAND_HISTORY");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DataImportService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> importCsv(String rawType, String sourceSystem, String idempotencyKey, MultipartFile file) {
        return process(rawType, sourceSystem, idempotencyKey, file, true);
    }

    @Transactional
    public Map<String, Object> previewCsv(String rawType, String sourceSystem, String idempotencyKey, MultipartFile file) {
        return process(rawType, sourceSystem, idempotencyKey, file, false);
    }

    private Map<String, Object> process(String rawType, String sourceSystem, String idempotencyKey, MultipartFile file, boolean commitImmediately) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new BusinessException("UNSUPPORTED_IMPORT_TYPE", "仅支持 SUPPLIER、MATERIAL、INVENTORY、DEMAND_HISTORY", HttpStatus.BAD_REQUEST);
        if (file == null || file.isEmpty()) throw new BusinessException("EMPTY_FILE", "请选择非空 CSV 文件", HttpStatus.BAD_REQUEST);
        String filename = file.getOriginalFilename() == null ? "unnamed.csv" : file.getOriginalFilename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) throw new BusinessException("UNSUPPORTED_FILE", "V1 仅支持 CSV 文件", HttpStatus.BAD_REQUEST);
        try {
            byte[] bytes = file.getBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            List<Map<String, Object>> same = jdbc.queryForList("select id,batch_no,status,total_rows,success_rows,failed_rows from data_import_batch where import_type=? and file_hash=?", type, hash);
            if (!same.isEmpty()) {
                Map<String, Object> replay = new LinkedHashMap<>(same.get(0));
                replay.put("replayed", true);
                replay.put("message", "相同类型与文件内容已处理，未重复写入");
                return replay;
            }
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                List<Map<String, Object>> idem = jdbc.queryForList("select file_hash from data_import_batch where idempotency_key=?", idempotencyKey);
                if (!idem.isEmpty() && !hash.equals(idem.get(0).get("file_hash"))) {
                    throw new BusinessException("IDEMPOTENCY_CONFLICT", "同一幂等键不能对应不同文件", HttpStatus.CONFLICT);
                }
            }
            AuthUser user = SecuritySupport.currentUser();
            String batchNo = "IMP-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            jdbc.update("insert into data_import_batch(batch_no,import_type,source_system,original_filename,file_hash,idempotency_key,status,operator_id) values(?,?,?,?,?,?,?,?)",
                    batchNo, type, sourceSystem, filename, hash, blankToNull(idempotencyKey), "VALIDATING", user.id());
            Long batchId = jdbc.queryForObject("select id from data_import_batch where batch_no=?", Long.class, batchNo);

            ParseResult parsed = parse(type, bytes);
            for (ImportError error : parsed.errors()) {
                jdbc.update("insert into data_import_error(batch_id,row_number,field_name,error_code,error_message,raw_data) values(?,?,?,?,?,?)",
                        batchId, error.row(), error.field(), error.code(), error.message(), error.raw());
            }
            if (!parsed.errors().isEmpty()) {
                jdbc.update("update data_import_batch set status='FAILED_VALIDATION',total_rows=?,failed_rows=?,finished_at=current_timestamp where id=?",
                        parsed.rows().size(), parsed.errors().stream().map(ImportError::row).distinct().count(), batchId);
                return batchResult(batchId, false, "校验失败；业务数据未写入，请下载错误明细后修正");
            }

            if (!commitImmediately) {
                jdbc.update("update data_import_batch set status='VALIDATED',total_rows=?,success_rows=?,failed_rows=0,staged_payload=?,finished_at=current_timestamp where id=?",
                        parsed.rows().size(), parsed.rows().size(), objectMapper.writeValueAsString(parsed.rows()), batchId);
                return batchResult(batchId, false, "校验通过，尚未写入业务数据；请确认后提交批次");
            }
            for (Map<String, String> row : parsed.rows()) applyRow(type, sourceSystem, batchId, row);
            jdbc.update("update data_import_batch set status='COMPLETED',total_rows=?,success_rows=?,failed_rows=0,finished_at=current_timestamp where id=?",
                    parsed.rows().size(), parsed.rows().size(), batchId);
            return batchResult(batchId, false, "导入完成");
        } catch (BusinessException ex) {
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("IMPORT_CONFLICT", "导入内容与现有唯一数据冲突", HttpStatus.CONFLICT);
        } catch (Exception ex) {
            throw new BusinessException("CSV_PARSE_ERROR", "CSV 读取失败，请确认使用 UTF-8 编码和标准表头", HttpStatus.BAD_REQUEST);
        }
    }

    @Transactional
    public Map<String, Object> commit(long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,import_type,source_system,status,staged_payload from data_import_batch where id=?", batchId);
        if (rows.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", "导入批次不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> batch = rows.get(0);
        if ("COMPLETED".equals(batch.get("status"))) return batchResult(batchId, true, "批次已提交，未重复写入");
        if (!"VALIDATED".equals(batch.get("status"))) throw new BusinessException("IMPORT_HAS_ERRORS", "只有校验通过的批次才能提交", HttpStatus.UNPROCESSABLE_ENTITY);
        try {
            List<Map<String, String>> staged = objectMapper.readValue(batch.get("staged_payload").toString(), new TypeReference<>() {});
            for (Map<String, String> row : staged) applyRow(batch.get("import_type").toString(), batch.get("source_system").toString(), batchId, row);
            jdbc.update("update data_import_batch set status='COMPLETED',staged_payload=null,finished_at=current_timestamp where id=? and status='VALIDATED'", batchId);
            return batchResult(batchId, false, "批次已原子提交");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("IMPORT_SCHEMA_INVALID", "暂存数据无法读取", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public List<Map<String, Object>> errors(long batchId) {
        return jdbc.queryForList("select row_number,field_name,error_code,error_message,raw_data from data_import_error where batch_id=? order by row_number,id", batchId);
    }

    private ParseResult parse(String type, byte[] source) throws Exception {
        byte[] bytes = stripBom(source);
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build();
        List<Map<String, String>> rows = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        try (CSVParser parser = format.parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            Set<String> required = requiredHeaders(type);
            if (!parser.getHeaderMap().keySet().containsAll(required)) {
                Set<String> missing = new java.util.LinkedHashSet<>(required);
                missing.removeAll(parser.getHeaderMap().keySet());
                errors.add(new ImportError(1, "header", "MISSING_HEADER", "缺少表头: " + String.join(", ", missing), parser.getHeaderMap().keySet().toString()));
                return new ParseResult(rows, errors);
            }
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                parser.getHeaderMap().keySet().forEach(h -> row.put(h, record.isMapped(h) ? record.get(h).trim() : ""));
                rows.add(row);
                validate(type, (int) record.getRecordNumber() + 1, row, errors);
            }
        }
        if (rows.isEmpty() && errors.isEmpty()) errors.add(new ImportError(2, null, "NO_DATA", "文件没有数据行", ""));
        return new ParseResult(rows, errors);
    }

    private void validate(String type, int rowNo, Map<String, String> row, List<ImportError> errors) {
        for (String header : requiredHeaders(type)) {
            if (row.getOrDefault(header, "").isBlank()) errors.add(new ImportError(rowNo, header, "REQUIRED", "字段不能为空", row.toString()));
        }
        try {
            switch (type) {
                case "MATERIAL" -> {
                    positive(row, "safety_stock", false); positive(row, "min_order_qty", true); positive(row, "pack_size", true);
                    int lead = Integer.parseInt(row.get("lead_time_days")); if (lead < 1 || lead > 365) throw new IllegalArgumentException("lead_time_days");
                    positive(row, "standard_price", false);
                }
                case "INVENTORY" -> { positive(row, "on_hand_qty", false); positive(row, "reserved_qty", false); positive(row, "in_transit_qty", false); }
                case "DEMAND_HISTORY" -> { LocalDate.parse(row.get("demand_date")); positive(row, "quantity", false); }
                default -> { }
            }
        } catch (Exception ex) {
            errors.add(new ImportError(rowNo, ex.getMessage(), "INVALID_FORMAT", "数字或日期格式不合法", row.toString()));
        }
        if ("INVENTORY".equals(type)) {
            if (!exists("warehouse", "warehouse_code", row.get("warehouse_code"))) errors.add(new ImportError(rowNo,"warehouse_code","REFERENCE_NOT_FOUND","仓库编码不存在",row.get("warehouse_code")));
            if (!exists("material", "material_code", row.get("material_code"))) errors.add(new ImportError(rowNo,"material_code","REFERENCE_NOT_FOUND","物料编码不存在",row.get("material_code")));
            try { if (decimal(row,"reserved_qty").compareTo(decimal(row,"on_hand_qty")) > 0) errors.add(new ImportError(rowNo,"reserved_qty","BUSINESS_RULE","预留数量不能大于在库数量",row.get("reserved_qty"))); } catch (Exception ignored) { }
        }
        if ("DEMAND_HISTORY".equals(type) && !exists("material", "material_code", row.get("material_code"))) errors.add(new ImportError(rowNo,"material_code","REFERENCE_NOT_FOUND","物料编码不存在",row.get("material_code")));
    }

    private boolean exists(String table,String column,String value){ Number n=jdbc.queryForObject("select count(*) from "+table+" where "+column+"=? and status='ACTIVE'",Number.class,value); return n!=null&&n.longValue()>0; }

    private void applyRow(String type, String sourceSystem, Long batchId, Map<String, String> row) {
        switch (type) {
            case "SUPPLIER" -> upsertSupplier(row);
            case "MATERIAL" -> upsertMaterial(row);
            case "INVENTORY" -> upsertInventory(row);
            case "DEMAND_HISTORY" -> upsertDemand(sourceSystem, batchId, row);
            default -> throw new IllegalStateException(type);
        }
    }

    private void upsertSupplier(Map<String, String> row) {
        int updated = jdbc.update("update supplier set supplier_name=?,contact_name=?,contact_phone=?,level_code=?,updated_at=current_timestamp,version=version+1 where supplier_code=?",
                row.get("supplier_name"), row.get("contact_name"), row.get("contact_phone"), row.get("level_code"), row.get("supplier_code"));
        if (updated == 0) jdbc.update("insert into supplier(supplier_code,supplier_name,contact_name,contact_phone,level_code,status) values(?,?,?,?,?,'ACTIVE')",
                row.get("supplier_code"), row.get("supplier_name"), row.get("contact_name"), row.get("contact_phone"), row.get("level_code"));
    }

    private void upsertMaterial(Map<String, String> row) {
        Object[] values = {row.get("material_name"), row.get("category"), row.get("unit"), decimal(row,"safety_stock"), decimal(row,"min_order_qty"), decimal(row,"pack_size"), Integer.parseInt(row.get("lead_time_days")), decimal(row,"standard_price"), row.get("material_code")};
        int updated = jdbc.update("update material set material_name=?,category=?,unit=?,safety_stock=?,min_order_qty=?,pack_size=?,lead_time_days=?,standard_price=?,updated_at=current_timestamp,version=version+1 where material_code=?", values);
        if (updated == 0) jdbc.update("insert into material(material_name,category,unit,safety_stock,min_order_qty,pack_size,lead_time_days,standard_price,material_code,status) values(?,?,?,?,?,?,?,?,?,'ACTIVE')", values);
    }

    private void upsertInventory(Map<String, String> row) {
        Long warehouseId = referenceId("warehouse", "warehouse_code", row.get("warehouse_code"), "仓库编码不存在");
        Long materialId = referenceId("material", "material_code", row.get("material_code"), "物料编码不存在");
        int updated = jdbc.update("update inventory set on_hand_qty=?,reserved_qty=?,in_transit_qty=?,updated_at=current_timestamp,version=version+1 where warehouse_id=? and material_id=?",
                decimal(row,"on_hand_qty"), decimal(row,"reserved_qty"), decimal(row,"in_transit_qty"), warehouseId, materialId);
        if (updated == 0) jdbc.update("insert into inventory(warehouse_id,material_id,on_hand_qty,reserved_qty,in_transit_qty) values(?,?,?,?,?)",
                warehouseId, materialId, decimal(row,"on_hand_qty"), decimal(row,"reserved_qty"), decimal(row,"in_transit_qty"));
    }

    private void upsertDemand(String sourceSystem, Long batchId, Map<String, String> row) {
        Long materialId = referenceId("material", "material_code", row.get("material_code"), "物料编码不存在");
        LocalDate date = LocalDate.parse(row.get("demand_date"));
        int updated = jdbc.update("update demand_history set quantity=?,import_batch_id=?,data_label='IMPORTED_BUSINESS' where material_id=? and demand_date=? and source_system=?",
                decimal(row,"quantity"), batchId, materialId, date, sourceSystem);
        if (updated == 0) jdbc.update("insert into demand_history(material_id,demand_date,quantity,source_system,import_batch_id,data_label) values(?,?,?,?,?,'IMPORTED_BUSINESS')",
                materialId, date, decimal(row,"quantity"), sourceSystem, batchId);
    }

    private Long referenceId(String table, String codeColumn, String code, String message) {
        List<Long> ids = jdbc.query("select id from " + table + " where " + codeColumn + "=?", (rs, n) -> rs.getLong(1), code);
        if (ids.isEmpty()) throw new BusinessException("REFERENCE_NOT_FOUND", message + ": " + code, HttpStatus.UNPROCESSABLE_ENTITY);
        return ids.get(0);
    }

    private Map<String, Object> batchResult(Long id, boolean replayed, String message) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("select id,batch_no,import_type,source_system,original_filename,file_hash,status,total_rows,success_rows,failed_rows,created_at,finished_at from data_import_batch where id=?", id));
        result.put("replayed", replayed);
        result.put("message", message);
        result.put("errors", errors(id));
        return result;
    }

    private static Set<String> requiredHeaders(String type) {
        return switch (type) {
            case "SUPPLIER" -> Set.of("supplier_code", "supplier_name", "contact_name", "contact_phone", "level_code");
            case "MATERIAL" -> Set.of("material_code", "material_name", "category", "unit", "safety_stock", "min_order_qty", "pack_size", "lead_time_days", "standard_price");
            case "INVENTORY" -> Set.of("warehouse_code", "material_code", "on_hand_qty", "reserved_qty", "in_transit_qty");
            case "DEMAND_HISTORY" -> Set.of("material_code", "demand_date", "quantity");
            default -> Set.of();
        };
    }

    private static void positive(Map<String, String> row, String key, boolean strict) {
        BigDecimal value = decimal(row, key);
        if (strict ? value.signum() <= 0 : value.signum() < 0) throw new IllegalArgumentException(key);
    }

    private static BigDecimal decimal(Map<String, String> row, String key) {
        try { return new BigDecimal(row.get(key)); } catch (Exception ex) { throw new IllegalArgumentException(key); }
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private record ImportError(int row, String field, String code, String message, String raw) {}
    private record ParseResult(List<Map<String, String>> rows, List<ImportError> errors) {}
}
