"""Completion audit: find backend endpoints with no frontend caller.

Coarse on purpose: it maps controller paths against the strings used in
amz-frontend/src/api/*.ts. A hit means "some frontend code names this path",
not "the button works". Anything reported as missing must be checked by hand.
Comments are stripped first, so a path that only appears in an explanatory
comment is still reported as missing (that is the point).
"""
import io
import re
import sys
from pathlib import Path

# 位置参数是仓库根；以 - 开头的都是开关，不能被当成路径
# （上一版把 `--self-test` 当成 root，self-test 恰好不读文件才没炸，`--reverse` 一读就炸）。
_positional = [a for a in sys.argv[1:] if not a.startswith('-')]
root = Path(_positional[0] if _positional else '.').resolve()
fe_api = root / 'amz-frontend' / 'src' / 'api'

CLASS_MAP = re.compile(r'^@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"', re.M)
# 方法级注解先按行抓，再从括号里解析路径：
# 上一版用「必须有 ( 和字符串」的正则，直接把 15 个 `@PostMapping`（无参、路径就是类前缀）
# 整个漏掉了 —— 分母本身就少算，被漏掉的还偏偏是数据写入端点。
MAPPING_LINE = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\b[ \t]*([^\n]*)')
PATH_LITERAL = re.compile(r'(?:value|path)\s*=\s*"([^"]*)"|"([^"]*)"')

# 注释里写出的路径不算「前端调用过」。这一条是踩出来的：opsAlerts.ts 的头注里
# 列着三个「刻意不接」的扫描端点（为了说明为什么不接），整文件文本匹配因此把它们
# 判成了已覆盖，OpsController 显示 0 条缺口——而页面上根本没有这三个按钮。
# `(?<!:)` 是为了不吃掉 https:// 这类串里的双斜杠。
LINE_COMMENT = re.compile(r'(?<!:)//[^\n]*')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
# 两种注释一次性按位置扫掉。分开两次 sub 会串味：先剥块注释时，行注释里出现的
# `api/*.ts` 会被当成块注释的起点，一路吞到文件里下一个 `*/`，把真正的调用代码删没
# ——agentMemory.ts 就是这么被误判成「未接入」的。交替匹配则从左到右消费，
# 行注释整行先没了，块注释内部再含 `//` 也不会提前收尾。
COMMENTS = re.compile(r'(?<!:)//[^\n]*|/\*.*?\*/', re.S)


def strip_comments(text: str) -> str:
    return COMMENTS.sub('', text)

frontend_text = "\n".join(
    strip_comments(io.open(p, encoding='utf-8', errors='replace').read())
    for p in fe_api.glob('*.ts')
)


def build_matcher(full_path: str) -> str:
    """把 /a/{id}/b 变成可以匹配前端模板串的正则：参数段允许 ${...} 或任意非斜杠内容。

    结尾必须钉住：上一版没有右边界，`/ai/chat` 会被 api/ai.ts 里的 `/ai/chat-stream`
    命中，于是 POST /ai/chat 这个前端根本没调的端点被算成「已接入」——
    少报缺口比多报缺口更危险，因为它会让清点结果看起来已经收敛。
    允许的后继字符里没有 `-` 与字母数字，斜杠仍然允许（子路径照常匹配）。
    """
    out = []
    for seg in full_path.split('/'):
        if not seg:
            continue
        if seg.startswith('{') and seg.endswith('}'):
            out.append(r"(?:\$\{[^}]*\}|[^/`'\"]+)")
        else:
            out.append(re.escape(seg))
    return '/'.join(out) + r'(?![\w-])'


NON_PATH_ONLY_ARGS = re.compile(r"^\(\s*(?:consumes|produces|params|headers)\s*=")
VALUE_OR_PATH_ARG = re.compile(r"(?:value|path)\s*=")


