# 系统集成台账

Java 17 + Spring Boot 3.5.16 + Spring JDBC + PostgreSQL 16。前端使用原生 HTML/CSS/JavaScript，已集成在 Spring Boot JAR 中，由同一个服务提供页面和接口。

根据 `1.html` 原型实现：TC系统维护、事业部管理、集成方系统管理、系统集成台账。本期不建设白名单和日志查看模块。

## 启动与访问

本机已经安装 JDK 17 和 Maven。请在项目目录执行：

```powershell
cd D:\AIDemo\codex-demos\systemlog
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

脚本检查并启动项目专用 PostgreSQL，使用 Maven 打包，然后启动 Spring Boot。首次构建需要下载 Maven 依赖。修改 Java 或页面文件后，重新运行这个命令即可重新打包。

已构建且代码未修改时，可以跳过打包：

```powershell
.\start.ps1 -SkipBuild
```

| 地址 | 用途 |
| --- | --- |
| <http://127.0.0.1:8000/> | 业务前端页面 |
| <http://127.0.0.1:8000/docs> | Swagger接口文档和调试 |
| <http://127.0.0.1:8000/openapi.json> | OpenAPI定义 |
| <http://127.0.0.1:8000/health> | API和数据库健康检查 |

直接从浏览器打开首页使用业务系统，`1.html` 是原始静态原型，不是应用入口。

前台运行时按 Ctrl+C 停止，也可以在另一终端执行：

```powershell
.\stop.ps1                 # 停止项目API
.\stop.ps1 -Database       # 同时停止项目专用数据库
```

数据库文件、API启动记录和日志等本机文件位于 `.local/`；数据库停机或应用重启均保留已有数据。应用已经运行时，请先停止再启动。可用 `.\start.ps1 -Port 8001` 调整API端口。

## Java环境和IDE

要求 JDK 17+、Maven 3.6.3+。本机 `JAVA_HOME` 已指向 JDK 17，系统PATH中的默认java可能是11，请以 `mvn -version` 显示的版本为准。

在IDE中以 Maven 项目打开 `pom.xml`，选择JDK17，运行 `com.example.systemlog.SystemlogApplication`，工作目录设为项目根目录，程序会自动读取 `.env`。

也可以手工构建和运行：

```powershell
mvn -B package
& "$env:JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -jar target\systemlog-1.0.0.jar
```

前端没有独立的npm构建或开发服务器，HTML/CSS/JS与后端一起打包。源文件在 `static/`，Java代码在 `src/main/java/`。

## PostgreSQL和DBeaver

当前项目专用数据库：

| 配置项 | 值 |
| --- | --- |
| 类型 | PostgreSQL |
| 主机 | 127.0.0.1 |
| 端口 | 55432 |
| 数据库 | systemlog |
| 用户名 | ledger |
| 密码 | `.env` 的 DATABASE_URL 中，`ledger:`之后、`@127.0.0.1`之前的部分 |
| Schema | public |

在DBeaver中新建PostgreSQL连接，填写上述信息并点击测试连接。展开 `systemlog → Schemas → public → Tables` 查看数据。

项目使用本机已安装的PostgreSQL程序，独立数据目录为 `.local/postgres`。现有5432服务使用独立配置；切换到它时，请先创建项目数据库和账号，然后修改 `.env`：

```dotenv
DATABASE_URL=postgresql://ledger:your_password@127.0.0.1:5432/systemlog
AUTO_MIGRATE=true
SEED_DEMO=true
APP_HOST=127.0.0.1
APP_PORT=8000
```

密码特殊字符需要URL编码。数据库账号需要在本项目数据库中建表的权限；`.env` 已加入忽略列表。亦可使用Spring标准配置 `spring.datasource.url/username/password`；它们优先于DATABASE_URL。

首次使用专用实例的机器可以执行：

```powershell
python scripts/local_postgres.py start
python scripts/local_postgres.py status
python scripts/local_postgres.py stop
```

这个可选的数据库辅助脚本只使用Python标准库，自动发现已安装的PostgreSQL；也可传入 `--bin 'D:\middleware\postgresql\bin'`。使用已有数据库时，后端运行只需Java，不需要Python。

## 页面功能

- 基础数据：查询、分页、新增、编辑、启用/禁用、删除。
- 台账：按系统、事业部、状态、双方负责人、提供方、维护人和关键词筛选。
- 多事业部、多负责人、多接口编辑；支持展开接口明细、查看完整详情。
- 关联文档登记名称、来源、永久ID/存储Key等元数据。
- 新增和编辑通过API真正写入数据库，保存后刷新列表。
- 删除需要确认；被引用的基础数据、重复名称、无效字段和版本冲突会显示错误。
- CSV按当前已查询的条件导出，一条接口一行。

文档文件上传下载、迪墨云接入、Nacos远程读取、日志采集和查看未接入。页面中的文档、服务名和日志路径用于登记；演示文件标识并不对应真实文件。

## 数据与一致性

| 演示数据 | 数量 |
| --- | ---: |
| TC系统 | 4，含1条禁用 |
| 事业部 | 6，含1条禁用 |
| 集成方系统 | 7，含1条禁用 |
| 集成台账 | 12 |
| 接口明细 | 36 |
| 负责人 | 30 |
| 关联文档元数据 | 6 |

演示数据含多个事业部、多个负责人、生效/作废记录及测试/生产配置，使用文档示例IP和example.com域名。

Java启动器执行 `migrations/001_initial.sql` 并按 `demo-v1` 标记初始化假数据，使用事务和PostgreSQL advisory lock保证并发启动安全。已有迁移及种子记录会被识别，原来的数据库和用户修改不被覆盖，也不会重复插入。SQL文件校验和与先前初始化结果兼容。

`AUTO_MIGRATE=false` 可关闭启动迁移；`SEED_DEMO=false` 可关闭演示数据插入。后续结构调整在 `migrations/` 中新增按编号递增的SQL文件，已执行的文件不可修改。

8张业务表：`base_tc_system`、`base_division`、`base_partner_system`、`integration_ledger`、`integration_ledger_division`、`integration_ledger_contact`、`integration_interface`、`integration_attachment`。另有2张初始化记录表。

台账及所有子表在同一Spring事务内保存。编辑或删除需携带当前 `version`，成功编辑后版本号加1；过期版本返回409。子记录带ID保留原记录，不带ID新增，未提交的旧子记录删除；其他台账的子记录ID不可使用。接口实际生效状态同时考虑台账和接口自身状态。

基础数据名称唯一，已引用的数据禁止删除。禁用数据不能被新增引用，但历史台账编辑可保留原有引用。主表先分页，再批量查询子记录，避免多人、多接口造成重复记录。

## API约定

基础数据资源：`/api/v1/tc-systems`、`/api/v1/divisions`、`/api/v1/partner-systems`，均支持GET列表、POST新增、GET/PUT/DELETE `/{id}`。

台账：`/api/v1/ledgers` 支持GET/POST，`/{id}` 支持GET/PUT/DELETE，`/export` 导出CSV。删除台账需传 `?version=当前版本`。PUT为全量保存，不是部分更新；事业部和接口至少各一条，负责人和文档数组可为空。

JSON和查询参数使用snake_case，分页返回 `{items,total,page,page_size}`。默认20条、最多100条，按更新时间和ID倒序。

接口完整请求模型可在 `/docs` 查看。状态码：201创建、204删除、404不存在、409重复/引用/版本冲突、422参数校验失败、503连接不可用。统一错误格式 `{error:{code,message,details?}}`。

本地演示未接入登录认证，`X-Actor`只用于审计标识，默认 `local-demo`，页面使用 `local-ui`。默认服务仅监听本机。

## 测试

```powershell
mvn -B test
```

使用真实PostgreSQL，在随机命名的 `test_systemlog_*` schema中创建、验证并清理测试数据，不操作public业务表。默认读取 `.env` 的连接串，可通过 `TEST_DATABASE_URL` 覆盖；账号需要创建schema权限。

集成测试覆盖：种子数据和幂等初始化、分页、基础数据CRUD与名称唯一性、引用限制、台账事务、ID保留、级联删除、失败回滚、跨台账ID校验、禁用引用规则、多条件查询、实际生效状态、并发版本冲突、参数校验、CSV转义、页面资源和OpenAPI。

## Docker部署

安装Docker Compose后可运行：

```sh
docker compose up --build -d
docker compose logs -f api
docker compose down
```

使用多阶段构建打包Spring Boot JAR；数据库映射到 `127.0.0.1:55433`，页面/API映射到 `127.0.0.1:8000`，数据持久化到命名卷 `ledger_pgdata`。默认演示密码 `ledger_dev_password`，可用 `POSTGRES_PASSWORD` 设置为字母、数字、下划线组成的新值。

本机部署已验证；当前机器未安装Docker，容器方案尚未实测。

参考：[Spring Boot 3.5 Web应用](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html)、[Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)。
