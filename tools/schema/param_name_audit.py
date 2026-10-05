"""Param-name audit: 前端 params 里的键必须是后端真会读的 @RequestParam 名。

形状尺（stub_shape_audit.py）回答「桩的字段后端给不给」；这把尺回答请求方向：
「前端 query 串里的键，后端接不接」。Spring 对未知的 query 参数是**静默忽略**的——
页面带着一个筛选键发请求、后端根本没这个 @RequestParam 时，UI 显示"已筛选"，
数据其实没筛。这正是上一班键名探针想量、却产出 8 条全伪影的那一半。

口径（沿用形状尺踩出来的方法论）：
- 后端参数名按 Spring 契约取：`value="x"` 显式名优先，其次 `@RequestParam("x")`，
  裸注解与 `(required=false)`/`(defaultValue=...)` 取 **Java 参数名**——
  `defaultValue = "14"` 是默认值不是名字，上一班 8 条伪影里就有它。
- `defaultValue` 或 `required=false` 意味着后端可选（Spring 语义：有 defaultValue
  即非必填）；后端必填而前端键缺失是潜在 400，单独披露不阻断（先量清楚再收紧）。
- 前端只比 `params:` 键（axios 的 params = query 串），POST body 的键不比——
  上一班的伪影之一就是拿 body 键去比 query 参数名。路径里内联的 `?key=${v}`
  也是 query，键要一并提取。
- `params: params({...})` 这类 null 过滤 helper：不改键名只筛值，键集合仍是
  字面量键的超集语义，可比；`params: params(q)` 变量实参与 `{ params }` 透传
  解析不了，**披露**不猜。
- 候选端点按「靠路径参数才命中的位置数」打分取最优组（同形状尺）；
  多候选裁决聚合：全过→过，全红→红，混合→披露 ambiguous。
- 分母必须打印：call-sites / with-params / parseable / compared / pass / RED。
  任何「0 findings」先看分母。

工具的失败模式必须是「多报并说明」，不能是「静默跳过」。
"""
import io
import re
import sys
from pathlib import Path

_positional = [a for a in sys.argv[1:] if not a.startswith('-')]
root = Path(_positional[0] if _positional else '.').resolve()

API_DIR = root / 'amz-frontend' / 'src' / 'api'
GATEWAY_YML = root / 'amz-gateway' / 'src' / 'main' / 'resources' / 'application.yml'
REWRITE_LINE = re.compile(
    r'RewritePath=/api/([a-z-]+)\(\?<segment>\.\*\),\s*(/[\w/-]+)\$\{segment\}')

# ---------------- Java 侧扫描（与 stub_shape_audit 同款两遍法） ----------------

JAVA_STRING = re.compile(
    r'"""(?:\\.|[^\\])*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)
JAVA_COMMENT = re.compile(r'//[^\n]*|/\*.*?\*/', re.S)
PAIR = {'{': '}', '[': ']', '(': ')'}
KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'throw'}


def java_strip_comments(text: str) -> str:
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


def balanced_end(text: str, open_idx: int, spans) -> int:
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


def top_level_parts(inner: str, spans):
    parts, depth, last = [], 0, 0
    for i, c in enumerate(inner):
        if any(a <= i < b for a, b in spans):
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


CLASS_MAP = re.compile(r'^@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"', re.M)
MAPPING_LINE = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\b[ \t]*([^\n]*)')
PATH_LITERAL = re.compile(r'(?:value|path)\s*=\s*"([^"]*)"|"([^"]*)"')
NON_PATH_ONLY_ARGS = re.compile(r"^\(\s*(?:consumes|produces|params|headers)\s*=")
VALUE_OR_PATH_ARG = re.compile(r"(?:value|path)\s*=")
ANN_RUN = re.compile(r'^(?:@\w+(?:\s*\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*)+')
REQ_PARAM = re.compile(r'@RequestParam\b')
REQUEST_PARAM_NAME = re.compile(
    r'@RequestParam\s*(?:\(\s*(?:value\s*=\s*)?"([^"]+)"\s*[^)]*\)|\(\s*\))?')


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


def signature_params(text: str, anchor: int):
    """映射注解之后方法签名的顶层参数段列表（字符串感知）。"""
    own = MAPPING_LINE.match(text, anchor)
    rest = text[own.end():] if own else text[anchor + 1:]
    rest = ANN_RUN.sub('', rest.lstrip(), count=1)
    i = rest.find('(')
    if i == -1:
        return []
    spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(rest)]
    close = balanced_end(rest, i, spans)
    if close == -1:
        return None        # 括号不配平：调用方披露，不许静默
    inner = rest[i + 1:close]
    # 切片后必须重算区间：外层的 spans 下标对切片无效（本班第二次踩同一类坑）
    inner_spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(inner)]
    return [p.strip() for p in top_level_parts(inner, inner_spans) if p.strip()]


