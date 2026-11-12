# Translation glossary (#123)

English is the source: `apps/ui/i18n/values/*.xml` (one file per area). Each language lives in
`apps/ui/i18n/values-<tag>/` with the same files, keys and placeholders (`tests/i18n-strings.test.ts` fails otherwise).
They are NOT Compose's `values/`: its generated accessors cost ~150 B of wasm per key. `:ui:generateI18n` numbers the
keys (`Str.<key>` / `Plurals.<key>`, inline, no code per key) and compiles each language to one compact
`files/i18n/<lang>.txt` (generated, git-ignored).

## How a string is added
1. Put the English in `apps/ui/i18n/values/<area>.xml` (`area_thing` keys, e.g. `general_theme`).
2. Translate it into every `apps/ui/i18n/values-<tag>/<area>.xml` in the same change, following the terms below.
3. Use it with `localized(Str.key)`; a count uses `<plurals>` + `localizedPlural(Plurals.key, n, n)`.
4. Lower the hard-coded baseline: `bun scripts/i18n-strings.ts --write`.
5. Test tags stay English (`general_theme_Light`): UI automation must find the same node in every language.

## Turkish (tr)
Tone: polite plural (siz), short, plain; imperatives in the -In form ("Seçin", "Temizleyin"). Button labels are the
bare verb stem ("Temizle", "Kaydet"), as Turkish apps do. Text the code uppercases is written in Turkish capitals in
the catalog (İ, not I): Kotlin's uppercase() has no Turkish "i".

| English | Türkçe | Note |
|---|---|---|
| agent | ajan | the AI that does the work |
| session | oturum | |
| chat | sohbet | |
| worktree | worktree | git term, kept |
| branch | dal | |
| host / machine | makine | |
| device | cihaz | |
| pair (a device) | eşleştir | |
| settings | Ayarlar | |
| theme | tema | |
| light / dark | açık / koyu | |
| system (follow the OS) | sistem | |
| reply | yanıt | |
| tool call | araç çağrısı | |
| terminal | terminal | |
| cache | önbellek | |
| provider | sağlayıcı | |
| sign in / login | giriş yap / giriş | |
| account | hesap | |
| API key | API anahtarı | |
| token | token | kept |
| install | kur / kurulum | |
| subscription | abonelik | |
| limit | limit | |
| memory (the agents' shared notes) | hafıza | |
| shared link | paylaşılan bağlantı | |
| dictation | dikte | |
| read aloud | sesli oku | |
| tidy-up (memory, dictation) | toparlama | |
| labs | deneyler | "Privacy & labs" = "Gizlilik ve deneyler" |
| experimental | deneysel | |
| retry / try again | tekrar dene | |
| effort | çaba | |
| build (a version) | derleme | |
| broker (the server a device connects to) | sunucu | |
| pairing link / code | eşleştirme bağlantısı / kodu | |
| QR code | karekod | |
| credentials | kimlik bilgileri | |
| offline | çevrimdışı | |
| setup (wizard) | kurulum | |
| view | görünüm | |
| tab | sekme | |
| workspace | çalışma alanı | |
| sidebar | kenar çubuğu | |
| split (a pane) | böl | |
| git fetch / pull / push / publish | Getir / Çek / Gönder / Yayınla | |
| kill (a session) | sonlandır | |
| mute / unmute | sessize al / sesi aç | |
| changes (a diff) | değişiklikler | |
| shell | kabuk | |
| reset (a limit) | sıfırlama | "resets X" = "sıfırlanma: X" (no suffix on a time or name) |
| usage window | kullanım penceresi | |
| credits | kredi | |
| billing cycle | fatura dönemi | |
| percent | %62 | Turkish puts % first: "%%1$d" |
| supermux | supermux | the brand, never translated or capitalised |
