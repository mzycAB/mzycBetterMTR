$ErrorActionPreference = 'Stop'
$root = "c:\Users\user\Desktop\2\mzycBetterMTR-Forge-1.18.2\src\main\java"
$files = Get-ChildItem $root -Recurse -Filter *.java
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$changed = 0
$stats = @{ lit = 0; trans = 0; empty = 0; sendSuccess = 0; serverLevel = 0; level = 0 }

foreach ($f in $files) {
    $t = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $o = $t

    # 1) Component.literal(...) -> new TextComponent(...)
    $c1 = ([regex]::Matches($t, 'Component\.literal\(')).Count
    $t = $t -replace 'Component\.literal\(', 'new net.minecraft.network.chat.TextComponent('

    # 2) Component.translatable(...) -> new TranslatableComponent(...)
    $c2 = ([regex]::Matches($t, 'Component\.translatable\(')).Count
    $t = $t -replace 'Component\.translatable\(', 'new net.minecraft.network.chat.TranslatableComponent('

    # 3) Component.empty() -> TextComponent.EMPTY
    $c3 = ([regex]::Matches($t, 'Component\.empty\(\)')).Count
    $t = $t -replace 'Component\.empty\(\)', 'net.minecraft.network.chat.TextComponent.EMPTY'

    # 4) sendSuccess(() -> EXPR, bool) -> sendSuccess(EXPR, bool)
    $c4 = ([regex]::Matches($t, 'sendSuccess\(\(\)\s*->')).Count
    $t = [regex]::Replace($t, 'sendSuccess\(\(\)\s*->\s*(.*?),\s*(true|false)\);', 'sendSuccess($1, $2);')

    # 5) .serverLevel() -> .getLevel()
    $c5 = ([regex]::Matches($t, '\.serverLevel\(\)')).Count
    $t = $t -replace '\.serverLevel\(\)', '.getLevel()'

    # 6) entity.level() / self.level() -> .getLevel()
    $c6 = ([regex]::Matches($t, '(entity|self)\.level\(\)')).Count
    $t = $t -replace '(entity|self)\.level\(\)', '$1.getLevel()'

    $stats.lit += $c1; $stats.trans += $c2; $stats.empty += $c3
    $stats.sendSuccess += $c4; $stats.serverLevel += $c5; $stats.level += $c6

    if ($t -ne $o) {
        [System.IO.File]::WriteAllText($f.FullName, $t, $utf8NoBom)
        $changed++
    }
}
Write-Output "changed files = $changed"
$stats.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key) = $($_.Value)" }