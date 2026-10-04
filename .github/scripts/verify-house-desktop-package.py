#!/usr/bin/env python3
"""Verify a packaged HOUSE Commander Lab desktop distribution on its native OS."""

from __future__ import annotations

import argparse
import os
import platform
import subprocess
import sys
import tempfile
from pathlib import Path


ADD_OPENS = [
    "java.desktop/java.beans=ALL-UNNAMED",
    "java.desktop/javax.swing.border=ALL-UNNAMED",
    "java.desktop/javax.swing.event=ALL-UNNAMED",
    "java.desktop/sun.swing=ALL-UNNAMED",
    "java.desktop/java.awt.image=ALL-UNNAMED",
    "java.desktop/java.awt.color=ALL-UNNAMED",
    "java.desktop/sun.awt.image=ALL-UNNAMED",
    "java.desktop/javax.swing=ALL-UNNAMED",
    "java.desktop/java.awt=ALL-UNNAMED",
    "java.base/java.util=ALL-UNNAMED",
    "java.base/java.lang=ALL-UNNAMED",
    "java.base/java.lang.reflect=ALL-UNNAMED",
    "java.base/java.text=ALL-UNNAMED",
    "java.desktop/java.awt.font=ALL-UNNAMED",
    "java.base/jdk.internal.misc=ALL-UNNAMED",
    "java.base/sun.nio.ch=ALL-UNNAMED",
    "java.base/java.nio=ALL-UNNAMED",
    "java.base/java.math=ALL-UNNAMED",
    "java.base/java.util.concurrent=ALL-UNNAMED",
    "java.base/java.net=ALL-UNNAMED",
]


def normalize_arch(value: str) -> str:
    v = (value or "").lower()
    if v in {"amd64", "x86_64", "x64"}:
        return "x64"
    if v in {"arm64", "aarch64"}:
        return "arm64"
    return v


def find_java(root: Path) -> Path:
    candidates = [
        root / "runtime" / "bin" / ("java.exe" if os.name == "nt" else "java"),
        root / "runtime" / "Contents" / "Home" / "bin" / "java",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(
        "Bundled Java runtime was not found. Checked: "
        + ", ".join(str(p) for p in candidates)
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("package_root", type=Path)
    parser.add_argument("--expected-arch", choices=["x64", "arm64"], required=True)
    args = parser.parse_args()

    root = args.package_root.resolve()
    if not root.is_dir():
        raise SystemExit(f"Package root is missing: {root}")

    jar = root / "HOUSE-Commander-Lab.jar"
    if not jar.is_file():
        raise SystemExit(f"HOUSE JAR is missing: {jar}")

    java = find_java(root)
    actual_arch = normalize_arch(platform.machine())
    if actual_arch != args.expected_arch:
        raise SystemExit(
            f"Runner architecture mismatch: expected {args.expected_arch}, got {actual_arch}"
        )

    print(f"NATIVE_PLATFORM={platform.system()} {platform.release()}")
    print(f"NATIVE_ARCH={actual_arch}")
    print(f"BUNDLED_JAVA={java}")
    print(f"HOUSE_JAR={jar}")

    subprocess.run([str(java), "-version"], check=True)

    with tempfile.TemporaryDirectory(prefix="house-native-smoke-") as temp_home:
        command = [
            str(java),
            "-Xmx2g",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true",
            f"-Duser.home={temp_home}",
        ]
        for value in ADD_OPENS:
            command.append(f"--add-opens={value}")
        command += [
            "-cp",
            str(jar),
            "com.housecommander.desktop.HouseDesktopSmoke",
        ]
        completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            raise SystemExit(
                f"HOUSE native smoke failed with exit code {completed.returncode}"
            )

    print("HOUSE_NATIVE_PACKAGE_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