def shape(path: str) -> str:
    """把 /a/{id}/b 与 /a/${x}/b 都归一成 /a/{}/b，用于跨服务比对同一条路径。

    查询串先丢掉：`/ai/eval/run?mode=${mode}` 与 `/ai/eval/run` 是同一条端点，
    把 `?mode=...` 留在段里会让前端明明调用了的端点被判成「后端没有」。
    """
    path = path.split('?')[0]
    segs = ['{}' if s.startswith('{') or s.startswith('$') else s for s in path.split('/') if s]
    return '/' + '/'.join(segs)


def parse_mappings(text: str):
    """把方法级 mapping 注解解析成 (verb, path, anchor, raw)。

    path == '' 表示无参注解（`@PostMapping`），它的路径就是类前缀；
    path is None 表示这行有括号但没解析出字符串字面量（consumes/produces/params 之类），
    调用方必须把它打印出来——工具的失败模式只能是「少算并说明」，不能是静默少一条。
    """
    out = []
    for m in MAPPING_LINE.finditer(text):
        verb, rest = m.group(1), m.group(2).strip()
        raw = m.group(0).strip()
        if not rest:
            out.append((verb, '', m.start(), raw))
            continue
        if not rest.startswith('('):
            out.append((verb, None, m.start(), raw))
            continue
        # 只有 consumes/produces/params/headers 的写法：Spring 按类前缀取路径。
        # 上一版把它算成「解析不出来」，于是一条真实端点直接从分母里消失了。
        if NON_PATH_ONLY_ARGS.match(rest) and not VALUE_OR_PATH_ARG.search(rest):
            out.append((verb, '', m.start(), raw))
            continue
        literal = PATH_LITERAL.search(rest)
        path = ((literal.group(1) or literal.group(2)) if literal else None)
        out.append((verb, path, m.start(), raw))
    return out




GATEWAY_YML = root / 'amz-gateway' / 'src' / 'main' / 'resources' / 'application.yml'
REWRITE_LINE = re.compile(
    r'RewritePath=/api/([a-z-]+)\(\?<segment>\.\*\),\s*(/[\w/-]+)\$\{segment\}')


def gateway_alias_map() -> dict:
    """后端前缀 -> 前端书写前缀，从网关的 RewritePath 过滤器现场解析。

    这张表原本在工具里手抄了第三份（另两份是 viteProxy.ts 与网关 yml）。
    三份手抄 = 三个可以各自漂移的地方，所以这里改成只认网关这一份事实来源：
    网关没有配别名时工具就退化成「前端必须写后端全路径」，宁可多报缺口也不能自己造绿灯。
    """
    if not GATEWAY_YML.exists():
        return {}
    text = io.open(GATEWAY_YML, encoding='utf-8', errors='replace').read()
    return {target: '/api/' + src for src, target in REWRITE_LINE.findall(text)}


GATEWAY_ALIASES = gateway_alias_map()


def to_frontend_alias(full_path: str) -> str:
    for backend_prefix, frontend_prefix in GATEWAY_ALIASES.items():
        if full_path == backend_prefix or full_path.startswith(backend_prefix + '/'):
            # 网关把 /api/x 重写成 /spapi/x，而 axios 的 baseURL 会再补一层 /api；
            # 前端源码里写的是去掉 /api 之后的形状，所以返回 /x 而不是 /api/x。
            return frontend_prefix[len('/api'):] + full_path[len(backend_prefix):]
    return ''




def collect_feign_paths() -> set:
    """其他模块的 Feign 客户端声明过的路径 = 存在服务间调用方，不该由浏览器负责。"""
    found = set()
    for client in sorted(root.glob('amz-service/*/src/main/java/com/amz/client/**/*.java')):
        text = io.open(client, encoding='utf-8', errors='replace').read()
        for _, p, _, _ in parse_mappings(text):
            if p:
                found.add(shape(p if p.startswith('/') else '/' + p))
    return found


