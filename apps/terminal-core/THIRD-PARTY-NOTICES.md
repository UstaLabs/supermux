# Third-party notices — `dev.supermux.terminal:terminal-core`

terminal-core itself is MIT licensed ([`LICENSE`](LICENSE), the same MIT licence as the
rest of this repository). Its published artifacts additionally **contain compiled
third-party code**: the Ghostty VT engine and the libraries Ghostty's `libghostty-vt`
links. This file lists exactly what ends up inside the artifacts of the pinned build
(Ghostty `22391ed6491f2924361dcad1f9a9176a390fd20f`, Zig `0.16.0`) and reproduces the
required notices.

Which artifacts: every `libsupermux_terminal*.{so,dylib,dll,a}`, the Android
`jni/<abi>/libsupermux_terminal_jni.so` inside the AAR, the JVM jar's
`dev/supermux/terminal/native/<target>/…` resources, the iOS static archive
`libsupermux_terminal.a` and `supermux-terminal.wasm`.

How this list was made: the combined static archive
(`build/native/<target>/lib/libsupermux_terminal.a`) has exactly 13 members, and each is
attributed below (`ar t`). Nothing else is linked in: the shipped Linux shared library
needs only `libc.so.6` + `librt.so.1`, the Android one only `libc`/`libm`/`libdl`, and the
wasm module has **no imports at all**.

| archive member | component | licence |
|---|---|---|
| `base64.o`, `codepoint_width.o`, `index_of.o`, `vt.o`, `libghostty-vt-static_zcu.o` | Ghostty (`libghostty-vt`) | MIT |
| (inside `libghostty-vt-static_zcu.o`) | uucode 0.2.0 | MIT |
| `wuffs-v0.4.o` | Wuffs | MIT *or* Apache-2.0 (MIT taken here) |
| `simdutf.o` | simdutf 5.2.8 | MIT *or* Apache-2.0 (MIT taken here) |
| `libhighway_zcu.o`, `per_target.o`, `targets.o`, `abort.o` | Google Highway 1.2.0 | Apache-2.0 (some files BSD-3-Clause) |
| `compiler_rt.o` | Zig 0.16.0 `compiler_rt` | MIT |
| `w00_terminal_bridge.o` | this package (`native/src/terminal_bridge.c`) | MIT |

**Build-time only, not linked into any artifact:** Zig's `translate-c` + `aro`, zlib,
the `pixels` test images, `ghostty-themes`, the Android NDK (r28c, used to compile and
link the Android libraries), the JDK's `jni.h`/`jni_md.h`, and — for the C smoke tests
only, never for a shipped library — Zig's bundled libc++.

---

## 1. Ghostty / libghostty-vt — MIT

<https://github.com/ghostty-org/ghostty>, pinned commit
`22391ed6491f2924361dcad1f9a9176a390fd20f` (app version `1.3.2-dev`, `libghostty-vt`
`0.1.0-dev`). The VT parser, screen/scrollback, render state and the key/mouse encoders
are Ghostty's; this package adds only the `st_*` wrapper on top.

```
MIT License

Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. uucode 0.2.0 — MIT

<https://github.com/jacobsandlund/uucode>, pinned by Ghostty's `build.zig.zon`
(`9d55524551411b493cca41ca06363625d90aff1e`). Unicode tables (grapheme breaking,
character widths) compiled into `libghostty-vt`.

```
# uucode license

MIT License

Copyright (c) 2026 Jacob Sandlund

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

uucode's own `LICENSE.md` additionally credits, for code it vendors, the **Unicode
License** (Unicode Character Database data) and **Björn Höhrmann's** MIT-licensed UTF-8
decoder; those notices live in the `licenses/` directory of the uucode repository (that
directory is not part of the packaged tarball Zig fetches).

## 3. Wuffs — MIT or Apache-2.0

<https://github.com/google/wuffs>, pinned by Ghostty
(`7411f488fe2e2c205c3d3b3d28638b7356522930`, release C file `wuffs-v0.4.c`). Used by
Ghostty for fast base64 / image decode helpers. Wuffs is offered under **both** the MIT
licence and Apache-2.0; this distribution takes the MIT option:

