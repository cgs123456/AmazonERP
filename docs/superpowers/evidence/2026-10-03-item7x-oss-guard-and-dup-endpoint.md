# 7x：非凭据缺口的逐项处理——OSS 未配置改显式拒绝，删零调用方的重复报表端点

日期：2026-10-03 · 分支：master · 前提：用户指示「需要 API key 的先不管，逐项处理其他缺口」

## 1. 这一轮处理的是 D 桶两条（不涉及凭据）

| 端点 | 原判定 | 处理 |
| --- | --- | --- |
| `GET /ad/report/{shopId}` | 与已接的 `GET /ad/reports?shopId=` 同服务方法 `getShopReports`，属重复口径 | 证明零调用方（前端只用 `/ad/reports`，全仓无 Feign 引用）后**整块删除** |
| `POST /user/updateImage` | 指向阿里云 OSS，而配置默认值是 `your-access-key-id` / 空串 / `your-bucket-name` | 不接 UI、不假装能上传；改为**未配置即显式拒绝** |

## 2. updateImage 原来的失败形态为什么不算「已知限制」

`OssConfig` 直接把带占位符的三项注入 `OssUtil`，`uploadImg` 不做任何判断就
`OSSClientBuilder().build(...)` + `putObject`，最终抛 SDK 的 `ClientException`，
被全局兜底成 500「服务器内部错误」。调用方与运维都看不出真实原因是**这台机器根本没配凭据**——
这不是「功能未开放」，是把配置缺失伪装成系统故障。

守卫放在 `OssUtil`（唯一接缝，`uploadImg`/`deleteImg` 共用），判定条件就是配置的默认值本身：
空值或以 `your-` 开头即未配置；`uploadImg` 还额外要求 `accessUrl` 非空——
上传成功却返回一个打不开的 URL，比拒绝更坏。

## 3. 写测试时抓到的第二个问题（不是测试写错）

`deleteImg` 外层是 `catch (Exception e) { throw new RuntimeException("删除OSS图片失败", e); }`。
我第一版把守卫插在 `try` 内，测试立刻报
`expected: <CodeErrorException> but was: <RuntimeException>`——
**业务拒绝被兜底 catch 洗成了不透明的删除失败**，正是这轮要修的那个毛病换了个位置。
把守卫挪到 `try` 之前才对（代码里留了两行说明为什么必须在外面）。

## 4. 验收

| 检查 | 结果 |
| --- | --- |
| `mvn -pl amz-common,...-ad,...-user -am test` | **rc=0，BUILD SUCCESS**（含 amz-common 181 条） |
| 新增 `OssUtilConfigGuardTest` | 4 passed（只测判定与拒绝，**不发任何网络请求**） |
| 变异：注释掉 `uploadImg` 的守卫调用 | Tests run: 4, **Failures: 3**，恢复后按 sha256 校验一致 |
| 删除重复端点后的清点 | `user_facing_candidates 36 → **35**`、`endpoints_without_a_frontend_name 55 → 54`、`--self-test 7/7`、`unparsed=0` |
| 列漂移 | `--gate rc=0`，`drifted_tables=0 pending_registered=0`（仍是零豁免） |

过程里我自己犯了三个小错，都记下来免得下次再犯：Java 字符串字面量不能断行（编译报
`unclosed string literal`）；删方法时按 `行号-1` 估边界会留下一个孤立 `/**`，
而它是**缩进 4 格**的、按顶格匹配找不到；`l.strip() == '        requireConfigured(...)'`
这种「把缩进算进常量里」的比较永远不相等，导致脚本 StopIteration 后**整段没落盘**，
看起来像"改了没生效"。
