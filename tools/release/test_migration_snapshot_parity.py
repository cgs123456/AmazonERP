# -*- coding: utf-8 -*-
"""迁移-DDL 快照同步门禁（本地可跑，等价于 CI 的 "Schema snapshot freshness"）。

存在动因（HANDOFF §S23.7 / §S24.4，2026-10-16 真实事故）：
    §S22 加了 V6 迁移但忘同步 schema 快照。那次**本机全绿**——漂移检查只存在于
    CI 的 synthetic-data job（`snapshot_schema.py --check`），常规门禁一套都碰不到它。
    积压 17 个提交一次性推送后 CI 才红，且报错点离肇事提交隔了一整节交接文档。
    同类场景（下一个改任何 DDL 的人）必然复发，除非本地有同一道门。

口径（为什么是调同一个入口而不是重实现比较）：
    本测试直接以子进程跑 CI 用的那个 `--check`，断言它的真实退出码。
    「再实现一遍结构比较」会和真门漂开——解析器一改，这里绿着、CI 红着，
    假绿恰是本次事故要防的形状。测试变重的代价（约 2 秒子进程）换的是
    「本地绿 ⇒ 那道门绿」这个等价关系本身。

第二条测试钉快照的 sources 清单与文件系统一致：
    --check 已经隐式覆盖这一点（多一个迁移文件必然改 sha256 集合 → 红），
    但它报的是笼统的 "DDL drift"；当有人日后重构解析器、或快照被手工编辑成
    「能过 --check 却引用了不存在的文件」时，这条给出点名的差异
    （「加了迁移忘重生成」与「快照里有已删除的幽灵路径」是两种修法）。

变异证据（提交前真跑过，非设想）：
    往 V6 注释里加一个字 → --check FAIL（sha256 变）→ 本文件两条测试同时红 →
    还原后全绿。见 commit message。
"""
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT = ROOT / "tools" / "synthetic-data" / "schema" / "schema-snapshot.json"
CHECKER = ROOT / "tools" / "synthetic-data" / "snapshot_schema.py"


def flyway_files_on_disk():
    return sorted(
        p.relative_to(ROOT).as_posix()
        for p in ROOT.glob("amz-service/*/src/main/resources/db/migration/V*__*.sql")
        if "target" not in p.parts
    )


class TestMigrationSnapshotParity(unittest.TestCase):

    def test_schema_snapshot_check_is_green_locally(self):
        """CI 的 "Schema snapshot freshness" 必须在本地同样跑得过——这就是它的本地替身。"""
        proc = subprocess.run(
            [sys.executable, str(CHECKER), "--check"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=180,
        )
        out = (proc.stdout + proc.stderr).strip()
        self.assertEqual(
            proc.returncode, 0,
            "DDL 与 schema 快照漂移。修法：python tools/synthetic-data/snapshot_schema.py"
            "（写模式重生成三个文件，再 --check 确认），把重生成结果与迁移改动放进同一个提交。\n"
            f"checker 输出：\n{out}",
        )
        self.assertIn("[schema] OK", out)

    def test_snapshot_records_exactly_the_flyway_files_on_disk(self):
        """快照 sources 里的 flyway-migration 集合必须与文件系统一一对应。"""
        with io.open(SNAPSHOT, encoding="utf-8") as fh:
            payload = json.load(fh)
        in_snapshot = sorted(
            s["path"] for s in payload["sources"] if s.get("group") == "flyway-migration"
        )
        on_disk = flyway_files_on_disk()
        missing = sorted(set(on_disk) - set(in_snapshot))
        ghosts = sorted(set(in_snapshot) - set(on_disk))
        self.assertFalse(
            missing,
            "这些迁移文件不在 schema 快照里（新加了迁移没重生成快照）："
            f"{missing}。跑 snapshot_schema.py 重生成后与迁移同提交。",
        )
        self.assertFalse(
            ghosts,
            f"快照引用了磁盘上不存在的迁移：{ghosts}（解析根目录变了？还是快照被手工改过？）。",
        )


if __name__ == "__main__":
    unittest.main()
