#!/usr/bin/env python3
"""
Generate one markdown summary per tracked file under docs/file-history/, mirroring the repo tree.

Each doc contains the file's purpose (best-effort, from its leading comment/docstring) and its full
commit history (date, hash, author, subject) derived from `git log`. Regenerate with:

    python docs/file-history/generate.py

Run from the repo root. Re-runnable: it overwrites the docs/file-history/ tree (except this script).
"""
import os
import subprocess
import sys

OUT = os.path.join("docs", "file-history")
SELF = os.path.join(OUT, "generate.py").replace("\\", "/")


def sh(args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def tracked_files():
    files = [f.strip() for f in sh(["git", "ls-files"]).splitlines() if f.strip()]
    # don't document the history folder itself (or this script)
    return [f for f in files if not f.startswith(OUT.replace("\\", "/") + "/") and f != SELF]


LANG = {
    ".java": "Java", ".py": "Python", ".js": "JavaScript", ".cs": "C#", ".xaml": "XAML",
    ".html": "HTML", ".css": "CSS", ".json": "JSON", ".md": "Markdown", ".yml": "YAML",
    ".yaml": "YAML", ".nsi": "NSIS", ".xml": "XML", ".txt": "Text", ".sh": "Shell",
    ".manifest": "XML manifest", ".ico": "icon", ".png": "image", ".jar": "jar",
    ".gitignore": "gitignore", ".dockerignore": "dockerignore",
}
BINARY = {".ico", ".png", ".jar"}


def lang_of(path):
    base = os.path.basename(path)
    if base == "Dockerfile" or base.startswith("Dockerfile"):
        return "Dockerfile"
    _, ext = os.path.splitext(path)
    return LANG.get(ext.lower(), ext.lstrip(".").upper() or "file")


def extract_purpose(path):
    """Best-effort one-paragraph purpose from the file's leading comment/docstring."""
    _, ext = os.path.splitext(path)
    if ext.lower() in BINARY:
        return ""
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = [next(fh) for _ in range(60)]
    except (OSError, StopIteration) as e:
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                lines = fh.readlines()[:60]
        except OSError:
            return ""
    text = "".join(lines)
    out = []
    import re
    if ext.lower() == ".py":
        # module docstring (allow a shebang and/or leading comments before it)
        t = re.sub(r"^#!.*\n", "", text, count=1)
        m = re.match(r'\s*(?:#.*\n|\s)*[ru]?["\']{3}(.*?)["\']{3}', t, re.S)
        if not m:
            m = re.search(r'["\']{3}(.*?)["\']{3}', t[:800], re.S)
        if m:
            return " ".join(m.group(1).strip().split())[:600]
    # block comment /* ... */ or <!-- ... -->
    import re
    for pat in (r"/\*+(.*?)\*/", r"<!--(.*?)-->"):
        m = re.search(pat, text, re.S)
        if m and m.start() < 400:
            body = re.sub(r"^\s*\*", "", m.group(1).strip(), flags=re.M)
            return " ".join(body.split())[:600]
    # consecutive single-line comments near the top (//, #)
    started = False
    for ln in lines:
        s = ln.strip()
        if s.startswith("//") or (s.startswith("#") and not s.startswith("#!")):
            out.append(s.lstrip("/#").strip())
            started = True
        elif started and s == "":
            continue
        elif started:
            break
    return " ".join(" ".join(out).split())[:600]


def commits_for(path):
    raw = sh(["git", "log", "--format=%h\t%ad\t%an\t%s", "--date=short", "--", path])
    rows = []
    for line in raw.splitlines():
        parts = line.split("\t", 3)
        if len(parts) == 4:
            rows.append(parts)
    return rows


def md_escape(s):
    return s.replace("|", "\\|")


def write_doc(path, commits):
    doc_path = os.path.join(OUT, path) + ".md"
    os.makedirs(os.path.dirname(doc_path), exist_ok=True)
    lang = lang_of(path)
    try:
        size = os.path.getsize(path)
        nlines = sum(1 for _ in open(path, "rb")) if os.path.splitext(path)[1].lower() not in BINARY else None
    except OSError:
        size, nlines = 0, None
    first = commits[-1][1] if commits else "?"
    last = commits[0][1] if commits else "?"
    purpose = extract_purpose(path)

    rel_back = "../" * (doc_path.count("/"))
    out = []
    out.append(f"# `{path}`\n")
    meta = [f"**{lang}**"]
    if nlines is not None:
        meta.append(f"{nlines} lines")
    meta.append(f"{size:,} bytes")
    meta.append(f"{len(commits)} commit(s)")
    meta.append(f"first {first}")
    meta.append(f"last {last}")
    out.append(" · ".join(meta) + "\n")
    out.append("## Purpose\n")
    out.append((purpose if purpose else "_No leading comment/docstring found — see the file and its history below._") + "\n")
    out.append("## Commit history\n")
    out.append("| Date | Commit | Author | Summary |")
    out.append("| --- | --- | --- | --- |")
    for h, d, a, s in commits:
        out.append(f"| {d} | `{h}` | {md_escape(a)} | {md_escape(s)} |")
    if not commits:
        out.append("| _no history_ | | | |")
    out.append("")
    out.append(f"[← file-history index]({rel_back}{OUT.replace(os.sep, '/')}/README.md)")
    out.append("")
    with open(doc_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))
    return doc_path, len(commits)


def main():
    files = tracked_files()
    print(f"Documenting {len(files)} files...")
    by_dir = {}
    for path in files:
        commits = commits_for(path)
        write_doc(path, commits)
        top = path.split("/")[0] if "/" in path else "(root)"
        by_dir.setdefault(top, []).append((path, len(commits)))

    # index
    idx = ["# Per-file history\n",
           f"One markdown summary per tracked file ({len(files)} files), generated from the repository's "
           "commit history. Each doc lists the file's purpose and every commit that touched it. "
           "Regenerate with `python docs/file-history/generate.py`.\n"]
    for top in sorted(by_dir):
        idx.append(f"## {top}\n")
        for path, n in sorted(by_dir[top]):
            idx.append(f"- [`{path}`]({path}.md) — {n} commit(s)")
        idx.append("")
    with open(os.path.join(OUT, "README.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(idx))
    print(f"Done. Wrote {len(files)} docs + README.md under {OUT}/")


if __name__ == "__main__":
    if not os.path.isdir(".git"):
        sys.exit("run from the repo root")
    main()