def request_param_name(param: str):
    """一个参数段 -> (名字或 None, required, dynamic)。

    名字按 Spring 契约：value="x" / @"x" 显式名优先，否则取 Java 参数名。
    defaultValue=... 不是名字；带 defaultValue 或 required=false 即可选。
    dynamic：@RequestParam Map<...> 这种动态接参，静态不可核。
    """
    if not REQ_PARAM.search(param):
        return None, None, False
    spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(param)]
    m = REQ_PARAM.search(param)
    attrs = ''
    if m.end() < len(param) and param[m.end()] == '(':
        close = balanced_end(param, m.end(), spans)
        if close == -1:
            return None, None, True
        attrs = param[m.end() + 1:close]
    explicit = None
    if attrs:
        # 逐段解析：只有「首位裸字符串」或 value= 才是显式名。
        # defaultValue = "14" 的 "14" 是默认值不是名字——上一班探针 8 条伪影之一。
        a_spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(attrs)]
        for seg in top_level_parts(attrs, a_spans):
            seg = seg.strip()
            if not seg:
                continue
            if seg.startswith('"'):
                explicit = seg.strip('"')
                break
            mv = re.match(r'^value\s*=\s*"([^"]*)"', seg)
            if mv:
                explicit = mv.group(1)
                break
            if seg.startswith('defaultValue'):
                break          # 首位是 defaultValue：没有显式名，名字取 Java 参数名
    required = not (re.search(r'\brequired\s*=\s*false\b', attrs)
                    or 'defaultValue' in attrs)
    dynamic = bool(re.search(r'Map\s*<', param))
    body = strip_annotations(param)
    body = body.split('=', 1)[0]          # int size = 1 这类带默认值的写法
    name = last_identifier(body)
    if explicit is not None:
        name = explicit
        # 显式名为空串（@RequestParam("")）罕见：宁可披露也不硬猜
        if explicit == '':
            name = None
    return name, required, dynamic


def strip_annotations(param: str) -> str:
    """把参数段里的注解（含配对括号）整段挖掉，剩下 类型 [名字 [= 默认值]]。

    不能只 sub 掉注解名留属性括号——split('=') 会把属性串连同参数名一起吃掉。
    """
    body = param
    while True:
        m2 = re.search(r'@\w+\b', body)
        if not m2:
            break
        j = m2.end()
        b_spans = [(s.start(), s.end()) for s in JAVA_STRING.finditer(body)]
        if j < len(body) and body[j] == '(':
            close = balanced_end(body, j, b_spans)
            body = body[:m2.start()] + ' ' + (body[close + 1:] if close != -1 else '')
        else:
            body = body[:m2.start()] + ' ' + body[j:]
    return body


def last_identifier(body: str):
    toks = [t for t in re.split(r'[\s<>,\[\]\?\.\*&]+', body.strip()) if t]
    for t in reversed(toks):
        if re.match(r'^[A-Za-z_$][\w$]*$', t) and t not in KEYWORDS:
            return t
    return None


SCALAR_TYPES = {'String', 'Integer', 'int', 'Long', 'long', 'Boolean', 'boolean',
                'Double', 'double', 'BigDecimal', 'Short', 'short', 'Float',
                'float', 'Byte', 'byte'}


def param_query_names(param: str, index):
    """一个参数段对 query 串的贡献。

    返回 (names{name:required}|None, dynamic, is_query)。is_query=False 表示
    这个形参与 query 无关（@PathVariable/@RequestBody），调用方跳过。
    无注解形参走 Spring ModelAttribute 语义：标量取参数名（可选），
    POJO 按其 DTO 字段绑定（全部可选，字段解析不了则 names=None 披露）。
    """
    has_rp = bool(REQ_PARAM.search(param))
    if re.search(r'@(PathVariable|RequestBody|RequestPart)\b', param) and not has_rp:
        return None, False, False
    body = strip_annotations(param).split('=', 1)[0]
    toks = [t for t in re.split(r'[\s<>,\[\]\?\.\*&]+', body.strip()) if t]
    # MultipartFile 即使带 @RequestParam 也是 multipart body part，不是 query
    if 'MultipartFile' in toks:
        return None, False, False
    if has_rp:
        name, required, dynamic = request_param_name(param)
        if dynamic:
            return None, True, True
        if name is None:
            return set(), False, True
        return {name: required}, False, True
    if '@' in param:
        return None, False, False          # 其它注解形参（@Valid 等）：不猜
    if not toks or len(toks) == 1:
        return None, False, False          # 只有类型没有名字：无法静态绑定
    ttype = toks[-2].split('.')[-1]
    pname = toks[-1]
    if ttype in SCALAR_TYPES:
        return {pname: False}, False, True
    if re.match(r'^[A-Z]', ttype):
        fields = index.fields(ttype)
        if fields is None:
            return None, False, True       # POJO 类找不到/无成员：披露
        return {f: False for f in fields}, False, True
    return None, False, True               # Map/泛型等动态形态：披露


# ---------------- DTO 字段索引（无注解 POJO 按 ModelAttribute 绑定 query） ----------------
# 与 stub_shape_audit 的 FieldIndex 同款：@JsonProperty/record/嵌套类型口径一致。

FIELD_ANNOTATED = re.compile(
    r'@JsonProperty\(\s*(?:value\s*=\s*)?"([^"]+)"\s*\)\s*'
    r'(?:(?:private|protected|public)\s+[^;=]+?\s+(\w+)\s*(?:=[^;]*)?;)')
FIELD_PLAIN = re.compile(
    r'(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?'
    r'[^;=]+?\s+(\w+)\s*(?:=[^;]*)?;')
RECORD_COMP = re.compile(r'\brecord\s+(\w+)\s*\(([^)]*)\)', re.S)
TYPE_DECL = re.compile(r'\b(?:class|record|interface|enum)\s+(\w+)')
DECL_STOPPERS = ';='


def java_json_fields(text: str):
    annotated = {}
    for jname, fname in FIELD_ANNOTATED.findall(text):
        annotated[fname] = jname
    fields = {}
    for m in FIELD_PLAIN.finditer(text):
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


def _strip_nested_types(block: str, spans) -> str:
    cut = []
    for m in TYPE_DECL.finditer(block):
        if m.start() == 0:
            continue
        if m.group(1) in KEYWORDS:
            continue
        brace = None
        i = m.end()
        while i < min(m.end() + 600, len(block)):
            if any(a <= i < b for a, b in spans):
                i += 1
                continue
            if block[i] == '{':
                brace = i
                break
            if block[i] in DECL_STOPPERS:
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


