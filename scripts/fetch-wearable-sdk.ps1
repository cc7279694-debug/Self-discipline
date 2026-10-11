$ErrorActionPreference = 'Stop'

# Download only the binary published in Xiaomi's official Interconnect appendix.
# It is retained locally because the ZIP supplies no redistribution license.
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$vendorDirectory = Join-Path $repositoryRoot '.local\vendor'
$archivePath = Join-Path $vendorDirectory 'interconnect_dev_test_demo.zip'
$aarPath = Join-Path $vendorDirectory 'xms-wearable-lib_1.4_release.aar'
$archiveSha256 = '8E3D74EEBDA558E2BEF45E32F0965C5B77EF939A06CDC55136929F9427A95CA7'
$aarSha256 = '9C40FD1C5409BB948523D503AF71E2978AE522C35636AFE7D64F474C0F6BC195'
New-Item -ItemType Directory -Path $vendorDirectory -Force | Out-Null

if (Test-Path -LiteralPath $aarPath) {
    if ((Get-FileHash -LiteralPath $aarPath -Algorithm SHA256).Hash -eq $aarSha256) {
        Write-Output "Verified official wearable SDK: $aarPath"
        exit 0
    }
    throw 'Existing wearable AAR differs from the inspected official checksum. Preserve and inspect it.'
}

Invoke-WebRequest -Uri 'https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip' -OutFile $archivePath
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $archiveSha256) {
    throw 'Official archive changed. Review it before extracting or building.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    # Never execute demo scripts or unpack the archive's sample signing materials.
    $entry = $archive.GetEntry('interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar')
    if ($null -eq $entry -or $entry.Length -ne 102453) { throw 'Expected official AAR missing.' }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $aarPath, $false)
} finally { $archive.Dispose() }
if ((Get-FileHash -LiteralPath $aarPath -Algorithm SHA256).Hash -ne $aarSha256) {
    throw 'Extracted AAR checksum mismatch.'
}
Write-Output "Verified official wearable SDK: $aarPath"
