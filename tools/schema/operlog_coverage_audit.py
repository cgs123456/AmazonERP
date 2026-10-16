#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全仓写端点 @OperLog 覆盖盘点（人读的台账，刻意不进 CI）。

Why this exists (HANDOFF §S27.6):
    补 MultiplatformController 那 14 个端点的 @OperLog 时（决策②），暴露出一个
    事实——**全仓没有一张表回答"其它模块的写端点审计覆盖如何"**。已有
    OperLogCoverageContractTest 只管 11 个"受管敏感域"，且刻意不追求全量
    （给「搜索历史删除」这类低价值端点机械加注解只会稀释审计表）。缺的是中间那层：
    一份能看、能分类、能决定下一步补哪里的全仓台账。

    与那个门禁测试的分工：**门禁守住"受管域不能退化"，本脚本回答"还有什么没进受管"**。
    本脚本刻意不做成 CI 闸门——全量覆盖率是产品决策（哪些端点值得审计），
    机械设阈值会把「有人故意不审计的端点」变成必须填的注解，注释也说了那只会稀释审计表。

用法：
    python tools/schema/operlog_coverage_audit.py                 # 全仓概览
    python tools/schema/operlog_coverage_audit.py --gaps          # 只看缺口
    python tools/schema/operlog_coverage_audit.py --module ad     # 单模块明细

口径（与 OperLogCoverageContractTest 一致，几处刻意不同，全部记在下面）：
    - 覆盖全部模块（门禁测试只列受管敏感域）；
    - **把 @PatchMapping 也算写**：Spring 4 起支持，漏算等于漏报一个维度；
    - **机器端点单列、不计缺口**：它们没有"当前用户"可记（webhook 走 HMAC 验签、
      oauth token 走 appKey+appSecret），加了 @OperLog 只会记出一堆无操作人的行；
    - **自我服务端点单列、不计角色缺口**（§S29 增）：改的是「自己的」数据
      （editInfo / updateImage / saveOrder / updatePreference…），加 @RequireRole
      会要求「已有角色才能改自己的头像」，是锁错了对象。判定靠**绑定证据**而非名单，
      见 is_self_service 的注释——名单必然过期，证据随代码自己变。

    分母必须打印。**未匹配一律披露不静默跳过**：解析失败会伪装成 0 缺口，
    那正是 §S20.7 的教训（凭印象代替枚举）。扫不到任何端点时直接报错退出。
