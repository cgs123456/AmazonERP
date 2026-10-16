# -*- coding: utf-8 -*-
"""源码行数棘轮（tools/release/file_size_ratchet.py）的契约测试。

测的是**棘轮的三个方向**，缺一个它就不再是棘轮：
1. 新的超限文件必须红 —— 这是闸门的全部意义。存量 59 个文件拆不过来，
   但可以也应该做到「不再多一个」。
2. 已登记的超限文件**变长**必须红，**变短**必须放行 —— 中间态最容易写错成
   「只要不超过 LIMIT 就行」，那样存量债会一路涨到 500 行才拦。
3. 基线里的**陈旧条目**必须红。少了这条，拆完文件的人只要删掉基线一行就万事大吉，
   但反过来若文件被重命名/拆分，基线会留下指向不存在路径的条目，
   而"0 违规"会被读成"债务已清"。

另外两条来自本班实际踩的坑，不是假想加固：
4. **合规的小文件绝不能被报**（第一版忘了 continue，于是每个不在基线里的源码文件
   都被报成「107 行超过 500 行」——报错与判据脱节的 bug 比漏报更难查，因为它看起来
   像是别的东西坏了）。
5. **分母守卫**：扫不到文件 + 基线为空时必须报错退出，不能打印 0 违规。

测试不碰真实仓库（除一处口径核对），所以判定逻辑走 `scan(files=...)` 注入，
收集逻辑走 `qualifies()` 直测。分开的理由：前者要构造 git 仓库才跑得动，
把两件事缠在一个测试里会让失败原因不可归因。
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "release"))

import file_size_ratchet as ratchet  # noqa: E402


def make_file(base: Path, relative: str, lines: int) -> Path:
    path = base / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(f"line {i}" for i in range(lines)) + "\n", encoding="utf-8")
    return path


class TestRatchetVerdicts(unittest.TestCase):
    """判定逻辑：给定「磁盘上的行数」与「基线」，该报什么。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def scan(self, files: dict[str, int], baseline: dict[str, int]):
        """files = {相对路径: 行数}，在临时目录里真造出这些文件。"""
        for relative, lines in files.items():
            make_file(self.base, relative, lines)
        return ratchet.scan(self.base, baseline, explicit_files=[
            self.base / relative for relative in files
        ])

    def test_new_oversized_file_is_flagged(self):
        findings, counts = self.scan(
            {"src/Foo.java": 600}, {})
        self.assertEqual([f.rule for f in findings], ["oversized-new-file"])
        self.assertIn("600", findings[0].detail)
        self.assertEqual(counts, {"src/Foo.java": 600})

    def test_compliant_file_is_never_flagged(self):
        """**这条钉住本班实际犯的 bug**：第一版把「不在基线里」当成违规，
        却没有先排除合规文件，于是 107 行的文件被报成「超过 500 行」。

        报错信息与判据脱节的缺陷比漏报更难查——它会让人去查那个 107 行的文件，
        而真正坏的是扫描器。
        """
        findings, counts = self.scan(
            {"src/Small.java": 107, "src/Empty.java": 0}, {})
        self.assertEqual(findings, [], f"合规文件被误报：{[f.to_dict() for f in findings]}")
        self.assertEqual(counts, {}, "合规文件不该进基线")

    def test_recorded_file_may_shrink_but_not_grow(self):
        """棘轮的本体：变长红、变短绿、降到阈值以下要求更新基线。

        第三条（shrink below the limit）是我一开始写错、被工具推翻的一条。
        我原本断言「480 行（已从 900 还到合规）不该报任何东西」，工具报了 stale。
        查过才知道工具对：

            基线留 {Big: 900}，文件还到 480 → 不报 → 基线仍 900
            → 文件再涨回 899 → 899 > 900 为假 → 不报 grew
            → **债无声涨回原额，棘轮形同虚设**

        所以「合规了但基线还挂着」必须报，逼人 --update-baseline 把条目摘掉。
        这不是拦住还款，而是还款的**记账步骤**不能省。
        """
        grew, _ = self.scan({"src/Big.java": 520}, {"src/Big.java": 500})
        self.assertEqual([f.rule for f in grew], ["oversized-file-grew"])
        self.assertIn("+20", grew[0].detail, "必须报出增量，否则读不出是谁加了多少")

        # 仍在阈值以上、只是变短：放行
        shrank, counts = self.scan({"src/Big.java": 700}, {"src/Big.java": 900})
        self.assertEqual(shrank, [], "变短必须放行——棘轮不能拦住还款")
        self.assertEqual(counts, {"src/Big.java": 700}, "还款后的新值应进新基线")

        # 降到阈值以下：报 stale，要求把这行从基线摘掉（见 docstring 的债回升证明）
        repaid, counts = self.scan({"src/Big.java": 480}, {"src/Big.java": 900})
        self.assertEqual([f.rule for f in repaid], ["stale-baseline-entry"],
                         f"还到合规但基线仍挂着，必须报 stale 逼人更新："
                         f"{[f.to_dict() for f in repaid]}")
        self.assertEqual(counts, {}, "已降到阈值以下的文件不该再进 counts")

    def test_stale_baseline_entry_is_flagged(self):
        """基线条目对应文件已删除/已合规/不在统计口径内 → 必须报陈旧。

        少了这条，基线只会单调增长：每次拆分留下的旧条目没人清，
        于是「59 个待拆」这个数永远降不下去，读者以为债务没动过。
        """
        findings, _ = self.scan({"src/Gone.java": 600}, {
            "src/Gone.java": 700,      # 合法：变短，不该报
            "src/Missing.java": 600,   # 文件不存在 → 陈旧
            "src/AlsoGone.java": 900,  # 文件不存在 → 陈旧
        })
        stale = [f for f in findings if f.rule == "stale-baseline-entry"]
        self.assertEqual(sorted(f.path for f in stale),
                         ["src/AlsoGone.java", "src/Missing.java"],
                         f"陈旧条目应恰好是这两个：{[f.to_dict() for f in findings]}")
        self.assertEqual(len(findings), 2,
                         f"src/Gone.java 变短了不该被报：{[f.to_dict() for f in findings]}")

    def test_empty_baseline_and_no_findings_is_an_error_not_a_pass(self):
        """分母守卫：什么都没扫到 + 基线为空 = 扫描器失效，不是「全部合规」。"""
        findings, counts = ratchet.scan(self.base, {}, explicit_files=[])
        self.assertEqual((findings, counts), ([], {}))
        proc = subprocess.run(
            [sys.executable, str(ROOT / "tools" / "release" / "file_size_ratchet.py"),
             "--root", str(self.base), "--baseline", str(self.base / "no-baseline.json")],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
        self.assertEqual(proc.returncode, 2,
                         f"空仓必须 exit 2，实际 {proc.returncode} / {proc.stdout}{proc.stderr}")
        self.assertIn("扫描逻辑失效", (proc.stdout or "") + (proc.stderr or ""))

    def test_main_returns_one_when_findings_exist(self):
        """CLI 的失败必须能被 CI 看见：exit 1，而不是打印完就 0。"""
        make_file(self.base, "src/Big.java", 600)
        baseline = self.base / "baseline.json"
        baseline.write_text(json.dumps({"files": {}}), encoding="utf-8")
        code = ratchet.main(["--root", str(self.base), "--baseline", str(baseline)])
        self.assertEqual(code, 1)

    def test_update_baseline_shrinks_the_recorded_debt(self):
        """拆完一个文件后 --update-baseline 应该把总数降下来——这是「债务单调递减」
        能被验证的方式，而不是只写在注释里的愿望。"""
        make_file(self.base, "src/A.java", 900)
        make_file(self.base, "src/B.java", 600)
        baseline = self.base / "baseline.json"
        ratchet.main(["--root", str(self.base), "--baseline", str(baseline),
                      "--update-baseline"])
        before = json.loads(baseline.read_text(encoding="utf-8"))
        self.assertEqual(before["files"], {"src/A.java": 900, "src/B.java": 600})

        # 拆掉 A
        make_file(self.base, "src/A.java", 300)
        ratchet.main(["--root", str(self.base), "--baseline", str(baseline),
                      "--update-baseline"])
        after = json.loads(baseline.read_text(encoding="utf-8"))
        self.assertEqual(after["files"], {"src/B.java": 600})
        self.assertLess(after["summary"]["totalLines"], before["summary"]["totalLines"],
                        "还款后基线总量必须下降，否则棘轮读数不可信")


class TestCollectionScope(unittest.TestCase):
    """收集口径：什么算「源码文件」。测的是判据本身，不是恰好扫到了几个。"""

    def test_only_source_suffixes_count(self):
        for name, expected in [
            ("a/src/main/java/Foo.java", True),
            ("a/src/test/java/FooTest.java", True),
            ("x/src/views/Foo.vue", True),
            ("x/src/api/foo.ts", True),
            ("db/migration/V1__init.sql", False),      # 迁移由各自的不可变性门禁管
            ("README.md", False),
            ("x/package.json", False),
        ]:
            self.assertEqual(ratchet.qualifies(Path(name)), expected,
                             f"扩展名口径不对：{name}")

    def test_build_artifacts_and_generated_are_excluded(self):
        """排除产物与生成物。少了这条，一次 mvn package 就能让门禁红一堆
        与代码质量无关的"违规"，然后所有人学会的第一件事是关掉它。"""
        for name in [
            "amz-service/amz-service-user/target/generated-sources/Foo.java",
            "amz-frontend/node_modules/pkg/index.ts",
            "amz-frontend/dist/assets/app.ts",
            "x/src/main/java/Foo.d.ts",
        ]:
            self.assertFalse(ratchet.qualifies(Path(name)), f"不该统计：{name}")

    def test_real_repo_baseline_is_not_empty(self):
        """口径核对（跑真实仓库）：基线必须非空且与磁盘一致。

        这条不锁具体数字——锁数字会让任何一次合法拆分都要改测试，
        于是测试沦为「改代码时顺手改的东西」。它锁的是**工具没在空转**：
        全仓确有数十个超限文件，若哪天扫出 0 个，就是口径坏了。
        """
        baseline = ratchet.load_baseline(
            Path(__file__).resolve().parent / ratchet.BASELINE_NAME)
        self.assertGreater(len(baseline), 10,
                           f"基线只剩 {len(baseline)} 条——要么债务已清（不可能），"
                           f"要么扫描口径坏了")
        findings, counts = ratchet.scan(ROOT, baseline)
        self.assertEqual(findings, [],
                         f"基线与磁盘不一致：{[f.to_dict() for f in findings][:5]}")
        self.assertEqual(set(counts), set(baseline), "基线集合与实扫集合应完全一致")


if __name__ == "__main__":
    unittest.main()
