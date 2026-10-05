"""Stub shape audit: e2e 桩返回的字段必须真实存在于后端返回的 DTO 里。

反向尺（endpoint_coverage_audit.py --reverse）回答「桩拦的路径后端有没有」；
这把尺回答下一层：「桩给的 JSON 字段，后端真会给吗」。桩越像真的，e2e 越能
替字段漂移打掩护——桩里写着 foo、后端 DTO 早已改名 bar 时，e2e 依旧全绿，
页面接上真实后端后那一列永远是 undefined（api-stub.ts 头注里自己承认这条边界）。

口径（刻意从窄，避免重演键名探针「8 条全是伪影」的覆辙）：
- 只比「顶层字段名」：桩 data 的对象键 ⊆ 后端 data 类型的字段。桩少给字段是
  正常的（页面未必渲染全部），桩多给字段才是危险方向——那意味着后端从未
  有过这个字段，页面若依赖它，接真实后端后必是 undefined。
- 只在两端都能静态解析时才比：后端拿到具名 DTO 类（Lombok @Data / record）
  就提字段；拿到 Map<String, Object> 等静态不可知形态就**披露**为
  unverifiable，绝不静默放行，也绝不编造字段清单。
- 形态错位（桩给对象、后端是数组；或反之）同样是红：它说明桩描的是另一条
  端点的形状。
- 分母必须打印：registrations / comparable / passed / RED / unverifiable /
  not-comparable。任何「0 findings」先看分母是不是空的。

工具的失败模式必须是「多报并说明」，不能是「静默跳过」。
"""
import io
import re
import sys
from pathlib import Path

_positional = [a for a in sys.argv[1:] if not a.startswith('-')]
root = Path(_positional[0] if _positional else '.').resolve()

STUB_FILE = root / 'amz-frontend' / 'e2e' / 'support' / 'api-stub.ts'
GATEWAY_YML = root / 'amz-gateway' / 'src' / 'main' / 'resources' / 'application.yml'
REWRITE_LINE = re.compile(
    r'RewritePath=/api/([a-z-]+)\(\?<segment>\.\*\),\s*(/[\w/-]+)\$\{segment\}')

# shape / stub_shape / prefix_matches 与 endpoint_coverage_audit.py 同款。
# 不跨文件 import：那份脚本在模块顶层读仓库文件，import 它等于把两把尺的
# 失败模式捆在一起；三件套各自带自测钉住。
def shape(path: str) -> str:
    path = path.split('?')[0]
    segs = ['{}' if s.startswith('{') or s.startswith('$') else s for s in path.split('/') if s]
    return '/' + '/'.join(segs)


def stub_shape(raw: str) -> str:
    s = raw.replace('\\/', '/')
    s = re.sub(r'\(\?:[^)]*\)\+?', '', s)
    s = s.replace('\\d+', '{}').replace('[^/]+', '{}').replace('.*', '{}')
    s = s.replace('(', '').replace(')', '').replace('|', '/')
    # ^/$ 是锚点不是路径字符：不剥掉的话形状永远对不上后端（本班实测全量失配）
    s = s.replace('$', '').replace('^', '')
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


def gateway_alias_map() -> dict:
    """后端注册前缀 -> 前端书写前缀（/api/xxx 形式），只认网关 yml 这一份事实。"""
    if not GATEWAY_YML.exists():
        return {}
    text = io.open(GATEWAY_YML, encoding='utf-8', errors='replace').read()
    return {target: '/api/' + src for src, target in REWRITE_LINE.findall(text)}


GATEWAY_ALIASES = gateway_alias_map()


def to_backend_shape(front_shape: str) -> str:
    """桩/前端书写形状 -> 后端注册形状。

    installApiStub 把 pathname.slice('/api'.length) 交给桩匹配，所以桩里的路径
    与 api/*.ts 同一种写法（去掉 /api 的前端形状）；后端形状带自己的前缀。
    """
    for backend_prefix, frontend_prefix in GATEWAY_ALIASES.items():
        front_prefix = frontend_prefix[len('/api'):]
        if front_shape == front_prefix or front_shape.startswith(front_prefix + '/'):
            return backend_prefix + front_shape[len(front_prefix):]
    return front_shape


# ---------------- 通用扫描：字符串/正则不透明、括号配平 ----------------

# 正则字面量的 body 用 + 不用 *：裸 //（行注释）不是合法正则字面量，
# 若被当成字面量标成不透明区间，注释剥离会把全文件的行注释都漏掉
# （本班实测：桩常量里加一行带 tools/schema/ 路径的注释，整个常量解析
# 失败、变异测试静默测不出——假绿的绿）。
REGEX_LITERAL = re.compile(r'/(?:\\.|\[(?:\\.|[^\]\\])*\]|[^/\\\[\n])+/[a-z]*')
COMMENTS = re.compile(r'(?<!:)//[^\n]*|/\*.*?\*/', re.S)


