# 2026-10-03 · 项 7a：`/ad/search-term` 15 个端点接入 + 规则引擎语义修正

承接 `2026-10-03-endpoint-coverage-audit.txt` 的候选清单。本轮动的是最大的一组用户可见缺口
（`SearchTermController` 15 个端点，清点时 14 个被判定为「应该给浏览器但没人接」），
以及清点工具自己被证伪的第四处缺陷。

## 一、接了什么

页面 `amz-frontend/src/views/AdSearchTerms.vue`（路由 `/ad-search-terms`，侧边栏「搜索词与规则」），
接口层 `amz-frontend/src/api/adSearchTerms.ts`，六个分区：

| 分区 | 端点 |
| --- | --- |
| 规则 | `GET /rule/list/{shopId}`、`POST /rule`、`PUT /rule/{id}`、`POST /rule/{id}/toggle`、`DELETE /rule/{id}`、`POST /rule/{ruleId}/execute`、`POST /rule/execute/{shopId}` |
| 搜索词报表 | `GET /list/{shopId}`、`POST /ad/search-term`（无参映射，路径就是类前缀） |
| 综合分析 | `GET /analyze/{shopId}` |
| 词根聚类 | `GET /cluster/{shopId}` |
| 出单词库 | `GET /converting/list/{shopId}`、`POST /converting/extract/{shopId}` |
| ASIN 反查 | `GET /asin-reverse/{shopId}`、`POST /asin-reverse/batch` |

`POST /rule/execute/{shopId}`（批量出建议）在后端由 `executeRules` 逐条调用单条执行，
页面上的入口是每行的「出建议」；批量端点保留为接口层函数，等下面第二节那条边界处理完再接界面。

## 二、后端：把「自动规则」这个名字底下的三处名不副实改掉

动手前读了 `AdAutoRuleServiceImpl`，结论是这个服务名字里的三个词都不成立。改的是代码，
不是只在页面上补一句免责声明——否则接口返回体本身仍在说谎。

1. **`executed` 字段是造出来的。** 原代码 `action.put("executed", "暂停关键词/搜索词投放")`，
   但整个类没有一个字节写到广告账号：注入的 `AdvertisingApiClient` 与 `AdKeywordMapper`
   两个字段都是死的。现在
   - 动作明细改为 `suggestion` + `applied:false`；
   - 结果顶层固定 `appliedToAdAccount:false` 并带 `note` 指明全系统唯一的真实改价通道
     是 `BidScheduleExecutor`（分时调价，按小时自动下发）；
   - 删掉两个死注入，并加了一条反射围栏测试：`AdAutoRuleServiceImpl` 再持有
     `AdvertisingApiClient` 就直接红——「接上执行器」必须同步改语义，不能悄悄改。
2. **`scope`/`scope_value` 只落库不生效。** 一条写着「限定活动 camp-777」的规则实际会扫描
   全店搜索词。现在 `applyScope` 把它们翻成真正的 SQL 条件（CAMPAIGN→campaign_id，
   KEYWORD→keyword_id）；空范围值保持全店语义，因为 V1 的三条种子规则就是
   `scope=KEYWORD, scope_value=NULL`，加过滤会把它们从「全店」静默变成「命中 0 条」。
3. **非法组合被允许写入， resulting 规则永远判不出来。** `conditionField="FOO"`、
   `conditionOp="BETWEEN"` 而不给上界、上界小于下界、`timeWindow=0`（起始日期跑到未来）、
   `action="ENABLE"`（DDL 注释里有这个值，switch 里没有分支）——过去统统能存进去，
   页面上看是一条已启用的规则，实际永不命中。现在写入侧按 DDL 注释枚举 + 跨字段规则校验，
   `updateRule` 校验的是「库里现值 + 本次非空覆盖」的合并形态（PUT 只带一个字段是合法用法，
   只校验请求体会把局部更新挡死，也会放过 BETWEEN 缺上界这类组合）。
   `ASIN` 范围被明确拒绝：`amz_ad_search_term` 没有 ASIN 字段，写了不可能生效。

