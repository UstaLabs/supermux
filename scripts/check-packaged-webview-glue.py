#!/usr/bin/env python3
"""Check the web view's Windows glue INSIDE a packaged desktop app (#137).

usage: check-packaged-webview-glue.py <app lib dir> <arch> <wv2.h>   e.g. ".../app" x64 apps/webview-compose/native/windows/wv2.h

The webview-compose jar must carry dev/supermux/webview/native/windows-<arch>/{webview_windows.dll, native.properties},
the recorded sha256 must describe the PACKAGED bytes, and the recorded ABI must be the glue's (wv2.h's
WV_ABI_VERSION). Without them WinNativeLoader refuses, and every Browser tab and HTML card on Windows says it can't
show web pages: no release before v0.18.3 had the glue at all, so a missing one fails the release here.
"""
import glob, hashlib, os, re, sys, zipfile

app_dir, arch, header = sys.argv[1], sys.argv[2], sys.argv[3]
jars = [j for j in glob.glob(os.path.join(app_dir, "*.jar")) if os.path.basename(j).startswith("webview-compose")]
if not jars:
    sys.exit(f"no webview-compose jar in {app_dir}")
abi = re.search(r"#define WV_ABI_VERSION (\d+)", open(header, encoding="utf-8").read())
if not abi:
    sys.exit(f"{header} defines no WV_ABI_VERSION")
root = f"dev/supermux/webview/native/windows-{arch}/"
with zipfile.ZipFile(jars[0]) as z:
    try:
        props = z.read(root + "native.properties").decode()
    except KeyError:
        sys.exit(f"{jars[0]} carries no {root}native.properties: Browser tabs and HTML cards could not show pages on Windows {arch}")
    fields = dict(l.split("=", 1) for l in props.splitlines() if "=" in l and not l.startswith("#"))
    library = fields.get("library", "")
    try:
        lib = z.read(root + library)
    except KeyError:
        sys.exit(f"{jars[0]} carries no {root}{library}")
    sha = hashlib.sha256(lib).hexdigest()
    if sha != fields.get("sha256"):
        sys.exit(f"{root}{library}: packaged {sha} != recorded {fields.get('sha256')}")
    if fields.get("abi") != abi.group(1):
        sys.exit(f"{root}native.properties records ABI {fields.get('abi')}, the glue's is {abi.group(1)}")
print(f"webview glue windows-{arch}: {library} {len(lib)} B, digest matches, ABI {abi.group(1)} ({os.path.basename(jars[0])})")