def strip_comments(text: str) -> str:
    """两遍法剥注释：先按原文的字符串/正则区间保护，只删区间外的注释。

    单遍正则在本仓库真实踩中：桩正则以转义斜杠收尾（`/history\\//`）时，
    文本上恰好出现一对相邻的 `//`，注释剥离会把 `//, data: ... }` 整段当
    行注释吃掉，留下一个永远配不平的开括号。`(?<!:)` 仍然保留，作为
    字符串保护之外的第二道防线（https:// 不算注释）。
    块注释删除时按其中换行数补回换行，避免把前后两行代码拼成一行。
    """
    spans = opaque_spans(text)
    out, last = [], 0
    for m in COMMENTS.finditer(text):
        if not not_in_spans(m.start(), spans):
            continue
        out.append(text[last:m.start()])
        out.append('\n' * m.group(0).count('\n'))
        last = m.end()
    out.append(text[last:])
    return ''.join(out)


def opaque_spans(text: str, include_regex: bool = True) -> list:
    """字符串（' " `）与正则字面量的区间；扫描时整段跳过。

    include_regex=False 只标字符串区间：提取桩注册表时要先找 match: 的
    正则字面量，如果它自己也在区间里就会被「区间内候选一律丢弃」的过滤器
    筛掉（registrations=0 的假空分母就是这么来的）。

    引号按 ' " ` 顺序各扫一遍：先扫 ' 会把 "..." 里的撇号误标——本文件
    （prettier 单引号风格）里双引号串几乎只出现在单引号串内部，先 ' 后 "
    恰好把两者都罩住；换文件前先跑 --self-test。
    """
    spans = []
    if include_regex:
        for m in REGEX_LITERAL.finditer(text):
            spans.append((m.start(), m.end()))
    for quote in ("'", '"', '`'):
        i = 0
        while i < len(text):
            if any(a <= i < b for a, b in spans):
                i += 1
                continue
            if text[i] == quote:
                j = i + 1
                while j < len(text):
                    if text[j] == '\\':
                        j += 2
                        continue
                    if text[j] == quote:
                        j += 1
                        break
                    if text[j] == '\n':
                        break
                    j += 1
                spans.append((i, min(j, len(text))))
                i = min(j + 1, len(text))
                continue
            i += 1
    return sorted(spans)


PAIR = {'{': '}', '[': ']', '(': ')'}


def balanced_end(text: str, open_idx: int, spans=None) -> int:
    """text[open_idx] 是 { [ ( 之一，返回配对闭括号下标；配不上返回 -1。"""
    spans = spans if spans is not None else opaque_spans(text)
    stack = []
    i = open_idx
    while i < len(text):
        if any(a <= i < b for a, b in spans):
            i += 1
            continue
        c = text[i]
        if c in PAIR:
            stack.append(c)
        elif c in PAIR.values():
            if not stack or PAIR[stack.pop()] != c:
                return -1
            if not stack:
                return i
        i += 1
    return -1


def not_in_spans(pos: int, spans) -> bool:
    return not any(a <= pos < b for a, b in spans)


def top_level_parts(inner: str, spans):
    """按顶层逗号切分。"""
    parts, depth, last = [], 0, 0
    for i, c in enumerate(inner):
        if not not_in_spans(i, spans):
            continue
        if c in PAIR:
            depth += 1
        elif c in PAIR.values():
            depth -= 1
        elif c == ',' and depth == 0:
            parts.append(inner[last:i])
            last = i + 1
    parts.append(inner[last:])
    return parts


# ---------------- 桩侧：常量表 / 字面量 -> 字段键 ----------------

def const_table(text: str) -> dict:
    """顶层 `const NAME = <literal>` 的初始化表达式（文本，不递归求值）。"""
    out = {}
    spans = opaque_spans(text)
    for m in re.finditer(r'\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*', text):
        start = m.end()
        if not not_in_spans(start, spans) or (start > 0 and not not_in_spans(start - 1, spans)):
            continue
        if start < len(text) and text[start] in PAIR:
            end = balanced_end(text, start, spans)
            if end != -1:
                out.setdefault(m.group(1), text[start:end + 1])
    return out


def object_keys(expr, consts, memo=None, depth=0):
    """对象/数组字面量（含常量引用与展开）的顶层字段键并集。

    返回 (keys, kind, note)。kind 是载荷形态：object / array-of-object /
    array-of-scalar / scalar / string / null / function / mixed-array /
    spread-unknown / unknown-ref / unbalanced-object / empty / no-data。
    kind 决定这条注册「可比、不可比、还是形态错位」，note 必须可打印。
    """
    memo = memo if memo is not None else {}
    expr = expr.strip() if isinstance(expr, str) else None
    if depth > 8:
        return set(), 'unknown-ref', '递归过深'
    memo_key = expr
    if memo_key in memo:
        return memo[memo_key]
    memo[memo_key] = (set(), 'unknown-ref', '')
    result = _keys_impl(expr, consts, memo, depth)
    memo[memo_key] = result
    return result


