package com.example.systemlog.config;

import com.example.systemlog.model.Requests.*;
import com.example.systemlog.repository.LedgerRepository;
import com.example.systemlog.service.LedgerService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.systemlog.service.LedgerService.*;

@Component
public class DatabaseInitializer implements ApplicationRunner {
    private final TransactionTemplate transactions;
    private final LedgerRepository db;
    private final LedgerService service;
    private final DataSource source;
    private final Environment environment;
    public DatabaseInitializer(TransactionTemplate transactions,LedgerRepository db,LedgerService service,
                               DataSource source,Environment environment) {
        this.transactions=transactions;this.db=db;this.service=service;this.source=source;this.environment=environment;
    }
    @Override public void run(ApplicationArguments args) { initialize(); }
    public void initialize() {
        if(!environment.getProperty("ledger.auto-migrate",Boolean.class,true)) return;
        transactions.executeWithoutResult(status->{
            db.query("SELECT pg_advisory_xact_lock(2026101501)",Map.of());
            db.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version VARCHAR(100) PRIMARY KEY,checksum CHAR(64) NOT NULL,applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)",Map.of());
            try {
                var migrations=new PathMatchingResourcePatternResolver().getResources("classpath*:db/migrations/*.sql");
                Arrays.sort(migrations,Comparator.comparing(Resource::getFilename));
                if(migrations.length==0)throw new IllegalStateException("没有找到数据库迁移文件");
                for(var migration:migrations)migrate(migration);
            } catch(java.io.IOException ex) {throw new IllegalStateException("读取迁移资源失败",ex);}
            if(environment.getProperty("ledger.seed-demo",Boolean.class,true)) seed();
        });
    }
    private void migrate(Resource resource) {
        String filename=resource.getFilename();
        try {
            String sql=new String(resource.getContentAsByteArray(),StandardCharsets.UTF_8).replace("\r\n","\n");
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
            var previous=db.find("SELECT checksum FROM schema_migrations WHERE version=:version",Map.of("version",filename));
            if(previous!=null) {
                if(!hash.equals(previous.get("checksum"))) throw new IllegalStateException("已执行的迁移被修改："+filename);
                return;
            }
            var connection=DataSourceUtils.getConnection(source);
            try { ScriptUtils.executeSqlScript(connection,resource); }
            finally { DataSourceUtils.releaseConnection(connection,source); }
            db.execute("INSERT INTO schema_migrations(version,checksum) VALUES (:version,:checksum)",Map.of("version",filename,"checksum",hash));
        } catch(Exception ex) { throw new IllegalStateException("数据库迁移失败："+filename,ex); }
    }
    private List<Long> bases(String table,List<String> names) {
        List<Long> ids=new ArrayList<>();
        for(int n=0;n<names.size();n++) {
            var row=db.find("SELECT id FROM "+table+" WHERE name=:name",Map.of("name",names.get(n)));
            if(row==null) row=db.insert(table,values("name",names.get(n),"maintainer_name",List.of("张三","李四","王工").get(n%3),"status","enabled"),"demo-seeder");
            ids.add(id(row.get("id")));
        }
        return ids;
    }
    public void seed() {
        if(db.find("SELECT seed_key FROM demo_seed_runs WHERE seed_key='demo-v1'",Map.of())!=null) return;
        var tc=bases("base_tc_system",List.of("集团TC PLM系统","研发TC系统","制造TC系统"));
        var divisions=bases("base_division",List.of("工程院","乘用车事业部","商用车事业部","动力总成事业部","新能源事业部"));
        var partners=bases("base_partner_system",List.of("ERP系统","MES系统","SRM系统","OA系统","WMS系统","质量QMS系统"));
        List<String> names=List.of("用户查询接口","订单同步接口","物料查询接口","库存同步接口");
        List<String> paths=List.of("user","order","material","inventory");
        var now=OffsetDateTime.now(ZoneOffset.ofHours(8));
        for(int n=0;n<12;n++) {
            LedgerWrite p=new LedgerWrite();p.tcSystemId=tc.get(n%3);p.partnerSystemId=partners.get(n%6);
            p.divisionIds.add(divisions.get(n%5));if(n%2==0)p.divisionIds.add(divisions.get((n+1)%5));
            p.testIp="192.0.2."+(10+n);p.prodIp="198.51.100."+(10+n);
            p.integrationStatus=(n==4||n==9)?"invalid":"active";
            p.preferencesText=String.format("DEMO_INTEGRATION_%03d",n+1);p.handleText=String.format("demo_handle_%03d",n+1);
            p.remark=String.format("演示集成项目 %02d（虚构数据）",n+1);p.maintainerName=List.of("张三","李四","王工").get(n%3);
            p.contacts.add(contact("tc",List.of("张三","李四","赵工").get(n%3),0));
            p.contacts.add(contact("partner",List.of("王工","陈工","刘工").get(n%3),0));
            if(n%2==0)p.contacts.add(contact("tc","周工",1));
            for(int k=0;k<2+n%3;k++) {
                Interface i=new Interface();i.interfaceName=names.get(k);i.providerSide=k%2==0?"tc":"non_tc";
                i.integrationStatus=k==1&&n%3==0?"invalid":"active";
                i.testNacosServiceName="partner-"+(n+1)+"-test";i.prodNacosServiceName="partner-"+(n+1)+"-prod";
                i.testUrl="https://test-"+(n+1)+".example.com/api/"+paths.get(k);
                i.prodUrl="https://prod-"+(n+1)+".example.com/api/"+paths.get(k);
                i.messageSample="{\"requestId\":\"DEMO-"+(n+1)+"\",\"data\":{\"name\":\"演示数据\"}}";
                i.testLogPath="/demo/logs/test/partner-"+(n+1)+"/"+paths.get(k)+".log";
                i.prodLogPath="/demo/logs/prod/partner-"+(n+1)+"/"+paths.get(k)+".log";
                i.remark="虚构接口，仅用于本地联调";i.sortNo=k;p.interfaces.add(i);
            }
            if(n%2==0) {
                Attachment a=new Attachment();a.fileName=String.format("集成说明-%02d.pdf",n+1);a.storageSource="dimou";
                a.storageRef=String.format("demo/documents/integration-%02d.pdf",n+1);a.fileSize=10240L+n*1024;
                a.contentType="application/pdf";p.attachments.add(a);
            }
            var ledger=service.create(p,"demo-seeder");
            var timestamps=values("id",ledger.get("id"),"created",now.minusDays(30-n).minusHours(n),"updated",now.minusDays(12-n));
            db.execute("UPDATE integration_ledger SET created_at=:created,updated_at=:updated WHERE id=:id",timestamps);
            for(String table:List.of("integration_ledger_contact","integration_interface","integration_attachment"))
                db.execute("UPDATE "+table+" SET created_at=:created,updated_at=:updated WHERE ledger_id=:id",timestamps);
        }
        for(var entry:Map.of("base_tc_system","旧版TC系统","base_division","历史事业部","base_partner_system","旧版集成系统").entrySet()) {
            if(db.find("SELECT id FROM "+entry.getKey()+" WHERE name=:name",Map.of("name",entry.getValue()))==null)
                db.insert(entry.getKey(),values("name",entry.getValue(),"status","disabled","maintainer_name","管理员"),"demo-seeder");
        }
        db.execute("INSERT INTO demo_seed_runs(seed_key) VALUES ('demo-v1')",Map.of());
    }
    private Contact contact(String side,String name,int sort) {
        Contact c=new Contact();c.contactSide=side;c.contactName=name;c.sortNo=sort;return c;
    }
}
