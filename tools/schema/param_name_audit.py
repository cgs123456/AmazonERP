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


def backend_endpoint_params(index):
    """形状 -> [(verb, names{name:required}, dynamic, 控制器名)]。"""
    idx = {}
    for f in sorted(root.glob('amz-service/*/src/main/java/com/amz/controller/*.java')):
        text = java_strip_comments(io.open(f, encoding='utf-8', errors='replace').read())
        cls = CLASS_MAP.search(text)
        prefix = cls.group(1) if cls else ''
        for verb, path, anchor, _raw in parse_mappings(text):
            if path is None:
                continue
            full = (prefix + path) if path.startswith('/') or path == '' else prefix + '/' + path
            full = full.rstrip('/') or '/'
            names, dynamic, unparsed_sig = {}, False, False
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
            idx.setdefault((shape_of(full), verb.upper()), []).append(
                {'names': names, 'dynamic': dynamic, 'sig': unparsed_sig, 'file': f.name})
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


def object_entry_keys(expr: str, consts=None):
    """对象字面量的顶层键（shorthand/引号键都认）。返回 (keys, spread_unknown)。"""
    keys, spread_unknown = set(), False
    spans = opaque_spans(expr)
    for part in ts_top_level_parts(expr, spans):
        part = part.strip()
        if not part:
            continue
        if part.startswith('...'):
            spread_unknown = True
            continue
        m = re.match(r'^(?:\'[^\']*\'|"[^"]*"|[A-Za-z_$][\w$]*)\s*:', part)
        if m:
            keys.add(m.group(0).rsplit(':', 1)[0].strip().strip('\'"'))
        elif re.match(r'^[A-Za-z_$][\w$]*$', part):
            keys.add(part)
    return keys, spread_unknown


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


def params_info_of(args_text: str, consts):
    """调用实参里的 query 键集合。

    返回 (keys|None, note)。None = 解析不了，note 说明原因（披露口径）。
    键来源：params: 对象字面量 / null 过滤 helper 包着的对象字面量 /
    路径内联 ?key=；const 展开解析一层。
    """
    spans = opaque_spans(args_text)
    # 路径参数（第一个实参）里内联的 ?key=${v}
    keys, spread_unknown = set(), False
    first = split_call_args(args_text, spans)
    if not first:
        return None, '空调用'
    path_lit = first[0]
    keys |= set(re.findall(r'[?&]([A-Za-z_]\w*)=', path_lit))
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
                    return None, 'params 值括号不配平'
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
        if re.search(r'^\{\s*params\s*\}$', arg.strip()):
            return None, 'params 透传（变量），v1 不解析'
    if params_arg is None:
        return (keys, '无 params 实参' if not keys else '')
    expr = params_arg
    m = re.match(r'^([A-Za-z_$][\w$]*)\s*\(', expr)
    if m:
        inner_start = expr.find('(')
        e_spans = opaque_spans(expr)
        inner_end = balanced_end(expr, inner_start, e_spans)
        if inner_end == -1:
            return None, 'helper 调用括号不配平'
        inner = expr[inner_start + 1:inner_end].strip()
        if inner.startswith('{') and balanced_end(inner, 0, e_spans) == len(inner) - 1:
            expr = inner
        else:
            # helper 转发变量实参：params 键不可解析，但路径内联键仍然确定
            return (keys or None), 'params 经 helper 转发变量实参，v1 不解析' + (
                '；仅比对路径内联键' if keys else '')
    # expr 可能已被剥掉 helper 壳，必须重算本表达式的 spans（外层的下标不适用）
    spans = opaque_spans(expr)
    if expr.startswith('{') and balanced_end(expr, 0, spans) == len(expr) - 1:
        k2, su = object_entry_keys(expr[1:-1])
        keys |= k2
        spread_unknown = spread_unknown or su
        if spread_unknown:
            return (keys or None), '对象里有未解析的展开（...x），v1 不猜键名' + (
                '；仅比对路径内联键' if keys else '')
        return keys, ''
    if re.match(r'^[A-Za-z_$][\w$]*$', expr) and consts and expr in consts:
        v = consts[expr]
        if v.startswith('{') and balanced_end(v, 0, opaque_spans(v)) == len(v) - 1:
            k2, su = object_entry_keys(v[1:-1])
            if not su:
                keys |= k2
                return keys, ''
    return None, 'params 是变量/表达式（%s…），v1 不解析' % expr[:24]


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
    k3, note3 = params_info_of(call3[m3.end():call3.rfind(')')], {})
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
    for m in FRONTEND_CALL.finditer(text):
        if any(a <= m.start() < b for a, b in spans):
            continue
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
        keys, note = params_info_of(args_text, consts)
        rows.append({'file': p.name, 'verb': m.group(1).upper(), 'path': path,
                     'keys': keys, 'note': note})

findings, unverifiable, not_comparable, ambiguous, missing_required = [], [], [], [], []
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
    cands = []
    for (b_shape, verb), entries in ep_idx.items():
        if verb != r['verb']:
            continue
        # 前端 api 路径是完整字面量（${} 占位），必须形状全等：
        # build_matcher 前缀匹配会让短形状（/bidSchedule/{id}）吃掉动作后缀
        # （/bidSchedule/{id}/toggle），打分也并列，唯一的正确口径就是全等。
        alias = to_frontend_alias(b_shape)
        if front_shape == b_shape or (alias and shape_of(alias) == front_shape):
            cands.append((b_shape, entries))
    if not cands:
        not_comparable.append(dict(r, note='后端无同方法同形状匹配（路径级缺口归 --reverse 管）'))
        continue
    verdicts = []
    local_unverifiable = []
    for _b_shape, entries in cands:
        for e in entries:
            if e['sig']:
                local_unverifiable.append((_b_shape, '签名解析失败（%s）' % e['file']))
                continue
            if e['dynamic']:
                local_unverifiable.append((_b_shape, '后端 @RequestParam Map 动态接参'))
                continue
            extra = r['keys'] - set(e['names'])
            miss = {n for n, req in e['names'].items() if req} - r['keys']
            if extra:
                verdicts.append((_b_shape, 'red', sorted(extra)))
            else:
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
print('passed=%d RED=%d missing-required-disclosed=%d ambiguous=%d unverifiable=%d not-comparable=%d'
      % (passed, len(findings), len(missing_required), len(ambiguous),
         len(unverifiable), len(not_comparable)))
if findings:
    print('GATE RED frontend-param-not-in-backend-requestparam: %d' % len(findings))
    for verb, path, extra in findings:
        print('   %s %s  前端多出: %s' % (verb, path, extra))
if ambiguous:
    print('disclosed ambiguous=%d（同一匹配多动词返回冲突，需人工归因）' % len(ambiguous))
    for s, states in ambiguous:
        print('   %-52s %s' % (s, states))
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
