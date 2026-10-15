# 数据库表关系说明

**核心是“系统集成台账”主表**：一条台账记录“哪个 TC 系统，与哪个集成方系统进行集成”，再关联事业部、负责人、接口和文档。

```mermaid
flowchart TB
    TC["TC系统<br/>base_tc_system"]
    PS["集成方系统<br/>base_partner_system"]

    L["系统集成台账【主表】<br/>integration_ledger<br/>双方系统、IP、状态、备注"]

    TC -->|"一个TC系统 → 多条台账"| L
    PS -->|"一个集成方系统 → 多条台账"| L

    L -->|"一条台账 → 多条关联"| LD["台账—事业部关联<br/>integration_ledger_division"]
    D["事业部<br/>base_division"] -->|"一个事业部 → 多条关联"| LD

    L -->|"一对多"| C["负责人<br/>integration_ledger_contact<br/>区分TC方 / 集成方"]
    L -->|"一对多"| I["接口明细<br/>integration_interface<br/>地址、服务名、报文样例"]
    L -->|"一对多"| A["关联文档<br/>integration_attachment<br/>文件名、存储标识"]

    classDef master fill:#e8f2ff,stroke:#3579bb,color:#173c60;
    classDef core fill:#173c60,stroke:#173c60,color:#fff;
    classDef child fill:#edf8f1,stroke:#6ba483,color:#285d40;
    class TC,PS,D master;
    class L core;
    class LD,C,I,A child;
```

**用一个例子理解：**

| 内容 | 保存位置 | 示例 |
|---|---|---|
| 哪两个系统集成 | 台账主表 | 集团 TC PLM ↔ ERP |
| 涉及哪些事业部 | 事业部关联表 | 工程院、乘用车事业部，各存一条关联 |
| 谁负责 | 负责人表 | TC方张三、李四；集成方王工，各存一条 |
| 有哪些接口 | 接口明细表 | 用户查询、订单同步，各存一条 |
| 有哪些文档 | 关联文档表 | 接口说明.pdf、联调说明.docx，各存一条 |

**事业部是多对多关系**：一条台账可以涉及多个事业部，一个事业部也可以参与多条台账，因此中间需要一张“台账—事业部关联表”。

另外两张表 `schema_migrations`、`demo_seed_runs` 分别记录建表版本和假数据初始化情况，**没有业务外键关系**，所以没有画进上图。

删除规则是：已被引用的基础数据禁止删除；删除台账时，会同时删除它所属的事业部关联、负责人、接口明细和文档关联记录。

## PNG 关系图

![数据库表关系图](mermaid-diagram.png)
