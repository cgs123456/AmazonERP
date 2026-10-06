# DDL 快照与漂移报告（自动生成，请勿手工编辑）

生成器：`tools/synthetic-data/snapshot_schema.py`（工具版本 1.2.0）

本报告由仓库内 DDL 解析得出，是模拟数据生成器的**唯一列清单来源**（spec §7.9 要求 1：禁止手抄列定义）。

## 1. 来源与表数

| 来源分组 | 文件数 | CREATE TABLE 数 |
|---|---|---|
| `compose-init-sql` | 1 | 0 |
| `flyway-migration` | 56 | 113 |
| **去重并集** | 57 个文件 | **102** 张表 |

数据库（14 个）：`amz_ad`、`amz_ai`、`amz_customer`、`amz_finance`、`amz_logistics`、`amz_multiplatform`、`amz_ops`、`amz_order`、`amz_procurement`、`amz_product`、`amz_report`、`amz_search`、`amz_spapi`、`amz_user`

## 2. 同一张表的多份定义

共 0 张表出现多份定义，其中 **0 张存在列级差异**。

未发现列级差异。

## 3. 结构风险清单

- 无主键表：**0** 张
- 显式外键：**0** 张表（其余表的关联仅存在于应用层，模拟数据生成器必须自行保证顺序）
- 视图：1 个（`v_profit_summary_by_sku`）
- 阻断性解析问题：**0** 条
- 守卫式动态 DDL：21 条（不参与静态表清单展开）
- 列定义不一致（同一列在不同定义中类型/默认值不同）：**0** 列
- 存在`条件列`（并非每份定义都有）的表：**0** 张 / 共 0 列 -> 生成器默认**不写入**这些列，只有 `--include-conditional` 才写入（对应升级脚本执行成功的库）

## 4. ALTER TABLE 事实（MySQL 8 兼容性，P0-39 机器可核证据）

- ALTER TABLE 语句：**38** 条；其中 ADD COLUMN：**22** 条
- 使用 `ADD COLUMN IF NOT EXISTS` 的语句：**0** 条（MySQL 8 不支持该语法，执行即 `ERROR 1064`）
- 快照重放的 ALTER 操作：**48** 条生效 / **0** 条未生效（列重命名 4 处）

| 表 | 列重命名 |
|---|---|
| `amz_payment_collection` | `order_id` → `amazon_order_id` |
| `amz_platform_message` | `order_id` → `platform_order_no` |
| `amz_settlement_detail` | `order_id` → `amazon_order_id` |
| `amz_shipment_routing` | `order_id` → `amazon_order_id` |

