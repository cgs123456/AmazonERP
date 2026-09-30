# 2026-09-30 跨服务调用系统性静默降级：`Result<T>` 无法被反序列化

> 结论先行：**全仓 20 个 `@FeignClient` 接口里 38 个返回 `Result<...>` 的方法，此前每一次调用都在解码阶段失败并被
> fallback 静默吞掉**——HTTP 往返真实发生、对端正常应答，但调用方读不回响应体。
> 根因只有一行：`com.amz.result.Result` 有两个带参构造器且都未标注 `@JsonCreator`，又没有无参构造器，
> Jackson 因此**没有任何 creator 可用**。修复是加一个 `@NoArgsConstructor`。
> 起因是 P2-1 traceId 实测过程中顺带看到的 `degraded: cause=Type definition error` 一行日志。

## 1. 症状与定位路径

在双服务探针（`product` → Feign → `order`）里，OAP 已证明 Exit/Entry span 成对存在、跨进程传播正常，
但调用方日志每次都有：

```
WARN c.a.c.f.OrderServiceFeignClientFallbackFactory - Feign call to amz-service-order (product)
  degraded: cause=Type definition error: [simple type, class com.amz.result.Result]
WARN c.amz.controller.ProductController - FBA 费率查询返回非 200：sizeTier=standard
```

`ProductController` 随后把它转成 `null` → 走硬编码估算值，响应里 `source=estimated`。
也就是说：**链路是通的，业务结果是错的，而且没有任何告警级别以上的信号。**

## 2. 真实异常（本地复现，非推断）

用与 Boot 注入给 Feign `SpringDecoder` 的同一个 mapper 构造方式
（`Jackson2ObjectMapperBuilder.json().build()`）解码一个正常响应体：

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
Cannot construct instance of `com.amz.result.Result` (no Creators, like default constructor, exist):
cannot deserialize from Object value (no delegate- or property-based Creator)
```

`Result` 的实际形状：`@Data` + 两个 public 构造器 `Result(String,int,T)`、`Result(String,int,T,ApiError)`，
无 `@NoArgsConstructor`、无 `@JsonCreator`、无 `@JsonDeserialize(builder=…)`。

### 2.1 一个被证伪的流行解释

第一份外部分析给出的原因是"`maven-compiler-plugin` 没配 `<parameters>true</parameters>`，
参数名不在 class 文件里，所以 properties-based creator 用不了"。**这是错的**：

- `spring-boot-starter-parent:3.5.16` 的 `pluginManagement` 里就有 `<parameters>true</parameters>`；
- `javap -v` 看 `Result.class` 有 14 个 `MethodParameters` 属性，参数名确实在。

真正的机制是"多个候选构造器 + 无注解 + 无默认构造器"，与参数名无关。
记录这一条是因为它足够可信、极易被直接采信，而两种诊断会导向完全不同的修法。

## 3. 影响面（逐个核对，不外推）

| 类别 | 结果 |
|---|---|
| `@FeignClient` 接口 | 20 个（`amz-common`/`amz-gateway` 内无），共 44 个方法，其中 38 个返回 `Result<...>` |
| 受影响方法 | 所有返回 `Result<...>` 的 **38 个**（独立复核：20 个接口分布在 ai 8 / order 3 / report 3 / finance 2 / product 2 / search 1 / ops 1；外壳就是 `Result`，与泛型实参无关） |
| 天然绕开的 | `order → finance`（返回 `void`）、`report` 的 6 个方法（返回 `Map<String,Object>`） |
| `report` 的另一处契约错位 | 那 6 个方法把整封 `{code,message,data}` 信封当业务载荷解析，不报错但语义错——属同一族的静默失真 |
| 泛型实参是否也有同类风险 | 逐个查过 class 文件：`Product`、`User`、`RemoteBatchCostSummary`、`RemoteFeeEstimate`、`RemoteReportInfo` **都有无参构造器** ⇒ 只有 `Result` 一个卡点 |

## 4. 为什么没有任何测试抓到

- 既有的 `ApiErrorSerializationTest` / `PagingContractTest` 只测**写方向**（`writeValueAsString`）；
  序列化不需要 creator，所以"JSON 契约"全绿而解码一直是坏的。
- 全仓没有任何测试做一次真实的跨服务 HTTP 往返：Feign 相关测试只断言注解与路径契约
  （如 `ProcurementCostClientContractTest`），其余是 Mockito 打桩的 service 测试，
  打桩直接绕过编解码。

## 5. 修复与取舍

改 `amz-common/src/main/java/com/amz/result/Result.java`：加 `@NoArgsConstructor`，并在类注释里写清
"这不是便利性的加法而是服务间调用的硬要求，删掉即复现故障"。

- 为什么不选 `@JsonCreator`+逐参数 `@JsonProperty`：能修，但要为两个构造器共同维护参数名映射，
  且 `data` 是泛型参数；`ApiError`/`PageMeta`/其余跨服务 DTO 本来就都用无参构造器 + setter 这条路，
  保持一致更好。
- 为什么不是去配 `<parameters>`：见 §2.1，它本来就是开的。
- 兼容性：`@Data` 早就生成了 public setter，本类从来不是不可变 DTO；新增无参构造器不改变任何
  现有序列化输出（写方向 23 个既有断言全绿可证）。

## 6. 验证

新增 `amz-common/src/test/java/com/amz/result/ResultJsonDecodeContractTest.java`（4 条，全走读方向）：
普通成功体、失败体（含 `error` 还原）、`_page` / `_hiddenFields` 线名解码、写出读回往返。

| 验证 | 结果 |
|---|---|
| 修复前 | 4/4 抛 `InvalidDefinitionException`（即 §2 的复现） |
| 修复后 | 4/4 绿；与 `ApiErrorSerializationTest`、`PagingContractTest` 同跑 23/23 绿 |
| 反向验证 | 删掉 `@NoArgsConstructor` → **4/4 红**；还原 → 4/4 绿 |
| 运行时对照 | 同一双服务链路：修复前每次请求必现 `degraded: cause=Type definition error`；修复后本次窗口内降级/非 200 告警 **0 行** |
| 全仓终态 | `mvn -o -B clean verify -fae` → **BUILD SUCCESS，VERIFY_RC=0，19/19 模块成功**（`Result` 是全仓共用响应外壳，故必须整仓验） |

注：修复后 `product` 仍可能返回 `source=estimated`——直连 `order` 的 `/order/fees/lookup` 在演示库里返回
`{code:200, data:null}`（没有匹配的费率行），这是正当的业务降级，不是本缺陷。要拿 `source=real`
需要造数据，属另一件事（本轮刻意不向在跑的演示库写数据）。

## 7. 遗留建议

1. **fallback 把故障藏得太深**：20 个 `fallbackFactory` 都只 `log.warn("…cause={}", cause.getMessage())`，
   丢堆栈与响应体；其中 3 个（report）直接返回 `Collections.emptyMap()`，与"确实没数据"无法区分。
   建议：解码/连接类异常单独打可告警的级别或指标，并让降级在响应里显式可见（类似 `source=estimated` 的做法）。
2. `report` 那 6 个返回裸 `Map` 的方法应改为 `Result<Map<…>>` 或明确契约，消除信封当载荷的语义错位。
3. 补一条真实的跨服务往返测试（哪怕用 WireMock 起一个返回标准 `Result` 的桩），让 §4 的盲区不再复现。