新增 `AdAutoRuleSemanticsTest` 18 例（枚举/BETWEEN 上下界/窗口范围/ASIN 拒绝/合并校验/
两种 scope 的 SQL 断言/结果诚实性/死注入围栏），改 `AdAutoRuleServiceImplTest` 的 setUp
（去掉两个已删字段）与接口/控制器 javadoc。ad 模块相关三套 34 例全绿。

变异检查（把缺陷注回去必须看到红，再按 sha256 复原 `e6748165…`）：

| 变异 | 结果 |
| --- | --- |
| M1 去掉 `applyScope` | 红（`executeScansOnlyConfiguredCampaign`） |
| M2a `appliedToAdAccount` 改 true | 红（`executeResultIsExplicitlyAdviceOnly`） |
| M2b `suggestion` 改回 `executed` | 红（同一例） |
| M3 创建时不校验 | 红（`createRejectsInvertedBetweenBounds`） |
| M4 更新时不做合并校验 | 红（`updateValidatesMergedView`） |
| M5 重新注入 `AdvertisingApiClient` | 红（`ruleEngineHoldsNoAdvertisingClient`） |

## 三、清点工具的第四处误报：无参映射注解整个不在分母里

`METHOD_MAP` 要求 `@PostMapping(` 有括号，而 **15 个** 方法级注解是无参的
（`@PostMapping` / `@PutMapping`，路径就是类前缀），所以它们从来没进过统计。
偏偏这类端点多为写入端点（`POST /ad/search-term`、`POST /ad/campaigns`、`PUT /ad/creatives`…）。
上一版还叠加一条 `if full == prefix and not path: continue`，等于同一个端点被漏两次。

改法：先按行抓注解，再从括号里取字符串；解析不出路径的（如
`@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`）**显式打印出来**而不是跳过——
工具的失败模式只能是「少算并说明」，不能是静默少一条。同时打印
`parsed_method_annotations` 作为分母绝对值，供下次比对（分母塌陷需要一个基线才看得见）。

同一份代码前后对比：

| | 修复前 | 修复后 |
| --- | --- | --- |
| 控制器 | 59 | 60 |
| 解析出的方法注解 | 未打印 | 356（1 条打印待人工确认） |
| 前端未点名的端点 | 115 | 102 |
| 用户可见候选 | 94 | 82 |

候选差集已核对：**消失的 13 条全是本轮接上的 `/ad/search-term/*`**；
**新增的 1 条是 `GET /spapi/operations`**（分母修复后才显形，不是误报）。
快照另存 `2026-10-03-endpoint-coverage-audit-v2.txt`，v1 保留以便对照。

新显形的那 1 条待确认注解：`UploadsController.java` 的
`@PostMapping(consumes = ...)`，即 `POST /spapi/uploads`（multipart 图片上传），
归类为「该接 UI，属于商品图片域」，留给后面单独一轮。

## 四、页面上写进去的边界（不是注释，是用户看得见的文案）

- 顶部说明四条：搜索词报表**没有自动来源**（`AdvertisingApiClient` 只有活动/关键词/日报表三个通道，
  唯一写入口是本页录入）；规则执行**只产出建议**；**没有调度器**自动跑规则；
  `rule_type` 只是分类标签、不参与判定。
- 「上次执行」列 + 出建议的确认文案明说不会暂停投放、不会改价、不会加否词。
- 结果卡不自己宣布「已执行」：文案取自后端 `appliedToAdAccount`；
  后端若真返回 true，页面反而报一条越界警告。
- 空态各说各的原因：报表为空 = 通道缺失，不是「投放没词」；ASIN 反查为空 = 该表只由导入写入；
  分析扫描 0 行 = 「没有数据」，不是「表现良好」。
