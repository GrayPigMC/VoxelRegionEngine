#!/usr/bin/env python3
"""Synchronize the portable engine and Forge library sources into an explicit GTLCore checkout."""
# SPDX-License-Identifier: MPL-2.0
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, required=True, help="Authorized GTLCore checkout; never resolved through a sibling build dependency")
    parser.add_argument("--apply", action="store_true", help="Write the self-contained source snapshot; otherwise print its identity only")
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    target = args.core.resolve()
    if not (target / "src/main/java/org/gtlcore/gtlcore/GTLCore.java").is_file():
        raise SystemExit("Target is not a GTLCore checkout")
    if not (target / "vre-engine/build.gradle").is_file() or not (target / "gradle/vre-packaging.gradle").is_file():
        raise SystemExit("Install the GTLCore VRE build wiring before synchronizing the snapshot")

    files: dict[str, Path] = {}
    portable = source / "src/main/java"
    for path in sorted(portable.rglob("*.java")):
        files[f"vre-engine/src/main/java/{path.relative_to(portable).as_posix()}"] = path
    forge = source / "tools/forge-test/src/main/java/org/voxelregion/forge"
    for path in sorted(forge.rglob("*.java")):
        files[f"vre-forge/src/main/java/org/voxelregion/forge/{path.relative_to(forge).as_posix()}"] = path
    resources = source / "tools/forge-test/src/main/resources"
    for name in ("voxelregion-forge.mixins.json", "voxelregion-receipts.mixins.json",
                 "data/voxelregion_test/tags/blocks/protected.json", "data/voxelregion_test/tags/blocks/blocks_propagation.json"):
        files[f"vre-forge/src/main/resources/{name}"] = resources / name
    for module in ("vre-engine", "vre-forge"):
        for name in ("LICENSE", "NOTICE"):
            files[f"{module}/{name}"] = source / name
    # Preserve the independent LGPL component, including its complete source and
    # provenance. It is never relabelled as part of the MPL portable library.
    lighting = source / "third_party/starlight-forge"
    for path in sorted(lighting.rglob("*")):
        if path.is_file():
            files[f"vre-starlight/{path.relative_to(lighting).as_posix()}"] = path
    files["gradle/vre-starlight-packaging.gradle"] = source / "tools/gradle/starlight-packaging.gradle"

    digest = hashlib.sha256()
    hashes: dict[str, str] = {}
    snapshot: dict[str, bytes] = {}
    for name, path in sorted(files.items()):
        snapshot[name] = path.read_bytes()
        data = snapshot[name].replace(b"\r\n", b"\n")
        hashes[name] = hashlib.sha256(data).hexdigest()
        digest.update(name.encode("utf-8") + b"\0" + data + b"\0")
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=source, text=True, capture_output=True, check=True).stdout.strip()
    status = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=source,
                            text=True, capture_output=True, check=True).stdout
    manifest = {"library": "VoxelRegionEngine", "version": "0.1.0", "sourceRevision": revision,
                "sourceDirty": bool(status.strip()), "sourceSha256": digest.hexdigest(), "files": hashes}
    if args.apply:
        manifest_path = target / "vre-vendor.json"
        if manifest_path.is_file():
            old = json.loads(manifest_path.read_text(encoding="utf-8"))
            for name in set(old.get("files", {})) - set(files):
                obsolete = (target / name).resolve()
                if not obsolete.is_relative_to(target) or not name.startswith(("vre-engine/", "vre-forge/", "vre-starlight/")):
                    raise SystemExit(f"Refusing stale manifest path outside the managed VRE modules: {name}")
                if obsolete.is_file():
                    obsolete.unlink()
        for name, data in snapshot.items():
            destination = target / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            # Keep the upstream source content; the manifest normalizes line endings for identity.
            destination.write_bytes(data)
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in manifest.items() if key != "files"}, ensure_ascii=False))
    print(f"{'Synchronized' if args.apply else 'Would synchronize'} {len(files)} files into {target}")


if __name__ == "__main__":
    main()