def _keys_impl(expr, consts, memo, depth):
    if not expr:
        return set(), 'empty', ''
    spans = opaque_spans(expr)
    if expr[0] == '{' and balanced_end(expr, 0, spans) == len(expr) - 1:
        inner = expr[1:-1]
        keys, spread_unknown = set(), False
        for part in top_level_parts(inner, opaque_spans(inner)):
            part = part.strip()
            if not part:
                continue
            if part.startswith('...'):
                k2, kind2, _n = object_keys(part[3:].strip(), consts, memo, depth + 1)
                keys |= k2
                if kind2 in ('unknown-ref', 'function', 'spread-unknown', 'mixed-array'):
                    spread_unknown = True
                continue
            m = re.match(r'^(?:\'[^\']*\'|"[^"]*"|[A-Za-z_$][\w$]*)\s*:', part)
            if m:
                keys.add(m.group(0).rsplit(':', 1)[0].strip().strip('\'"'))
        return keys, ('spread-unknown' if spread_unknown else 'object'), ''
    if expr[0] == '[' and balanced_end(expr, 0, spans) == len(expr) - 1:
        inner = expr[1:-1]
        keys, kinds, notes = set(), set(), []
        for part in top_level_parts(inner, opaque_spans(inner)):
            part = part.strip()
            if not part:
                continue
            k2, kind2, note2 = object_keys(part, consts, memo, depth + 1)
            keys |= k2
            kinds.add(kind2)
            if note2:
                notes.append(note2)
        if not kinds:
            return set(), 'empty', ''
        if kinds <= {'object', 'spread-unknown'}:
            return keys, 'array-of-object', '; '.join(notes)
        if kinds <= {'scalar', 'string', 'null', 'empty'}:
            return set(), 'array-of-scalar', ''
        return keys, 'mixed-array', '数组元素形态混杂: %s %s' % (sorted(kinds), notes)
    if expr[0] in '\'"`':
        return set(), 'string', ''
    if expr in ('null', 'undefined'):
        return set(), 'null', ''
    if re.match(r'^[\d.]+$', expr) or expr in ('true', 'false'):
        return set(), 'scalar', ''
    # 字面量判完才轮到标识符：'null'/'true' 不许被当常量名
    if re.match(r'^[A-Za-z_$][\w$]*$', expr):
        if expr in consts:
            return object_keys(consts[expr], consts, memo, depth + 1)
        return set(), 'unknown-ref', '未解析标识符 %s' % expr
    if expr[0] == '(' or expr.startswith('function') or '=>' in expr:
        return set(), 'function', ''
    if expr[0] == '{':
        return set(), 'unbalanced-object', '对象字面量括号不配平'
    return set(), 'unknown-ref', '无法归类: %s' % expr[:60]


# ---------------- 后端侧：控制器映射 -> data 字段 ----------------

CLASS_MAP = re.compile(r'^@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"', re.M)
MAPPING_LINE = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\b[ \t]*([^\n]*)')
PATH_LITERAL = re.compile(r'(?:value|path)\s*=\s*"([^"]*)"|"([^"]*)"')
NON_PATH_ONLY_ARGS = re.compile(r"^\(\s*(?:consumes|produces|params|headers)\s*=")
VALUE_OR_PATH_ARG = re.compile(r"(?:value|path)\s*=")
ANN_RUN = re.compile(r'^(?:@\w+(?:\s*\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*)+')
RESULT_TYPE = re.compile(r'^(?:public|protected|private)?\s*(?:static\s+)?'
                         r'([\w.$]+(?:\s*<.*>)?)\s+\w+$', re.S)

VOID_TYPES = {'Void', 'void'}
SCALAR_TYPES = {'String', 'Integer', 'int', 'Long', 'long', 'Boolean', 'boolean',
                'Double', 'double', 'BigDecimal', 'Short', 'short', 'Float',
                'float', 'Byte', 'byte', 'Number'}


def parse_mappings(text):
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
        if NON_PATH_ONLY_ARGS.match(rest) and not VALUE_OR_PATH_ARG.search(rest):
            out.append((verb, '', m.start(), raw))
            continue
        literal = PATH_LITERAL.search(rest)
        path = ((literal.group(1) or literal.group(2)) if literal else None)
        out.append((verb, path, m.start(), raw))
    return out


def annotation_block(text: str, anchor: int) -> str:
    start = max(text.rfind('}', 0, anchor), text.rfind(';', 0, anchor)) + 1
    return text[start:anchor]


def unwrap_generic(t: str, wrapper: str):
    """List<Foo> -> Foo；不是该包装返回 None；嵌套泛型按配对剥。"""
    if not (t.startswith(wrapper + '<') and t.endswith('>')):
        return None
    inner = t[len(wrapper) + 1:-1]
    depth = 1
    for i, c in enumerate(inner):
        if c == '<':
            depth += 1
        elif c == '>':
            depth -= 1
            if depth == 0:
                return inner if i == len(inner) - 1 else inner[:i] + inner[i + 1:]
    return inner