def annotation_block(text: str, anchor: int) -> str:
    """取一个 mapping 注解所属的方法注解块：往前找到上一个 } 或 ; 为止。"""
    start = max(text.rfind('}', 0, anchor), text.rfind(';', 0, anchor)) + 1
    return text[start:anchor]


# 泛型是可选的：`request.get('/x')` 这种不写 `<void, ApiResponse<T>>` 的调用也是调用点。
# 上一版强制要求 `<`，于是不带泛型的调用静默不进分母 —— 反向尺会因此漏报。
FRONTEND_CALL = re.compile(
    r"request\s*\.\s*(get|post|put|delete|patch)\s*(?:<.*?>)?\s*\(\s*[`\"'](/[A-Za-z][^`\"']*)[`\"']",
    re.S)
STUB_LITERAL = re.compile(r'match:\s*/\^(.*?)/[a-z]*\s*,')
SSE_LITERAL = re.compile(r'match:\s*/\^(.*?)/[a-z]*\s*,\s*contentType', re.S)


def stub_shape(raw: str) -> str:
    """把桩正则 `\\/multiplatform\\/account\\/\\d+\\/test` 变成可比对的形状段。

    只处理仓库里实际出现的构造（转义斜杠、`\d+`、`[^/]+`、`.*`、可选组、行尾 `$`，
    以及 `/plan/12/approvals` 这种把 id 写死的桩）。认不出来的段原样留着，
    比对时它匹配不上任何后端形状，就会被当成缺口打印出来 ——
    工具的失败模式必须是「多报并说明」，不能是静默跳过。
    """
    s = raw.replace('\\/', '/')
    s = re.sub(r'\(\?:[^)]*\)\+?', '', s)
    s = s.replace('\\d+', '{}').replace('[^/]+', '{}').replace('.*', '{}')
    s = s.replace('(', '').replace(')', '').replace('|', '/')
    s = s.replace('$', '')
    s = shape(s)
    return '/' + '/'.join('{}' if seg.isdigit() else seg for seg in s.split('/') if seg)


def prefix_matches(stub_segs, backend_segs) -> bool:
    if len(stub_segs) > len(backend_segs):
        return False
    for a, b in zip(stub_segs, backend_segs):
        if a == '{}' or b == '{}' or a == b:
            continue
        return False
    return True


def backend_verbs_by_shape():
    """所有控制器映射：形状 -> {方法}，以及形状集合（供反向核对）。"""
    by_shape = {}
    for controller in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
        text = io.open(controller, encoding='utf-8', errors='replace').read()
        cls = CLASS_MAP.search(text)
        prefix = cls.group(1) if cls else ''
        for verb, path, _, _ in parse_mappings(text):
            if path is None:
                continue
            full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
            full = full.rstrip('/') or '/'
            by_shape.setdefault(shape(full), set()).add(verb.upper())
    return by_shape


def frontend_calls():
    """api/*.ts 里真实的调用点（剥掉注释后）：(方法, 形状, 文件)。"""
    out = []
    for p in sorted((root / 'amz-frontend' / 'src' / 'api').glob('*.ts')):
        text = strip_comments(io.open(p, encoding='utf-8', errors='replace').read())
        for m in FRONTEND_CALL.finditer(text):
            verb, path = m.group(1).upper(), m.group(2)
            if '/' not in path[1:]:
                continue          # 单段串（如错误文案里的 `/x`）不是路径
            out.append((verb, shape(path), p.name))
    return out


def e2e_stub_paths():
    stub = root / 'amz-frontend' / 'e2e' / 'support' / 'api-stub.ts'
    if not stub.exists():
        return []
    text = io.open(stub, encoding='utf-8', errors='replace').read()
    return [stub_shape(r) for r in STUB_LITERAL.findall(text)]


def route_paths():
    router = root / 'amz-frontend' / 'src' / 'router' / 'index.ts'
    text = io.open(router, encoding='utf-8', errors='replace').read()
    return [p for p in re.findall(r"path:\s*'([^']+)'", text)]


