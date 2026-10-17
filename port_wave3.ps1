$ErrorActionPreference = "Stop"
$root = "c:\Users\user\Desktop\2\mzycBetterMTR-Forge-1.18.2\src\main\java"
$files = Get-ChildItem -Path $root -Recurse -Filter *.java
$changed = 0
$opts = [System.Text.RegularExpressions.RegexOptions]::Singleline

foreach ($f in $files) {
    $t = [System.IO.File]::ReadAllText($f.FullName)
    $orig = $t

    # sendSuccess(() -> EXPR, bool) -> sendSuccess(EXPR, bool)   (1.18.2 takes Component, not Supplier)
    $t = [regex]::Replace($t, 'send(Success|Failure)\(\s*\(\)\s*->\s*(.*?),\s*(true|false)\);', 'send$1($2, $3);', $opts)

    # Commands.performPrefixedCommand -> performCommand (1.18.2)
    $t = $t -replace 'performPrefixedCommand\(base, command\)', 'performCommand(base, command)'

    if ($t -ne $orig) {
        [System.IO.File]::WriteAllText($f.FullName, $t)
        $changed++
        Write-Host "changed: $($f.Name)"
    }
}
Write-Host "TOTAL CHANGED: $changed"