def java_type_fields(text: str) -> dict:
    text = java_strip_comments(text)
    spans = [(m.start(), m.end()) for m in JAVA_STRING.finditer(text)]
    out = {}
    for m in TYPE_DECL.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        open_idx = None
        i = m.end()
        while i < min(m.end() + 600, len(text)):
            if any(a <= i < b for a, b in spans):
                i += 1
                continue
            if text[i] == '{':
                open_idx = i
                break
            if text[i] in DECL_STOPPERS:
                break
            i += 1
        if open_idx is None:
            continue
        close = balanced_end(text, open_idx, spans)
        if close == -1:
            continue
        block = _strip_nested_types(text[m.start():close + 1], spans)
        fields = java_json_fields(block)
        if fields is not None:
            out.setdefault(m.group(1), fields)
    return out


class FieldIndex:
    def __init__(self, repo_root: Path):
        self.by_name = {}
        files = list(repo_root.glob('amz-service/*/src/main/java/**/*.java'))
        files += list(repo_root.glob('amz-common/src/main/java/**/*.java'))
        for f in files:
            text = io.open(f, encoding='utf-8', errors='replace').read()
            for name, fields in java_type_fields(text).items():
                self.by_name.setdefault(name, fields)

    def fields(self, class_name: str):
        return self.by_name.get(class_name)


def parse_params_qualifier(raw: str):
    """mapping 注解里的 params="a"/{"a","!b"} 分发限定符 -> (present, forbid)。"""
    present, forbid = set(), set()
    qm = re.search(r'params\s*=\s*(\{[^}]*\}|"[^"]*")', raw)
    if qm:
        for e in re.findall(r'"([^"]*)"', qm.group(1)):
            if e.startswith('!'):
                forbid.add(e[1:])
            else:
                present.add(e)
    return present, forbid


def backend_endpoint_params(index):
    """形状 -> [entry]。entry 带 names/dynamic/sig/file 与 params= 分发限定符。

    `@PostMapping(value="/x", params="shopId")` 与 `params="!shopId"` 是同路径
    同方法的两条 Spring 分发变体：按 query 里 shopId 的存在性路由。
    限定符本身也是「这个变体要求该键必须存在/缺席」的契约。
    """
    idx = {}
    for f in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
        text = java_strip_comments(io.open(f, encoding='utf-8', errors='replace').read())
        cls = CLASS_MAP.search(text)
        prefix = cls.group(1) if cls else ''
        for verb, path, anchor, raw in parse_mappings(text):
            if path is None:
                continue
            full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
            full = full.rstrip('/') or '/'
            names, dynamic, unparsed_sig = {}, False, False
            present, forbid = parse_params_qualifier(raw)
            params = signature_params(text, anchor)
            if params is None:
                unparsed_sig = True
            else:
                for p in params:
                    pnames, dyn, is_query = param_query_names(p, index)
                    if dyn:
                        dynamic = True
                    if not is_query:
                        continue
                    if pnames is None:
                        dynamic = True     # 静态不可核：按披露口径处理
                        continue
                    names.update(pnames)
            names.update({p: True for p in present})   # 分发要求的键必然必填
            idx.setdefault((shape_of(full), verb.upper()), []).append(
                {'names': names, 'dynamic': dynamic, 'sig': unparsed_sig,
                 'file': f.name, 'present': present, 'forbid': forbid})
    return idx


def shape_of(path: str) -> str:
    # 后端 {x} 与前端 ${x} 都归一成 {}：跨端比对的同一条路径
    segs = ['{}' if s.startswith('{') or s.startswith('$') else s
            for s in path.split('/') if s]
    return '/' + '/'.join(segs)


# ---------------- 前端侧扫描（TS，两遍法剥注释） ----------------

REGEX_LITERAL = re.compile(r'/(?:\\.|\[(?:\\.|[^\]\\])*\]|[^/\\\[\n])+/[a-z]*')
TS_COMMENTS = re.compile(r'(?<!:)//[^\n]*|/\*.*?\*/', re.S)


def strip_comments(text: str) -> str:
    spans = opaque_spans(text)
    out, last = [], 0
    for m in TS_COMMENTS.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        out.append(text[last:m.start()])
        out.append('\n' * m.group(0).count('\n'))
        last = m.end()
    out.append(text[last:])
    return ''.join(out)


def opaque_spans(text: str) -> list:
    spans = []
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


def ts_top_level_parts(inner: str, spans):
    parts, depth, last = [], 0, 0
    for i, c in enumerate(inner):
        if any(a <= i < b for a, b in spans):
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


def object_entry_keys(expr: str, resolver=None):
    """对象字面量的顶层键，按溯源分两桶。返回 (lit, typ, spread_unknown)。

    lit：直接写在对象里的键（确定会出现在请求对象上）；
    typ：展开目标经 resolver（函数签名类型）解析出的键（可能发送的上界）。
    """
    lit, typ = set(), set()
    spread_unknown = False
    spans = opaque_spans(expr)
    for part in ts_top_level_parts(expr, spans):
        part = part.strip()
        if not part:
            continue
        if part.startswith('...'):
            target = part[3:].strip()
            resolved = None
            if resolver and re.match(r'^[A-Za-z_$][\w$]*$', target):
                resolved = resolver(target)
            if resolved is not None:
                typ |= resolved
            else:
                spread_unknown = True
            continue
        m = re.match(r'^(?:\'[^\']*\'|"[^"]*"|[A-Za-z_$][\w$]*)\s*:', part)
        if m:
            lit.add(m.group(0).rsplit(':', 1)[0].strip().strip('\'"'))
        elif re.match(r'^[A-Za-z_$][\w$]*$', part):
            lit.add(part)
    return lit, typ, spread_unknown


