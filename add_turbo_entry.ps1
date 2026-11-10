# Insert SD-Turbo Q4_0 + Q8_0 GGUF entries into SdCppCatalog.kt (byte-preserving).
# Sizes/URLs verified against the HuggingFace API for samoudi/sd-turbo-gguf on 2026-11-06:
#   sd_turbo_q4_0.gguf -> 1847909248 bytes
#   sd_turbo_q8_0.gguf -> 2149136352 bytes
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot 'sd-cpp\src\main\java\me\rerere\sdcpp\SdCppCatalog.kt'
if (-not (Test-Path -LiteralPath $path)) { throw "catalog not found: $path" }

$raw = [System.IO.File]::ReadAllText($path)
if ($raw -match "`r`n") { $nl = "`r`n" } else { $nl = "`n" }
$hasBom = ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF)
$enc = New-Object System.Text.UTF8Encoding($hasBom)

if ($raw.Contains('id = "sdturbo-q4_0"') -or $raw.Contains('id = "sdturbo-q8_0"')) {
    Write-Host 'SKIP: SD-Turbo entries already present'
    exit 0
}
if (-not $raw.Contains('val models: List<SdModelEntry> = listOf(')) {
    throw 'val models: List<SdModelEntry> = listOf( header not found'
}

# Anchor: closing of the LAST entry plus the listOf( closer. Unique in the file.
$anchor = '        ),' + $nl + '    )'
$hits = ([regex]::Matches($raw, [regex]::Escape($anchor))).Count
if ($hits -ne 1) { throw "expected exactly 1 insertion anchor, found $hits" }

$dot = [string][char]0x00B7   # middle dot used by existing displayNames; keep this .ps1 pure ASCII

function New-Entry {
    param($Id, $Disp, $File, [long]$Size, [int]$Steps, [string]$Cfg, [string]$Note1, [string]$Note2)
    $l = @(
        '        SdModelEntry(',
        ('            id = "' + $Id + '",'),
        ('            displayName = "' + $Disp + ' ' + $dot + ' ' + $Id.Split('-')[1].Replace('_0','').ToUpper() + '",'),
        ('            fileName = "' + $File + '",'),
        ('            url = "https://huggingface.co/samoudi/sd-turbo-gguf/resolve/main/' + $File + '",'),
        ('            sizeBytes = ' + $Size + 'L,'),
        '            baseSize = 512,',
        ('            defaultSteps = ' + $Steps + ','),
        ('            defaultCfg = ' + $Cfg + ','),
        ('            note = "' + $Note1 + '" +'),
        ('                "' + $Note2 + '",'),
        '        ),'
    )
    ,($l -join $nl)
}

$q4 = New-Entry -Id 'sdturbo-q4_0' -Disp 'Stable Diffusion Turbo' `
    -File 'sd_turbo_q4_0.gguf' -Size 1847909248 -Steps 4 -Cfg '1.0f' `
    -Note1 'Distilled SD1.5 turbo checkpoint: 4 steps, low CFG, ' `
    -Note2 '~4x faster than the 20-step entries. Source: samoudi/sd-turbo-gguf.'

$q8 = New-Entry -Id 'sdturbo-q8_0' -Disp 'Stable Diffusion Turbo' `
    -File 'sd_turbo_q8_0.gguf' -Size 2149136352 -Steps 4 -Cfg '1.0f' `
    -Note1 'Near-lossless turbo weights; same 4-step, low-CFG recipe ' `
    -Note2 'as Q4_0 for phones that can spare the extra ~300 MB.'

# Insert AFTER the last entry's closing '        ),' and before the listOf closer '    )'.
$replacement = '        ),' + $nl + $q4 + $nl + $q8 + $nl + '    )'
$new = $raw.Replace($anchor, $replacement)
if ($new -eq $raw) { throw 'replacement did not change text' }

[System.IO.File]::WriteAllText($path, $new, $enc)
Write-Host ('OK: inserted sdturbo-q4_0 + sdturbo-q8_0 (encoding BOM=' + $hasBom + ', newline=' + ($nl -replace "`r",'CR' -replace "`n",'LF') + ')')
