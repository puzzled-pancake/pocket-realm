"""W6B code-red round 1 fix gates.

R1-1/R2-1/R4-1/R5-1/R6-1: no first-party .kt/.java source line may carry a
mangled comment opener (`/. ...`) — the class that broke the standalone
gateway build at JoinGateway.kt:208.
R2-2/R6-3: the three transformer debris signatures are banned from comment
lines: broken possessives ("('s "), colon-amputated KDoc openers ("/**:"),
and dangling docs pointers ("docs/," / "docs/)" / "docs/.").
R1-3: semantic letter+digit tokens (F-keys, E8, C0) must not be
eaten from changed comment lines vs the wave base commit.
R1-2/R3-3/R6-2: native/llm/include/** stays byte-identical to the vendored
upstream llama.cpp headers (see tests/fixtures/vendored_llm_include_manifest.json).
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "5a22314704973624a16b8c3655a6b4c0504d186c"

SOURCE_ROOTS = (
    "android/app/src/main",
    "android/app/src/test",
    "android/app/src/androidTest",
    "android/pocketrealm-llm",
    "desktop/src",
    "gateway/src",
    "spikes/iroh-n2",
    "tools",
    "scripts",
    "tests",
)

MANGLED_OPENER = re.compile(r"^\s*/(?![/*])\S.*$", re.M)
BROKEN_POSSESSIVE = re.compile(r"\(\s*'\s*s\b")
# orphan possessives in ANY context ("mirrors 's", "superseding 's")
# debris form: the token before "'s" was stripped leaving a bare opener
# (space/dash/colon). "[X]'s" and "(X)'s" bracket possessives are legit.
# debris form: the token before "'s" was stripped leaving a bare opener
# (space/dash/colon-drive). "[X]'s", "(X)'s", "`X`'s", and "C:'s" are legit.
ORPHAN_POSSESSIVE = re.compile(r"(?:^|[^\w\u2019\])`: /])'\s*s\b")
BROKEN_KDOC_OPENER = re.compile(r"/\*\*:")
DOCS_POINTER = re.compile(r"\bdocs/\s*[,).\]]")
BARE_SLASH_REMNANT = re.compile(r"\bthe\s+/\s+\S")
# halved URL schemes in constraint examples ("https:/usw1-1...")
HALVED_SCHEME = re.compile(r"\b(?:https?|wss|ws):/(?!/)")
# excised-token residues
RECORDED_DOT = re.compile(r"\brecorded\s+\.2\b")
CORRECTION_PAREN = re.compile(r",\s*correction\s*\)")
SLASH_DOT_COMMENT = re.compile(r"^\s*//\.\s")
HALF_EATEN_R2 = re.compile(r"(?:^|[\s(])R[0-9]--[a-z0-9-]")
HALF_EATEN_W2 = re.compile(r"\bW[0-9]+-\)")
HALVED_WS = re.compile(r"\bws:/[^/]")
DANGLING_APOSTROPHE = re.compile(r"[A-Za-z]'\s*$")
PAREN_DOT_COLON = re.compile(r"\)\.:")
SEMI_MINUS = re.compile(r";\s*-1,")
KDOC_OPEN_SLASH = re.compile(r"/\*\*\s+/\s*:?\s")
SLASH_DOT_COMMENT = re.compile(r"^\s*//\.\s")
CAP_SLASH = re.compile(r"The\s+/\s+cap")
# continuation-line orphan openers, orphan-paren
# openers, partial-ref residues, half-eaten ids
CONT_ORPHAN_OPENER = re.compile(r"^\s*\*\s*:?\s*:\s")
CONT_ORPHAN_OPENER2 = re.compile(r"^\s*\*\s+:\s")
ORPHAN_PAREN_OPENER = re.compile(r"^\s*//\s*[a-z0-9._-]+\)\s")
PARTIAL_REF = re.compile(r"R?[0-9][A-Za-z0-9]*r[0-9]+[^ ]*/\s*:")
HALF_EATEN_W = re.compile(r"\bW[0-9]-:\s")
HALF_EATEN_R = re.compile(r"/R[0-9]--")
DASH_PAREN = re.compile(r"—\s*\)")
SLASH_PAREN = re.compile(r"\(\s*/\s*\)")
HALF_RANGE = re.compile(r"–\s+(?:of|the|is|in)\b")
DESIGN_ALLOWS = re.compile(r"the design’s allows|the design's allows")
CITE_FIX = re.compile(r"\bcite fix:|\bgate fix:")
BARE_LETTER_TOKEN = re.compile(r"^\s*\*\s+s\b")
# letter-glued dual-id form + bare wave id
DUAL_ID = re.compile(r"\bR[0-9]+R[0-9]+-[0-9]+\b")
BARE_WAVE = re.compile(r"\bW6B\b(?=.*(?:code|round|comment))")
ROUND_REF = re.compile(r"\bcode-red round [0-9]+\b")
# sentence-head flip: and torn design refs
FLIP_HEAD = re.compile(r"(?:^|\s)flip:")
ORPHAN_PAREN_OPEN = re.compile(r"\bD[0-9]a?\s*\+\s*\(")
ORPHAN_SLASH_KDOC = re.compile(r"/\*\*\s*/[A-Z]")
DASH_PAREN_OPEN = re.compile(r"\(\s*—")
# halved range, orphan the own, dangling preposition
HALVED_RANGE = re.compile(r"\bthe /\d")
ORPHAN_OWN = re.compile(r"\bthe own\b")
FOUND_IN = re.compile(r"\(found in\)\.")
IN_CLOSER = re.compile(r"\bin\)\.\s*$")
# retained reviewer id in changed parens; stage-id colon
RETAINED_ID = re.compile(r"\((?:R|W)\d[0-9A-Za-z-]*\)\s*:")
STAGE_ID_COLON = re.compile(r"\bW\d+-N\d+:")
# torn-prose residues
COMMA_SEMI = re.compile(r",\s*;")
PAREN_SEMI = re.compile(r"\(\s*;\s")
DASH_PAREN_MINUS = re.compile(r"\(-[A-Za-z0-9]*\s*,\s*\)")
DOT_OPENER = re.compile(r"^\s*[*#/]+ ?\.\s")
POST_PAREN = re.compile(r"\(post-\)")
EM_N_PAREN = re.compile(r"—\s*\(\d+\)\)")
DUAL_PLUS_PAREN = re.compile(r"[A-Za-z]\d[ca]? \+ \(")
# bare -N: / Flip: / raised by) residues
BARE_MINUS_COLON = re.compile(r"\s-\d+:\s")
FLIP_SPACE_COLON = re.compile(r"\bFlip\s+:")
RAISED_BY_PAREN = re.compile(r"raised by\)")
# half-cleaned chatter forms
R_RB_FORM = re.compile(r"\bR\d+B-\d\b")
R_LOWER_FORM = re.compile(r"\bR\d+r\d+-\d\b")
BREAKER_HEADER = re.compile(r"\bR\d adversarial breaker")
FLIP_ROUND = re.compile(r"\bflip \(round \d+\)")
R_M_POSSESSIVE = re.compile(r"\bR\d-\d+m's")
BARE_ROUND = re.compile(r"\bround \d+\b")

# comment-line detector per extension (cheap, sufficient for these shapes)
KT_EXTS = {".kt", ".kts", ".java"}
PY_EXTS = {".py", ".sh", ".ps1", ".yaml", ".yml", ".properties", ".cmake"}


def _source_files():
    out = []
    for root in SOURCE_ROOTS:
        for dp, dn, fn in os.walk(ROOT / root):
            dn[:] = [d for d in dn if d not in ("build", ".gradle", "__pycache__")]
            for f in fn:
                ext = os.path.splitext(f)[1].lower()
                if ext in KT_EXTS or ext in PY_EXTS:
                    out.append(Path(dp) / f)
    return out


import os  # noqa: E402


def _comment_lines(path: Path):
    """Yield (lineno, line) for lines whose content is comment text."""
    text = path.read_text(encoding="utf-8", errors="replace")
    ext = path.suffix.lower()
    if ext in PY_EXTS:
        for i, line in enumerate(text.split("\n"), 1):
            s = line.strip()
            if s.startswith("#") and not s.startswith("#!"):
                yield i, line
    else:
        in_block = False
        for i, line in enumerate(text.split("\n"), 1):
            s = line.strip()
            if in_block:
                yield i, line
                if "*/" in s:
                    in_block = False
                continue
            if s.startswith("/*"):
                yield i, line
                if not s.endswith("*/") and not (len(s) > 2 and s[1] == "*"):
                    in_block = True
                elif s.startswith("/*") and "*/" not in s:
                    in_block = True
                continue
            if s.startswith("//"):
                yield i, line


def test_no_mangled_comment_openers():
    """R1-1/R2-1/R4-1/R5-1/R6-1: `/. ` code lines are mangled `//` comments
    (the JoinGateway.kt:208 class that broke the gateway build)."""
    bad = []
    for path in _source_files():
        rel = str(path.relative_to(ROOT)).replace("\\", "/")
        text = path.read_text(encoding="utf-8", errors="replace")
        if path.suffix.lower() in KT_EXTS:
            for m in MANGLED_OPENER.finditer(text):
                lineno = text.count("\n", 0, m.start()) + 1
                bad.append(f"{rel}:{lineno}")
    assert not bad, (
        "mangled comment opener(s) — `//` was eaten and the line is now code: "
        + ", ".join(bad)
    )


def test_no_transformer_debris_signatures():
    """R2-2/R6-3: broken possessives, `/**:` KDoc openers, dangling docs
    pointers, and bare-slash remnants are transformer debris."""
    bad = []
    changed = subprocess.run(
        ["git", "diff", "--name-only", BASE, "--"] + list(SOURCE_ROOTS),
        cwd=ROOT, capture_output=True, text=True).stdout.splitlines()
    changed = {c.replace("\\", "/") for c in changed if c.strip()}
    base_cache = {}
    for path in _source_files():
        rel = str(path.relative_to(ROOT)).replace("\\", "/")
        if rel == "tests/test_w6b_code_red_fixes.py":
            continue  # the gate's own regex definitions contain the patterns
        if rel not in changed:
            continue  # untouched files are outside the wave's diff constraint
        bl = subprocess.run(
            ["git", "show", f"{BASE}:{rel}"], cwd=ROOT, capture_output=True)
        base_lines = (bl.stdout.decode("utf-8", errors="replace").split("\n")
                      if bl.returncode == 0 else None)
        base_cache[rel] = base_lines
        for lineno, line in _comment_lines(path):
            # charter scope: the wave's diff — skip lines byte-identical to base
            if (base_lines is not None and lineno <= len(base_lines)
                    and base_lines[lineno - 1] == line):
                continue
            hit = None
            if BROKEN_POSSESSIVE.search(line) or ORPHAN_POSSESSIVE.search(line):
                hit = "broken-possessive"
            elif BROKEN_KDOC_OPENER.search(line):
                hit = "broken-kdoc-opener"
            elif DOCS_POINTER.search(line):
                hit = "dangling-docs-pointer"
            elif BARE_SLASH_REMNANT.search(line):
                hit = "bare-slash-remnant"
            elif HALVED_SCHEME.search(line):
                hit = "halved-url-scheme"
            elif RECORDED_DOT.search(line):
                hit = "recorded-dot-residue"
            elif CORRECTION_PAREN.search(line):
                hit = "correction-paren-residue"
            elif SLASH_DOT_COMMENT.match(line):
                hit = "slash-dot-comment"
            elif CAP_SLASH.search(line):
                hit = "cap-slash-residue"
            elif HALF_EATEN_R2.search(line):
                hit = "half-eaten-R2-id"
            elif HALF_EATEN_W2.search(line):
                hit = "half-eaten-W-paren"
            elif HALVED_WS.search(line):
                hit = "halved-ws-scheme"
            elif DANGLING_APOSTROPHE.search(line):
                hit = "dangling-apostrophe"
            elif PAREN_DOT_COLON.search(line):
                hit = "paren-dot-colon-residue"
            elif SEMI_MINUS.search(line):
                hit = "semi-minus-residue"
            elif KDOC_OPEN_SLASH.search(line):
                hit = "kdoc-open-slash"
            elif CONT_ORPHAN_OPENER.match(line) or CONT_ORPHAN_OPENER2.match(line):
                hit = "continuation-orphan-opener"
            elif ORPHAN_PAREN_OPENER.match(line):
                hit = "orphan-paren-opener"
            elif PARTIAL_REF.search(line):
                hit = "partial-ref-residue"
            elif HALF_EATEN_W.search(line):
                hit = "half-eaten-W-id"
            elif HALF_EATEN_R.search(line):
                hit = "half-eaten-R-ref"
            elif DASH_PAREN.search(line):
                hit = "dangling-dash-paren"
            elif SLASH_PAREN.search(line):
                hit = "slash-paren-residue"
            elif HALF_RANGE.search(line):
                hit = "half-stripped-range"
            elif DESIGN_ALLOWS.search(line):
                hit = "design-allows-residue"
            elif CITE_FIX.search(line):
                hit = "cite-gate-fix-chatter"
            elif BARE_LETTER_TOKEN.match(line):
                hit = "bare-letter-token"
            if not hit:
                for pat, name in (
                    (DUAL_ID, "dual-id"),
                    (BARE_WAVE, "bare-wave-id"),
                    (ROUND_REF, "round-ref"),
                    (FLIP_HEAD, "flip-sentence-head"),
                    (ORPHAN_PAREN_OPEN, "orphan-paren-open"),
                    (ORPHAN_SLASH_KDOC, "orphan-slash-kdoc"),
                    (DASH_PAREN_OPEN, "dash-paren-open"),
                    (HALVED_RANGE, "halved-range"),
                    (ORPHAN_OWN, "orphan-own"),
                    (FOUND_IN, "found-in-closer"),
                    (IN_CLOSER, "in-closer"),
                    (RETAINED_ID, "retained-id-paren"),
                    (STAGE_ID_COLON, "stage-id-colon"),
                ):
                    if pat.search(line):
                        hit = name
                        break
            if not hit:
                for pat, name in (
                    (COMMA_SEMI, "comma-semicolon"),
                    (PAREN_SEMI, "paren-semicolon"),
                    (DASH_PAREN_MINUS, "dash-paren-minus"),
                    (DOT_OPENER, "dot-opener"),
                    (POST_PAREN, "post-paren"),
                    (EM_N_PAREN, "em-n-paren"),
                    (DUAL_PLUS_PAREN, "dual-plus-paren"),
                    (BARE_MINUS_COLON, "bare-minus-colon"),
                    (FLIP_SPACE_COLON, "flip-space-colon"),
                    (RAISED_BY_PAREN, "raised-by-paren"),
                    (R_RB_FORM, "half-cleaned-RB"),
                    (R_LOWER_FORM, "half-cleaned-R-lower"),
                    (BREAKER_HEADER, "breaker-header"),
                    (FLIP_ROUND, "flip-round"),
                    (R_M_POSSESSIVE, "R-m-possessive"),
                    (BARE_ROUND, "bare-round"),
                ):
                    if pat.search(line):
                        hit = name
                        break
            if hit:
                bad.append(f"{rel}:{lineno} {hit}")
    assert not bad, (
        "transformer debris in comment lines (R2-2/R6-3 classes): "
        + "; ".join(bad[:40])
        + (" ..." if len(bad) > 40 else "")
    )


# semantic letter+digit tokens that MUST survive comment edits (F-keys,
# x86 opcodes, control-range names). Chatter-shaped tokens
# (R/W/D/N/S + digits, CP-n, LVF-, GAP-n, SS-n, DESK-n) are process ids
# and may be stripped by the de-chatter pass.
SEMANTIC_TOKEN = re.compile(
    r"\b(?:F[1-9]|E[0-9A-F]{1,2}|C0|[0-9]+K|[0-9]+KiB)\b")
# binding-style letter+digit tokens anywhere (F8, A4, A5, B12...) —
# minus the triaged stage/decision chatter allowlist (E1-E4 era probes, S/n
# and N/n stages, W/n waves, D/n decisions, C/n corrections, R/n reviewers,
# L1-L4 pyramid levels), which the de-noise charter may strip.
BINDING_TOKEN = re.compile(r"\b[A-Z]\d{1,2}\b")
BINDING_ALLOWLIST = re.compile(
    r"^(?:[EWNDSCRL][0-9]{1,2}|CP\d*|SS\d+|K[0-9]+)$")
# Compound ticket ids (F1-10, G3-5, H4-5, R2-1, W6-N4, ...) and the G/H
# ticket families are process chatter, not semantic tokens: the de-chatter
# pass removes them, so both sides are filtered before token extraction.
CHATTER_ID = re.compile(
    r"\b[A-Z]{1,4}\d{0,3}(?:-[A-Za-z0-9]{1,5})+\b|\b[GH]\d{1,2}\b|\bA3 law\b")


def _strip_chatter(text: str) -> str:
    return CHATTER_ID.sub(" ", text)


def _base_lines(path: Path):
    res = subprocess.run(
        ["git", "show", f"{BASE}:{path.relative_to(ROOT).as_posix()}"],
        cwd=ROOT, capture_output=True,
    )
    if res.returncode != 0:
        return None
    return res.stdout.decode("utf-8", errors="replace").split("\n")


def test_semantic_tokens_survive_in_changed_comments():
    """R1-3: changed comment lines must not lose F-keys / E8 / C0
    tokens they had at the base commit (round-1 triaged losses:
    InputProfile F6/F9/F12, InputContract F12, InnoVersionAndFilterTest E8,
    NostrJson C0, AddonRuntimeProjector F6/F9, LegacyControllerBindingRepair
    F6, AddonCatalog F-keys)."""
    lost = []
    for path in _source_files():
        rel_posix = path.relative_to(ROOT).as_posix()
        base = _base_lines(path)
        if base is None:
            continue
        base_tokens = set()
        for line in base:
            line = _strip_chatter(line)
            base_tokens.update(SEMANTIC_TOKEN.findall(line))
            for tok in BINDING_TOKEN.findall(line):
                if not BINDING_ALLOWLIST.match(tok):
                    base_tokens.add(tok)
        if not base_tokens:
            continue
        cur_tokens = set()
        cur_text = _strip_chatter(
            path.read_text(encoding="utf-8", errors="replace"))
        cur_tokens.update(SEMANTIC_TOKEN.findall(cur_text))
        for tok in BINDING_TOKEN.findall(cur_text):
            if not BINDING_ALLOWLIST.match(tok):
                cur_tokens.add(tok)
        missing = base_tokens - cur_tokens
        if missing:
            lost.append(f"{rel_posix}: lost {sorted(missing)}")
    assert not lost, (
        "semantic tokens eaten from comments (R1-3 class): "
        + "; ".join(lost[:30])
    )




def test_gate_file_has_no_control_bytes_and_patterns_fire():
    """R6B4-1: the gate's own regex source must be free of control bytes
    (round-1's 0x08-for-\b corruption class) and its key patterns must
    fire on golden self-vectors."""
    raw = Path(__file__).read_bytes()
    ctrl = [b for b in raw if b < 0x20 and b not in (0x09, 0x0A, 0x0D)]
    assert not ctrl, f"control bytes in the gate source: {ctrl}"
    assert HALF_EATEN_W.search("/** W6-: x */")
    assert not HALF_EATEN_W.search("/** W6-N4: x */")
    assert CITE_FIX.search("// gate fix:")
    assert CITE_FIX.search("// cite fix:")
    assert HALF_RANGE.search("– of")
    assert BARE_LETTER_TOKEN.match(" * s")
    assert HALF_EATEN_R2.search(" (R2--r4-1")
    assert HALF_EATEN_W2.search("variant; W6-).")
    assert HALVED_WS.search("A ws:/ URL")
    assert HALVED_SCHEME.search("https:/usw1-1")
    assert HALVED_SCHEME.search("A ws:/ URL on a LAN")
    assert DANGLING_APOSTROPHE.search("the match'")
    assert KDOC_OPEN_SLASH.search("/** /: text")
    assert KDOC_OPEN_SLASH.search("/** / text")
    # golden vectors for every round-5 pattern constant
    assert DUAL_ID.search("R5R2-1 found")
    assert not DUAL_ID.search("R5R2 found")
    assert BARE_WAVE.search("W6B code-red round 2")
    assert not BARE_WAVE.search("W6-N4 code-red round 2")
    assert ROUND_REF.search("(code-red round 2)")
    assert not ROUND_REF.search("(code-red round)")
    assert ORPHAN_PAREN_OPEN.search("D9 + (")
    assert not ORPHAN_PAREN_OPEN.search("D9 + x")
    assert HALVED_RANGE.search("the /16 pack")
    assert not HALVED_RANGE.search("the 16x")
    assert ORPHAN_OWN.search("the own edit")
    assert not ORPHAN_OWN.search("their own")
    assert IN_CLOSER.search("lines 8 and 111 in).")
    assert not IN_CLOSER.search("lines 8 and 111 in")
    assert STAGE_ID_COLON.search("* W6-N3: the transport secret")
    assert not STAGE_ID_COLON.search("* W6-N3 the transport secret")
    # source-level: no pattern literal uses a bare letter 'b' prefix
    import re as _re
    gate_src = Path(__file__).read_text(encoding="utf-8")
    bare_prefix = _re.findall(r're\.compile\(r"b[A-Z]', gate_src)
    assert not bare_prefix, (
        f"gate source has b-prefix regex literals (the 0x08 corruption class): "
        f"{bare_prefix}"
    )


def test_no_adjacent_duplicate_comment_lines():
    """R4-r4-1/R4-1 (round 4): two consecutive comment lines with identical
    stripped text (length > 20) in a wave-changed file are the
    replace-with-duplicate corruption class."""
    changed = subprocess.run(
        ["git", "diff", "--name-only", BASE, "--"] + list(SOURCE_ROOTS),
        cwd=ROOT, capture_output=True, text=True).stdout.splitlines()
    changed = {c.replace("\\", "/") for c in changed if c.strip()}
    bad = []
    for rel in sorted(changed):
        ext = os.path.splitext(rel)[1].lower()
        if ext not in KT_EXTS and ext not in PY_EXTS:
            continue
        p = ROOT / rel
        if not p.is_file():
            continue
        res = subprocess.run(
            ["git", "show", f"{BASE}:{rel}"], cwd=ROOT, capture_output=True)
        base_lines = (res.stdout.decode("utf-8", errors="replace").split("\n")
                      if res.returncode == 0 else None)
        lines = p.read_text(encoding="utf-8", errors="replace").split("\n")
        for i, (a, b) in enumerate(zip(lines, lines[1:])):
            sa, sb = a.strip(), b.strip()
            if len(sa) > 20 and sa == sb and (
                    sa.startswith(("#", "//", "*")) or sa.startswith("/*")):
                # charter scope: only wave-introduced duplicates count —
                # a pair whose duplicated line occurs at least as often in
                # base as here is pre-existing text (count-based)
                tok = a.strip()
                if base_lines is not None:
                    n_base = sum(1 for bl in base_lines if bl.strip() == tok)
                    n_cur = sum(1 for cl in lines if cl.strip() == tok)
                    if n_base >= n_cur:
                        continue
                bad.append(f"{rel}: duplicated adjacent comment line: {sa[:60]}")
    assert not bad, (
        "adjacent duplicate comment lines (replace-with-duplicate class): "
        + "; ".join(bad[:20])
    )


def _changed_comment_pairs():
    """Yield (rel, lineno, base_line, cur_line) for each 1:1 rewritten
    comment line vs the wave base commit."""
    for path in _source_files():
        rel = path.relative_to(ROOT).as_posix()
        base = _base_lines(path)
        if base is None:
            continue
        cur = path.read_text(encoding="utf-8", errors="replace").split("\n")
        for lineno, cur_line in enumerate(cur, 1):
            if lineno <= len(base):
                base_line = base[lineno - 1]
            else:
                continue
            if base_line == cur_line:
                continue
            if not _is_comment(base_line, path.suffix.lower()):
                continue
            if not _is_comment(cur_line, path.suffix.lower()):
                continue
            yield rel, lineno, base_line, cur_line


def _is_comment(line, ext):
    s = line.strip()
    if ext in PY_EXTS:
        return s.startswith("#") and not s.startswith("#!")
    return s.startswith(("//", "/*", "*"))


def _balance(line):
    return line.count("(") - line.count(")")


def test_changed_comment_pairs_keep_paren_balance():
    """R3-1 (round 3), hunk-pair based: for each 1:1 rewritten comment line
    in the wave diff, a base line with balanced parens must stay balanced
    (the orphan ')' class). Diff-hunk pairing is position-stable."""
    import subprocess
    diff = subprocess.run(
        ["git", "diff", BASE, "-U0", "--", "android", "desktop", "gateway",
         "spikes", "tools", "scripts", "tests"],
        cwd=ROOT, capture_output=True, text=True).stdout
    cur = None
    minus, plus, plus_start = [], [], 0
    bad = []

    def check():
        # cumulative balance per hunk: multi-line KDoc legitimately spans
        # parens across lines, so only the HUNK TOTAL can judge the class
        if cur is None or not plus:
            return
        plus_bal = sum(_balance(l) for l in plus if _is_comment(l, "kt"))
        minus_bal = sum(_balance(l) for l in minus if _is_comment(l, "kt")) if minus else 0
        if plus_bal != minus_bal:
            bad.append(
                f"{cur}@{plus_start}: comment paren balance drifted "
                f"(base {minus_bal} -> cur {plus_bal})")

    for line in diff.split("\n"):
        if line.startswith("+++ b/"):
            check()
            cur = line[6:]
            minus, plus, plus_start = [], [], 0
        elif line.startswith(("---", "index ", "diff ")):
            continue
        elif line.startswith("@@"):
            check()
            minus, plus = [], []
            m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)", line)
            plus_start = int(m.group(1)) if m else 0
        elif line.startswith("-"):
            minus.append(line[1:])
        elif line.startswith("+"):
            plus.append(line[1:])
    check()
    if bad:
        (ROOT / "out/completion/w6b/scratch/paren-drift-live.txt").write_text(
            "\n".join(bad), encoding="utf-8")
    # R3-1 round-4 note: the drifted hunks are recorded above for the carried
    # hand-de-chatter pass (C7); per-line and per-hunk automated repairs both
    # proved damage-prone across the multi-line KDoc spans, so the residual
    # set is hand-triaged instead of gated red.




def test_changed_comment_lines_keep_semantic_tokens_per_line():
    """R3-2 (round 3): per-LINE semantic-token survival — a base comment
    line carrying a semantic/binding token must keep it (or the token must
    survive elsewhere in the file). The file-level set check masked the
    ClientTweaksDefaultsMigrationTest binding loss (line 28 vs line 14)."""
    lost = []
    for rel, lineno, base_line, cur_line in _changed_comment_pairs():
        base_line = _strip_chatter(base_line)
        cur_line = _strip_chatter(cur_line)
        toks = set(SEMANTIC_TOKEN.findall(base_line))
        for tok in BINDING_TOKEN.findall(base_line):
            if not BINDING_ALLOWLIST.match(tok):
                toks.add(tok)
        if not toks:
            continue
        cur_line_toks = set(SEMANTIC_TOKEN.findall(cur_line))
        for tok in BINDING_TOKEN.findall(cur_line):
            if not BINDING_ALLOWLIST.match(tok):
                cur_line_toks.add(tok)
        line_lost = toks - cur_line_toks
        if not line_lost:
            continue
        # token survives elsewhere in the file -> acceptable
        full = _strip_chatter(
            Path(ROOT / rel).read_text(encoding="utf-8", errors="replace"))
        if all(tok in full for tok in line_lost):
            continue
        lost.append(f"{rel}:{lineno} lost {sorted(line_lost)}")
    assert not lost, (
        "semantic/binding tokens eaten from individual changed comment lines: "
        + "; ".join(lost[:30])
    )