```
This software is distributed under the terms of both the MIT license and the
Apache License (Version 2.0).


MIT license

Copyright 2023 The Wuffs Authors

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

## 4. simdutf 5.2.8 — MIT or Apache-2.0

<https://github.com/simdutf/simdutf>, vendored inside the Ghostty checkout at
`pkg/simdutf/vendor/simdutf.{h,cpp}` (amalgamated, auto-generated 2026-04-21).
Ghostty's vendored copy carries no `LICENSE` file of its own; upstream simdutf at tag
`v5.2.8` ships **both** `LICENSE-MIT` and `LICENSE-APACHE`, so it is dual-licensed and
this distribution takes the MIT option (text below, verbatim from that tag). simdutf's
copy of the Apache-2.0 appendix fills the boilerplate in with "Copyright 2020 The simdutf
authors"; its MIT file says 2021 — both are upstream's own wording.

```
Copyright 2021 The simdutf authors

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## 5. Google Highway 1.2.0 — Apache-2.0 (parts BSD-3-Clause)

<https://github.com/google/highway>, pinned by Ghostty
(`66486a10623fa0d72fe91260f96c892e41aceb06`). SIMD runtime dispatch used by simdutf /
Ghostty. Copyright the Highway Project Authors; licensed under Apache-2.0 (appendix
below), with some files under BSD-3-Clause:

```
Copyright (c) The Highway Project Authors. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1.  Redistributions of source code must retain the above copyright notice, this
    list of conditions and the following disclaimer.

2.  Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

3.  Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## 6. Zig 0.16.0 `compiler_rt` — MIT

<https://ziglang.org>. `compiler_rt.o` (compiler intrinsics) comes from the Zig standard
library. Parts of Zig's `compiler_rt` are derived from LLVM's compiler-rt, which is
licensed Apache-2.0 **with** the LLVM exception. The Zig toolchain itself is only a build
tool and is not redistributed here; only these intrinsics are.

```
The MIT License (Expat)

Copyright (c) Zig contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

---

## Provenance of these texts

Every licence text above was taken from the pinned source that actually went into the
build (the Ghostty checkout at its pinned commit, or the content-hash-verified tarball in
Zig's package cache), and then **independently re-fetched from upstream and diffed** on
2026-09-22. All five quoted blocks are byte-identical to upstream; no discrepancy was
found.

| text | upstream revision it was verified against | how |
|---|---|---|
| Ghostty MIT | `ghostty-org/ghostty@22391ed6491f2924361dcad1f9a9176a390fd20f` | raw.githubusercontent at the pinned SHA — identical to the local checkout |
| uucode MIT | `jacobsandlund/uucode@9d55524551411b493cca41ca06363625d90aff1e` | raw.githubusercontent at the pinned SHA — identical to the packaged tarball |
| Wuffs MIT | `google/wuffs`, `LICENSE` on `main` | GitHub has no raw view for the pinned snapshot commit `7411f488fe2e2c205c3d3b3d28638b7356522930`; the branch text is identical to the pinned tarball's `LICENSE`, which is what the build used |
| simdutf MIT | `simdutf/simdutf@v5.2.8`, `LICENSE-MIT` | fetched at the exact tag; the tag also carries `LICENSE-APACHE`, which is what makes the dual licence verifiable |
| Google Highway Apache-2.0 + BSD-3 | `google/highway@66486a10623fa0d72fe91260f96c892e41aceb06` | raw.githubusercontent at the pinned SHA — identical to the packaged tarball; Highway's `LICENSE` **is** the Apache-2.0 appendix reproduced below |
| Zig MIT | `ziglang/zig`, `LICENSE` on `master` | GitHub has no `0.16.0` tag yet; the text is identical to `LICENSE` inside the installed, sha256+minisign-verified Zig 0.16.0 toolchain |

Re-running the check: fetch each URL above and `diff` it against the corresponding fenced
block in this file (the Apache appendix is Highway's `LICENSE`).

---

## Appendix: Apache License 2.0

Applies to Google Highway (and, at the recipient's option, to simdutf and Wuffs).

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