def sidebar_refs():
    nav = root / 'amz-frontend' / 'src' / 'components' / 'AppSidebar.vue'
    text = io.open(nav, encoding='utf-8', errors='replace').read()
    return set(re.findall(r"navigateTo\('([^']+)'\)", text)) | set(
        re.findall(r"isActive\('([^']+)'\)", text))


def is_catch_all(path: str) -> bool:
    """`/:pathMatch(.*)*` 这类兜底路由不参与可达性比对：它匹配一切，也吞噬一切。"""
    return '*' in path or '(' in path


def route_prefix(pattern: str) -> str:
    """动态路由的列表页前缀 = 第一个参数段之前的路径（/orders/:id -> /orders）。"""
    segs = pattern.split('/')
    idx = next((i for i, s in enumerate(segs) if s.startswith(':')), len(segs))
    return '/'.join(segs[:idx]) or '/'


def seg_match(pattern: str, path: str) -> bool:
    """导航项是否命中路由模式：`:param` 段吃掉任意单个非空段，其余逐字相等。"""
    ps, xs = pattern.split('/'), path.split('/')
    if len(ps) != len(xs):
        return False
    return all(a == b or (a.startswith(':') and b) for a, b in zip(ps, xs))


ANN_RUN = re.compile(r'^(?:@\w+(?:\s*\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*)+')


def method_name_after(text: str, anchor: int) -> str:
    """取映射注解之后第一个方法声明的名字。

    不能从 anchor 起直接找 `word(`：那里第一个括号属于注解自己（PostMapping），
    上一版因此把方法名全看成 PostMapping，调用点计数变成「全是 0」。
    也不该把中间的其它注解（@ShopScoped、@InternalServiceAccess("...")）当成签名。
    """
    own = MAPPING_LINE.match(text, anchor)
    rest = text[own.end():] if own else text[anchor + 1:]
    rest = ANN_RUN.sub('', rest.lstrip(), count=1)
    sig = re.search(r'(\w+)\s*\(', rest)
    return sig.group(1) if sig else ''


def feign_declared_uncalled():
    """Feign 客户端声明过、但全仓没有任何调用点的方法 = 假豁免。

    清点原先把 has-feign-caller 当成「有服务间调用方，所以不该由浏览器负责」，
    但声明存在不等于有人调用。这两类必须分开：删除这类声明会让被它豁免的端点
    立刻变成真缺口（/logistics/warehouse/stock/aging 就是这么浮出来的）。
    """
    java_src = [(f, io.open(f, encoding='utf-8', errors='replace').read())
                for f in root.glob('amz-service/*/src/main/java/**/*.java')]
    rows = []
    for client in sorted(root.glob('amz-service/*/src/main/java/com/amz/client/**/*.java')):
        text = io.open(client, encoding='utf-8', errors='replace').read()
        cls = CLASS_MAP.search(text)
        prefix = cls.group(1) if cls else ''
        for _, path, anchor, _ in parse_mappings(text):
            if not path:
                continue
            name = method_name_after(text, anchor)
            if not name or name in ('if', 'for', 'while', 'return'):
                continue
            callers = sum(1 for f, body in java_src
                          if f != client and re.search(r'\.' + re.escape(name) + r'\s*\(', body))
            if callers == 0:
                full = (prefix + path) if path.startswith('/') else prefix + '/' + path
                rows.append((shape(full), name, client.name))
    return rows


ALIAS_FRONT_TO_BACK = {}
for _backend_prefix in GATEWAY_ALIASES:
    _front = to_frontend_alias(_backend_prefix)
    if _front:
        ALIAS_FRONT_TO_BACK[shape(_front)] = _backend_prefix


