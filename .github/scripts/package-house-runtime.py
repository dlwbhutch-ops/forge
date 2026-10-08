#!/usr/bin/env python3
"""Bundle Forge rules data used by HOUSE; omit the graphical/adventure assets."""
import hashlib
import io
import os
from pathlib import Path
import zipfile
import runpy

root = Path(__file__).resolve().parents[2]
runpy.run_path(str(root / ".github/scripts/build-house-token-registry.py"), run_name="__main__")
res = root / "forge-gui/res"
# Empower derives token filenames at runtime, so ordinary TokenScript scans miss them.
for token in ("u_empower", "u_empower_jace"):
    if not (res / "tokenscripts" / (token + ".txt")).is_file():
        raise RuntimeError(f"Missing required Empower token: {token}")
out = root / "forge-gui-android/assets"
out.mkdir(parents=True, exist_ok=True)
build = os.environ.get("GITHUB_RUN_NUMBER", "local")
version = f"HOUSE-0.7.1-b{build}"

def add(archive, name, data, compress=True):
    info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    archive.writestr(info, data)

cards = io.BytesIO()
with zipfile.ZipFile(cards, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted((res / "cardsfolder").rglob("*.txt")):
        add(archive, path.relative_to(res / "cardsfolder").as_posix(), path.read_bytes())

target = out / "house-forge-runtime.zip"
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    add(archive, "res/cardsfolder/cardsfolder.zip", cards.getvalue(), compress=False)
    for directory in ["tokenscripts", "editions", "blockdata", "setlookup", "formats", "ai", "lists"]:
        for path in sorted((res / directory).rglob("*")):
            if path.is_file():
                add(archive, "res/" + path.relative_to(res).as_posix(), path.read_bytes())
    add(archive, "res/languages/en-US.properties", (res / "languages/en-US.properties").read_bytes())
    add(archive, "LICENSE.txt", (root / "forge-gui/LICENSE.txt").read_bytes())
    add(archive, "res/build.txt", version.encode())

digest = hashlib.sha256(target.read_bytes()).hexdigest()
(out / "house-forge-runtime.id").write_text(digest + "\n")
print(f"Bundled {target.stat().st_size:,} bytes of Forge rules; {version}; sha256={digest}")
