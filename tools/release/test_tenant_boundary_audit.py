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

# 类级 @RequestMapping：不是端点。
CLASS_LEVEL_MAPPING = chr(10).join([
    "package com.amz.controller;",
    "@RestController",
    "@RequestMapping('/inventory')",
    "public class FakeController {",
    "    @GetMapping('/list')",
    "    public Result<String> list(@RequestParam Long shopId) {",
    "        return Result.success('ok');",
    "    }",
    "}",
])

# 真没守护：按 id 取一行就写回，方法体内没有任何 shop 校验。
UNGUARDED = chr(10).join([
    "package com.amz.service;",
    "public class FakeService {",
    "    public void touch(Long id) {",
    "        Row row = rowMapper.selectById(id);",
    "        rowMapper.updateById(row);",
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

    def test_class_level_mapping_is_not_an_endpoint(self):
        """类级 @RequestMapping 不是端点，不该进候选清单。"""
        code, out = run_audit(CLASS_LEVEL_MAPPING, "FakeController.java")
        self.assertNotIn("endpoint", out,
                         "类级 @RequestMapping 被当成端点，候选会被整类刷屏：" + out)

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