def to_backend_shape(front_shape: str) -> str:
    """前端书写形状 -> 后端注册形状（走网关别名）。"""
    for f, b in ALIAS_FRONT_TO_BACK.items():
        if front_shape == f or front_shape.startswith(f + '/'):
            return b + front_shape[len(f):]
    return front_shape


if '--self-test' in sys.argv:
    # 三条都是踩过的坑，钉在这里而不是只写注释：任何一条断了，清点结果就不可信。
    cases = []

    def check(name, got, want):
        cases.append((name, got == want, got, want))

    line_only = "// 见 api/*.ts 与其余约定\nrequest.get(`/ai/agent/memory/preference/${userId}`)\n"
    check('行注释里的 api/*.ts 不得吞掉下一行调用代码',
          bool(re.compile(build_matcher('/ai/agent/memory/preference/{userId}'))
              .search(strip_comments(line_only))), True)

    block_only = "/** 刻意不接：POST /ops/review/scan、POST /ops/rank/scan */\n"
    check('块注释里列出的路径不算调用',
          bool(re.compile(build_matcher('/ops/review/scan')).search(strip_comments(block_only))), False)

    near_miss = "request.get(`/ai/chat-stream`)\n"
    check('/ai/chat 不得被 /ai/chat-stream 命中',
          bool(re.compile(build_matcher('/ai/chat')).search(near_miss)), False)
    check('/ai/chat-stream 本身仍要命中',
          bool(re.compile(build_matcher('/ai/chat-stream')).search(near_miss)), True)

    nested = "request.get(`/ad/report/${shopId}`)\n"
    check('子路径式命中仍成立（斜杠后继续写路径不算断点）',
          bool(re.compile(build_matcher('/ad/report/{shopId}')).search(nested)), True)

    amap = parse_mappings('@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)')
    check('attribute-only mapping resolves to the class prefix', len(amap) == 1 and amap[0][1] == '', True)
    cmap = parse_mappings('@PostMapping(UPLOAD_PATH)')
    check('constant-path mapping is disclosed, never guessed', len(cmap) == 1 and cmap[0][1] is None, True)

    # —— 反向核对的四条逻辑，全部用内联样本，不依赖仓库当前内容 ——
    two_line_call = "const x = request\n  .get<void, ApiResponse<Foo>>(`/a/b/${id}`, { params })\n"
    check('跨两行写的调用仍被抓到（含方法）',
          [(m.group(1), shape(m.group(2))) for m in FRONTEND_CALL.finditer(two_line_call)],
          [('get', '/a/b/{}')])
    commented_call = "// request.post('/a/ghost', {})\nrequest.post('/a/real', {})\n"
    check('注释里的调用不算调用点，同段的真调用仍要抓到（正/负对照同一条）',
          sorted({m.group(2) for m in FRONTEND_CALL.finditer(strip_comments(commented_call))}),
          ['/a/real'])
    check('不带泛型的 request.get 也是调用点（不能只认 <void, ApiResponse<T>> 写法）',
          [m.group(2) for m in FRONTEND_CALL.finditer("request.get('/a/plain')")],
          ['/a/plain'])
    check('桩正则转成可比形状',
          stub_shape(r'\/multiplatform\/account\/\d+\/test'), '/multiplatform/account/{}/test')
    check('桩的列表形状能前缀命中后端带参形状',
          prefix_matches(shape('/multiplatform/order/list').split('/')[1:],
                         shape('/multiplatform/order/list/{shopId}').split('/')[1:]), True)
    check('前缀对不上就是不存在（不能靠去掉首段凑）',
          prefix_matches(['ad', 'report'], ['report', '{shopId}']), False)
    javadoc_prefix = ' * 见 @RequestMapping("/fake") 的例子\n@RequestMapping("/real")\n'
    check('类前缀只认行首注解，javadoc 里的例子不算',
          CLASS_MAP.search(javadoc_prefix).group(1), '/real')
    yml_sample = ('- RewritePath=/api/connectors(?<segment>.*), /spapi/connectors${segment}\n'
                  '- RewritePath=/ws/(?<segment>.*), /${segment}\n')
    check('别名只从网关 RewritePath 现场解析，且不吃 /ws 那条',
          REWRITE_LINE.findall(yml_sample), [('connectors', '/spapi/connectors')])
    check('别名来回换算必须复原（前端形状 -> 后端形状）',
          all(to_backend_shape(to_frontend_alias(b)) == b for b in GATEWAY_ALIASES)
          and len(GATEWAY_ALIASES) >= 3, True)
    check('方法名取映射注解之后第一个签名，跳过中间注解',
          method_name_after('@GetMapping("/events")\n    @ShopScoped\n'
                            '    Result<List<X>> listEvents(@RequestParam Long shopId);', 0),
          'listEvents')
    check('桩里的行尾 $ 与写死的数字都要归一',
          [stub_shape(r'\/user\/getInfo$'), stub_shape(r'\/procurement\/plan\/12\/approvals')],
          ['/user/getInfo', '/procurement/plan/{}/approvals'])
    check('带查询串的调用与不带的是同一条端点',
          shape('/ai/eval/run?mode=${mode}'), '/ai/eval/run')

    # —— 动态路由可达性比对（2026-10-07，闭合 2026-10-04 文档第 4 条「只统计不比对」）——
    check('动态路由前缀取第一个参数段之前', route_prefix('/orders/:id'), '/orders')
    check('兜底路由被识别并不参与比对', is_catch_all('/:pathMatch(.*)*'), True)
    check('普通动态路由不是兜底', is_catch_all('/orders/:id'), False)
    check('段匹配：:param 吃一个非空段', seg_match('/orders/:id', '/orders/123'), True)
    check('段匹配：缺段与多段都算断',
          [seg_match('/orders/:id', '/orders'), seg_match('/orders/:id', '/orders/1/2')],
          [False, False])
    check('段匹配：静态段必须逐字相等', seg_match('/orders/:id', '/carts/123'), False)

    failed = [c for c in cases if not c[1]]
    for name, ok_flag, got, want in cases:
        print('%-56s %s (got=%s want=%s)' % (
            name.encode('ascii', 'replace').decode(), 'PASS' if ok_flag else 'FAIL', got, want))
    print('SELFTEST %d/%d passed' % (len(cases) - len(failed), len(cases)))

    sys.exit(1 if failed else 0)


