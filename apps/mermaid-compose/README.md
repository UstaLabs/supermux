# mermaid-compose

Native Mermaid rendering for Kotlin / Compose Multiplatform, vendored into supermux.

**Origin.** This module is a copy of `mermaid-compose` from
[**cmp-mermaid**](https://github.com/swithun-liu/cmp-mermaid) by **swithun-liu** (MIT, © 2026
swithun-liu), taken at commit `2a72c5bc` of our fork
[UstaLabs/cmp-mermaid](https://github.com/UstaLabs/cmp-mermaid) (upstream `39ba7e2` + the ER row
colour fix, sent upstream as swithun-liu/cmp-mermaid#1). All credit for the translation of
Mermaid.js 12.0.0 to Kotlin goes to the original author. We vendor rather than depend on it so we
can customise it with supermux; package names (`com.swithun.cmpmermaid.*`) are kept on purpose
so upstream fixes still apply cleanly.

- License: [LICENSE](LICENSE) (MIT). Third-party notices, including Mermaid.js, dagre-d3-es and
  Jison (MIT) and the elkjs port (**EPL-2.0**, files under `flowchart/upstream/elk`):
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and `third_party/`.
- Local changes on top of the origin commit are listed below; keep this list current.

## Local changes

- Build script rewritten for the supermux Gradle build (our version catalog, no publishing / CocoaPods).
- Fonts trimmed from 5.5 MB to 0.7 MB: dropped Droid Sans Fallback (CJK, 3.4 MB), Noto Sans
  Symbols 2 (0.6 MB) and Arimo Italic / Bold Italic (0.7 MB). CJK and symbol glyphs fall back to the
  platform default family; italics are synthesized from Arimo Regular / Bold. Arimo (Arial-metric)
  and Droid Sans Mono stay because Mermaid's layout metrics assume them.