def classify(inner: str):
    """Result<> 里的 data 类型 -> (kind, element, raw)。

    kind：dto / list-dto / page-dto（可比）、list-map / list-scalar /
    page-scalar / map / scalar / void（不可比）、unparsed（披露）。
    解析不出来必须给可打印的 raw，不许猜。
    """
    inner = re.sub(r'\s+', '', inner)
    if inner in VOID_TYPES:
        return ('void', None, inner)
    if inner in SCALAR_TYPES:
        return ('scalar', None, inner)
    list_elem = unwrap_generic(inner, 'List')
    if list_elem is not None:
        return _classify_element(list_elem, 'list')
    page_elem = unwrap_generic(inner, 'PageResult')
    if page_elem is not None:
        return _classify_element(page_elem, 'page')
    if unwrap_generic(inner, 'Map') is not None:
        return ('map', None, inner)
    if re.match(r'^[A-Z][\w.$]*$', inner):
        return ('dto', inner.split('.')[-1], inner)
    return ('unparsed', None, inner)


def _classify_element(elem: str, kind: str):
    if elem in VOID_TYPES:
        return (kind + '-scalar', None, elem)
    if elem in SCALAR_TYPES:
        return (kind + '-scalar', None, elem)
    if unwrap_generic(elem, 'Map') is not None:
        return (kind + '-map', None, elem)
    if re.match(r'^[A-Z][\w.$]*$', elem):
        return (kind + '-dto', elem.split('.')[-1], elem)
    return ('unparsed', None, elem)


def return_data_type(text: str, anchor: int):
    """映射注解之后方法声明的 data 类型（剥掉 Result<> 信封）。

    解析失败返回 ('unparsed', None, 签名头)，调用方据此披露，不许当「不存在」。
    """
    own = MAPPING_LINE.match(text, anchor)
    rest = text[own.end():] if own else text[anchor + 1:]
    rest = ANN_RUN.sub('', rest.lstrip(), count=1)
    head = rest.split('{', 1)[0]
    head = head[:head.find('(')] if '(' in head else head
    head = ' '.join(head.split())
    m = RESULT_TYPE.match(head)
    if not m:
        return ('unparsed', None, head[:60])
    t = re.sub(r'\s+', '', m.group(1))
    inner = unwrap_generic(t, 'Result')
    if inner is None:
        return ('not-result', None, t)
    return classify(inner)


FIELD_ANNOTATED = re.compile(
    r'@JsonProperty\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)\s*'
    r'(?:(?:private|protected|public)\s+[^;=]+?\s+(\w+)\s*(?:=[^;]*)?;)')
FIELD_PLAIN = re.compile(
    r'(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?'
    r'[^;=]+?\s+(\w+)\s*(?:=[^;]*)?;')
RECORD_COMP = re.compile(r'\brecord\s+(\w+)\s*\(([^)]*)\)', re.S)
KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'throw'}


def java_json_fields(text: str):
    """一个 java 类型的对外 JSON 字段名集合。

    Lombok @Data 的 private 字段按原名出 JSON；@JsonProperty 覆盖原名；
    record 组件按组件名出 JSON；static 字段（含 serialVersionUID）不序列化。
    解析不到任何成员时返回 None——None 与「真没有字段」是两回事，调用方
    必须区分（前者披露，后者是可比的空集）。
    """
    annotated = {}
    for jname, fname in FIELD_ANNOTATED.findall(text):
        annotated[fname] = jname
    fields = {}
    for m in FIELD_PLAIN.finditer(text):
        # start(1) 是全文绝对下标，必须先减 start(0) 换算成 group(0) 内的偏移，
        # 否则后面的字段头部会越切越长、把初始化值里的 '(' 误当方法签名
        decl_head = m.group(0)[:m.start(1) - m.start(0)]
        # 签名带 '('：方法声明会一路吞到分号，把 return null 的 null 当字段名
        if 'static' in decl_head.split() or '(' in decl_head:
            continue
        fname = m.group(1)
        if fname in KEYWORDS or fname == 'null':
            continue
        fields[fname] = annotated.get(fname, fname)
    for _cls, comps in RECORD_COMP.findall(text):
        for comp in comps.split(','):
            toks = comp.strip().split()
            if toks and re.match(r'^\w+$', toks[-1]) and toks[-1] not in fields:
                fields[toks[-1]] = toks[-1]
    if not fields:
        return None
    return {out for out in fields.values() if out != 'serialVersionUID'}


