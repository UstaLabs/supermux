#!/usr/bin/env bash
# #142 size probe: re-run wasm-opt over Kotlin's pre-opt wasm with flag variants and report the
# gzip -9 (and brotli) size of each. CI only — each run needs ~1.5 GB RAM and ~1.5 min.
# usage: sweep.sh <pre-opt.wasm> <out-dir>
set -euo pipefail
in=$1; out=$2; mkdir -p "$out"
WO=$(ls -d ~/.gradle/binaryen/binaryen-version_*/bin/wasm-opt | tail -1)
FEAT="--enable-gc --enable-reference-types --enable-exception-handling --enable-bulk-memory --enable-nontrapping-float-to-int"
KEEP="--no-inline=kotlin.wasm.internal.throwValue --no-inline=kotlin.wasm.internal.getKotlinException --no-inline=kotlin.wasm.internal.jsToKotlinStringAdapter"
COMMON="$FEAT $KEEP --inline-functions-with-loops --traps-never-happen --fast-math --closed-world"
# What KGP 2.4.10 runs (BinaryenConfig.binaryenArgs).
KGP="--type-ssa -O3 -O3 --gufa -O3 --type-merging -O3 -Oz"
declare -A V=(
  [kgp]="$KGP"
  [kgp-names]="$KGP -g"
  [kgp+Oz]="$KGP -Oz"
  [kgp+converge]="$KGP --converge"
  [kgp+gfx]="--generate-global-effects $KGP"
  [kgp+unsubtype]="$KGP --abstract-type-refining --unsubtyping --remove-unused-types -Oz"
  [kgp+minrec]="$KGP --minimize-rec-groups"
  [shrink-Os]="--type-ssa -O3 --gufa -Os --type-merging -Os -Oz -Oz"
  [kgp-noinl-lambda]="--type-ssa -O3 -O3 --gufa -O3 --type-merging -O3 -Oz --no-inline=*\$lambda*"
)
printf "%-18s %12s %10s %10s %8s\n" variant raw gzip9 brotli secs | tee "$out/sweep.txt"
for k in kgp kgp-names kgp+Oz kgp+converge kgp+gfx kgp+unsubtype kgp+minrec shrink-Os kgp-noinl-lambda; do
  s=$(date +%s)
  if "$WO" "$in" -o "$out/$k.wasm" $COMMON ${V[$k]} 2> "$out/$k.err"; then
    raw=$(stat -c%s "$out/$k.wasm"); gz=$(gzip -9c "$out/$k.wasm" | wc -c)
    br=$( (command -v brotli >/dev/null && brotli -c -q 11 "$out/$k.wasm" | wc -c) || echo -)
  else raw=FAIL; gz=-; br=-; fi
  printf "%-18s %12s %10s %10s %8s\n" "$k" "$raw" "$gz" "$br" $(( $(date +%s)-s )) | tee -a "$out/sweep.txt"
  [ "$k" = kgp-names ] || rm -f "$out/$k.wasm"
done
