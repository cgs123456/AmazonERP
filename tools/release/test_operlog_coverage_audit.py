# -*- coding: utf-8 -*-
"""仓内审计覆盖盘点工具的契约测试。

测的不是"覆盖率是多少"（那是产品决策，见 tools/schema/operlog_coverage_audit.py
的模块 docstring——刻意不做成 CI 闸门），而是**尺子本身准不准**：
枚举口径漏一类端点、聚合分错模块、或者扫不到东西还报告"没问题"，
都会让下一班拿一张错的表做决定。§S23.3 修的那个扫描器盲区就是尺子自己的缺陷。

三条断言各有分工：
1. 分母守卫：扫不到端点必须报错退出，而不是打印 0 缺口
   （"0 缺口"会被读成"全覆盖"——这是解析失败最危险的伪装）；
2. 模块归属：不能把各行归到一个根本不存在的模块名下。
   这条来自本文件自己的事故：第一版用绝对路径 parts[1] 取模块名，
   在 Windows 上那是当前用户名（Users），于是 13 个模块全并成一行"Users"，
   汇总看着正常、分布完全错；
3. 口径宽度：@PatchMapping 必须在分母里。全仓当前 0 个 PATCH 端点，
   所以这条只能靠临时文件证明——正因为今天没人走过这条分支，
   才更要钉住它（否则将来真有人加 PATCH 端点，它会静默逃出盘点）。
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "tools" / "schema" / "operlog_coverage_audit.py"


class TestOperLogCoverageAudit(unittest.TestCase):

    def setUp(self):
        if not AUDIT.is_file():
            self.fail("operlog_coverage_audit.py 不存在——盘点工具被删了？")

    def run_audit(self, *args):
        return subprocess.run(
            [sys.executable, str(AUDIT), *args],
            cwd=str(ROOT), capture_output=True, text=True, timeout=180,
        )

    def test_empty_scan_fails_instead_of_reporting_zero_gaps(self):
        """扫不到端点必须 exit 2 并说明解析失效，不能输出 0 缺口。"""
        proc = self.run_audit("--module", "__definitely_no_such_module__")
        self.assertEqual(proc.returncode, 2, f"应报错退出；实际 exit={proc.returncode}")
        combined = proc.stdout + proc.stderr
        self.assertIn("扫描逻辑失效", combined, combined)

    def test_modules_are_attributed_per_service_not_a_single_bucket(self):
        """聚合出来的模块名必须是 amz-service-*，且至少 10 个（分布不能被并成一个）。"""
        proc = self.run_audit()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        module_lines = [
            line.split()[0] for line in proc.stdout.splitlines()
            if line.startswith("amz-service-")
        ]
        self.assertGreaterEqual(
            len(module_lines), 10,
            f"只解析出 {len(module_lines)} 个模块，聚合很可能被并成了一个桶：{module_lines}")
        for name in module_lines:
            self.assertTrue(
                name.startswith("amz-service-"),
                f"模块名形状不对：{name!r}（曾因用绝对路径切片而在 Windows 上变成用户名）")

    def test_patch_mapping_counts_as_a_write_endpoint(self):
        """@PatchMapping 必须计为写端点。

        全仓当前 0 个 PATCH 端点，所以「跑一遍真盘点」这条路是空转——那恰恰是
        最容易退化的分支（没人走、没人验）。因此这里造一个临时 Controller 真跑扫描器，
        而不是断言源码里有没有 "PatchMapping" 字样（后者只证明字符串在，
        证明不了正则真能匹配，是弱测试）。
        """
        sys.path.insert(0, str(ROOT / "tools" / "schema"))
        try:
            import operlog_coverage_audit as audit
        finally:
            sys.path.pop(0)

        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "FakeController.java"
            fake.write_text(
                "package com.amz.controller;\n"
                "import org.springframework.web.bind.annotation.*;\n"
                "@RestController\n"
                '@RequestMapping("/x")\n'
                "public class FakeController {\n"
                '    @PatchMapping("/partial/{id}")\n'
                "    public String partial(Long id) { return null; }\n"
                "}\n",
                encoding="utf-8",
            )
            endpoints = audit.scan_controller(fake)

        self.assertEqual(len(endpoints), 1, f"应扫到 1 个端点，实际 {endpoints}")
        self.assertEqual(endpoints[0]["verb"], "PATCH",
                         f"PATCH 端点被算成别的动作：{endpoints[0]['verb']}")


if __name__ == "__main__":
    unittest.main()
