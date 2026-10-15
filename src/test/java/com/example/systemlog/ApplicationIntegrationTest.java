package com.example.systemlog;

import com.example.systemlog.config.DatabaseInitializer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseInitializer initializer;
    static final String SCHEMA="test_systemlog_"+UUID.randomUUID().toString().replace("-","");
    static String jdbcUrl,user,password;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        String source=System.getenv("TEST_DATABASE_URL");
        if(source==null) source=Files.readAllLines(Path.of(".env")).stream().filter(s->s.startsWith("DATABASE_URL=")).findFirst().orElseThrow().substring(13).trim();
        URI uri=URI.create(source);String[] auth=uri.getRawUserInfo().split(":",2);
        user=URLDecoder.decode(auth[0].replace("+","%2B"),StandardCharsets.UTF_8);
        password=URLDecoder.decode(auth[1].replace("+","%2B"),StandardCharsets.UTF_8);
        jdbcUrl="jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getRawPath();
        try(var conn=DriverManager.getConnection(jdbcUrl,user,password);var statement=conn.createStatement()) {
            statement.execute("CREATE SCHEMA "+SCHEMA);
        }
        properties.add("spring.datasource.url",()->jdbcUrl+"?currentSchema="+SCHEMA);
        properties.add("spring.datasource.username",()->user);
        properties.add("spring.datasource.password",()->password);
        properties.add("ledger.auto-migrate",()->true);properties.add("ledger.seed-demo",()->true);
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbcUrl!=null)try(var conn=DriverManager.getConnection(jdbcUrl,user,password);var statement=conn.createStatement()) {
            statement.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
        }
    }
    Map<String,Object> request(MockHttpServletRequestBuilder builder,int expected) throws Exception {
        var response=mvc.perform(builder).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),response.getContentAsString(StandardCharsets.UTF_8));
        if(response.getContentAsByteArray().length==0)return Map.of();
        return json.readValue(response.getContentAsByteArray(),new TypeReference<>(){});
    }
    Map<String,Object> postJson(String url,Object body,int expected) throws Exception {
        return request(post(url).contentType("application/json").content(json.writeValueAsBytes(body)),expected);
    }
    Map<String,Object> putJson(String url,Object body,int expected) throws Exception {
        return request(put(url).contentType("application/json").content(json.writeValueAsBytes(body)),expected);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    @SuppressWarnings("unchecked") Map<String,Object> payload() throws Exception {
        long tc=((Number)items(request(get("/api/v1/tc-systems?status=enabled"),200)).get(0).get("id")).longValue();
        long ps=((Number)items(request(get("/api/v1/partner-systems?status=enabled"),200)).get(0).get("id")).longValue();
        var divisions=items(request(get("/api/v1/divisions?status=enabled"),200));
        return new LinkedHashMap<>(Map.of(
            "tc_system_id",tc,"partner_system_id",ps,"division_ids",List.of(divisions.get(0).get("id"),divisions.get(1).get("id")),
            "contacts",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("contact_side","tc","contact_name","测试张工")),new LinkedHashMap<>(Map.of("contact_side","partner","contact_name","测试王工")))),
            "interfaces",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("provider_side","tc","interface_name","接口A")),new LinkedHashMap<>(Map.of("provider_side","non_tc","interface_name","接口B")))),
            "attachments",List.of(Map.of("file_name","测试.pdf","storage_source","local","storage_ref","test/a.pdf")),
            "test_ip","192.0.2.111","prod_ip","2001:db8::10","maintainer_name","测试维护人"));
    }
    @SuppressWarnings("unchecked") Map<String,Object> editable(Map<String,Object> record) {
        Set<String> fields=Set.of("tc_system_id","partner_system_id","division_ids","contacts","interfaces","attachments","test_ip","prod_ip","integration_status","preferences_text","handle_text","remark","maintainer_name","version");
        Map<String,Object> result=new LinkedHashMap<>();record.forEach((key,value)->{if(fields.contains(key))result.put(key,value);});
        for(String key:List.of("contacts","interfaces","attachments")) {
            var rows=(List<Map<String,Object>>)result.get(key);
            for(var row:rows)for(String audit:List.of("ledger_id","created_by","created_at","updated_by","updated_at","effective_status"))row.remove(audit);
        }
        return result;
    }
    @Test @Order(1) void healthSeedAndPagination() throws Exception {
        assertEquals("spring-boot",request(get("/health"),200).get("framework"));
        var page=request(get("/api/v1/ledgers?page_size=2"),200);
        assertEquals(12,((Number)page.get("total")).intValue());assertEquals(2,items(page).size());
        assertEquals(36,jdbc.queryForObject("SELECT count(*) FROM integration_interface",Integer.class));
        assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM integration_attachment",Integer.class));
        var second=request(get("/api/v1/ledgers?page_size=2&page=2"),200);
        assertNotEquals(items(page).get(0).get("id"),items(second).get(0).get("id"));
        request(get("/api/v1/ledgers?page_size=101"),422);
    }
    @Test void seedIsIdempotent() {
        long before=jdbc.queryForObject("SELECT count(*) FROM integration_ledger",Long.class);
        initializer.initialize();initializer.initialize();
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM integration_ledger",Long.class));
    }
    @Test void baseCrudAndDuplicateNames() throws Exception {
        for(String resource:List.of("tc-systems","divisions","partner-systems")) {
            String url="/api/v1/"+resource;String name="临时_%_"+UUID.randomUUID();
            var row=postJson(url,Map.of("name","  "+name+"  "),201);assertEquals(name,row.get("name"));
            postJson(url,Map.of("name",name),409);
            var page=request(get(url).param("name",name),200);assertEquals(1,((Number)page.get("total")).intValue());
            putJson(url+"/"+row.get("id"),Map.of("name",name,"status","disabled"),200);
            request(delete(url+"/"+row.get("id")),204);request(get(url+"/"+row.get("id")),404);
        }
    }
    @Test void referencedBaseCannotBeDeleted() throws Exception {
        var record=items(request(get("/api/v1/ledgers"),200)).get(0);
        request(delete("/api/v1/tc-systems/"+record.get("tc_system_id")),409);
    }
    @Test @SuppressWarnings("unchecked") void aggregateUpdateVersionAndCascade() throws Exception {
        var record=postJson("/api/v1/ledgers",payload(),201);String url="/api/v1/ledgers/"+record.get("id");
        var update=editable(record);var children=(List<Map<String,Object>>)update.get("interfaces");
        Object childId=children.get(0).get("id");children.remove(1);children.get(0).put("interface_name","修改接口");
        update.put("contacts",List.of());update.put("attachments",List.of());
        var changed=putJson(url,update,200);assertEquals(2,changed.get("version"));
        assertEquals(childId,((List<Map<String,Object>>)changed.get("interfaces")).get(0).get("id"));
        putJson(url,update,409);request(delete(url+"?version=1"),409);request(delete(url+"?version=2"),204);
        for(String table:List.of("integration_interface","integration_ledger_contact","integration_ledger_division","integration_attachment"))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE ledger_id=?",Integer.class,record.get("id")));
    }
    @Test @SuppressWarnings("unchecked") void invalidChildRollsBackParentAndChildren() throws Exception {
        var record=postJson("/api/v1/ledgers",payload(),201);var update=editable(record);
        update.put("remark","不应提交");update.put("contacts",List.of());
        ((List<Map<String,Object>>)update.get("interfaces")).get(1).put("id",Long.MAX_VALUE);
        putJson("/api/v1/ledgers/"+record.get("id"),update,422);
        var after=request(get("/api/v1/ledgers/"+record.get("id")),200);
        assertNull(after.get("remark"));assertEquals(1,after.get("version"));assertEquals(2,((List<?>)after.get("contacts")).size());
    }
    @Test @SuppressWarnings("unchecked") void childOwnershipAndCreateRollback() throws Exception {
        var first=postJson("/api/v1/ledgers",payload(),201);var second=postJson("/api/v1/ledgers",payload(),201);
        var update=editable(second);
        ((List<Map<String,Object>>)update.get("interfaces")).get(0).put("id",((List<Map<String,Object>>)first.get("interfaces")).get(0).get("id"));
        putJson("/api/v1/ledgers/"+second.get("id"),update,422);
        long before=jdbc.queryForObject("SELECT count(*) FROM integration_ledger",Long.class);
        var invalid=payload();((List<Map<String,Object>>)invalid.get("interfaces")).get(1).put("id",Long.MAX_VALUE);
        postJson("/api/v1/ledgers",invalid,422);assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM integration_ledger",Long.class));
    }
    @Test void disabledReferencesAllowOnlyHistoricalReuse() throws Exception {
        var tc=postJson("/api/v1/tc-systems",Map.of("name","禁用测试"+UUID.randomUUID()),201);
        var payload=payload();payload.put("tc_system_id",tc.get("id"));var original=postJson("/api/v1/ledgers",payload,201);
        putJson("/api/v1/tc-systems/"+tc.get("id"),Map.of("name",tc.get("name"),"status","disabled"),200);
        postJson("/api/v1/ledgers",payload,409);putJson("/api/v1/ledgers/"+original.get("id"),editable(original),200);
    }
    @Test void filtersAndEffectiveStatus() throws Exception {
        var payload=payload();String marker="筛选_"+UUID.randomUUID();payload.put("remark",marker);payload.put("integration_status","invalid");
        var record=postJson("/api/v1/ledgers",payload,201);
        var page=request(get("/api/v1/ledgers").param("q",marker).param("provider_side","tc").param("tc_contact","测试张工"),200);
        assertEquals(1,((Number)page.get("total")).intValue());assertEquals(record.get("id"),items(page).get(0).get("id"));
        assertTrue(json.writeValueAsString(record.get("interfaces")).contains("\"effective_status\":\"invalid\""));
    }
    @Test void concurrentUpdatesOnlyOneSucceeds() throws Exception {
        var record=postJson("/api/v1/ledgers",payload(),201);byte[] body=json.writeValueAsBytes(editable(record));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> update=()->mvc.perform(put("/api/v1/ledgers/"+record.get("id")).contentType("application/json").content(body)).andReturn().getResponse().getStatus();
            var results=executor.invokeAll(List.of(update,update));
            var statuses=new ArrayList<>(List.of(results.get(0).get(),results.get(1).get()));Collections.sort(statuses);
            assertEquals(List.of(200,409),statuses);
        } finally {executor.shutdownNow();}
    }
    @Test void validationAndNotFound() throws Exception {
        for(var entry:Map.of("test_ip","not-ip","integration_status","pending","interfaces",List.of(),"division_ids",List.of(),"tc_system_id",0).entrySet()) {
            var invalid=payload();invalid.put(entry.getKey(),entry.getValue());postJson("/api/v1/ledgers",invalid,422);
        }
        postJson("/api/v1/tc-systems",Map.of("name","   "),422);
        postJson("/api/v1/tc-systems",Map.of("name","a","unexpected",true),422);
        request(get("/api/v1/ledgers/9223372036854775807"),404);
    }
    @Test @SuppressWarnings("unchecked") void csvExportAndFormulaSafety() throws Exception {
        var payload=payload();String marker="导出"+UUID.randomUUID();payload.put("remark",marker);
        ((List<Map<String,Object>>)payload.get("interfaces")).get(0).put("interface_name","=SUM(1,1)");
        postJson("/api/v1/ledgers",payload,201);
        var response=mvc.perform(get("/api/v1/ledgers/export").param("q",marker)).andReturn().getResponse();
        assertEquals(200,response.getStatus());String csv=response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\ufeff"));assertTrue(csv.contains("'=SUM(1,1)"));
    }
    @Test void frontendAndOpenapiAreServed() throws Exception {
        assertEquals(200,mvc.perform(get("/index.html")).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/assets/app.js")).andReturn().getResponse().getStatus());
        assertTrue(request(get("/openapi.json"),200).containsKey("paths"));
    }
}
