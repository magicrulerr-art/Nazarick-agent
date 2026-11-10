$ErrorActionPreference = 'Stop'
$p = Join-Path $PSScriptRoot 'app\build.gradle.kts'
$t = [IO.File]::ReadAllText($p)
$q = [char]34
$old = 'versionName = ' + $q + '0.0.0' + $q
$new = 'versionName = ' + $q + '0.0.1' + $q
if (-not $t.Contains($old)) { Write-Output 'OLD-NOT-FOUND'; exit 1 }
$t = $t.Replace($old, $new)
[IO.File]::WriteAllText($p, $t)
Write-Output 'OK'
