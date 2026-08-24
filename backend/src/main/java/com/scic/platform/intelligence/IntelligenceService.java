package com.scic.platform.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scic.platform.collaboration.CollaborationService;
import com.scic.platform.common.BusinessException;
import com.scic.platform.procurement.ProcurementService;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import com.scic.platform.system.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IntelligenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiGateway ai;
    private final ProcurementService procurement;
    private final CollaborationService collaboration;
    private final AuditService audit;

    public IntelligenceService(JdbcTemplate jdbc, ObjectMapper json, AiGateway ai,
                               ProcurementService procurement, CollaborationService collaboration, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ai = ai;
        this.procurement = procurement;
        this.collaboration = collaboration;
        this.audit = audit;
    }

    public List<Map<String, Object>> forecastRuns(String materialCode) {
        String base = "select f.id,f.run_no,m.material_code,m.material_name,f.as_of_date,f.horizon_days,f.model_name,f.model_version,f.data_label,f.mae,f.rmse,f.mape,f.fallback_reason,f.feature_version,f.random_seed,f.status,f.created_at from forecast_run f join material m on m.id=f.material_id";
        if (materialCode == null || materialCode.isBlank()) return jdbc.queryForList(base + " order by f.id desc");
        return jdbc.queryForList(base + " where m.material_code=? order by f.id desc", materialCode.trim());
    }

    public Map<String, Object> forecastRun(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select f.id,f.run_no,m.material_code,m.material_name,m.unit,m.lead_time_days,f.as_of_date,f.horizon_days,f.model_name,f.model_version,f.data_hash,f.data_label,f.mae,f.rmse,f.mape,f.fallback_reason,f.validation_details,f.split_details,f.feature_version,f.random_seed,f.status,f.created_at from forecast_run f join material m on m.id=f.material_id where f.id=?", id);
        if (rows.isEmpty()) throw notFound("预测记录不存在");
        Map<String,Object> result=new LinkedHashMap<>(rows.get(0));
        result.put("results",jdbc.queryForList("select forecast_date,predicted_qty,raw_predicted_qty,lower_bound,upper_bound,suggested_order_qty,warning_code,postprocess_note from forecast_result where run_id=? order by forecast_date",id));
        return result;
    }

    @Transactional
    public Map<String, Object> runForecast(String requestId, String materialCode) {
        AuthUser user=SecuritySupport.currentUser();
        if(!"BUYER".equals(user.role())) throw forbidden("只有采购协同人员可运行预测");
        Map<String,Object> material=one("select id,material_code,material_name,lead_time_days,safety_stock,min_order_qty,pack_size from material where material_code=? and status='ACTIVE'",materialCode,"物料不存在或已停用");
        long materialId=((Number)material.get("id")).longValue();
        List<Map<String,Object>> history=jdbc.queryForList("select demand_date,quantity,data_label from demand_history where material_id=? order by demand_date",materialId);
        if(history.isEmpty()) throw new BusinessException("DATA_INSUFFICIENT","没有可用于预测的历史需求",HttpStatus.UNPROCESSABLE_ENTITY);
        List<Map<String,Object>> points=new ArrayList<>();
        StringBuilder hashSource=new StringBuilder();
        LocalDate asOf=null;
        for(Map<String,Object> row:history){
            LocalDate date=toDate(row.get("demand_date")); BigDecimal qty=decimal(row.get("quantity"));
            points.add(Map.of("date",date.toString(),"quantity",qty)); hashSource.append(date).append(':').append(qty.toPlainString()).append(';'); asOf=date;
        }
        String dataHash=sha256(hashSource.toString());
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("material_id",Long.toString(materialId)); payload.put("material_code",materialCode); payload.put("history",points);
        payload.put("lead_time_days",((Number)material.get("lead_time_days")).intValue()); payload.put("as_of_date",asOf.toString()); payload.put("horizon",14);
        Map<String,Object> response=ai.forecast(payload,requestId);
        List<Map<String,Object>> sequence=castList(response.get("sequence"));
        if(sequence.size()!=14) throw new BusinessException("AI_OUTPUT_INVALID","预测结果必须恰好包含14天",HttpStatus.BAD_GATEWAY);
        Map<String,Object> metrics=castMap(response.get("metrics"));
        Map<String,Object> evaluation=castMap(response.get("evaluation"));
        String runNo=number("FC"); String model=response.get("model").toString().toUpperCase(Locale.ROOT);
        String fallback=response.get("fallback_reason")==null?null:response.get("fallback_reason").toString();
        String dataLabel=history.stream().allMatch(r->"DEMO_SYNTHETIC".equals(r.get("data_label")))?"DEMO_SYNTHETIC":"MIXED_OR_IMPORTED";
        jdbc.update("insert into forecast_run(run_no,material_id,as_of_date,horizon_days,model_name,model_version,data_hash,data_label,mae,rmse,mape,fallback_reason,validation_details,split_details,feature_version,random_seed,status) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runNo,materialId,asOf,14,model,"ai-service-1.0",dataHash,dataLabel,numberOrNull(metrics.get("mae")),numberOrNull(metrics.get("rmse")),numberOrNull(metrics.get("mape")),fallback,
                write(evaluation),write(evaluation.get("split")),"lag-rolling-calendar-v1",42,"SUCCEEDED");
        Long runId=jdbc.queryForObject("select id from forecast_run where run_no=?",Long.class,runNo);
        int lead=((Number)material.get("lead_time_days")).intValue();
        BigDecimal suggestion=lead>14?BigDecimal.ZERO:calculateSuggestion(materialId,material,sequence);
        String warning=lead>14?"HORIZON_INSUFFICIENT":fallback!=null&&fallback.contains("DATA_INSUFFICIENT")?"DATA_INSUFFICIENT":null;
        Map<String,Object> post=castMap(response.get("postprocessing"));
        String postNote=post.get("explanation")==null?"预测值经非负截断；递归生成14日序列":post.get("explanation").toString();
        int position=0;
        for(Map<String,Object> point:sequence){
            LocalDate date=LocalDate.parse(point.get("date").toString()); BigDecimal forecast=decimal(point.get("forecast"));
            if(forecast.signum()<0||date.isBefore(asOf.plusDays(1))) throw new BusinessException("AI_OUTPUT_INVALID","预测日期或数值不合法",HttpStatus.BAD_GATEWAY);
            jdbc.update("insert into forecast_result(run_id,forecast_date,predicted_qty,raw_predicted_qty,suggested_order_qty,warning_code,postprocess_note) values(?,?,?,?,?,?,?)",
                    runId,date,forecast,forecast,position++==0?suggestion:BigDecimal.ZERO,warning,postNote);
        }
        audit.log(requestId,"RUN_FORECAST","FORECAST_RUN",runId,null,"SUCCEEDED",materialCode+";"+model+";"+dataLabel);
        Map<String,Object> result=forecastRun(runId); result.put("warnings",response.get("warnings")); result.put("suggestedOrderQty",suggestion); result.put("selectionNote","XGBoost仅在三折验证至少赢两折且平均MAE低于MA7时采用；否则使用MA7。");
        return result;
    }

    public List<Map<String,Object>> parseRecords(){
        AuthUser u=SecuritySupport.currentUser();
        String base="select id,parse_no,task_type,schema_version,provider,model_name,schema_valid,business_valid,validation_errors,target_type,target_id,final_status,preview_version,expires_at,confirmed_at,latency_ms,created_by,created_at from ai_parse_record";
        if("ADMIN".equals(u.role())||"MANAGER".equals(u.role())) return jdbc.queryForList(base+" order by id desc");
        return jdbc.queryForList(base+" where created_by=? order by id desc",u.id());
    }

    @Transactional
    public Map<String,Object> createPreview(String requestId,String rawTask,String text,Map<String,Object> context){
        AuthUser user=SecuritySupport.currentUser(); String task=normalizeTask(rawTask); authorizeTask(user,task);
        long started=System.nanoTime(); Map<String,Object> upstream=ai.parse(Map.of("text",text,"intent_hint",task.toLowerCase(Locale.ROOT)),requestId);
        Map<String,Object> fields=new LinkedHashMap<>(castMap(upstream.get("fields")));
        Validation validation=validateFields(task,fields,user,context==null?Map.of():context);
        List<String> missing=stringList(upstream.get("missing_fields"));
        boolean schemaValid=missing.isEmpty(); boolean businessValid=schemaValid&&validation.valid();
        String status=businessValid?"PREVIEW":missing.isEmpty()?"INVALID":"NEEDS_INPUT";
        String no=number("AP"); OffsetDateTime expires=OffsetDateTime.now().plusMinutes(30);
        String errors=write(Map.of("missingFields",missing,"businessChecks",validation.checks(),"warnings",upstream.getOrDefault("warnings",List.of())));
        long latency=Math.round((System.nanoTime()-started)/1_000_000.0);
        jdbc.update("insert into ai_parse_record(parse_no,task_type,schema_version,input_text,context_snapshot,provider,model_name,prompt_version,raw_response,normalized_json,schema_valid,business_valid,validation_errors,final_status,preview_version,expires_at,request_id,latency_ms,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                no,task,"1.0",text,write(validation.context()),upstream.get("provider"),"rule-or-configured-provider","parse-v1",write(upstream),write(validation.fields()),schemaValid,businessValid,errors,"PREVIEW",0,java.sql.Timestamp.from(expires.toInstant()),requestId,latency,user.id());
        Long id=jdbc.queryForObject("select id from ai_parse_record where parse_no=?",Long.class,no);
        audit.log(requestId,"CREATE_AI_PREVIEW","AI_PARSE_RECORD",id,null,"PREVIEW",task+";provider="+upstream.get("provider"));
        return preview(id,status,missing,validation,upstream,expires);
    }

    @Transactional
    public Map<String,Object> updatePreview(String requestId,long id,int expectedVersion,Map<String,Object> correctedFields){
        AuthUser user=SecuritySupport.currentUser(); Map<String,Object> record=parseOwned(id,user);
        ensurePreviewUsable(record,expectedVersion);
        String task=record.get("task_type").toString(); Map<String,Object> context=readMap(record.get("context_snapshot"));
        Validation validation=validateFields(task,new LinkedHashMap<>(correctedFields),user,context);
        List<String> missing=requiredMissing(task,validation.fields());
        boolean schemaValid=missing.isEmpty(); boolean businessValid=schemaValid&&validation.valid();
        int updated=jdbc.update("update ai_parse_record set normalized_json=?,schema_valid=?,business_valid=?,validation_errors=?,preview_version=preview_version+1 where id=? and preview_version=? and final_status='PREVIEW'",
                write(validation.fields()),schemaValid,businessValid,write(Map.of("missingFields",missing,"businessChecks",validation.checks())),id,expectedVersion);
        if(updated!=1) throw conflict("预览已更新，请刷新");
        audit.log(requestId,"UPDATE_AI_PREVIEW","AI_PARSE_RECORD",id,"PREVIEW_V"+expectedVersion,"PREVIEW_V"+(expectedVersion+1),task);
        return previewRecord(id);
    }

    @Transactional
    public Map<String,Object> confirmPreview(String requestId,long id,int expectedVersion,String idempotencyKey){
        if(idempotencyKey==null||idempotencyKey.isBlank()) throw new BusinessException("IDEMPOTENCY_REQUIRED","确认AI预览必须提供 Idempotency-Key",HttpStatus.BAD_REQUEST);
        AuthUser user=SecuritySupport.currentUser(); Map<String,Object> record=parseOwned(id,user);
        if("CONFIRMED".equals(record.get("final_status"))){ if(idempotencyKey.equals(record.get("confirm_idempotency_key"))) return previewRecord(id); throw conflict("预览已经由另一请求确认"); }
        ensurePreviewUsable(record,expectedVersion);
        if(!Boolean.TRUE.equals(record.get("schema_valid"))||!Boolean.TRUE.equals(record.get("business_valid"))) throw new BusinessException("AI_OUTPUT_INVALID","预览仍有缺失字段或业务校验错误",HttpStatus.UNPROCESSABLE_ENTITY);
        String task=record.get("task_type").toString(); Map<String,Object> fields=readMap(record.get("normalized_json")); Map<String,Object> context=readMap(record.get("context_snapshot"));
        Object target;
        if("PURCHASE_DEMAND".equals(task)){
            var input=new ProcurementService.DemandInput(fields.get("material_code").toString(),decimal(fields.get("quantity")),LocalDate.parse(fields.get("required_date").toString()),"NORMAL","由AI受控解析预览确认生成；原文记录="+record.get("parse_no"));
            target=procurement.createDemand(requestId,input,"AI_PARSE",record.get("parse_no").toString());
        }else if("PLAN_CHANGE".equals(task)){
            long planId=((Number)context.get("planId")).longValue(); int targetVersion=((Number)context.get("targetVersion")).intValue();
            Map<String,Object> current=one("select status,version from purchase_plan where id=?",planId,"采购计划不存在");
            if(!"DRAFT".equals(current.get("status"))||((Number)current.get("version")).intValue()!=targetVersion) throw conflict("目标计划状态或版本已变化，必须重新解析");
            Long materialId=((Number)context.get("materialId")).longValue(); BigDecimal oldQty=jdbc.queryForObject("select quantity from purchase_plan_item where plan_id=? and material_id=?",BigDecimal.class,planId,materialId);
            BigDecimal newQty=decimal(fields.get("new_quantity"));
            jdbc.update("update purchase_plan_item set quantity=? where plan_id=? and material_id=?",newQty,planId,materialId);
            jdbc.update("update purchase_plan set total_amount=(select coalesce(sum(quantity*unit_price),0) from purchase_plan_item where plan_id=?),version=version+1,updated_at=current_timestamp where id=? and version=?",planId,planId,targetVersion);
            jdbc.update("insert into demand_event(material_id,event_date,event_type,quantity_delta,description,source_system) values(?,current_date,'PLAN_CHANGE',?,?, 'AI_PARSE_CONFIRMED')",materialId,newQty.subtract(oldQty),fields.get("reason"));
            target=Map.of("id",planId,"status","DRAFT","version",targetVersion+1,"oldQuantity",oldQty,"newQuantity",newQty);
        }else{
            long orderId=((Number)context.get("orderId")).longValue(); LocalDate eta=LocalDate.parse(fields.get("eta").toString());
            var input=new CollaborationService.NoticeInput(orderId,eta.atTime(9,0).atOffset(ZoneOffset.ofHours(8)),null,fields.get("tracking_no")==null?null:fields.get("tracking_no").toString(),List.of());
            target=collaboration.createNotice(requestId,input,"AI_PARSE",record.get("parse_no").toString());
        }
        Long targetId=extractId(target); String targetType=switch(task){case "PURCHASE_DEMAND"->"PURCHASE_DEMAND";case "PLAN_CHANGE"->"PURCHASE_PLAN";default->"DELIVERY_NOTICE";};
        int updated=jdbc.update("update ai_parse_record set final_status='CONFIRMED',target_type=?,target_id=?,confirmed_by=?,confirmed_at=current_timestamp,confirm_idempotency_key=? where id=? and final_status='PREVIEW' and preview_version=?",
                targetType,targetId,user.id(),idempotencyKey,id,expectedVersion);
        if(updated!=1) throw conflict("预览已变化或已确认");
        audit.log(requestId,"CONFIRM_AI_PREVIEW","AI_PARSE_RECORD",id,"PREVIEW","CONFIRMED",targetType+":"+targetId);
        Map<String,Object> result=previewRecord(id); result.put("target",target); return result;
    }

    @Transactional
    public Map<String,Object> cancelPreview(String requestId,long id,int expectedVersion){
        AuthUser user=SecuritySupport.currentUser(); Map<String,Object> record=parseOwned(id,user); ensurePreviewUsable(record,expectedVersion);
        jdbc.update("update ai_parse_record set final_status='CANCELLED',preview_version=preview_version+1 where id=? and preview_version=? and final_status='PREVIEW'",id,expectedVersion);
        audit.log(requestId,"CANCEL_AI_PREVIEW","AI_PARSE_RECORD",id,"PREVIEW","CANCELLED",null); return previewRecord(id);
    }

    public Map<String,Object> previewRecord(long id){
        AuthUser user=SecuritySupport.currentUser(); Map<String,Object> r=parseVisible(id,user); Map<String,Object> out=new LinkedHashMap<>();
        out.put("previewId",r.get("id")); out.put("previewNo",r.get("parse_no")); out.put("taskType",r.get("task_type")); out.put("status",r.get("final_status")); out.put("previewVersion",r.get("preview_version")); out.put("expiresAt",r.get("expires_at")); out.put("sourceText",r.get("input_text")); out.put("normalizedFields",readMap(r.get("normalized_json"))); out.put("schemaValid",r.get("schema_valid")); out.put("businessValid",r.get("business_valid")); out.put("validation",readMap(r.get("validation_errors"))); out.put("provider",r.get("provider")); out.put("requiresConfirmation",true); out.put("targetType",r.get("target_type")); out.put("targetId",r.get("target_id")); out.put("createdAt",r.get("created_at")); return out;
    }

    private Map<String,Object> preview(long id,String status,List<String> missing,Validation v,Map<String,Object> upstream,OffsetDateTime expires){
        Map<String,Object> out=new LinkedHashMap<>(); out.put("previewId",id); out.put("status",status); out.put("previewVersion",0); out.put("expiresAt",expires); out.put("schemaVersion","1.0"); out.put("sourceText",upstream.get("original_text")); out.put("normalizedFields",v.fields());
        out.put("evidence",v.fields().entrySet().stream().filter(e->e.getValue()!=null).map(e->Map.of("field",e.getKey(),"value",e.getValue(),"source","原文解析")).toList()); out.put("missingFields",missing); out.put("schemaChecks",Map.of("valid",missing.isEmpty())); out.put("businessChecks",v.checks()); out.put("warnings",upstream.getOrDefault("warnings",List.of())); out.put("impactPreview",v.impact()); out.put("provider",upstream.get("provider")); out.put("confidenceNotice","解析置信仅供参考，不参与任何自动业务决策"); out.put("requiresConfirmation",true); return out;
    }

    private Validation validateFields(String task,Map<String,Object> f,AuthUser user,Map<String,Object> existingContext){
        List<Map<String,Object>> checks=new ArrayList<>(); Map<String,Object> context=new LinkedHashMap<>(existingContext); Map<String,Object> impact=new LinkedHashMap<>(); boolean ok=true;
        if("PURCHASE_DEMAND".equals(task)){
            String code=str(f.get("material_code")); String name=str(f.get("material_name")); List<Map<String,Object>> rows=code!=null?jdbc.queryForList("select id,material_code,material_name,unit from material where material_code=? and status='ACTIVE'",code):name!=null?jdbc.queryForList("select id,material_code,material_name,unit from material where material_name=? and status='ACTIVE'",name):List.of();
            if(rows.size()==1){ f.put("material_code",rows.get(0).get("material_code")); f.put("material_name",rows.get(0).get("material_name")); checks.add(check("material","PASS","物料存在且已启用")); }else{ok=false;checks.add(check("material","FAIL","物料无法唯一匹配"));}
            ok&=positiveField(f,"quantity",checks); ok&=dateField(f,"required_date",checks); impact.put("result","确认后仅创建DRAFT采购需求，不会下单");
        }else if("PLAN_CHANGE".equals(task)){
            String planNo=str(f.get("plan_no")); String materialCode=str(f.get("material_code")); List<Map<String,Object>> plans=planNo==null?List.of():jdbc.queryForList("select id,status,version from purchase_plan where plan_no=?",planNo);
            if(plans.size()==1&&"DRAFT".equals(plans.get(0).get("status"))){context.put("planId",plans.get(0).get("id"));context.put("targetVersion",plans.get(0).get("version"));checks.add(check("plan","PASS","目标计划为DRAFT"));}else{ok=false;checks.add(check("plan","FAIL","目标计划不存在或不是DRAFT"));}
            List<Map<String,Object>> mats=materialCode==null?List.of():jdbc.queryForList("select id from material where material_code=? and status='ACTIVE'",materialCode); if(mats.size()==1){context.put("materialId",mats.get(0).get("id"));checks.add(check("material","PASS","物料存在"));}else{ok=false;checks.add(check("material","FAIL","物料不存在"));}
            ok&=positiveField(f,"new_quantity",checks); impact.put("result","确认后只修改DRAFT计划数量并记录demand_event，不会提交审批");
        }else{
            String orderNo=str(f.get("order_no")); List<Map<String,Object>> orders=orderNo==null?List.of():jdbc.queryForList("select id,status,version,supplier_id from purchase_order where order_no=?",orderNo);
            if(orders.size()==1&&"SUPPLIER".equals(user.role())&&user.supplierId()!=null&&((Number)orders.get(0).get("supplier_id")).longValue()==user.supplierId()&&List.of("CONFIRMED","PENDING_SHIPMENT").contains(orders.get(0).get("status"))){context.put("orderId",orders.get(0).get("id"));context.put("targetVersion",orders.get(0).get("version"));checks.add(check("order","PASS","订单属于当前供应商且可创建通知"));}else{ok=false;checks.add(check("order","FAIL","订单不存在、越权或状态不允许"));}
            ok&=dateField(f,"eta",checks); impact.put("result","确认后仅创建DRAFT到货通知，不会发运或收货");
        }
        return new Validation(ok,f,checks,context,impact);
    }

    private boolean positiveField(Map<String,Object> f,String key,List<Map<String,Object>> checks){ try{BigDecimal v=decimal(f.get(key)); if(v.signum()<=0)throw new Exception(); f.put(key,v);checks.add(check(key,"PASS","数量合法"));return true;}catch(Exception e){checks.add(check(key,"FAIL","数量必须大于0"));return false;} }
    private boolean dateField(Map<String,Object> f,String key,List<Map<String,Object>> checks){ try{LocalDate d=LocalDate.parse(f.get(key).toString()); if(d.isBefore(LocalDate.now()))throw new Exception(); f.put(key,d.toString());checks.add(check(key,"PASS","日期合法"));return true;}catch(Exception e){checks.add(check(key,"FAIL","日期缺失、格式错误或早于今天"));return false;} }
    private static Map<String,Object> check(String field,String status,String message){return Map.of("field",field,"status",status,"message",message);}

    private List<String> requiredMissing(String task,Map<String,Object> fields){
        List<String> keys=switch(task){case "PURCHASE_DEMAND"->List.of("material_code","quantity","required_date");case "PLAN_CHANGE"->List.of("plan_no","material_code","new_quantity");default->List.of("order_no","eta");};
        return keys.stream().filter(key->{Object value=fields.get(key);return value==null||value.toString().isBlank();}).toList();
    }
    private void ensurePreviewUsable(Map<String,Object> r,int version){ if(!"PREVIEW".equals(r.get("final_status")))throw conflict("预览不处于可确认状态");if(((Number)r.get("preview_version")).intValue()!=version)throw conflict("预览版本已变化");OffsetDateTime exp=((java.sql.Timestamp)r.get("expires_at")).toInstant().atOffset(ZoneOffset.ofHours(8));if(OffsetDateTime.now().isAfter(exp))throw new BusinessException("AI_PREVIEW_EXPIRED","预览已超过30分钟，请重新解析",HttpStatus.CONFLICT); }
    private Map<String,Object> parseOwned(long id,AuthUser u){List<Map<String,Object>> r=jdbc.queryForList("select * from ai_parse_record where id=? and created_by=?",id,u.id());if(r.isEmpty())throw notFound("AI预览不存在");return r.get(0);}
    private Map<String,Object> parseVisible(long id,AuthUser u){if("ADMIN".equals(u.role())||"MANAGER".equals(u.role())){List<Map<String,Object>>r=jdbc.queryForList("select * from ai_parse_record where id=?",id);if(r.isEmpty())throw notFound("AI记录不存在");return r.get(0);}return parseOwned(id,u);}
    private void authorizeTask(AuthUser u,String task){if("BUYER".equals(u.role())&&List.of("PURCHASE_DEMAND","PLAN_CHANGE").contains(task))return;if("SUPPLIER".equals(u.role())&&"DELIVERY_NOTICE".equals(task))return;throw forbidden("当前角色不能解析该类业务文本");}
    private static String normalizeTask(String raw){String t=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);if(!List.of("PURCHASE_DEMAND","PLAN_CHANGE","DELIVERY_NOTICE").contains(t))throw new BusinessException("VALIDATION_ERROR","仅支持三类白名单任务",HttpStatus.BAD_REQUEST);return t;}
    private BigDecimal calculateSuggestion(long materialId,Map<String,Object> material,List<Map<String,Object>> seq){BigDecimal forecast=seq.stream().map(p->decimal(p.get("forecast"))).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal supply=jdbc.queryForObject("select coalesce(sum(on_hand_qty-reserved_qty+in_transit_qty),0) from inventory where material_id=?",BigDecimal.class,materialId);if(supply==null)supply=BigDecimal.ZERO;BigDecimal need=forecast.add(decimal(material.get("safety_stock"))).subtract(supply);if(need.signum()<=0)return BigDecimal.ZERO;BigDecimal min=decimal(material.get("min_order_qty"));BigDecimal pack=decimal(material.get("pack_size"));need=need.max(min);return need.divide(pack,0,RoundingMode.CEILING).multiply(pack);}
    private Map<String,Object> one(String sql,Object arg,String msg){List<Map<String,Object>>r=jdbc.queryForList(sql,arg);if(r.isEmpty())throw notFound(msg);return r.get(0);}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> readMap(Object v){if(v==null)return new LinkedHashMap<>();try{return json.readValue(v.toString(),new TypeReference<>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object v){return v instanceof Map<?,?> m?(Map<String,Object>)m:new LinkedHashMap<>();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> castList(Object v){return v instanceof List<?> l?(List<Map<String,Object>>)l:List.of();}
    private static List<String> stringList(Object v){if(!(v instanceof List<?> l))return List.of();return l.stream().map(Object::toString).toList();}
    private static LocalDate toDate(Object v){return v instanceof java.sql.Date d?d.toLocalDate():LocalDate.parse(v.toString());}
    private static BigDecimal decimal(Object v){if(v==null)throw new IllegalArgumentException();return v instanceof BigDecimal b?b:new BigDecimal(v.toString());}
    private static BigDecimal numberOrNull(Object v){if(v==null)return null;return decimal(v);}
    private static String str(Object v){return v==null||v.toString().isBlank()?null:v.toString();}
    private static Long extractId(Object target){if(target instanceof Map<?,?>m&&m.get("id") instanceof Number n)return n.longValue();return null;}
    private static String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String number(String p){return p+"-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+"-"+UUID.randomUUID().toString().substring(0,5).toUpperCase();}
    private static BusinessException notFound(String m){return new BusinessException("RESOURCE_NOT_FOUND",m,HttpStatus.NOT_FOUND);}
    private static BusinessException forbidden(String m){return new BusinessException("FORBIDDEN",m,HttpStatus.FORBIDDEN);}
    private static BusinessException conflict(String m){return new BusinessException("VERSION_CONFLICT",m,HttpStatus.CONFLICT);}
    private record Validation(boolean valid,Map<String,Object> fields,List<Map<String,Object>> checks,Map<String,Object> context,Map<String,Object> impact){}
}