def const_table(text: str) -> dict:
    out = {}
    spans = opaque_spans(text)
    for m in re.finditer(r'\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*', text):
        start = m.end()
        if any(a <= start < b for a, b in spans):
            continue
        if start < len(text) and text[start] in PAIR:
            end = balanced_end(text, start, spans)
            if end != -1:
                out.setdefault(m.group(1), text[start:end + 1])
    return out


# ---------------- 函数作用域归属与类型解析（收披露桶） ----------------
# 「同名参数命中多个签名」的归属歧义，解法是把文件按顶层 const 箭头函数的
# 声明切成顺序区间：每个调用点只归属**包含它的那一个**函数体，
# 再在该函数的参数表里找同名形参的类型。helper（如 null 过滤的 params(q)）
# 自己的 q: Record<...> 永远不会串到 api 函数的 q: ReportQuery 上。

ARROW_DECL = re.compile(r'(?:export\s+)?const\s+[A-Za-z_$][\w$]*[^=\n]*=\s*(?:async\s*)?\(')


def ts_arrow_decls(text: str, spans) -> list:
    """顺序区间 [{params, body_start, body_end}]：body_end 是下一个声明的起点。"""
    decls = []
    for m in ARROW_DECL.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        p_start = m.end() - 1
        p_end = balanced_end(text, p_start, spans)
        if p_end == -1:
            continue
        params_text = text[p_start + 1:p_end]
        am = re.search(r'=>', text[p_end:p_end + 200])
        if not am:
            continue
        decls.append({'params': params_text,
                      'body_start': p_end + am.end(),
                      'decl_start': m.start()})
    for i, d in enumerate(decls):
        d['body_end'] = decls[i + 1]['decl_start'] if i + 1 < len(decls) else len(text)
    return decls


