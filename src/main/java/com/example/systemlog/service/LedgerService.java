package com.example.systemlog.service;

import com.example.systemlog.model.Requests.*;
import com.example.systemlog.repository.LedgerRepository;
import com.example.systemlog.web.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    public static final Map<String,String> BASES = Map.of(
        "tc-systems", "base_tc_system", "divisions", "base_division", "partner-systems", "base_partner_system");
    private static final String FROM = """
        FROM integration_ledger l JOIN base_tc_system tc ON tc.id=l.tc_system_id
        JOIN base_partner_system ps ON ps.id=l.partner_system_id
        """;
    private static final String SELECT = "SELECT l.*,tc.name AS tc_system_name,ps.name AS partner_system_name ";
    private static final Map<String,String> CHILDREN = Map.of(
        "contacts", "integration_ledger_contact", "interfaces", "integration_interface", "attachments", "integration_attachment");
    private final LedgerRepository db;
    public LedgerService(LedgerRepository db) { this.db = db; }
    public static long id(Object value) { return ((Number)value).longValue(); }
    public static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    public static Map<String,Object> values(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
    public static String pattern(String value) {
        return "%"+value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")+"%";
    }
    public String table(String resource) {
        String table = BASES.get(resource);
        if(table == null) throw new ApiException(404, "资源不存在");
        return table;
    }
    private Map<String,Object> require(Map<String,Object> row, String message) {
        if(row == null) throw new ApiException(404, message);
        return row;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> listBase(String resource, int page, int size, String name, String maintainer, String status) {
        String table = table(resource);
        checkEnum(status, Set.of("enabled","disabled"), "基础数据状态");
        List<String> clauses = new ArrayList<>(); Map<String,Object> params = new LinkedHashMap<>();
        if(text(name)!=null) { clauses.add("name ILIKE :name"); params.put("name", pattern(name)); }
        if(text(maintainer)!=null) { clauses.add("maintainer_name ILIKE :maintainer"); params.put("maintainer",pattern(maintainer)); }
        if(status!=null) { clauses.add("status=:status"); params.put("status", status); }
        String where = clauses.isEmpty()?"":" WHERE "+String.join(" AND ",clauses);
        long count = db.count("SELECT count(*) FROM "+table+where,params);
        params.put("limit",size); params.put("offset", (long)(page-1)*size);
        return values("items",db.query("SELECT * FROM "+table+where+" ORDER BY updated_at DESC,id DESC LIMIT :limit OFFSET :offset",params),
                      "total",count,"page",page,"page_size",size);
    }
    public Map<String,Object> baseDetail(String resource, long rowId) {
        return require(db.find("SELECT * FROM "+table(resource)+" WHERE id=:id",Map.of("id",rowId)),"基础数据记录不存在");
    }
    @Transactional
    public Map<String,Object> saveBase(String resource, Long rowId, BaseWrite payload, String actor) {
        Map<String,Object> fields = values("name",text(payload.name),"maintainer_name",text(payload.maintainerName),"status",payload.status);
        return rowId==null?db.insert(table(resource),fields,actor):require(db.update(table(resource),rowId,fields,actor),"基础数据记录不存在");
    }
    @Transactional
    public void deleteBase(String resource, long rowId) {
        if(db.execute("DELETE FROM "+table(resource)+" WHERE id=:id",Map.of("id",rowId))==0)
            throw new ApiException(404,"基础数据记录不存在");
    }
    private static void checkEnum(String value, Set<String> allowed, String name) {
        if(value!=null && !allowed.contains(value)) throw new ApiException(422,name+"无效");
    }
    private String where(Map<String,String> filters, Map<String,Object> params) {
        List<String> clauses = new ArrayList<>();
        checkEnum(filters.get("integration_status"),Set.of("active","invalid"),"集成状态");
        checkEnum(filters.get("provider_side"),Set.of("tc","non_tc"),"接口提供方");
        for(String key:List.of("tc_system_id","partner_system_id","integration_status")) {
            String value = text(filters.get(key));
            if(value!=null) {
                Object parsed = key.endsWith("_id")?positiveId(value):value;
                clauses.add("l."+key+"=:"+key); params.put(key,parsed);
            }
        }
        String division = text(filters.get("division_id"));
        if(division!=null) {
            clauses.add("EXISTS(SELECT 1 FROM integration_ledger_division d WHERE d.ledger_id=l.id AND d.division_id=:division_id)");
            params.put("division_id",positiveId(division));
        }
        if(text(filters.get("provider_side"))!=null) {
            clauses.add("EXISTS(SELECT 1 FROM integration_interface i WHERE i.ledger_id=l.id AND i.provider_side=:provider_side)");
            params.put("provider_side",filters.get("provider_side"));
        }
        for(String key:List.of("tc_contact","partner_contact")) if(text(filters.get(key))!=null) {
            String side = key.equals("tc_contact")?"tc":"partner";
            clauses.add("EXISTS(SELECT 1 FROM integration_ledger_contact c WHERE c.ledger_id=l.id AND c.contact_side='"+side+"' AND c.contact_name ILIKE :"+key+")");
            params.put(key,pattern(filters.get(key)));
        }
        if(text(filters.get("maintainer"))!=null) {
            clauses.add("l.maintainer_name ILIKE :maintainer"); params.put("maintainer",pattern(filters.get("maintainer")));
        }
        if(text(filters.get("q"))!=null) {
            clauses.add("(tc.name ILIKE :q OR ps.name ILIKE :q OR l.remark ILIKE :q)"); params.put("q",pattern(filters.get("q")));
        }
        return clauses.isEmpty()?"":" WHERE "+String.join(" AND ",clauses);
    }
    private long positiveId(String value) {
        try { long number = Long.parseLong(value); if(number>0) return number; } catch(NumberFormatException ignored) {}
        throw new ApiException(422,"ID必须为正整数");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(int page,int size,Map<String,String> filters,boolean export) {
        Map<String,Object> params = new LinkedHashMap<>(); String where = where(filters,params);
        long total = db.count("SELECT count(*) "+FROM+where,params);
        if(export && total>10000) throw new ApiException(400,"单次导出最多10000条台账，请缩小筛选范围");
        params.put("limit",size); params.put("offset",(long)(page-1)*size);
        var rows = db.query(SELECT+FROM+where+" ORDER BY l.updated_at DESC,l.id DESC LIMIT :limit OFFSET :offset",params);
        hydrate(rows);
        return values("items",rows,"total",total,"page",page,"page_size",size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long ledgerId) { return detailInternal(ledgerId); }
    private Map<String,Object> detailInternal(long ledgerId) {
        var row = require(db.find(SELECT+FROM+" WHERE l.id=:id",Map.of("id",ledgerId)),"台账不存在");
        hydrate(List.of(row)); return row;
    }
    @SuppressWarnings("unchecked")
    private void hydrate(List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return;
        Map<Long,Map<String,Object>> indexed = new HashMap<>();
        for(var row:rows) {
            indexed.put(id(row.get("id")),row);
            for(String key:List.of("division_ids","divisions","contacts","interfaces","attachments")) row.put(key,new ArrayList<>());
        }
        var params = Map.of("ids",indexed.keySet());
        var divisions = db.query("SELECT ld.ledger_id,d.* FROM integration_ledger_division ld JOIN base_division d ON d.id=ld.division_id WHERE ld.ledger_id IN (:ids) ORDER BY d.id",params);
        for(var division:divisions) {
            var parent = indexed.get(id(division.remove("ledger_id")));
            ((List<Object>)parent.get("division_ids")).add(division.get("id"));
            ((List<Object>)parent.get("divisions")).add(division);
        }
        CHILDREN.forEach((key,table)->{
            for(var child:db.query("SELECT * FROM "+table+" WHERE ledger_id IN (:ids) ORDER BY sort_no,id",params)) {
                var parent = indexed.get(id(child.get("ledger_id")));
                if(key.equals("interfaces")) child.put("effective_status",
                    "active".equals(parent.get("integration_status")) && "active".equals(child.get("integration_status"))?"active":"invalid");
                ((List<Object>)parent.get(key)).add(child);
            }
        });
    }
    private String ip(String input) {
        String value = text(input); if(value==null) return null;
        boolean valid = false;
        if(value.contains(":")) {
            try { valid = value.matches("[0-9a-fA-F:.]+") && InetAddress.getByName(value).getAddress().length==16; }
            catch(Exception ignored) {}
        } else {
            String[] pieces = value.split("\\.",-1);
            valid = pieces.length==4;
            for(String piece:pieces) {
                if(!piece.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(piece)>255) { valid=false; break; }
            }
        }
        if(!valid) throw new ApiException(422,"IP地址无效："+value);
        return value;
    }
    private String url(String input) {
        String value = text(input); if(value==null) return null;
        try {
            URI uri=URI.create(value);
            if(("http".equals(uri.getScheme())||"https".equals(uri.getScheme())) && uri.getHost()!=null) return value;
        } catch(IllegalArgumentException ignored) {}
        throw new ApiException(422,"接口地址必须是有效的HTTP/HTTPS地址");
    }
    private Map<String,Object> ledgerValues(LedgerWrite p) {
        return values("tc_system_id",p.tcSystemId,"partner_system_id",p.partnerSystemId,
            "test_ip",ip(p.testIp),"prod_ip",ip(p.prodIp),"integration_status",p.integrationStatus,
            "preferences_text",text(p.preferencesText),"handle_text",text(p.handleText),
            "remark",text(p.remark),"maintainer_name",text(p.maintainerName));
    }
    private void references(LedgerWrite p, Map<String,Object> old) {
        Set<Long> oldDivisions = new HashSet<>();
        if(old!=null) for(var row:db.query("SELECT division_id FROM integration_ledger_division WHERE ledger_id=:id",Map.of("id",old.get("id"))))
            oldDivisions.add(id(row.get("division_id")));
        checkRefs("base_tc_system",List.of(p.tcSystemId), old==null?Set.of():Set.of(id(old.get("tc_system_id"))),"TC系统");
        checkRefs("base_partner_system",List.of(p.partnerSystemId),old==null?Set.of():Set.of(id(old.get("partner_system_id"))),"集成方系统");
        checkRefs("base_division",p.divisionIds,oldDivisions,"事业部");
    }
    private void checkRefs(String table,List<Long> ids,Set<Long> previous,String label) {
        Map<Long,String> statuses = new HashMap<>();
        for(var row:db.query("SELECT id,status FROM "+table+" WHERE id IN (:ids) ORDER BY id FOR SHARE",Map.of("ids",ids)))
            statuses.put(id(row.get("id")),(String)row.get("status"));
        for(long ref:ids) {
            if(!statuses.containsKey(ref)) throw new ApiException(422,label+"不存在："+ref);
            if(!"enabled".equals(statuses.get(ref)) && !previous.contains(ref)) throw new ApiException(409,label+"已禁用，不能新增引用");
        }
    }
    @Transactional
    public Map<String,Object> create(LedgerWrite payload,String actor) {
        references(payload,null);
        var row=db.insert("integration_ledger",ledgerValues(payload),actor);
        syncChildren(id(row.get("id")),payload,actor,true);
        return detailInternal(id(row.get("id")));
    }
    @Transactional
    public Map<String,Object> update(long ledgerId,LedgerUpdate payload,String actor) {
        var old=lock(ledgerId,payload.version);
        references(payload,old);
        var values=ledgerValues(payload); values.put("version",((Number)old.get("version")).intValue()+1);
        db.update("integration_ledger",ledgerId,values,actor);
        syncChildren(ledgerId,payload,actor,false);
        return detailInternal(ledgerId);
    }
    private Map<String,Object> lock(long ledgerId,int version) {
        var row=require(db.find("SELECT * FROM integration_ledger WHERE id=:id FOR UPDATE",Map.of("id",ledgerId)),"台账不存在");
        if(((Number)row.get("version")).intValue()!=version) throw new ApiException(409,"台账已被其他操作修改，请刷新后重试");
        return row;
    }
    @Transactional
    public void delete(long ledgerId,int version) {
        lock(ledgerId,version); db.execute("DELETE FROM integration_ledger WHERE id=:id",Map.of("id",ledgerId));
    }
    private void syncChildren(long ledgerId,LedgerWrite p,String actor,boolean creating) {
        db.execute("DELETE FROM integration_ledger_division WHERE ledger_id=:id",Map.of("id",ledgerId));
        for(long division:p.divisionIds) db.execute("INSERT INTO integration_ledger_division(ledger_id,division_id) VALUES (:id,:division)",Map.of("id",ledgerId,"division",division));
        sync("integration_ledger_contact",ledgerId,p.contacts,c->c.id,c->values(
            "contact_side",c.contactSide,"contact_name",text(c.contactName),"sort_no",c.sortNo),actor,creating);
        sync("integration_interface",ledgerId,p.interfaces,i->i.id,i->values(
            "provider_side",i.providerSide,"interface_name",text(i.interfaceName),"integration_status",i.integrationStatus,
            "test_nacos_service_name",text(i.testNacosServiceName),"prod_nacos_service_name",text(i.prodNacosServiceName),
            "test_url",url(i.testUrl),"prod_url",url(i.prodUrl),"message_sample",i.messageSample,
            "test_log_path",text(i.testLogPath),"prod_log_path",text(i.prodLogPath),"remark",text(i.remark),"sort_no",i.sortNo),actor,creating);
        sync("integration_attachment",ledgerId,p.attachments,a->a.id,a->values(
            "file_name",text(a.fileName),"storage_source",a.storageSource,"storage_ref",text(a.storageRef),
            "file_size",a.fileSize,"content_type",text(a.contentType),"sort_no",a.sortNo),actor,creating);
    }
    private <T> void sync(String table,long ledgerId,List<T> children,Function<T,Long> getId,
                          Function<T,Map<String,Object>> fields,String actor,boolean creating) {
        Set<Long> existing=new HashSet<>();
        for(var row:db.query("SELECT id FROM "+table+" WHERE ledger_id=:id",Map.of("id",ledgerId))) existing.add(id(row.get("id")));
        Set<Long> seen=new HashSet<>();
        for(T child:children) {
            Long childId=getId.apply(child); var values=fields.apply(child);
            if(childId!=null) {
                if(creating||!existing.contains(childId)||!seen.add(childId)) throw new ApiException(422,"明细ID重复、不存在或不属于当前台账");
                db.update(table,childId,values,actor);
            } else {
                values.put("ledger_id",ledgerId); seen.add(id(db.insert(table,values,actor).get("id")));
            }
        }
        existing.removeAll(seen);
        if(!existing.isEmpty()) db.execute("DELETE FROM "+table+" WHERE ledger_id=:ledger AND id IN (:ids)",Map.of("ledger",ledgerId,"ids",existing));
    }
}