JAVA_STRING = re.compile(
    r'"""(?:\\.|[^\\])*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)
JAVA_COMMENT = re.compile(r'//[^\n]*|/\*.*?\*/', re.S)
TYPE_DECL = re.compile(r'\b(?:class|record|interface|enum)\s+(\w+)')
# 声明行之后、类体 { 之前允许出现的其它字符里，遇到这些说明这不是类体开头
# （抽象方法 / 字段常量行直接分号收尾）
DECL_STOPPERS = ';='


def java_strip_comments(text: str) -> str:
    """Java 版两遍法：先标字符串/文本块/字符区间，再删区间外注释。

    中文注释里出现引号是常态（「...」或 "..."），单遍剥离会被骗。
    """
    spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(text)]
    out, last = [], 0
    for m in JAVA_COMMENT.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        out.append(text[last:m.start()])
        out.append('\n' * m.group(0).count('\n'))
        last = m.end()
    out.append(text[last:])
    return ''.join(out)


def java_type_fields(text: str) -> dict:
    """一个 .java 文件里**每个**类型声明（含嵌套 record/内部类）-> 字段集合。

    只按文件名建索引会漏掉控制器/服务里的嵌套 DTO（Capability、OutboxView、
    ReplayResult 都是嵌套 record，公开给 Feign/前端用），按声明扫才能齐。
    """
    text = java_strip_comments(text)
    spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(text)]
    out = {}
    for m in TYPE_DECL.finditer(text):
        if not not_in_spans(m.start(), spans):
            continue
        # record 的组件在头部的 (…) 里、类体 { 之前——先抓出来
        comp_fields = {}
        paren_start = None
        open_idx = None
        i = m.end()
        while i < min(m.end() + 600, len(text)):
            if not not_in_spans(i, spans):
                i += 1
                continue
            c = text[i]
            if c == '(' and paren_start is None:
                paren_start = i
            elif c == '{':
                open_idx = i
                break
            elif c in DECL_STOPPERS and paren_start is None:
                break
            i += 1
        if paren_start is not None:
            paren_end = balanced_end(text, paren_start, spans)
            if paren_end != -1:
                for comp in text[paren_start + 1:paren_end].split(','):
                    toks = comp.strip().split()
                    if toks and re.match(r'^\w+$', toks[-1]):
                        comp_fields[toks[-1]] = toks[-1]
        if open_idx is None:
            if comp_fields:
                out.setdefault(m.group(1), set(comp_fields.values()))
            continue
        close = balanced_end(text, open_idx, spans)
        if close == -1:
            continue
        block = _strip_nested_types(text[m.start():close + 1], spans)
        names = java_json_fields(block) or set()
        names |= set(comp_fields.values())
        if names:
            out.setdefault(m.group(1), {v for v in names if v != 'serialVersionUID'})
    return out


def _strip_nested_types(block: str, spans) -> str:
    """把块内嵌套类型的 声明+类体 整段挖掉：嵌套 record 的组件不许算进外层的字段。"""
    cut = []
    for m in TYPE_DECL.finditer(block):
        if m.start() == 0:
            continue  # 块是从自身声明关键字开始切片的，第一个不是嵌套类型
        if m.group(1) in ('if', 'for', 'while', 'switch', 'catch', 'synchronized'):
            continue
        cl = None
        brace = None
        i = m.end()
        while i < min(m.end() + 600, len(block)):
            if not not_in_spans(i, spans):
                i += 1
                continue
            c = block[i]
            if c == '(' and brace is None:
                pass
            elif c == '{':
                brace = i
                break
            elif c in DECL_STOPPERS:
                break
            i += 1
        if brace is None:
            continue
        close = balanced_end(block, brace, spans)
        if close != -1:
            cut.append((m.start(), close + 1))
    for a, b in sorted(cut, reverse=True):
        block = block[:a] + ' ' + block[b:]
    return block


def wildcard_score(stub_segs, b_segs) -> int:
    """候选后端形状的「牵强程度」：靠通配段才对上的位置数。

    桩字面段吃进后端 {}（如 stub 的 rotate 撞上 /oauth/app/list/{shopId} 的
    末段通配）或反向，都计 1；参数段对参数段（{} 对 {id}）也计 1——
    真端点的得分恒低于借通配搭上车的兄弟端点。
    """
    return sum(1 for a, b in zip(stub_segs, b_segs) if a == '{}' or b == '{}')