def require_repo_root() -> None:
    """root 不像仓库根就直接退出，不能让人拿着 0 条结果当「没有缺口」。

    这一条是当场踩出来的：在 amz-frontend/ 里跑 `python ../tools/schema/endpoint_coverage_audit.py`
    会安静地输出 controllers=0 / candidates=0，看起来像全绿，其实是分母为空。
    """
    controllers = list(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java'))
    api_files = list((root / 'amz-frontend' / 'src' / 'api').glob('*.ts'))
    if not controllers or not api_files:
        print('ROOT NOT A REPO: %s（控制器=%d 前端api=%d，两端分母都不能为空）'
              % (root, len(controllers), len(api_files)))
        print('请在仓库根目录运行，或显式传根：python tools/schema/endpoint_coverage_audit.py .')
        sys.exit(2)


require_repo_root()


if '--reverse' in sys.argv:
    # 正向尺回答「后端有端点、前端没人叫」；反向尺回答另外三件同样会假绿的事：
    #   1. 前端叫了而后端没有（或方法不对）—— 接线打错路径/动词时页面只会静默报错；
    #   2. e2e 桩拦了一条后端不存在的形状 —— 桩越像真的，测试越能替 404 打掩护；
    #   3. 静态路由没有侧边栏入口 / 侧边栏指向不存在的路由 —— 「孤儿页面」的正体。
    # 另外披露（不拦）：Feign 声明了却没有任何调用点，这类声明会错误豁免后端端点。
    by_shape = backend_verbs_by_shape()
    calls = frontend_calls()
    stubs = e2e_stub_paths()
    routes = route_paths()
    nav = sidebar_refs()
    segs_by_shape = {k: k.split('/')[1:] for k in by_shape}

    orphans, verbs = [], []
    for verb, fs, fname in calls:
        bs = to_backend_shape(fs)
        if bs not in by_shape:
            orphans.append((verb, fs, bs, fname))
        elif verb not in by_shape[bs]:
            verbs.append((verb, fs, '/'.join(sorted(by_shape[bs])), fname))

    stub_missing = []
    for ss in stubs:
        # 桩路径可能写成前端形状（/connectors/outbox）也可能写成后端形状（/spapi/status），
        # 两种都试：别名只有一处事实来源（网关 yml），这里不重复维护表。
        cands = {ss, to_backend_shape(ss)}
        if not any(prefix_matches(c.split('/')[1:], b)
                   for c in cands for b in segs_by_shape.values()):
            stub_missing.append(ss)

    static_routes = [p for p in routes if ':' not in p and not is_catch_all(p)]
    dynamic_routes = [p for p in routes if ':' in p and not is_catch_all(p)]
    route_orphans = [p for p in static_routes if p not in nav]
    # 动态路由可达性口径（2026-10-07 闭合 2026-10-04 文档第 4 条「只统计不比对」）：
    # 详情页（/orders/:id 形态）的入口是「从列表页点行」，不配自己的侧边栏项，
    # 所以「模式能被某个导航项直接命中」或「列表页前缀是导航项」二者满足其一才算可达；
    # 两者都落空才是真孤儿（详情页没有任何路径能到达）。兜底路由不参与（它会吞掉一切）。
    dynamic_unreachable = [
        p for p in dynamic_routes
        if not any(seg_match(p, n) for n in nav) and route_prefix(p) not in nav
    ]
    nav_dead = [p for p in sorted(nav)
                if ':' not in p and not is_catch_all(p) and p not in routes
                and not any(seg_match(r, p) for r in dynamic_routes)]
    uncalled = feign_declared_uncalled()

    print('reverse: backend-shapes=%d frontend-call-sites=%d e2e-stubs=%d routes=%d(static=%d dynamic=%d) nav-refs=%d'
          % (len(by_shape), len(calls), len(stubs), len(routes), len(static_routes),
             len(dynamic_routes), len(nav)))
    findings = 0
    if orphans:
        findings += len(orphans)
        print('GATE RED frontend-call-without-backend-mapping: %d' % len(orphans))
        for verb, fs, bs, fname in orphans:
            print('   %s %s (后端找 %s)  <- src/api/%s' % (verb, fs, bs, fname))
    if verbs:
        findings += len(verbs)
        print('GATE RED http-verb-mismatch: %d' % len(verbs))
        for verb, fs, have, fname in verbs:
            print('   %s %s 后端只有 %s  <- src/api/%s' % (verb, fs, have, fname))
    if stub_missing:
        findings += len(stub_missing)
        print('GATE RED e2e-stub-without-backend-mapping: %d' % len(stub_missing))
        for ss in stub_missing:
            print('   %s' % ss)
    if route_orphans:
        findings += len(route_orphans)
        print('GATE RED static-route-without-sidebar-entry: %d' % len(route_orphans))
        for p in route_orphans:
            print('   %s' % p)
    if dynamic_unreachable:
        findings += len(dynamic_unreachable)
        print('GATE RED dynamic-route-unreachable: %d' % len(dynamic_unreachable))
        for p in dynamic_unreachable:
            print('   %s（导航项命中不了该模式，且列表页前缀 %s 不在侧边栏）'
                  % (p, route_prefix(p)))
    if nav_dead:
        findings += len(nav_dead)
        print('GATE RED sidebar-entry-without-route: %d' % len(nav_dead))
        for p in nav_dead:
            print('   %s' % p)
    print('disclosed feign-declared-uncalled=%d（声明了但全仓无调用点，非阻断）' % len(uncalled))
    for path, name, fname in uncalled:
        print('   %-46s %-28s <- %s' % (path, name, fname))
    if not findings:
        print('reverse gate: 0 findings')
    sys.exit(1 if findings else 0)


FEIGN_PATHS = collect_feign_paths()

rows = []
alias_hits = []
unparsed = []
for controller in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
    text = io.open(controller, encoding='utf-8', errors='replace').read()
    cls = CLASS_MAP.search(text)
    prefix = cls.group(1) if cls else ''
    methods = parse_mappings(text)
    if not methods:
        continue
    missing = []
    for verb, path, anchor, raw in methods:
        if path is None:
            unparsed.append((controller.name, raw))
            continue
        full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
        full = full.rstrip('/') or '/'
        # 前端写的是模板串（`/search/search/${encodeURIComponent(key)}`），
        # 所以要把 {param} 与 ${...} 都当成通配段再比，否则每个带路径参数的端点都会误报成没人用。
        # 这一版初稿就是用字面串比较，把已经接好的 /search/search/{key} 报成了缺口。
        if re.compile(build_matcher(full)).search(frontend_text):
            continue
        # 网关别名：前端写 /connectors/outbox，后端注册的是 /spapi/connectors/outbox。
        # 只认这张显式表。曾经的「去掉第一段再比」会把 /ad/report/{shopId} 误当成
        # /report/{shopId}（那是报表服务的同名路径），等于工具自己造绿灯。
        alias = to_frontend_alias(full)
        if alias and re.compile(build_matcher(alias)).search(frontend_text):
            alias_hits.append((full, alias))
            continue
        block = annotation_block(text, anchor)
        tags = []
        if '@InternalServiceAccess' in block:
            tags.append('internal-access')
        if shape(full) in FEIGN_PATHS:
            tags.append('has-feign-caller')
        missing.append(('%s %s' % (verb.upper(), full), tags or ['user-facing-candidate']))
    rows.append((controller.parent.parent.name, controller.name, len(methods), missing))

candidates = [(mod, name, ep, tags) for mod, name, _, missing in rows
              for ep, tags in missing if tags == ['user-facing-candidate']]

print('%-22s %-34s %5s %s' % ('module', 'controller', 'eps', 'endpoints not named by amz-frontend/src/api'))
total_missing = 0
for module, name, n, missing in rows:
    mark = '' if not missing else '  <-- %d missing' % len(missing)
    print('%-22s %-34s %5d%s' % (module, name, n, mark))
    for ep, tags in missing:
        print('        [%s] %s' % (','.join(tags), ep.encode('ascii', 'backslashreplace').decode()))
        total_missing += 1
print('controllers=%d endpoints_without_a_frontend_name=%d user_facing_candidates=%d'
      % (len(rows), total_missing, len(candidates)))
print('parsed_method_annotations=%d unparsed=%d'
      % (sum(n for _, _, n, _ in rows), len(unparsed)))
print('matched_via_gateway_alias=%d' % len(alias_hits))
for path, alias in alias_hits:
    print('   %s  <- 前端写作 %s' % (
        path.encode('ascii', 'backslashreplace').decode(),
        alias.encode('ascii', 'backslashreplace').decode()))
print('')
print('==== 待接候选（既没有 @InternalServiceAccess，也没有任何 Feign 客户端指向它）====')
for module, name, ep, _ in candidates:
    print('  %-14s %-34s %s' % (module, name, ep.encode('ascii', 'backslashreplace').decode()))
if unparsed:
    print('')
    print('==== 没解析出路径的注解（需要人工确认，不能当成「不存在」）====')
    for name, raw in unparsed:
        print('  %-34s %s' % (name, raw.encode('ascii', 'backslashreplace').decode()))
