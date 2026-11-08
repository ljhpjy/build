#!/usr/bin/env python3
"""Sanity-check a bundled Termux Node runtime.

Usage:
    check-runtime.py <runtime-dir>

The directory must contain bin/node and (usually) lib/*.so*.
This verifies the ELF architecture and that every non-system DT_NEEDED
library is present in lib/, so the bundle does not fail on device with
"cannot locate symbol" / "library not found".
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

# Android system libraries that are always present on device and must not be bundled.
SYSTEM_LIBS = {
    "libc.so",
    "libm.so",
    "libdl.so",
    "liblog.so",
    "libandroid.so",
    "libstdc++.so",
    "libnetd_client.so",
    "libnativehelper.so",
    "libnativewindow.so",
    "libnativeloader.so",
    "libbinder_ndk.so",
    "libaaudio.so",
    "libGLESv1_CM.so",
    "libOpenMAXAL.so",
    "libbase.so",
    "libcutils.so",
    "libutils.so",
    "libbinder.so",
    "libhidlbase.so",
    "libhardware.so",
    "libsync.so",
    "libui.so",
    "libEGL.so",
    "libGLESv2.so",
    "libvulkan.so",
    "libjnigraphics.so",
    "libOpenSLES.so",
    "libmediandk.so",
    "libcamera2ndk.so",
    "libneuralnetworks.so",
    "libstatspull.so",
    "libstatssocket.so",
    "libpackagelistparser.so",
}


def readelf(args: list[str]) -> str:
    try:
        return subprocess.check_output(
            ["readelf", *args], text=True, errors="replace", stderr=subprocess.DEVNULL
        )
    except Exception:
        return ""


def machine_of(path: Path) -> str:
    match = re.search(r"Machine:\s+(.+)", readelf(["-h", str(path)]))
    return match.group(1).strip() if match else ""


def dynamic_entries(path: Path, tag: str) -> list[str]:
    text = readelf(["-d", str(path)])
    return re.findall(rf"\({tag}\)\s+[^:]+: \[([^\]]+)\]", text)


def interpreter_of(path: Path) -> str:
    text = readelf(["-l", str(path)])
    match = re.search(r"\[Requesting program interpreter: ([^\]]+)\]", text)
    return match.group(1) if match else ""


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-runtime.py <runtime-dir>", file=sys.stderr)
        return 2

    if not shutil.which("readelf"):
        print("readelf not found (install binutils)", file=sys.stderr)
        return 1

    root = Path(sys.argv[1])
    node = root / "bin" / "node"
    libdir = root / "lib"

    if not node.is_file():
        print(f"missing Node binary: {node}", file=sys.stderr)
        return 1
    if node.stat().st_size < 1_000_000:
        print(f"Node binary looks too small: {node.stat().st_size} bytes", file=sys.stderr)
        return 1

    machine = machine_of(node)
    if "AArch64" not in machine:
        print(f"Node binary is not aarch64: {machine or 'unknown'}", file=sys.stderr)
        return 1

    interp = interpreter_of(node)
    if interp and "linker64" not in interp:
        print(
            f"Node interpreter is not Android's linker64 ({interp}); this looks like a glibc build",
            file=sys.stderr,
        )
        return 1

    provided = {path.name for path in libdir.rglob("*") if path.is_file()}
    for path in libdir.rglob("*.so*"):
        for soname in dynamic_entries(path, "SONAME"):
            provided.add(soname)

    files = [node, *[path for path in libdir.rglob("*") if path.is_file()]]
    missing: set[str] = set()
    for path in files:
        for needed in dynamic_entries(path, "NEEDED"):
            if needed in provided or needed in SYSTEM_LIBS:
                continue
            missing.add(needed)

    if missing:
        print("unresolved non-system libraries: " + ", ".join(sorted(missing)), file=sys.stderr)
        return 1

    print(f"runtime OK: {machine}, {len(files)} files checked, all non-system libs bundled")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