class FieldIndex:
    """后端 java 源码的 类名 -> JSON 字段名集合 + 控制器文本缓存。"""

    def __init__(self, repo_root: Path):
        self.by_name = {}
        files = list(repo_root.glob('amz-service/*/src/main/java/**/*.java'))
        files += list(repo_root.glob('amz-common/src/main/java/**/*.java'))
        for f in files:
            text = io.open(f, encoding='utf-8', errors='replace').read()
            for name, fields in java_type_fields(text).items():
                self.by_name.setdefault(name, fields)
        self.controllers = []
        for f in sorted(repo_root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
            self.controllers.append((f, io.open(f, encoding='utf-8', errors='replace').read()))

    def fields(self, class_name: str):
        return self.by_name.get(class_name)


def backend_endpoint_index():
    """形状 -> [(verb, anchor, text)]，一次扫描同时建 FieldIndex。"""
    index = FieldIndex(root)
    idx = {}
    for _f, text in index.controllers:
        cls = CLASS_MAP.search(text)
        prefix = cls.group(1) if cls else ''
        for verb, path, anchor, _raw in parse_mappings(text):
            if path is None:
                continue
            full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
            full = full.rstrip('/') or '/'
            idx.setdefault(shape(full), []).append((verb.upper(), anchor, text))
    return idx, index


# ---------------- 自测：全部用内联样本，不依赖仓库当前内容 ----------------

if '--self-test' in sys.argv:
    cases = []

    def check(name, got, want):
        cases.append((name, got == want, got, want))

    check('对象字面量顶层键（嵌套与字符串里的逗号不串味）',
          sorted(object_keys("{'a': 1, b: {c: 'x, y'}, d: [1, 2]}", {})[0]),
          ['a', 'b', 'd'])
    check('展开运算符合并常量键',
          sorted(object_keys('{ ...BASE, extra: 1 }', {'BASE': '{ k1: 1, k2: 2 }'})[0]),
          ['extra', 'k1', 'k2'])
    check('数组：对象元素取键并集',
          sorted(object_keys('[{a:1,b:2},{b:3,c:4}]', {})[0]), ['a', 'b', 'c'])
    check('数组：标量元素不给键', object_keys("['x','y']", {}), (set(), 'array-of-scalar', ''))
    check('常量引用解析一层', object_keys('C', {'C': '[{p:1}]'}), ({'p'}, 'array-of-object', ''))
    check('函数载荷不可比', object_keys('(q) => ({ x: q.get("k") })', {})[1], 'function')
    check('null 不可比', object_keys('null', {}), (set(), 'null', ''))
    check('正则里的方括号不参与配平',
          balanced_end('{ match: /^\\/a\\/[\\d]+$/, data: 1 }', 0),
          len('{ match: /^\\/a\\/[\\d]+$/, data: 1 }') - 1)
    # 真实坑：桩正则以转义斜杠收尾（history\//）时文本上出现相邻 `//`，
    # 单遍注释剥离会把 `//, data: ... }` 当行注释吃掉，留下配不平的开括号。
    tricky = ("const T = [\n"
              "  { match: /^\\/a\\/history\\//, data: KEEP },\n"
              "  { match: /^\\/b$/, data: { k: 1 } },\n"
              "]\n")
    check('转义斜杠收尾的正则不被注释剥离吃掉',
          'data: KEEP' in strip_comments(tricky), True)
    check('URL 里的 // 也不算注释',
          strip_comments("const u = 'https://oss.example.com/a.png'\nconst ok2 = 1\n"),
          "const u = 'https://oss.example.com/a.png'\nconst ok2 = 1\n")
    check('裸 // 永远是注释，不得当成正则字面量保护起来',
          strip_comments('const a = 1 // 备注 tools/schema/x.py\nconst b = 2\n'),
          'const a = 1 \nconst b = 2\n')
    check('通配得分：真端点恒低于借通配搭车的兄弟端点',
          [wildcard_score(['oauth', 'app', '{}', 'rotate'], ['oauth', 'app', '{}', 'rotate']),
           wildcard_score(['oauth', 'app', '{}', 'rotate'], ['oauth', 'app', 'list', '{}'])],
          [1, 2])
    check('嵌套 record 单独成类型，且不污染外层字段',
          java_type_fields('class Outer { private int a;\n'
                           '    public record Inner(String code, int rank) {}\n'
                           '}\n'),
          {'Outer': {'a'}, 'Inner': {'code', 'rank'}})
    check('泛型嵌套剥对', unwrap_generic('List<Map<String,Foo>>', 'List'), 'Map<String,Foo>')
    check('Result<Boolean> 是标量', classify('Boolean'), ('scalar', None, 'Boolean'))
    check('Result<Void> 是 void', classify('Void')[0], 'void')
    check('Result<Map<String,Object>> 不可核', classify('Map<String,Object>')[0], 'map')
    check('List<Map<..>> 元素不可核', classify('List<Map<String,Object>>')[0], 'list-map')

    java = ('@Data public class Foo { private static final long serialVersionUID = 1L;\n'
            '    private String accessToken;\n'
            '    @JsonProperty("_page")\n'
            '    private PageMeta page;\n'
            '    private List<String> scopes = new ArrayList<>();\n'
            '    public Result<String> method() { return null; }\n'
            '    record Inner(long id, String name) {}\n'
            '}\n')
    check('java 字段抽取（@JsonProperty/record/剔除方法与静态）',
          java_json_fields(java), {'accessToken', '_page', 'scopes', 'id', 'name'})

    sig = ('@PostMapping("/x")\n    public Result<List<Foo>> listApps(@PathVariable Long id) {\n')
    check('返回类型从映射注解后的签名取', return_data_type(sig, 0)[0:2], ('list-dto', 'Foo'))

    failed = [c for c in cases if not c[1]]
    for name, ok_flag, got, want in cases:
        print('%-52s %s (got=%s want=%s)' % (
            name.encode('ascii', 'replace').decode(), 'PASS' if ok_flag else 'FAIL', got, want))
    print('SELFTEST %d/%d passed' % (len(cases) - len(failed), len(cases)))
    sys.exit(1 if failed else 0)


def require_repo_root() -> None:
    if not STUB_FILE.exists() or not list(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
        print('ROOT NOT A REPO: %s（api-stub.ts 或控制器分母为空）' % root)
        sys.exit(2)


require_repo_root()

# ---------------- 主流程：注册表 -> 后端形状 -> 字段比对 ----------------

stub_text = strip_comments(io.open(STUB_FILE, encoding='utf-8', errors='replace').read())
consts = const_table(stub_text)

# 只认 STUBS 注册表；SSE_STUBS 不是 JSON 信封（contentType/body），没有 data 键，
# 自然落进 no-data 披露桶。
regs = []
stubs_decl = re.search(r'\bconst\s+STUBS\b[^=]*=\s*', stub_text)
if stubs_decl:
    tbl_start = stubs_decl.end()
    tbl_end = balanced_end(stub_text, tbl_start) if stub_text[tbl_start:tbl_start + 1] == '[' else -1
    if tbl_end == -1:
        print('STUBS 注册表解析失败（括号不配平）——拒绝输出任何比对结果')
        sys.exit(2)
    table = stub_text[tbl_start + 1:tbl_end]
    table_spans = opaque_spans(table)
    # 候选正则字面量只按字符串区间过滤：match: 的正则本身就是字面量，
    # 用全量区间筛会把要找的东西筛掉（registrations=0 的假空分母）
    string_spans = opaque_spans(table, include_regex=False)
    for m in re.finditer(r'match\s*:', table):
        if not not_in_spans(m.start(), table_spans):
            continue
        entry_open = table.rfind('{', 0, m.start())
        if entry_open == -1 or not not_in_spans(entry_open, table_spans):
            continue
        entry_close = balanced_end(table, entry_open, table_spans)
        if entry_close == -1:
            continue
        mm = None
        for cand in REGEX_LITERAL.finditer(table, entry_open, entry_close):
            if not not_in_spans(cand.start(), string_spans):
                continue
            mm = cand
            break
        if not mm:
            continue
        regs.append((mm.group(0), table[entry_open + 1:entry_close]))

ep_idx, index = backend_endpoint_index()


def data_expr_of(entry: str):
    """注册项里 data 表达式的文本；没有 data 键返回 None。"""
    spans = opaque_spans(entry)
    m = re.search(r'\bdata\s*:', entry)
    if not m or not not_in_spans(m.start(), spans):
        return None
    start = m.end()
    depth = 0
    i = start
    while i < len(entry):
        if not not_in_spans(i, spans):
            i += 1
            continue
        c = entry[i]
        if c in PAIR:
            depth += 1
        elif c in PAIR.values():
            depth -= 1
        elif c == ',' and depth == 0:
            break
        i += 1
    return entry[start:i].strip()


FIELD_KINDS = ('object', 'array-of-object')
NO_FIELD_KINDS = ('scalar', 'string', 'null', 'empty', 'array-of-scalar')
UNRESOLVED_KINDS = ('no-data', 'function', 'unknown-ref', 'spread-unknown',
                    'mixed-array', 'unbalanced-object')

rows = []
for regex, entry in regs:
    # regex 形如 /^\/a\/\d+$/；取首尾定界符之间的正则体归一成路径形状
    body = regex[1:regex.rfind('/')]
    s_shape = stub_shape(body)
    anchored = body.endswith('$')
    cands = []
    for cand in {s_shape, to_backend_shape(s_shape)}:
        stub_segs = cand.split('/')[1:]
        for b_shape, entries in ep_idx.items():
            b_segs = b_shape.split('/')[1:]
            if not prefix_matches(stub_segs, b_segs):
                continue
            if anchored and len(stub_segs) != len(b_segs):
                # 桩正则有 $ 收尾：比它更长的后端形状是另一条端点，
                # 前缀命中只是通配段把桩的字面段吃掉的假象
                continue
            score = wildcard_score(stub_segs, b_segs)
            cands.append((score, b_shape, entries))
    d_expr = data_expr_of(entry)
    if d_expr is None:
        keys, kind, note = set(), 'no-data', '无 data 键（SSE 或遗留条目）'
    else:
        keys, kind, note = object_keys(d_expr, consts)
    best = min((c[0] for c in cands), default=-1)
    rows.append({'shape': s_shape, 'anchored': anchored, 'cands': cands,
                 'best': best, 'keys': keys, 'kind': kind, 'note': note})

findings, unverifiable, not_comparable, ambiguous = [], [], [], []
passed = comparable = 0
for r in rows:
    if r['kind'] in UNRESOLVED_KINDS or r['note']:
        not_comparable.append(r)
        continue
    if not r['cands']:
        not_comparable.append(dict(r, note='后端无映射（路径级缺口归 --reverse 管）'))
        continue
    best_cands = [c for c in r['cands'] if c[0] == r['best']]
    verdicts = []          # (b_shape, 'pass'|'red', finding-tuple) / unverifiable 单列
    local_unverifiable = []
    if r['kind'] in NO_FIELD_KINDS:
        for _score, b_shape, entries in best_cands:
            for _verb, anchor, text in entries:
                bkind, elem, raw_t = return_data_type(text, anchor)
                if bkind in ('dto', 'list-dto', 'page-dto'):
                    if r['kind'] == 'empty':
                        # 空数组是「列表为空」的诚实桩值：元素形状没被演练，
                        # 但没有说谎，放行（要演练元素形状就给非空样本）
                        verdicts.append((b_shape, 'pass', None))
                    elif r['kind'] in ('null', 'array-of-scalar'):
                        not_comparable.append(dict(r, note='桩形态 %s 对带字段后端 %s，语义存疑需人工'
                                                          % (r['kind'], b_shape)))
                    else:
                        verdicts.append((b_shape, 'red',
                                         (r['shape'], b_shape,
                                          ['<后端返回带字段形态 %s，桩却是 %s>' % (bkind, r['kind'])], [])))
                else:
                    verdicts.append((b_shape, 'pass', None))
    else:
        comparable += 1
        for _score, b_shape, entries in best_cands:
            for _verb, anchor, text in entries:
                bkind, elem, raw_t = return_data_type(text, anchor)
                if bkind in ('dto', 'list-dto', 'page-dto'):
                    fk = index.fields(elem) if elem else None
                    if fk is None:
                        local_unverifiable.append((b_shape, 'DTO 类 %s 未找到或无成员' % elem))
                        continue
                    extra = r['keys'] - fk
                    kind_swap = ((bkind == 'list-dto' and r['kind'] == 'object')
                                 or (bkind == 'dto' and r['kind'] == 'array-of-object'))
                    if extra or kind_swap:
                        verdicts.append((b_shape, 'red',
                                         (r['shape'], b_shape,
                                          sorted(extra) + (['<形态错位: 桩=%s 后端=%s>'
                                                           % (r['kind'], bkind)] if kind_swap else []),
                                          sorted(fk))))
                    else:
                        verdicts.append((b_shape, 'pass', None))
                elif bkind in ('map', 'list-map', 'unparsed', 'not-result'):
                    local_unverifiable.append((b_shape, '后端返回 %s' % (raw_t or bkind)))
                else:   # scalar / void / list-scalar / page-scalar：后端无字段形态
                    verdicts.append((b_shape, 'red',
                                     (r['shape'], b_shape,
                                      ['<后端返回无字段形态 %s，桩却给了字段>' % bkind], [])))
    if not verdicts:
        pass  # 最佳组全部不可核：只进 unverifiable 披露
    else:
        states = {v for _b, v, _f in verdicts}
        if states == {'pass'}:
            passed += 1
        elif states == {'red'}:
            seen = set()
            for _b, _v, f in verdicts:
                if (f[0], f[1], tuple(f[2])) not in seen:
                    seen.add((f[0], f[1], tuple(f[2])))
                    findings.append(f)
        else:
            ambiguous.append((r['shape'], sorted({'%s:%s' % (b, v) for b, v, _f in verdicts})))
    unverifiable.extend((r['shape'], b, why) for b, why in local_unverifiable)

print('stub-shape audit: registrations=%d consts=%d backend-shapes=%d'
      % (len(regs), len(consts), len(ep_idx)))
print('field-comparable=%d passed=%d RED=%d ambiguous=%d unverifiable=%d not-comparable=%d'
      % (comparable, passed, len(findings), len(ambiguous), len(unverifiable), len(not_comparable)))
if findings:
    print('GATE RED stub-field-not-in-backend-dto: %d' % len(findings))
    for s, b, extra, _have in findings:
        print('   %s -> %s  桩多出: %s' % (s, b, extra))
if ambiguous:
    print('disclosed ambiguous=%d（同一形状多个动词返回类型冲突，需人工归因）' % len(ambiguous))
    for s, states in ambiguous:
        print('   %-46s %s' % (s, states))
if unverifiable:
    print('disclosed unverifiable=%d（后端形态静态不可核，非阻断，需人工核对）' % len(unverifiable))
    for s, b, why in sorted(set(unverifiable)):
        print('   %-44s -> %-44s %s' % (s, b, why))
if not_comparable:
    print('disclosed not-comparable=%d（桩载荷无字段可比，非阻断）' % len(not_comparable))
    for r in not_comparable:
        print('   %-48s kind=%-16s %s' % (r['shape'], r['kind'], r['note'] or ''))
if not findings:
    print('stub-shape gate: 0 findings')
sys.exit(1 if findings else 0)
