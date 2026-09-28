#!/usr/bin/env python3
"""Check the native editor's grammar library INSIDE a packaged desktop app (M5).

usage: check-packaged-editor-syntax.py <app lib dir> <target>   e.g. ".../Supermux Desktop.app/Contents/app" macos-arm64

The editor-syntax jar must carry dev/supermux/editor/syntax/natives/<target>/{<lib>, native.properties}, and
the recorded sha256 + size must describe the PACKAGED bytes (macOS re-signs the dylib during packaging;
desktop/build.gradle.kts rewritePackagedNativeDigests re-pins it). SesNativeLoader refuses a mismatch and the
editor would silently fall back to plain text, so a mismatch fails the release here instead.
"""
import glob, hashlib, os, sys, zipfile

app_dir, target = sys.argv[1], sys.argv[2]
jars = [j for j in glob.glob(os.path.join(app_dir, "*.jar")) if os.path.basename(j).startswith("editor-syntax")]
if not jars:
    sys.exit(f"no editor-syntax jar in {app_dir}")
root = f"dev/supermux/editor/syntax/natives/{target}/"
with zipfile.ZipFile(jars[0]) as z:
    try:
        props = z.read(root + "native.properties").decode()
    except KeyError:
        sys.exit(f"{jars[0]} carries no {root}native.properties: the desktop editor would have no syntax on {target}")
    fields = dict(l.split("=", 1) for l in props.splitlines() if "=" in l and not l.startswith("#"))
    lib = z.read(root + fields["library"])
    sha = hashlib.sha256(lib).hexdigest()
    if sha != fields.get("sha256") or str(len(lib)) != fields.get("size"):
        sys.exit(f"{root}{fields['library']}: packaged {sha}/{len(lib)} != recorded {fields.get('sha256')}/{fields.get('size')}")
    tables = [n for n in z.namelist() if n.startswith("editor-syntax/tables/") and n.endswith(".sesz")]
    if not tables:
        sys.exit(f"{jars[0]} carries no grammar tables")
print(f"editor-syntax {target}: {fields['library']} {len(lib)} B, digest matches; {len(tables)} tables")
