# Adds Turkish (Q) and Japanese (Microsoft IME) to tester's input methods, keeping en-US first. Idempotent. Runs on
# tester's desktop (vm.sh ui), where the language list belongs. Windows stores the tags short ('tr', 'ja').
$list = Get-WinUserLanguageList
$tags = @($list | ForEach-Object { $_.LanguageTag })
$changed = $false
if (($tags -notcontains 'tr') -and ($tags -notcontains 'tr-TR')) { $list.Add('tr-TR'); $changed = $true }
if (($tags -notcontains 'ja') -and ($tags -notcontains 'ja-JP')) { $list.Add('ja-JP'); $changed = $true }
if ($changed) { Set-WinUserLanguageList $list -Force }
"languages changed: $changed"
# Get-WinUserLanguageList writes its list as one object: index it, so each language prints on its own line.
$now = Get-WinUserLanguageList
for ($i = 0; $i -lt $now.Count; $i++) { "$($now[$i].LanguageTag): $($now[$i].InputMethodTips -join ',')" }
