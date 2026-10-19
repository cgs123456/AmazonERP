# -*- coding: utf-8 -*-
"""租户边界审计工具的契约测试（tools/release/tenant_boundary_audit.py）。

测的不是"仓里有没有越权漏洞"（那会随代码变），而是**尺子本身准不准**：
漏判会让真正的越权混在"没问题"里；误判会让审计退化成人肉再 grep 一遍。

三条断言各自对应一次**实测踩到**的缺陷，不是凭空加固：

1. try 块内的 mapper 访问：第一版从访问行向上找方法起点，停在最近的
   `try {`（try 的闭合括号在访问行下方、永远不会被计数），
   于是 ListingPriceRuleController.execute 里写在 try 之上的
   `!rule.getShopId().equals(shopId)` 一次都没被看到（22 条候选里的误报）。
2. 类级 @RequestMapping 不是端点。按端点口径去查会把整个类报一遍
   （InventoryController / ShopCredentialController / SpApiOperationController
   三条误报，都是类级注解）。
3. 真没守护的代码必须报出来——否则一个把一切都判成"已守护"的实现
   也能让前两条全绿。

源码用普通字符串 + chr(10) 拼接，不用 \\n 字面量：本目录的测试此前两次
被 heredoc/转义弄成"未闭合字符串"，源码里引号和花括号已经够多，
再叠一层转义只会让下一处修改踩同一个坑。
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "tools" / "release" / "tenant_boundary_audit.py"

# 守护写在内层块之上：synchronized 同时命中"方法声明"正则（有修饰符位）
# 和"控制流"正则——只有后者生效才会继续往上找到真正的方法。
# 若守护行在嵌套块的**上方**，按块判定就会看不到它。
GUARDED_ABOVE_BLOCK = chr(10).join([
    "package com.amz.controller;",
    "public class FakeController {",
    "    @PostMapping('/{id}/execute')",
    "    public Result<Rule> execute(@PathVariable Long id, @RequestParam Long shopId) {",
    "        Rule rule = ruleMapper.selectById(id);",
    "        if (rule == null || !rule.getShopId().equals(shopId)) {",
    "            return Result.failure('nope');",
    "        }",
    "        synchronized (this) {",
    "            ruleMapper.updateById(rule);",
    "        }",
    "        return Result.success(rule);",
    "    }",
    "}",
])

# 变体：守护写在 try 之上。try 缩进 8 格时它不匹配"方法声明"（修饰符位要求
# 缩进 <= 4），所以这条单独钉住"缩进恰好让它看起来像方法声明"的形态。
GUARDED_ABOVE_TRY = chr(10).join([
    "package com.amz.controller;",
    "public class FakeController {",
    "    @PostMapping('/{id}/execute')",
    "    public Result<Rule> execute(@PathVariable Long id, @RequestParam Long shopId) {",
    "        Rule rule = ruleMapper.selectById(id);",
    "        if (rule == null || !rule.getShopId().equals(shopId)) {",
    "            return Result.failure('nope');",
    "        }",
    "try {",
    "            ruleMapper.updateById(rule);",
    "}",
    "        return Result.success(rule);",
    "    }",
    "}",
])

# 类级 @RequestMapping 且注解块里提到 shopId。
#
# 这是本仓误报的真实形状（InventoryController / ShopCredentialController /
# SpApiOperationController 三条）：类级注解上方是 javadoc，javadoc 里写了
# "shopId"，于是"提到 shopId"成立、而注解块里没有 @ShopScoped，
# 就被报成"shop 相关但无守护的端点"。不带 javadoc 的类级注解根本走不到
# 这一步（mentions_shop 先否掉了），所以夹具必须带上这段文字。
CLASS_LEVEL_MAPPING = chr(10).join([
    "package com.amz.controller;",
    "@RestController",
    "/** 本类所有端点都以 shopId 为维度查询，网关路由 /spapi/** 。 */",
    "@RequestMapping('/inventory')",
    "public class FakeController {",
    "    @GetMapping('/list')",
    "    public Result<String> list(@RequestParam Long shopId) {",
    "        return Result.success('ok');",
    "    }",
    "}",
])

# 真没守护：按 id 取一行就写回，方法体内没有任何 shop 校验。
# 只留 selectById 一条访问：三个真实缺陷（§S33.91/§S33.92/§S33.108）
# 全是这个形状，写两条访问时"漏掉 selectById 的检测"会被另一条掩盖而存活。
UNGUARDED = chr(10).join([
    "package com.amz.service;",
    "public class FakeService {",
    "    public void touch(Long id) {",
    "        Row row = rowMapper.selectById(id);",
    "        row.setMemo('x');",
    "    }",
    "}",
])

# 守护走的是 requireXxxAccess 家族的 helper（不是 requireShopAccess 这一种）。
# 本仓真实形态：AdAutoRuleServiceImpl.toggleRule 走 requireRuleAccess，
# 第一版 GUARD_NAME 把 helper 名字一个个枚举，漏了 requireRuleAccess，
# 于是这条被报成无守护（§S33.109 的 89 条候选里有 1 条是这么来的）。
GUARDED_BY_NAMED_HELPER = chr(10).join([
    "package com.amz.service;",
    "public class FakeService {",
    "    public boolean toggle(Long ruleId) {",
    "        Rule rule = requireRuleAccess(ruleId);",
    "        rule.setEnabled(1);",
    "        ruleMapper.updateById(rule);",
    "        return true;",
    "    }",
    "    private Rule requireRuleAccess(Long ruleId) {",
    "        return ruleMapper.selectById(ruleId);",
    "    }",
    "}",
])

# 类级 @RequestMapping 且方法体内有真没守护的访问：
# 用来钉住"类级注解算端点"这个变异——本仓没有带 data-access 的类级端点，
# 所以只有 endpoint 断言的话该变异是等价的，必须让两种判定落在同一份源码上。
CLASS_LEVEL_WITH_UNGUARDED_ACCESS = chr(10).join([
    "package com.amz.controller;",
    "@RestController",
    "/** 本类所有端点都以 shopId 为维度查询。 */",
    "@RequestMapping('/inventory')",
    "public class FakeController {",
    "    @GetMapping('/list')",
    "    public Result<Row> list(@RequestParam Long shopId) {",
    "        return Result.success(rowMapper.selectById(shopId));",
    "    }",
    "}",
])


def run_audit(source: str, filename: str) -> tuple[int, str]:
    """把一份 Java 源码放进临时模块目录里跑工具，返回 (退出码, 输出)。"""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        pkg = root / "amz-service" / "fake" / "src" / "main" / "java" / "com" / "amz"
        pkg.mkdir(parents=True)
        (pkg / filename).write_text(source, encoding="utf-8")
        proc = subprocess.run(
            [sys.executable, "-I", str(AUDIT), "--root", str(root), "--modules", "fake"],
            capture_output=True,
            text=True,
        )
    return proc.returncode, proc.stdout + proc.stderr


class TenantBoundaryAuditTest(unittest.TestCase):
    """尺子本身的行为契约。"""

    def test_guard_above_synchronized_block_is_recognised(self):
        """守护写在 synchronized 之上时不能误报：按"块"判定会看不到它。"""
        code, out = run_audit(GUARDED_ABOVE_BLOCK, "FakeController.java")
        self.assertNotIn("data-access", out,
                         "守护写在内层块之上却被判为无守护——判定又停在了最内层块：" + out)

    def test_guard_above_zero_indent_try_is_recognised(self):
        """零缩进 try 形似方法声明时，仍要一路找到真正的方法。"""
        code, out = run_audit(GUARDED_ABOVE_TRY, "FakeController.java")
        self.assertNotIn("data-access", out,
                         "零缩进 try 被当成了方法声明，守护行落在它上面就看不见了：" + out)

    def test_guard_helper_named_require_xxx_access_is_recognised(self):
        """requireXxxAccess 家族的 helper 必须算守护，不能只认 requireShopAccess。

        本仓真实形态：AdAutoRuleServiceImpl.toggleRule 走 requireRuleAccess，
        第一版把 helper 名逐个枚举（requireShop|requireOwner|...），
        漏了 requireRuleAccess，于是那条被报成无守护。
        """
        code, out = run_audit(GUARDED_BY_NAMED_HELPER, "FakeService.java")
        self.assertNotIn("data-access", out,
                         "requireRuleAccess 这类命名未被识别为守护，候选清单会被假阳性刷屏：" + out)

    def test_class_level_mapping_is_not_an_endpoint(self):
        """类级 @RequestMapping 不是端点，不该进候选清单。

        用"类级注解 + 方法内真无守护访问"这一份源码：只断言 endpoint 不出现时，
        "类级注解算端点"这个变异在本仓是等价的（没有带 data-access 的类级端点），
        变异会存活。让两份判定落在同一份源码上，它才真的被钉住。
        """
        code, out = run_audit(CLASS_LEVEL_WITH_UNGUARDED_ACCESS, "FakeController.java")
        self.assertNotIn("endpoint", out,
                         "类级 @RequestMapping 被当成端点，候选会被整类刷屏：" + out)
        self.assertIn("data-access", out,
                      "同一份源码里真无守护的访问仍应被报出（不能为了排除类级注解把访问也放过）："
                      + out)

    def test_unguarded_access_is_reported(self):
        """反向断言：真没守护的必须报出来，否则"全判已守护"也能全绿。"""
        code, out = run_audit(UNGUARDED, "FakeService.java")
        self.assertEqual(1, code, "无守护的 selectById 没被报出：" + out)
        self.assertIn("FakeService", out, out)

    def test_missing_service_root_exits_two(self):
        """扫不到东西要报错退出，不能打印 0 候选假装"没问题"。"""
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run(
                [sys.executable, "-I", str(AUDIT),
                 "--root", str(Path(tmp) / "nope"), "--modules", "fake"],
                capture_output=True,
                text=True,
            )
        self.assertEqual(2, proc.returncode,
                         "根路径不存在时应退出 2，实际=" + str(proc.returncode))

    def test_audit_scans_per_method_not_per_window(self):
        """判定必须按整个方法体，不是按固定行窗口。"""
        src = AUDIT.read_text(encoding="utf-8")
        self.assertIn("_method_spans", src,
                      "缺少按方法切分的实现：固定行窗口会漏掉远离访问点的守护")
        self.assertNotIn("_method_bounds(", src.replace("def _method_bounds(", ""),
                         "旧的向上扫描实现还在：它会在 try { 处停下")


if __name__ == "__main__":
    unittest.main()
