# Third-party notices — `dev.supermux.terminal:terminal-compose`

terminal-compose itself is MIT licensed ([`LICENSE`](LICENSE), the same MIT licence as the
rest of this repository), and every library it compiles against (Compose Multiplatform,
`dev.supermux.terminal:terminal-core`) arrives at ordinary Maven coordinates rather than
inside these artifacts.

One third-party **data** file set does ship inside them: the terminal's default typeface.
It is in every artifact this module publishes — the JVM jar, the Android AAR, the iOS
klibs and the wasm bundle — under
`composeResources/dev.supermux.terminal.compose.resources/font/`, and it reaches an app's
users, so the notice below travels with it (also as
`META-INF/dev.supermux.terminal/THIRD-PARTY-NOTICES.md` inside each archive).

| file | component | licence |
|---|---|---|
| `font/jetbrains_mono_regular.ttf` | JetBrains Mono 2.304, Regular | SIL Open Font License 1.1 |
| `font/jetbrains_mono_bold.ttf` | JetBrains Mono 2.304, Bold | SIL Open Font License 1.1 |
| `font/jetbrains_mono_italic.ttf` | JetBrains Mono 2.304, Italic | SIL Open Font License 1.1 |

---

## 1. JetBrains Mono — SIL Open Font License 1.1

<https://github.com/JetBrains/JetBrainsMono>, release `v2.304`
(`JetBrainsMono-2.304.zip`). The three `.ttf` files are that release's
`fonts/ttf/JetBrainsMono-{Regular,Bold,Italic}.ttf` **byte for byte**, renamed to the
snake_case names Compose Multiplatform's resource accessors require (a resource file name
has to be a valid Kotlin identifier). Nothing is subsetted, re-hinted or otherwise
modified, so the "Reserved Font Name" clause below is not engaged and the family name
inside the files is still `JetBrains Mono`.

```
sha256  a0bf60ef0f83c5ed…  jetbrains_mono_regular.ttf   273900 bytes
sha256  5590990c82e09739…  jetbrains_mono_bold.ttf      277828 bytes
sha256  9d0a1f7a708e6af1…  jetbrains_mono_italic.ttf    276840 bytes
```

The licence, reproduced from the release's own `OFL.txt`:

```
Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```