def ts_type_decls(text: str) -> dict:
    """顶层 type/interface 定义 -> RHS 文本（供递归解析键）。"""
    out = {}
    spans = opaque_spans(text)
    for m in re.finditer(r'(?:export\s+)?type\s+([A-Za-z_$][\w$]*)(?:<[^=]*>)?\s*=\s*', text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        rhs_start = m.end()
        out.setdefault(m.group(1), _ts_stmt_rhs(text, rhs_start, spans))
    for m in re.finditer(r'(?:export\s+)?interface\s+([A-Za-z_$][\w$]*)[^{]*\{', text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        end = balanced_end(text, m.end() - 1, spans)
        if end != -1:
            out.setdefault(m.group(1), text[m.end() - 1:end + 1])
    return out


def _ts_stmt_rhs(text: str, start: int, spans) -> str:
    """类型 RHS：块闭合即止；否则到下一个「深度 0 的行首新声明」或文件尾。

    不能只用正则找行首声明：类型字面量成员可以叫 `type`/`class`（finance-ext
    的 ListQuery 第二个成员就是 `type?: string`），深度 0 才是语句边界。
    """
    depth = 0
    i = start
    n = len(text)
    while i < n:
        if any(a <= i < b for a, b in spans):
            i += 1
            continue
        c = text[i]
        if c in '{[(':
            depth += 1
        elif c in '}])':
            depth -= 1
            if depth == 0:
                j = i + 1
                if j < n and text[j] == ';':
                    j += 1           # 块后的分号一并带上
                return text[start:j].strip()
        elif depth == 0 and c == '\n':
            m2 = re.match(r'\n\s*(?:(?:export|import|const|type|interface|class)\b|/\*|//)', text[i:])
            if m2:
                return text[start:i].strip()
        i += 1
    return text[start:].strip()


def ts_import_types(text: str) -> dict:
    """import type { A, B } from './x' -> {A: './x', B: './x'}。"""
    out = {}
    for m in re.finditer(r'import\s+type\s*\{([^}]*)\}\s*from\s*[\'"]([^\'"]+)[\'"]', text):
        for name in m.group(1).split(','):
            name = name.strip()
            if name:
                out.setdefault(name, m.group(2))
    return out


def _split_top_ops(t: str, ops: str, spans):
    """按顶层运算符切分（< > 也算深度：泛型里的 , & | 不算顶层）。"""
    openers, closers = {'{', '[', '(', '<'}, {'}', ']', ')', '>'}
    parts, last, depth = [], 0, 0
    i = 0
    while i < len(t):
        if any(a <= i < b for a, b in spans):
            i += 1
            continue
        c = t[i]
        if c in openers:
            depth += 1
        elif c in closers:
            depth -= 1
        elif depth == 0 and c in ops:
            parts.append(t[last:i])
            last = i + 1
        i += 1
    parts.append(t[last:])
    return [p.strip() for p in parts if p.strip()]


def ts_type_keys(t: str, types: dict, imports: dict, depth: int = 0):
    """类型表达式 -> 键集合。解析不了返回 None（披露口径），绝不猜。"""
    if depth > 6:
        return None
    t = t.strip().rstrip(';').strip()
    if not t:
        return None
    spans = [(m.start(), m.end()) for m in
             re.finditer(r'\'(?:\\.|[^\'\\\n])*|"(?:\\.|[^"\\\n])*"', t)]
    amp = _split_top_ops(t, '&', spans)
    if len(amp) > 1:
        out = set()
        for p in amp:
            k = ts_type_keys(p, types, imports, depth + 1)
            if k is None:
                return None
            out |= k
        return out
    union = _split_top_ops(t, '|', spans)
    if len(union) > 1:
        return None                      # 联合类型：不知道实际走哪个分支
    gm = re.match(r'^([A-Za-z_$][\w$]*)\s*<(.*)>$', t)
    if gm:
        head = gm.group(1)
        if head in ('Partial', 'Readonly'):
            return ts_type_keys(gm.group(2), types, imports, depth + 1)
        return None                      # Record/Pick/Omit 等动态形态
    if t.startswith('{'):
        spans2 = opaque_spans(t)
        end = balanced_end(t, 0, spans2)
        if end == len(t) - 1:
            return ts_type_literal_keys(t[1:end], types, imports, depth + 1)
        return None
    if re.match(r'^[A-Za-z_$][\w$.]*$', t):
        name = t.split('.')[-1]
        if name in types:
            return ts_type_keys(types[name], types, imports, depth + 1)
        if name in imports:
            it, ii = _load_file_types(imports[name])
            if name in it:
                return ts_type_keys(it[name], it, ii, depth + 1)
    return None


def ts_type_literal_keys(inner: str, types: dict, imports: dict, depth: int = 0):
    """类型字面量成员的键：name?: type / name: type / 方法签名，; , 换行分隔。"""
    keys = set()
    spans = opaque_spans(inner)
    depth_b, last = 0, 0
    parts = []
    for i, c in enumerate(inner):
        if any(a <= i < b for a, b in spans):
            continue
        if c in PAIR or c in '<>':
            if c in ('{', '[', '(', '<'):
                depth_b += 1
            else:
                depth_b -= 1
        elif depth_b == 0 and c in ';\n,':
            parts.append(inner[last:i])
            last = i + 1
    parts.append(inner[last:])
    for part in parts:
        part = part.strip()
        if not part:
            continue
        if part.startswith('['):
            return None                   # 索引签名：动态键
        m = re.match(r'^(?:readonly\s+)?([A-Za-z_$][\w$]*)\s*(?:\??\s*:|\()', part)
        if m:
            keys.add(m.group(1))
    return keys if keys else None


def _load_file_types(relpath: str):
    if relpath in _IMPORT_CACHE:
        return _IMPORT_CACHE[relpath]
    fpath = (API_DIR / relpath).resolve()
    fpath = fpath if fpath.suffix == '.ts' else fpath.with_name(fpath.name + '.ts')
    if not fpath.exists():
        _IMPORT_CACHE[relpath] = ({}, {})
        return _IMPORT_CACHE[relpath]
    text = strip_comments(io.open(fpath, encoding='utf-8', errors='replace').read())
    _IMPORT_CACHE[relpath] = (ts_type_decls(text), ts_import_types(text))
    return _IMPORT_CACHE[relpath]


_IMPORT_CACHE = {}


def make_resolver(decl, types: dict, imports: dict):
    """一个函数声明 -> 其形参名的键解析器（归属唯一，无多签名歧义）。"""
    def resolve(ident: str):
        t = ts_param_type(decl['params'], ident)
        if t is None:
            return None
        return ts_type_keys(t, types, imports)
    return resolve


def ts_param_type(params_text: str, ident: str):
    """参数表里 ident 的类型注解文本；没有类型注解返回 None。"""
    spans = opaque_spans(params_text)
    for part in ts_top_level_parts(params_text, spans):
        part = part.strip()
        m = re.match(r'^(?:\.\.\.)?([A-Za-z_$][\w$]*)\s*\???:\s*(.+)$', part, re.S)
        if m and m.group(1) == ident:
            t = m.group(2).strip()
            t = re.sub(r'\s*=\s*\{.*\}\s*$', '', t)      # q: ReportQuery = {}
            t = re.sub(r'\s*=\s*[^=]*$', '', t).strip()  # days: number = 14
            return t
    return None


def build_matcher(full_path: str) -> str:
    out = []
    for seg in full_path.split('/'):
        if not seg:
            continue
        if seg.startswith('{') and seg.endswith('}'):
            out.append(r"(?:\$\{[^}]*\}|[^/`'\"]+)")
        else:
            out.append(re.escape(seg))
    return '/'.join(out) + r'(?![\w-])'


def to_frontend_alias(full_path: str) -> str:
    if not GATEWAY_YML.exists():
        return ''
    for backend_prefix, frontend_prefix in REWRITE_LINE_MAP.items():
        if full_path == backend_prefix or full_path.startswith(backend_prefix + '/'):
            return frontend_prefix[len('/api'):] + full_path[len(backend_prefix):]
    return ''


if GATEWAY_YML.exists():
    REWRITE_LINE_MAP = {t: '/api/' + s
                        for s, t in REWRITE_LINE.findall(
                            io.open(GATEWAY_YML, encoding='utf-8', errors='replace').read())}
else:
    REWRITE_LINE_MAP = {}

FRONTEND_CALL = re.compile(
    r"request\s*\.\s*(get|post|put|delete|patch)\s*(?:<[^()]*>)?\s*\(", re.S)


def split_call_args(args_text: str, spans):
    depth = 0
    parts, last = [], 0
    for i, c in enumerate(args_text):
        if any(a <= i < b for a, b in spans):
            continue
        if c in PAIR:
            depth += 1
        elif c in PAIR.values():
            depth -= 1
        elif c == ',' and depth == 0:
            parts.append(args_text[last:i])
            last = i + 1
    parts.append(args_text[last:])
    return [p.strip() for p in parts if p.strip()]


def params_info_of(args_text: str, resolver=None, consts=None):
    """调用实参里的 query 键集合及其**溯源**。

    返回 (keys, note, literal)。literal ⊆ keys：来自调用点内联字面量/路径内联
    ?key= 的键是「确定写在请求对象上的」；来自函数签名类型注解的键是「可能
    发送的上界」（共享超类型会把多个端点的筛选键混在一起，运行时未必发送）。
    后端缺 literal 键 = 真静默忽略（红）；缺 type 键 = 契约层虚报（单独披露）。
    keys 为 None = 解析不了，note 说明原因（披露口径）。
    """
    literal = set()
    spans = opaque_spans(args_text)
    keys = set()
    first = split_call_args(args_text, spans)
    if not first:
        return None, '空调用', literal
    path_lit = first[0]
    path_keys = set(re.findall(r'[?&]([A-Za-z_]\w*)=', path_lit))
    keys |= path_keys
    literal |= path_keys
    params_arg = None
    for arg in first[1:]:
        a_spans = opaque_spans(arg)
        m = re.search(r'\bparams\s*:\s*', arg)
        if m and not any(a <= m.start() < b for a, b in a_spans):
            start = m.end()
            while start < len(arg) and arg[start] in ' \t\n':
                start += 1
            # 值以括号开头就按配对取整段；否则扫到值自身的顶层逗号。
            # 不能用全局深度：params: 前面还有外层对象的 `{`，深度起点不是 0。
            if start < len(arg) and arg[start] in PAIR:
                v_end = balanced_end(arg, start, a_spans)
                if v_end == -1:
                    return None, 'params 值括号不配平', literal
                params_arg = arg[start:v_end + 1].strip()
            else:
                i = start
                depth = 0
                while i < len(arg):
                    if not any(a <= i < b for a, b in a_spans):
                        c = arg[i]
                        # 值自身的顶层终止符：相对深度 0 处的逗号或外层收括号
                        if depth == 0 and c in (',', '}'):
                            break
                        if c in PAIR:
                            depth += 1
                        elif c in PAIR.values():
                            depth -= 1
                    i += 1
                params_arg = arg[start:i].strip()
            break
        if re.match(r'^\{\s*params\s*\}$', arg.strip()):
            if resolver:
                k2 = resolver('params')
                if k2 is not None:
                    keys |= k2
                    return keys, '', literal
            return None, 'params 透传（变量且类型解析不了），v1 不猜', literal
    if params_arg is None:
        return (keys, '无 params 实参' if not keys else '', literal)
    expr = params_arg
    m = re.match(r'^([A-Za-z_$][\w$]*)\s*\(', expr)
    if m:
        inner_start = expr.find('(')
        e_spans = opaque_spans(expr)
        inner_end = balanced_end(expr, inner_start, e_spans)
        if inner_end == -1:
            return None, 'helper 调用括号不配平', literal
        inner = expr[inner_start + 1:inner_end].strip()
        if inner.startswith('{') and balanced_end(inner, 0, e_spans) == len(inner) - 1:
            expr = inner
        elif resolver and re.match(r'^[A-Za-z_$][\w$]*$', inner):
            k2 = resolver(inner)
            if k2 is not None:
                keys |= k2
                return keys, '', literal
            return (keys or None), 'params 经 helper 转发变量实参且类型解析不了' + (
                '；仅比对路径内联键' if keys else ''), literal
        else:
            return (keys or None), 'params 经 helper 转发变量实参，v1 不解析' + (
                '；仅比对路径内联键' if keys else ''), literal
    # expr 可能已被剥掉 helper 壳，必须重算本表达式的 spans（外层的下标不适用）
    spans = opaque_spans(expr)
    if expr.startswith('{') and balanced_end(expr, 0, spans) == len(expr) - 1:
        k_lit, k_typ, su = object_entry_keys(expr[1:-1], resolver)
        keys |= k_lit | k_typ
        literal |= k_lit
        if su:
            return (keys or None), '对象里有未解析的展开（...x），v1 不猜键名' + (
                '；仅比对路径内联键' if keys else ''), literal
        return keys, '', literal
    if re.match(r'^[A-Za-z_$][\w$]*$', expr):
        if resolver:
            k2 = resolver(expr)
            if k2 is not None:
                keys |= k2
                return keys, '', literal
        if consts and expr in consts:
            v = consts[expr]
            if v.startswith('{') and balanced_end(v, 0, opaque_spans(v)) == len(v) - 1:
                k_lit, k_typ, su = object_entry_keys(v[1:-1], resolver)
                if not su:
                    keys |= k_lit | k_typ
                    literal |= k_lit
                    return keys, '', literal
    return None, 'params 是变量/表达式（%s…），v1 不解析' % expr[:24], literal


# ---------------- 自测 ----------------

if '--self-test' in sys.argv:
    cases = []

    def check(name, got, want):
        cases.append((name, got == want, got, want))

    check('value= 显式名优先', request_param_name('@RequestParam(value = "size", required = false) Integer size'),
          ('size', False, False))
    check('裸注解取 Java 参数名', request_param_name('@RequestParam String status'), ('status', True, False))
    check('defaultValue 不是名字、且意味着可选',
          request_param_name('@RequestParam(defaultValue = "14") int days'), ('days', False, False))
    check('required=false 可选', request_param_name('@RequestParam(required = false) String q'),
          ('q', False, False))
    check('短引号显式名', request_param_name('@RequestParam("shopId") Long shopId'), ('shopId', True, False))
    check('Map 接参是动态', request_param_name('@RequestParam Map<String,String> all')[2], True)
    check('泛型数组类型不影响取名', request_param_name('@RequestParam(required = false) String[] scopes'),
          ('scopes', False, False))
    sig = ('@GetMapping("/x")\n    public Result<Void> f(@PathVariable Long id,\n'
           '            @RequestParam(defaultValue = "1") int size) {\n')
    ps = signature_params(sig, 0)
    check('多行签名按顶层逗号切分', len(ps) == 2 and request_param_name(ps[1])[0] == 'size', True)

    call = "request.get<void, ApiResponse<X>>(`/report/v2/profit/list/${shopId}`, { params: params({ asin }) })"
    m = FRONTEND_CALL.search(call)
    args = call[m.end():call.rfind(')')]
    check('helper 包着的对象字面量取键', sorted(params_info_of(args, {})[0]), ['asin'])
    call2 = "request.get('/x', { params: { shopId, days, adType } })"
    m2 = FRONTEND_CALL.search(call2)
    check('shorthand 键提取', sorted(params_info_of(call2[m2.end():call2.rfind(')')], {})[0]),
          ['adType', 'days', 'shopId'])
    call3 = "request.get('/x', { params })"
    m3 = FRONTEND_CALL.search(call3)
    k3, note3, lit3 = params_info_of(call3[m3.end():call3.rfind(')')], {})
    check('params 透传披露不猜', k3 is None and '透传' in note3, True)
    call4 = "request.get(`/ai/eval/run?mode=${mode}`, {})"
    m4 = FRONTEND_CALL.search(call4)
    check('路径内联 query 的键也提取',
          sorted(params_info_of(call4[m4.end():call4.rfind(')')], {})[0]), ['mode'])
    check('无注解标量形参按参数名绑定（可选）',
          param_query_names('String phone', None), ({'phone': False}, False, True))
    check('无注解 POJO 形参按 DTO 字段绑定',
          param_query_names('LoginDto loginDto',
                            type('I', (), {'fields': lambda s, n: {'phone', 'code'}})()),
          ({'phone': False, 'code': False}, False, True))
    check('@RequestBody/@PathVariable 与 query 无关',
          [param_query_names('@RequestBody Foo f', None)[2],
           param_query_names('@PathVariable Long id', None)[2]], [False, False])
    check('defaultValue 段不会伪装成显式名（@RequestParam(defaultValue = "14")）',
          request_param_name('@RequestParam(defaultValue = "14") int days')[0], 'days')
    check('类型字面量键（?: 与换行分隔）',
          sorted(ts_type_literal_keys(' asin?: string\n  sku?: string\n', {}, {}, 0)),
          ['asin', 'sku'])
    ttypes = {'ReportQuery': '{ asin?: string; sku?: string }'}
    check('交叉类型合并键',
          sorted(ts_type_keys('ReportQuery & { startTime?: string }', ttypes, {}, 0)),
          ['asin', 'sku', 'startTime'])
    check('联合类型不可知', ts_type_keys('A | B', ttypes, {}, 0) is None, True)
    check('Record 不可知', ts_type_keys('Record<string, unknown>', {}, {}, 0) is None, True)
    scope = ('const params = (q: Record<string, unknown>) => {\n'
             '  return q\n'
             '}\n\n'
             'export type ReportQuery = { asin?: string; sku?: string }\n\n'
             'export const listX = (shopId: number, q: ReportQuery = {}) =>\n'
             '  request.get(`/x/${shopId}`, { params: params(q) })\n')
    tspans = opaque_spans(scope)
    sdecls = ts_arrow_decls(scope, tspans)
    check('箭头函数声明把文件切成顺序区间', len(sdecls), 2)
    stypes = ts_type_decls(scope)
    res2 = make_resolver(sdecls[1], stypes, {})
    check('归属唯一：api 函数的 q 解析出 ReportQuery 键',
          sorted(res2('q') or []), ['asin', 'sku'])
    check('params= 限定符解析（present 与 !forbid）',
          parse_params_qualifier('@PostMapping(value = "/x", params = {"shopId", "!days"})'),
          ({'shopId'}, {'days'}))
    lit_call = 'request.get(`/x/${id}`, { params: params({ asin }) })'
    lit_args = lit_call[lit_call.index('(') + 1:lit_call.rfind(')')]
    check('helper 包着的字面量键溯源为 literal',
          sorted(params_info_of(lit_args, None, {})[2]), ['asin'])
    check('matcher 不被子路径前缀骗（/ai/chat vs /ai/chat-stream）',
          [bool(re.search(build_matcher('/ai/chat'), '/ai/chat-stream')),
           bool(re.search(build_matcher('/ai/chat'), '/ai/chat'))], [False, True])

    failed = [c for c in cases if not c[1]]
    for name, ok_flag, got, want in cases:
        print('%-52s %s (got=%s want=%s)' % (
            name.encode('ascii', 'replace').decode(), 'PASS' if ok_flag else 'FAIL', got, want))
    print('SELFTEST %d/%d passed' % (len(cases) - len(failed), len(cases)))
    sys.exit(1 if failed else 0)


def require_repo_root() -> None:
    api_files = list(API_DIR.glob('*.ts'))
    controllers = list(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java'))
    if not api_files or not controllers:
        print('ROOT NOT A REPO: %s（api=%d 控制器=%d，两端分母都不能为空）'
              % (root, len(api_files), len(controllers)))
        sys.exit(2)


require_repo_root()

# ---------------- 主流程 ----------------

ep_idx = backend_endpoint_params(FieldIndex(root))

rows = []
for p in sorted(API_DIR.glob('*.ts')):
    text = strip_comments(io.open(p, encoding='utf-8', errors='replace').read())
    consts = const_table(text)
    spans = opaque_spans(text)
    types = ts_type_decls(text)
    imports = ts_import_types(text)
    decls = ts_arrow_decls(text, spans)
    for m in FRONTEND_CALL.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
        # 调用点归属：包含它的那一个函数体（顺序区间，唯一）——
        # helper 的同名形参永远不会被误当成 api 函数的形参
        enc = None
        for d in decls:
            if d['body_start'] <= m.start() < d['body_end']:
                enc = d          # 区间互不重叠，命中的就是唯一归属
                break
        resolver = make_resolver(enc, types, imports) if enc else None
        call_open = m.end() - 1
        close = balanced_end(text, call_open, spans)
        if close == -1:
            rows.append({'file': p.name, 'verb': m.group(1).upper(), 'path': '??',
                         'keys': None, 'note': '调用括号不配平'})
            continue
        args_text = text[call_open + 1:close]
        arg_spans = opaque_spans(args_text)
        first_arg = split_call_args(args_text, arg_spans)
        path = first_arg[0].strip().strip('`\'"') if first_arg else '??'
        keys, note, rliteral = params_info_of(args_text, resolver, consts)
        rows.append({'file': p.name, 'verb': m.group(1).upper(), 'path': path,
                     'keys': keys, 'note': note, 'literal': rliteral})

findings, unverifiable, not_comparable, ambiguous, missing_required = [], [], [], [], []
type_extra = []
passed = compared = 0
no_params = 0
for r in rows:
    if r['keys'] is None:
        not_comparable.append(r)
        continue
    if not r['keys']:
        no_params += 1
        continue
    path_part = r['path'].split('?')[0]
    front_shape = shape_of(path_part)
    all_variants = []
    for (b_shape, verb), entries in ep_idx.items():
        if verb != r['verb']:
            continue
        # 前端 api 路径是完整字面量（${} 占位），必须形状全等：
        # build_matcher 前缀匹配会让短形状（/bidSchedule/{id}）吃掉动作后缀
        # （/bidSchedule/{id}/toggle），打分也并列，唯一的正确口径就是全等。
        alias = to_frontend_alias(b_shape)
        if front_shape == b_shape or (alias and shape_of(alias) == front_shape):
            all_variants.extend((b_shape, e) for e in entries)
    # params= 分发限定符消歧：present ⊆ 调用键 且 forbid 与调用键不相交的变体
    # 才是本次请求实际命中的那条；全等形状 × 分发条件过滤后应只剩一条。
    cands = [(b, e) for b, e in all_variants
             if e['present'] <= r['keys'] and not (e['forbid'] & r['keys'])]
    if not cands:
        if all_variants:
            not_comparable.append(dict(
                r, note='Spring params= 分发条件都不满足（该请求在后端会 404），需人工核对'))
        else:
            not_comparable.append(dict(r, note='后端无同方法同形状匹配（路径级缺口归 --reverse 管）'))
        continue
    verdicts = []
    local_unverifiable = []
    for _b_shape, e in cands:
        if e['sig']:
            local_unverifiable.append((_b_shape, '签名解析失败（%s）' % e['file']))
            continue
        if e['dynamic']:
            local_unverifiable.append((_b_shape, '后端 @RequestParam Map 动态接参'))
            continue
        extra = r['keys'] - set(e['names'])
        miss = {n for n, req in e['names'].items() if req} - r['keys']
        # 溯源分桶：literal 键确定写在请求对象上，后端忽略=真静默失效（红）；
        # type 键只是共享类型的上界，运行时未必发送（披露，不当红）。
        extra_lit = extra & r.get('literal', set())
        extra_typ = extra - r.get('literal', set())
        if extra_lit:
            verdicts.append((_b_shape, 'red', sorted(extra_lit)))
        else:
            if extra_typ:
                type_extra.append((r['path'], _b_shape, sorted(extra_typ)))
            if miss:
                missing_required.append((r['path'], _b_shape, sorted(miss)))
            verdicts.append((_b_shape, 'pass', None))
    compared += 1
    if not verdicts:
        unverifiable.extend((r['path'], b, why) for b, why in local_unverifiable)
        continue
    states = {v for _b, v, _f in verdicts}
    if states == {'pass'}:
        passed += 1
    elif states == {'red'}:
        emitted = set()
        for _b, _v, f in verdicts:
            key = ','.join(f)
            if key not in emitted:
                emitted.add(key)
                findings.append((r['verb'], r['path'], f))
    else:
        ambiguous.append((r['path'], sorted({'%s:%s' % (b, v) for b, v, _f in verdicts})))
    unverifiable.extend((r['path'], b, why) for b, why in local_unverifiable)

print('param-name audit: backend-shapes=%d call-sites=%d with-keys=%d compared=%d'
      % (len(ep_idx), len(rows), sum(1 for r in rows if r['keys']), compared))
print('passed=%d RED=%d type-extra=%d missing-required=%d ambiguous=%d unverifiable=%d not-comparable=%d'
      % (passed, len(findings), len(type_extra), len(missing_required), len(ambiguous),
         len(unverifiable), len(not_comparable)))
if findings:
    print('GATE RED frontend-param-not-in-backend-requestparam: %d' % len(findings))
    for verb, path, extra in findings:
        print('   %s %s  前端多出: %s' % (verb, path, extra))
if ambiguous:
    print('disclosed ambiguous=%d（同一匹配多动词返回冲突，需人工归因）' % len(ambiguous))
    for s, states in ambiguous:
        print('   %-52s %s' % (s, states))
if type_extra:
    print('disclosed type-extra=%d（共享类型注解声明的键后端没有，运行时未必发送，契约虚报需人工核对）' % len(type_extra))
    for path, b, extra in sorted((a, b, tuple(c)) for a, b, c in type_extra):
        print('   %-48s -> %-44s 类型声明多出: %s' % (path, b, list(extra)))
if missing_required:
    print('disclosed missing-required=%d（后端必填而前端键缺失，潜在 400，先披露后收紧）' % len(missing_required))
    for path, b, miss in missing_required:
        print('   %-48s -> %-44s 缺: %s' % (path, b, miss))
if unverifiable:
    print('disclosed unverifiable=%d（后端动态接参/签名解析失败，非阻断）' % len(unverifiable))
    for s, b, why in sorted(set(unverifiable)):
        print('   %-48s -> %-44s %s' % (s, b, why))
if not_comparable:
    print('disclosed not-comparable=%d（前端 params 解析不了，非阻断）' % len(not_comparable))
    for r in not_comparable:
        print('   %-46s %-34s %s' % (r['path'][:46], r['file'], r['note']))
print('call-sites-without-params=%d（无 params 实参，本轮不核其必填位）' % no_params)
if not findings:
    print('param-name gate: 0 findings')
sys.exit(1 if findings else 0)