"""
import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

# 写端点注解。含 PatchMapping：既有的门禁测试没算它，那是它的口径；
# 盘点口径必须比门禁宽，否则"还没进门禁的端点"在盘点里也看不见。
WRITE_MAPPING = re.compile(r"@(PostMapping|PutMapping|DeleteMapping|PatchMapping)\b")
OPER_LOG = re.compile(r"@OperLog\b")
REQUIRE_ROLE = re.compile(r"@RequireRole\b")
# 方法签名：返回类型（含泛型/数组）+ 方法名 + 左括号。花括号不参与匹配，
# 免得把方法体里的控制语句或匿名类误判成新签名（真签名一定以 ( 结尾）。
METHOD_START = re.compile(r"^[a-z]+\s+[\w<>\[\].,?\s]+\s+\w+\s*\(")
METHOD_NAME = re.compile(r"^[a-z]+\s+[\w<>\[\].,?\s]+\s+(\w+)\s*\(")
URL_IN_ANNOTATION = re.compile(r'"/([^"]*)"')
VISIBILITY = ("public ", "protected ", "private ")
# Mapping 之后最多容忍多少行才出现方法签名。超过这个数说明解析走偏了，
# 宁可把上界退 Mapping 行，也不要跨过下一个方法的注解区把别人的注解算过来。
# 注解 + 多行签名的窗口。**不能写死成小数字**：KnowledgeController.uploadDocument
# 的注解+签名跨 5 行以上，写 6 会扫不到签名，退回 Mapping 行，于是写在 Mapping
# 下面的 @RequireRole 被判成不存在（§S29 实测）。
SIGNATURE_SCAN_LIMIT = 25
# 方法体最长看多少行。到顶还没配平就退化处理——宁可少看，不可越界到下一个方法，
# 否则会把下一个方法的守卫算到本端点头上（假绿）。
MAX_BODY_LINES = 120

# 机器端点：没有"当前用户"可记，唯一鉴权是自带的。
# 与 MultiplatformWriteEndpointGuardContractTest.MACHINE_ENDPOINTS 同口径。
MACHINE_ENDPOINTS = {
    "receiveWebhook": "平台回调（Temu/TikTok/Shein），白名单放行，HMAC 验签",
    "generateToken": "开放 API，外部系统凭 appKey+appSecret 换 token",
}


def find_repo_root() -> Path:
    current = Path(__file__).resolve()
    for depth in range(8):
        current = current.parent
        if (current / ".github" / "workflows" / "ci.yml").is_file() and (current / "amz-service").is_dir():
            return current
    raise SystemExit("无法定位仓库根目录（应含 .github/workflows/ci.yml 与 amz-service/）")


def scan_controller(path: Path):
    """返回该 Controller 的写端点清单。判定口径与门禁测试一致：方法级，不看文件级。"""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    endpoints = []
    last_method_start = -1
    service_bodies = load_service_bodies(find_module_root(path))
    # find_module_root 返回 None 时退化为只控制器取证（宁可漏判，不抛异常中断整张表）
    for i, line in enumerate(lines):
        trimmed = line.strip()
        if is_method_signature(trimmed):
            last_method_start = i
            continue
        mapping = WRITE_MAPPING.search(trimmed)
        if not mapping:
            continue
        # 注解区：上一个方法签名行之后，到**本方法自己的签名行**（含）。
        #
        # 下界不能写死成 i（Mapping 行）：@RequireRole 写在 @PostMapping 下面是常见写法
        # （AgentMemoryController.scanReminders 就是，见 §S29），区间只到 Mapping 行会把它
        # 整块漏掉，于是盘点报「没锁角色」——**一个已锁 ADMIN 的端点被列进待办**。
        # 上界必须走到本方法签名行，否则同样漏掉那一行下面的注解。
        start = last_method_start + 1 if last_method_start >= 0 else 0
        signature = next_signature_after(lines, i)
        end = signature + 1 if signature is not None else i + 1
        block = "\n".join(lines[start:end])
        name = method_name_after(lines, i)
        url = url_after(lines, i)
        endpoints.append({
            "line": i + 1,
            # PostMapping -> POST。切掉 7 字符 "Mapping"；@PatchMapping 同理得到 PATCH。
            "verb": mapping.group(1)[:-7].upper(),
            "method": name,
            "url": url,
            "oper_log": bool(OPER_LOG.search(block)),
            "role": bool(REQUIRE_ROLE.search(block)),
            "machine": name in MACHINE_ENDPOINTS,
            # 自我服务：改的是「自己的」数据，不是店铺级授权决策。与机器端点同属
            # 「不该按业务写操作加锁」的一类，但**不手维护名单**——靠绑定证据判定，
            # 因为名单必然过期（见 is_self_service 与 BINDING_CONTEXT 的注释）。
            # 判定看**整个方法体**（含调用点），不是只有注解+签名那几行——
            # 绑定身的守卫常写在方法体中间（如 `Integer userId = UserContext.getUserId();`）。
                "self_service": is_self_service(
                    chr(10).join(lines[start:method_body_end(lines, signature if signature is not None else i)]),
                    service_bodies, name),
        })
    return endpoints


# 登录态自身：给这两个加 @RequireRole 等于要求「已有角色」，而调用方正在获取角色。
# 未持 token 的用户调 refreshToken 是正常流程（access 过期），拦截它会把所有用户的
# 会话卡死在过期那一刻。
AUTH_ENDPOINTS = {
    "verify": "登录态自身：调用方还没有角色可判",
    "refreshToken": "access 过期换取新对，此刻只持有 refresh token",
}

# 自我服务证据（绑定式，而非「提到就算」）。
#
# **为什么必须是绑定式**：`UserContext.getUserId()` 单独出现**不能**证明这个端点只改自己。
# §S29 实测的假阳性：`UserController.updateRole`（锁 ADMIN）的 service 实现里也有
# `Integer current = UserContext.getUserId();`——但它只用来**拒绝**「改自己」，
# 真正的操作对象是 `@PathVariable Integer userId`，即调用方指定的任意用户。
# 按「提到就算」判，一个 ADMIN 专属端点会被标成「自我服务」，
# 于是从待锁清单里消失——**假阳性在这里等于把一个该锁的端点藏起来**，比漏判更危险。
#
# 因此要求两件事同时成立：
#   1. 出现「把认证身份写进操作对象」的赋值/绑定（setter 实参、selectById(...)、eq(...) 之类）；
#   2. 方法签名里**没有**调用方可指定的目标 id（@PathVariable / @RequestParam 的名字
#      暗示是别人的 id，如 userId / targetUserId）。
BINDING_CONTEXT = (
    # MyBatis-Plus 的归属条件：updateWrapper.eq(User::getId, <认证身份>)
    # （UserServiceImpl.editInfo / updateImage 就是这个形态）
    r"eq\s*\(\s*\w+::get\w*Id\s*,\s*(UserContext\.getUserId\(\)|userId|currentUserId\(\))\s*\)",
    # 把认证身份写进被操作对象：preference.setUserId(currentUserId())
    r"setUserId\s*\(\s*(UserContext\.getUserId\(\)|currentUserId\(\))\s*\)",
    # 先取身份、下一步按它做归属条件：Integer userId = UserContext.getUserId();
    r"\w+\s+(userId|currentUserId)\s*=\s*(UserContext\.getUserId\(\)|currentUserId\(\))",
    # 会话/记忆归属键：memoryAwareAgentService.chat(currentUserId(), ...)
    r"chat\s*\(\s*(UserContext\.getUserId\(\)|currentUserId\(\))",
)
# 调用方可指定的目标 id 参数名：出现即说明**操作对象可以由别人指定**，不能算自助。
# 钉的是「参数名暗示身份/目标」，因为仓库里读他人数据的端点几乎都这么命名
# （updateRole(userId) / deleteUser(userId) / toggleRule(targetId)）。
# 判据刻意宽松：宁可把一个自助端点误判为「该锁」（只是多留一条待办），
# 也不能把一个越权端点误判成自助（那会让该锁的端点从清单里消失）。
FOREIGN_TARGET_PARAM = re.compile(
    r"@(?:PathVariable|RequestParam|RequestHeader|RequestPart)"
    r"(?:\s*\([^)]*\))?"
    r"[\w<>\[ \],.\s]{0,80}?"
    r"\b(?:userId|userName|targetUserId|targetId|accountId)\b"
    r"\s*[,)]"
)

def is_self_service(block: str, service_bodies=None, method_name: str = "") -> bool:
    """是否「改的是自己的数据」——即结构上没有越权面。

    <b>判据是绑定，不是提及</b>：方法里出现 {@code UserContext.getUserId()} 不等于
    只改自己。{@code updateRole} 用它来**拒绝**改自己，操作对象仍是调用方给的
    {@code @PathVariable userId}——那种端点必须继续算作「可以越权」。

    三段取证，任一命中且未命中 FOREIGN_TARGET_PARAM 才判为自我服务：

    1. controller 方法体（薄控制器，守卫就写在控制器里）；
    2. service 实现层（多数 Controller 只转发，绑定身的守卫在 {@code UserServiceImpl}）；
       跨层是必须的——只看控制器会把 {@code saveOrder}/{@code editInfo}/{@code updateImage}
       三个自助端点全误判成待锁端点，下一位就会去给 VIEWER 换不了自己头像加一道锁；
    3. 登录态端点：调用方此刻没有角可判。

    若方法签名里有调用方可指定的目标 id（{@code userId} 之类的 Path/Query 参数），
    直接判否——**宁漏不假阳性**：漏判只是多留一条待锁项，假阳性是把该锁的端点藏起来。
    """
    if FOREIGN_TARGET_PARAM.search(block):
        return False
    if method_name in AUTH_ENDPOINTS:
        return True
    for body in (block, (service_bodies or {}).get(method_name) or ""):
        if body and any(re.search(pattern, body) for pattern in BINDING_CONTEXT):
            return True
    return False


def find_module_root(controller_path):
    """从 Controller 文件向上找模块根（含 {@code src/main/java} 的那一级）。

    刻意不用 {@code parents[N]}：写死数字会把「包层级变了」变成静默取不到 service 体，
    而那个失败**不报错、只是让自助端点被打成待锁端点**——表看着完全正常。
    """
    for parent in controller_path.parents:
        if (parent / "src" / "main" / "java").is_dir():
            return parent
    return None


def load_service_bodies(module_root):
    """读本模块 service 实现，返回 {方法名 → 方法体}。module_root 为 None 时返回空表。

    <b>只为取「自我服务」的证据而读，不做任何鉴权判断**。扫描器是人读的台账，
    跨一层是为了别把自助端点误报成待锁端点（那会让下一位去给
    {@code UserController.editInfo} 加一道让 VIEWER 换不了自己头像的锁）。

    模块根不存在（例如扫描器被指向别的目录）时返回空 dict——退化成只控制器取证，
    宁可漏判也不抛异常中断整张表。
    """
    bodies = {}
    if module_root is None:
        return bodies
    impl_dir = module_root / "src" / "main" / "java"
    if not impl_dir.is_dir():
        return bodies
    for java in sorted(impl_dir.rglob("*ServiceImpl.java")):
        text = java.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(text):
            signature = METHOD_NAME.match(line.strip())
            if not signature:
                continue
            end = method_body_end(text, i)
            bodies[signature.group(1)] = chr(10).join(text[i:end])
    return bodies


def is_method_signature(trimmed: str) -> bool:
    """是否方法签名起始行。多行签名（参数换行）由调用方按多行拼接处理。"""
    return METHOD_START.match(trimmed) is not None and trimmed.startswith(VISIBILITY)


def next_signature_after(lines, mapping_line):
    """Mapping 行之后本方法自己的签名**末行**；没找到返回 None。

    刻意用「出现第一个方法体开括号」而不是花括号配平：
    §S29 第一版按配平攒字符，而参数行以 {@code @RequestParam} 开头被当注解跳过，
    于是闭合的 {@code )} 丢了、配平永远不成立——刚加好锁的端点又被判成「没锁」。
    现在只找签名该有的形态：可见性/返回类型 + 方法名 + {@code (}，
    然后一路看到方法体的 {@code } 之前那一行。

    多行签名（参数换行）天然支持：{@code KnowledgeController.uploadDocument}
    的签名跨 3 行，固定行数窗口扫不到它，所以这里不用行数窗口判定。
    """
    for j in range(mapping_line + 1, min(mapping_line + SIGNATURE_SCAN_LIMIT, len(lines))):
        raw = strip_java_line_comment(lines[j]).strip()
        if not raw or raw.startswith("@") or raw.startswith("}"):
            continue
        # 方法体里不可能出现的新签名起点：以 ( 结尾仍需继续攒，直到看到 {
        if METHOD_START.match(raw) or raw.endswith("{"):
            return j
    return None


def method_body_end(lines, signature_line):
    """方法体的结束行（花括号配平后的那一行）。

    从方法签名所在行开始数花括号。返回到 end 的行号（不含）。
    配平失败（文件截断/异常结构）时退化为签名行+1，宁可少看几行也不越界到下一个方法。
    """
    depth = 0
    started = False
    for j in range(signature_line, min(signature_line + MAX_BODY_LINES, len(lines))):
        line = strip_java_line_comment(lines[j])
        depth += line.count("{") - line.count("}")
        if "{" in line:
            started = True
        if started and depth <= 0:
            return j + 1
    return signature_line + 1


def strip_java_line_comment(line: str) -> str:
    """去掉行注释与字符串字面量，避免把注释里的花括号/引号算进配平。"""
    out = []
    in_string = False
    i = 0
    while i < len(line):
        ch = line[i]
        if in_string:
            if ch == "\\" and i + 1 < len(line):
                i += 2
                continue
            if ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
                break
        out.append(ch)
        i += 1
    return "".join(out)


def method_name_after(lines, mapping_line):
    for i in range(mapping_line, min(mapping_line + 5, len(lines))):
        match = METHOD_NAME.match(lines[i].strip())
        if match:
            return match.group(1)
    return lines[mapping_line].strip()


def url_after(lines, mapping_line):
    """从 Mapping 行往下找 URL 字面量（跨行签名最多容忍 3 行）。"""
    for i in range(mapping_line, min(mapping_line + 3, len(lines))):
        match = URL_IN_ANNOTATION.search(lines[i])
        if match:
            return match.group(1)
    return ""


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--gaps", action="store_true", help="只列缺口（缺 @OperLog 的人操作端点）")
    parser.add_argument("--module", help="只看该模块（子串匹配，如 ad / spapi）")
    args = parser.parse_args()

    root = find_repo_root()
    rows = []
    for controller in sorted((root / "amz-service").glob("*/src/main/java/com/amz/controller/*.java")):
        # 模块名取相对 amz-service 的第一段。**不能写 controller.parts[1]**：
        # parts 是绝对路径切片，[1] 在 Windows 上会是当前用户名（Users），
        # 于是所有行都归到一个叫 "Users" 的假模块下——聚合结果看着正常、其实全错。
        module = controller.relative_to(root / "amz-service").parts[0]
        if args.module and args.module not in module:
            continue
        for endpoint in scan_controller(controller):
            endpoint.update({"module": module, "file": controller.name})
            rows.append(endpoint)

    # 分母必须打印：扫不到端点就是解析器失效，不是"没问题"
    if not rows:
        print("ERROR: 没有扫到任何写端点——扫描逻辑失效了，这不是通过", file=sys.stderr)
        return 2

    machine = [r for r in rows if r["machine"]]
    gaps = [r for r in rows if not r["oper_log"] and not r["machine"]]
    covered = len(rows) - len(machine) - len(gaps)
    # §S29：把「天然不该有角色门禁」的端点从「待锁」里剔出来，否则 57 个缺口里混着
    # 自助端点，读者只能逐个读源码才能知道哪些是真漏锁。剔出来的数必须单独报出来——
    # 报不出来的分类等于没分类。
    role_exempt = [r for r in rows
                   if r.get("self_service") and not r["machine"] and not r["role"]]
    role_gaps = [r for r in rows
                 if not r["role"] and not r["machine"] and not r.get("self_service")]

    if args.gaps:
        print(f"{'模块':26} {'文件':34} {'行':>4} {'动作':6} {'URL':38} {'方法':22} {'锁角色':8}")
        print("-" * 132)
        for r in gaps:
            # 「锁角色」= 该端点是否**应该**有 @RequireRole。Y=已有，N=缺失，
            # S=自我服务不适用。把 S 单独列出来是为了让人一眼看到「哪些不是漏锁」——
            # 否则 57 个缺口里混着 11 个天然不该锁的，下一位只能逐个去读源码判断。
            if r.get("self_service"):
                flag = "S"
            elif r["role"]:
                flag = "Y"
            else:
                flag = "N"
            print(f"{r['module']:26} {r['file']:34} {r['line']:>4} {r['verb']:6} "
                  f"{('/' + r['url'] if r['url'] else '-'):38} {r['method']:22} {flag:8}")
        print()
    else:
        by_module = defaultdict(lambda: [0, 0, 0])  # 缺口 / 其中已锁角色 / 角色也没有
        for r in gaps:
            by_module[r["module"]][0] += 1
            by_module[r["module"]][1 if r["role"] else 2] += 1

        print(f"{'模块':26} {'缺口':>4} {'已锁角色':>7} {'角色也没有':>8}")
        print("-" * 52)
        total = [0, 0, 0]
        for module, (a, b, c) in sorted(by_module.items(), key=lambda kv: -kv[1][0]):
            print(f"{module:26} {a:>4} {b:>7} {c:>8}")
            total[0] += a
            total[1] += b
            total[2] += c
        print("-" * 52)
        print(f"{'合计':26} {total[0]:>4} {total[1]:>7} {total[2]:>8}")
        print()
        print(f"写端点总数（含 PATCH）: {len(rows)}")
        print(f"已有 @OperLog: {covered}  ({covered * 100 // len(rows)}%)")
        print(f"人操作缺口: {len(gaps)}（其中 {total[1]} 个已有 @RequireRole，补审计是纯增益；"
              f"{total[2]} 个连角色都没有，先补角色再谈审计）")
        print(f"机器端点（不计缺口）: {len(machine)}"
              + (f"（{', '.join(sorted({r['method'] for r in machine}))}）" if machine else ""))
        print()
        # 角色覆盖单独一节：这一节回答的是「还有多少个端点没有角色门禁」，
        # 与上面的审计缺口是**两个不同的问题**，混在一张表里必然有一个被读错。
        print(f"--- 角色门禁覆盖（§S29 起单独统计）---")
        print(f"无 @RequireRole 且该锁: {len(role_gaps)}")
        print(f"自我服务端点（不适用角色门禁，剔出）: {len(role_exempt)}"
              + (f"（{', '.join(sorted({r['method'] for r in role_exempt}))}）" if role_exempt else ""))
        print("这两类都不该被算进「待补角色」——前者是真缺口，后者锁了会打错对象。")
        print()
        print("明细：加 --gaps。单模块：--module ad --gaps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
