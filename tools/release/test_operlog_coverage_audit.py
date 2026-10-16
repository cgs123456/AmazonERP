# -*- coding: utf-8 -*-
"""仓内审计覆盖盘点工具的契约测试。

测的不是"覆盖率是多少"（那是产品决策，见 tools/schema/operlog_coverage_audit.py
的模块 docstring——刻意不做成 CI 闸门），而是**尺子本身准不准**：
枚举口径漏一类端点、聚合分错模块、或者扫不到东西还报告"没问题"，
都会让下一班拿一张错的表做决定。§S23.3 修的那个扫描器盲区就是尺子自己的缺陷。

几条断言各有分工：
1. 分母守卫：扫不到端点必须报错退出，而不是打印 0 缺口
   （"0 缺口"会被读成"全覆盖"——这是解析失败最危险的伪装）；
2. 模块归属：不能把各行归到一个根本不存在的模块名下。
   这条来自本文件自己的事故：第一版用绝对路径 parts[1] 取模块名，
   在 Windows 上那是当前用户名（Users），于是 13 个模块全并成一行"Users"，
   汇总看着正常、分布完全错；
3. 口径宽度：@PatchMapping 必须在分母里。全仓当前 0 个 PATCH 端点，
   所以这条只能靠临时文件证明——正因为今天没人走过这条分支，
   才更要钉住它（否则将来真有人加 PATCH 端点，它会静默逃出盘点）。

§S29 新增三条，各自对应一次实测缺陷（不是假想的加固）：
4. @RequireRole 写在 @PostMapping **下面**也算已锁（区间只到 Mapping 行会漏判）；
5. **多行签名**（参数换行）同样不能让 @RequireRole 消失——第一版修复按花括号
   配平攒字符反而更错：参数行以 @RequestParam 开头被当注解跳过，闭合的 ) 丢了、
   配平永不成立；
6. 自我服务端点不计入"待补角色"（锁了会打错对象，见 is_self_service 的注释）。
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "tools" / "schema" / "operlog_coverage_audit.py"

# 临时 Controller 源码。**用普通字符串 + chr(10) 拼接，不用 \n 字面量**：
# 这些内容此前两次被 heredoc/转义弄坏成"未闭合字符串"——源码里的引号和花括号
# 已经够多了，再叠一层转义只会让下一处修改踩同一个坑。
JAVA_TEMPLATE = chr(10).join([
    "package com.amz.controller;",
    "import org.springframework.web.bind.annotation.*;",
    "@RestController",
    '@RequestMapping("/x")',
    "public class FakeController {",
    "{METHODS}",
    "}",
    "",
])


def fake_controller(method_source: str) -> str:
    """把一段 Java 方法源码嵌进临时 Controller。"""
    return JAVA_TEMPLATE.replace("{METHODS}", method_source)


class TestOperLogCoverageAudit(unittest.TestCase):

    def setUp(self):
        if not AUDIT.is_file():
            self.fail("operlog_coverage_audit.py 不存在——盘点工具被删了？")

    def run_audit(self, *args):
        # encoding 必须显式给 utf-8。**不给会静默失败**：Windows 控制台默认 GBK，
        # 而盘点脚本的中文输出里含 GBK 编不了的字符，读子进程输出的 reader 线程会抛
        # UnicodeDecodeError 死掉，proc.stdout 变成 None——于是断言报的是
        # "'NoneType' object is not subscriptable"，看起来像被测脚本坏了，
        # 实际 returncode 是对的（§S29 实测踩到：exit=2 但 stdout=None）。
        return subprocess.run(
            [sys.executable, str(AUDIT), *args],
            cwd=str(ROOT), capture_output=True, text=True, timeout=180,
            encoding="utf-8", errors="replace",
        )

    def load_audit(self):
        sys.path.insert(0, str(ROOT / "tools" / "schema"))
        try:
            import operlog_coverage_audit as audit
        finally:
            sys.path.pop(0)
        return audit

    def test_empty_scan_fails_instead_of_reporting_zero_gaps(self):
        """扫不到端点必须 exit 2 并说明解析失效，不能输出 0 缺口。"""
        proc = self.run_audit("--module", "__definitely_no_such_module__")
        self.assertEqual(proc.returncode, 2, f"应报错退出；实际 exit={proc.returncode}")
        combined = (proc.stdout or "") + (proc.stderr or "")
        self.assertIn("扫描逻辑失效", combined, combined)

    def test_modules_are_attributed_per_service_not_a_single_bucket(self):
        """聚合出来的模块名必须是 amz-service-*，且至少 10 个（分布不能被并成一个）。"""
        proc = self.run_audit()
        self.assertEqual(proc.returncode, 0, (proc.stdout or "") + (proc.stderr or ""))
        module_lines = [
            line.split()[0] for line in (proc.stdout or "").splitlines()
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
        最容易退化的分支（没人走、没人验）。因此这里造一个临时 Controller 真跑描器，
        而不是断言源码里有没有 "PatchMapping" 字样（后者只证明字符串在，
        证明不了正则真能匹配，是弱测试）。
        """
        audit = self.load_audit()
        method = chr(10).join([
            '    @PatchMapping("/partial/{id}")',
            "    public String partial(Long id) { return null; }",
        ])
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "FakeController.java"
            fake.write_text(fake_controller(method), encoding="utf-8")
            endpoints = audit.scan_controller(fake)

        self.assertEqual(len(endpoints), 1, f"应扫到 1 个端点，实际 {endpoints}")
        self.assertEqual(endpoints[0]["verb"], "PATCH",
                         f"PATCH 端点被算成别的动作：{endpoints[0]['verb']}")

    def test_require_role_after_mapping_is_still_detected(self):
        """@RequireRole 写在 @PostMapping **下面**也必须算「已锁角色」。

        这是实测出来的口径缺陷（§S29），不是假想：AgentMemoryController.scanReminders
        的写法就是 `@PostMapping` 在上、`@RequireRole({"ADMIN"})` 在下，而第一版区间
        只取到 Mapping 行，于是这个已锁 ADMIN 的端点被列进「没有角色门禁」的待办里——
        **拿一张错的表做决定，比没表更危险**：下一班会去给一个已经锁好的端点再加一道锁。
        """
        audit = self.load_audit()
        method = chr(10).join([
            '    @PostMapping("/locked-below")',
            '    @RequireRole({"ADMIN"})',
            "    public Result<String> lockedBelow() { return null; }",
            '    @PostMapping("/locked-above")',
            "    public Result<String> lockedAbove() { return null; }",
        ])
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "FakeController.java"
            fake.write_text(fake_controller(method), encoding="utf-8")
            endpoints = {e["method"]: e for e in audit.scan_controller(fake)}

        self.assertEqual(len(endpoints), 2, f"应扫到 2 个端点，实际 {list(endpoints)}")
        self.assertTrue(endpoints["lockedBelow"]["role"],
                        "@RequireRole 在 @PostMapping 之下时未被识别（§S29 的实测缺陷）")
        self.assertFalse(endpoints["lockedAbove"]["role"],
                         "对照组失效：没写 @RequireRole 的端点竟被判为已锁")

    def test_multi_line_signature_keeps_require_role_visible(self):
        """多行签名（参数换行）不能把 @RequireRole 弄丢。

        §S29 实测缺陷：KnowledgeController.uploadDocument 的注解+签名跨 5 行
        （@PostMapping → 注释 → @RequireRole → 方法名行 → 两个 @RequestParam 行），
        签名识别找不到「签名末行」→ 上界退回 Mapping 行 → 写在 Mapping 下面的
        @RequireRole 被判成不存在，**一个刚加好锁的端点又出现在缺口表里**。

        第一版修复按花括号配平攒字符，反而更错：参数行以 @RequestParam 开头被当注解
        跳过，闭合的 ) 丢了、配平永不成立。最终改成「找第一个方法体开括号」。
        这条测试钉的是最终形态，不是中间那个错版本。
        """
        audit = self.load_audit()
        method = chr(10).join([
            '    @PostMapping(value = "/docs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)',
            '    @RequireRole({"OPERATOR", "ADMIN"})',
            "    public Result<Map<String, Object>> upload(",
            '            @RequestParam("file") MultipartFile file,',
            '            @RequestParam("shopId") Long shopId) {',
            "        return null;",
            "    }",
        ])
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "FakeController.java"
            fake.write_text(fake_controller(method), encoding="utf-8")
            endpoints = audit.scan_controller(fake)

        self.assertEqual(len(endpoints), 1, f"应扫到 1 个端点，实际 {endpoints}")
        self.assertTrue(endpoints[0]["role"],
                        "多行签名下 @RequireRole 被判成不存在（§S29 实测缺陷）")

    def test_self_service_endpoint_is_not_counted_as_role_gap(self):
        """改自己数据的端点不得算作「待补角色」。

        §S29：盘点表把「无 @RequireRole」当成待办，但 editInfo/saveOrder 这类
        自助端点**锁了会打错对象**——加 OPERATOR 门禁等于让 VIEWER 连自己的
        头像都不能换。它们必须被单列出来，而不是混在缺口里让下一位逐个读源码判断。

        这条同时钉住**假阳性不能反噬**：操作对象来自 @PathVariable 的端点
        （如 updateRole，锁 ADMIN）不得因为方法体里出现过 getUserId() 就被
        标成自助——那个 getUserId() 只用来拒绝「改自己」。
        """
        audit = self.load_audit()
        self_service = chr(10).join([
            "    @PostMapping(\"/profile\")",
            "    public Result<Void> editProfile(ProfileDto dto) {",
            "        Integer userId = UserContext.getUserId();",
            "        profileMapper.eq(User::getId, userId);",
            "        return Result.success(null);",
            "    }",
        ])
        foreign_target = chr(10).join([
            "    @PostMapping(\"/{userId}/role\")",
            "    public Result<User> updateRole(@PathVariable Integer userId) {",
            "        Integer current = UserContext.getUserId();",
            "        if (current.equals(userId)) {",
            "            return Result.failure(\"不能改自己\");",
            "        }",
            "        return Result.success(userService.updateRole(userId));",
            "    }",
        ])
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "FakeController.java"
            fake.write_text(
                fake_controller(self_service + chr(10) + foreign_target),
                encoding="utf-8",
            )
            endpoints = {e["method"]: e for e in audit.scan_controller(fake)}

        self.assertEqual(len(endpoints), 2, f"应扫到 2 个端点，实际 {list(endpoints)}")
        self.assertTrue(endpoints["editProfile"]["self_service"],
                        "改自己数据的端点未被识别为自我服务（会被误列进待补角色）")
        self.assertFalse(endpoints["updateRole"]["self_service"],
                         "操作对象来自 @PathVariable 的端点被误判为自我服务——"
                         "那会让一个该锁 ADMIN 的端点从待锁清单里消失")


if __name__ == "__main__":
    unittest.main()
