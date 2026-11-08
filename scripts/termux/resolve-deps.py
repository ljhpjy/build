#!/usr/bin/env python3
"""Resolve a Termux .deb dependency closure from a Packages index.

Usage:
    resolve-deps.py <Packages> <root1> [root2 ...]

Output TSV columns:
    package<TAB>version<TAB>filename<TAB>sha256

The first root present in the index is used; the extras are fallbacks
(e.g. nodejs-lts -> nodejs).
"""

from __future__ import annotations

import re
import sys


def parse_packages(path: str) -> dict[str, dict[str, str]]:
    records: dict[str, dict[str, str]] = {}
    current: dict[str, str] = {}
    key = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip():
                name = current.get("Package")
                if name:
                    records[name] = current
                current, key = {}, None
                continue
            if line[0] in " \t":
                if key:
                    current[key] = (current.get(key, "") + " " + line.strip()).strip()
                continue
            if ":" in line:
                k, v = line.split(":", 1)
                key = k.strip()
                current[key] = v.strip()
    name = current.get("Package")
    if name:
        records[name] = current
    return records


def dependencies(records: dict[str, dict[str, str]], package: str):
    record = records.get(package, {})
    for field in ("Pre-Depends", "Depends"):
        value = (record.get(field) or "").replace("\n", ",")
        for item in value.split(","):
            item = item.strip()
            if not item:
                continue
            item = item.split("|")[0].strip()
            item = re.sub(r"\(.*?\)", "", item)
            item = re.sub(r"\[.*?\]", "", item)
            item = re.sub(r"<.*?>", "", item)
            item = item.split(":")[0].strip()
            if item and not item.startswith("${"):
                yield item


def version_tuple(version: str) -> tuple[int, int, int]:
    match = re.match(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        return (0, 0, 0)
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: resolve-deps.py <Packages> <root1> [root2 ...]", file=sys.stderr)
        return 2

    records = parse_packages(sys.argv[1])
    roots = [item for item in sys.argv[2:] if item]
    available = [item for item in roots if item in records]
    if not available:
        print(f"none of these packages found: {', '.join(roots)}", file=sys.stderr)
        return 3

    root = None
    for item in available:
        # nodejs/nodejs-lts 需要 >= 20.11；chromium 等其它包不做这个限制。
        if not item.startswith("nodejs") or version_tuple(records[item].get("Version", "")) >= (20, 11, 0):
            root = item
            break
    if root is None:
        details = ", ".join(f"{item}={records[item].get('Version', '?')}" for item in available)
        print(f"no usable root package (Node packages need >= 20.11.0): {details}", file=sys.stderr)
        return 4

    provides: dict[str, str] = {}
    for name, record in records.items():
        for item in (record.get("Provides") or "").split(","):
            item = item.split("(")[0].split(":")[0].strip()
            if item:
                provides.setdefault(item, name)

    resolved: dict[str, str | None] = {}
    order: list[str] = []
    queue: list[str] = [root]
    while queue:
        name = queue.pop(0)
        if name in resolved:
            continue
        actual = name if name in records else provides.get(name)
        resolved[name] = actual
        if actual is None:
            print(f"WARN: package not found in index: {name}", file=sys.stderr)
            continue
        order.append(actual)
        for dep in dependencies(records, actual):
            if dep not in resolved and dep not in queue:
                queue.append(dep)

    seen: set[str] = set()
    for name in order:
        if name in seen:
            continue
        seen.add(name)
        record = records[name]
        filename = record.get("Filename", "")
        if not filename:
            print(f"WARN: {name} has no Filename field", file=sys.stderr)
            continue
        print(
            "\t".join(
                [
                    name,
                    record.get("Version", ""),
                    filename,
                    record.get("SHA256", ""),
                ]
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