- 出单词自动提取的行不带 ASIN（报表没有 ASIN 维度），所以「按 ASIN 过滤」只对导入数据有效。
- 规则表单按后端的跨字段规则提前把关（BETWEEN 缺上界、限定范围缺 ID 时按钮不可用），
  但权威仍在后端：`POST` 被拒时表单不关、列表不刷、错误条保留。

## 五、闸口数字

- `vue-tsc --noEmit`：rc=0，0 行输出；
- 前端单测：**32 文件 / 320 例全绿**（本轮 +1 文件 +19 例，router 用例 7→8，合计 300→320）；
- `vite build`：rc=0，产出 `AdSearchTerms-*.js` 34.19 kB / css 5.34 kB；
- `repository_hygiene.py`：0 findings（路由文件改动后按既有流程把 pin 从 `2dda795a…` 换到 `1ce4dae5…`，
  只改这一行）；
- Java：ad 模块**全量 `mvn test`：135 例，0 失败，1 skip**（`AdMigrationMySqlIT` 需真实 MySQL），
  BUILD SUCCESS；其中本轮新写的 `AdAutoRuleSemanticsTest` 18 例。
- 前端变异检查 5 条全部被对应用例抓到（V1 刷新清掉越界警告、V2 删除绕过确认、
  V3 保存按钮不看校验、V4 导入不打店铺、V5 空数值原样发出），文件按 sha256 `d580fee8…` 复原后复跑绿。

E2E：本轮新增 3 例（规则渲染 / 出建议二次确认与结果文案 / 报表-分析-ASIN 各自取数），
`/ad/search-term` 全套 14 条路由登记进 hermetic 桩（batch 必须排在 `asin-reverse/` 通配之前，
无参根路径 `POST /ad/search-term` 放最后）。侧边栏导航烟测的 `NAV` 清单从 15 项加到 16 项（每项一个用例，全量数从 76 涨到 80 = +16 项里的 1 条 + 本轮 3 条）。

全量 E2E：**80 例 = 78 passed / 2 failed**（2.2 分钟）。两条失败是
`all-pages › Notifications 空态` 与 `full-interaction › Warehouse 新建仓库弹窗可打开并取消`，
**单独重跑这两条：2 passed（3.7 秒）**——与上一轮（连接器那轮）的失败对
（Finance 来源类型筛选 + ProductSearch 关键词搜索）不是同一批，符合已记录的冷启动抖动特征：
失败集会在全仓跑时在使用例之间漂移，CI `retries: 1` 吸收其中一条。
这条抖动本轮没有根治，也没有用重试把它盖成「稳定绿」。

## 六、本轮没做、也没假装做了的

1. **`POST /rule/execute/{shopId}` 没有界面入口。** 它逐条调用单条执行，页面上每行的「出建议」
   覆盖了同一语义；批量入口要的是「一次跑完并按规则分组看错误」，那需要另一套结果渲染，
   不在本轮范围内。接口层函数已经在 `adSearchTerms.ts` 里，不是缺口之外的凭空补全。
2. **搜索词报表的数据源没解决。** 接 SP-API 搜索词报表需要在 `AdvertisingApiClient` 加真实通道
   （报表创建→轮询→下载→解析），本机没有可用广告凭证，本轮不做 unverifiable 的实现；
   现在页面把这件事写在脸上，并给了手工录入这条路。
3. 规则命中后的**动作下发**仍不存在（PAUSE/ADD_NEGATIVE/预算类都没有 API 分支）。
   这是后端能力缺失，不是前端能补的，本轮只做成了「结果不许说已执行」。
4. 没有在真实 MySQL/ES 上验证任何一条查询；所有 SQL 断言止于 wrapper 生成的条件与绑定参数。
5. 上一轮遗留照旧：`Warehouse.vue` 用 `console.warn` 吞加载失败、报表模块新增端点仍需显式
   `ReportTenantGuard` 调用、`amz_logistics_quote` 删表要单独审批（不动共享库）。
