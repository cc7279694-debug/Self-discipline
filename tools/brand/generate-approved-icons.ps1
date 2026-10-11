[CmdletBinding()]
param(
    [string] $SourcePath = '',
    [string] $RuntimeOutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Drawing

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$brandDirectory = Join-Path $repositoryRoot 'docs\brand'
if ([string]::IsNullOrWhiteSpace($SourcePath)) {
    $SourcePath = Join-Path $brandDirectory 'mirra-approved-original.png'
}
$sourceFile = [IO.Path]::GetFullPath($SourcePath)
$expectedSourceHash = '71F4B8277F1F1A54215AAB9EADB9CC113EB14C2541176B93295EDF861A4759BE'
$sourceHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash
if ($sourceHash -ne $expectedSourceHash) {
    throw 'Source fingerprint differs from the user-confirmed original; do not substitute artwork.'
}

# Coordinates and thresholds belong to this fingerprinted source, not a new logo geometry.
$outlineMeanThreshold = 230.0
$monochromeMeanThreshold = 200.0
$edgePadding = 1
$adaptiveSafeRadiusDp = 31.0
$densities = @(
    @{ Name = 'mdpi'; Scale = 1.0; ForegroundPixels = 108; NotificationPixels = 24 },
    @{ Name = 'hdpi'; Scale = 1.5; ForegroundPixels = 162; NotificationPixels = 36 },
    @{ Name = 'xhdpi'; Scale = 2.0; ForegroundPixels = 216; NotificationPixels = 48 },
    @{ Name = 'xxhdpi'; Scale = 3.0; ForegroundPixels = 324; NotificationPixels = 72 },
    @{ Name = 'xxxhdpi'; Scale = 4.0; ForegroundPixels = 432; NotificationPixels = 96 }
)

function Get-BytesHash([byte[]] $Bytes) {
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hasher.ComputeHash($Bytes))).Replace('-', '') }
    finally { $hasher.Dispose() }
}

function ConvertTo-PngBytes([Drawing.Bitmap] $Bitmap) {
    $stream = [IO.MemoryStream]::new()
    try {
        $Bitmap.Save($stream, [Drawing.Imaging.ImageFormat]::Png)
        return ,$stream.ToArray()
    } finally { $stream.Dispose() }
}

function Save-DeterministicPng([Drawing.Bitmap] $Bitmap, [string] $Path) {
    $firstBytes = ConvertTo-PngBytes $Bitmap
    $secondBytes = ConvertTo-PngBytes $Bitmap
    $firstHash = Get-BytesHash $firstBytes
    if ($firstHash -ne (Get-BytesHash $secondBytes)) {
        throw 'PNG encoding was not deterministic in this runtime.'
    }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllBytes($Path, $firstBytes)
    return $firstHash
}

function Get-AlphaGeometry([Drawing.Bitmap] $Bitmap, [double] $DensityScale = 1.0) {
    $centerX = $Bitmap.Width / 2.0
    $centerY = $Bitmap.Height / 2.0
    $maximumRadius = 0.0
    $opaquePixels = 0
    $visiblePixels = 0
    for ($y = 0; $y -lt $Bitmap.Height; $y++) {
        for ($x = 0; $x -lt $Bitmap.Width; $x++) {
            $alpha = $Bitmap.GetPixel($x, $y).A
            if ($alpha -eq 0) { continue }
            $visiblePixels++
            if ($alpha -eq 255) { $opaquePixels++ }
            $dx = $x + 0.5 - $centerX
            $dy = $y + 0.5 - $centerY
            $maximumRadius = [Math]::Max($maximumRadius, [Math]::Sqrt($dx * $dx + $dy * $dy))
        }
    }
    if ($opaquePixels -eq 0) { throw 'Artwork lost all opaque source pixels.' }
    for ($position = 0; $position -lt $Bitmap.Width; $position++) {
        if ($Bitmap.GetPixel($position, 0).A -ne 0 -or
            $Bitmap.GetPixel($position, $Bitmap.Height - 1).A -ne 0 -or
            $Bitmap.GetPixel(0, $position).A -ne 0 -or
            $Bitmap.GetPixel($Bitmap.Width - 1, $position).A -ne 0) {
            throw 'Runtime layer must have transparent edges.'
        }
    }
    return [pscustomobject]@{
        MaximumRadiusDp = $maximumRadius / $DensityScale
        VisiblePixels = $visiblePixels
        OpaquePixels = $opaquePixels
    }
}

function Set-SingleWhiteInk([Drawing.Bitmap] $Bitmap) {
    # GDI+ bicubic interpolation can introduce non-white straight RGB at translucent edges.
    # Monochrome assets carry geometry only: preserve every resampled alpha, normalize RGB.
    # Do not call this for the approved full-color source artwork.
    for ($y = 0; $y -lt $Bitmap.Height; $y++) {
        for ($x = 0; $x -lt $Bitmap.Width; $x++) {
            $alpha = $Bitmap.GetPixel($x, $y).A
            $Bitmap.SetPixel($x, $y, [Drawing.Color]::FromArgb($alpha, 255, 255, 255))
            $pixel = $Bitmap.GetPixel($x, $y)
            if ($pixel.A -ne $alpha -or ($alpha -gt 0 -and
                ($pixel.R -ne 255 -or $pixel.G -ne 255 -or $pixel.B -ne 255))) {
                throw 'Single-ink normalization changed alpha or retained a second RGB ink.'
            }
        }
    }
}

function New-BrandLayer([Drawing.Bitmap] $Master, [int] $Size, [double] $SourcePixelsToDp, [double] $DensityScale) {
    $layer = [Drawing.Bitmap]::new($Size, $Size, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($layer)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.Clear([Drawing.Color]::Transparent)
        $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $width = [single]($Master.Width * $SourcePixelsToDp * $DensityScale)
        $height = [single]($Master.Height * $SourcePixelsToDp * $DensityScale)
        $left = [single](($Size - $width) / 2.0)
        $top = [single](($Size - $height) / 2.0)
        $destinationPoints = [Drawing.PointF[]]@(
            [Drawing.PointF]::new($left, $top),
            [Drawing.PointF]::new($left + $width, $top),
            [Drawing.PointF]::new($left, $top + $height)
        )
        $sourceRectangle = [Drawing.RectangleF]::new(0.0, 0.0, [single]$Master.Width, [single]$Master.Height)
        $graphics.DrawImage($Master, $destinationPoints, $sourceRectangle, [Drawing.GraphicsUnit]::Pixel, $attributes)
        return ,$layer
    } catch {
        $layer.Dispose()
        throw
    } finally {
        $attributes.Dispose()
        $graphics.Dispose()
    }
}

$source = [Drawing.Bitmap]::new($sourceFile)
$master = $null
$monochromeMaster = $null
$renderedLayers = [Collections.Generic.List[object]]::new()
try {
    if ($source.Width -ne 1254 -or $source.Height -ne 1254) { throw 'Unexpected approved source dimensions.' }
    $backgroundSample = $source.GetPixel(200, 200)
    if ($backgroundSample.R -ne 253 -or $backgroundSample.G -ne 253 -or $backgroundSample.B -ne 248) {
        throw 'Unexpected source background sample.'
    }

    # Find the existing dark outer boundary in the source's recorded central artwork area.
    $rows = [Collections.Generic.List[object]]::new()
    for ($y = 350; $y -le 870; $y++) {
        $left = -1
        $right = -1
        for ($x = 400; $x -le 850; $x++) {
            $pixel = $source.GetPixel($x, $y)
            if (($pixel.R + $pixel.G + $pixel.B) / 3.0 -lt $outlineMeanThreshold) {
                if ($left -lt 0) { $left = $x }
                $right = $x
            }
        }
        if ($left -ge 0) { $rows.Add([pscustomobject]@{ Y = $y; Left = $left; Right = $right }) }
    }
    $top = $rows[0].Y
    $bottom = $rows[$rows.Count - 1].Y
    $globalLeft = [int](($rows | Measure-Object -Property Left -Minimum).Minimum)
    $globalRight = [int](($rows | Measure-Object -Property Right -Maximum).Maximum)
    if ($top -ne 372 -or $bottom -ne 858 -or $globalLeft -ne 437 -or $globalRight -ne 816 -or $rows.Count -ne 487) {
        throw 'Source-derived boundary no longer matches the recorded original.'
    }
    $straightSideStart = ($rows | Where-Object { $_.Left -eq $globalLeft -and $_.Right -eq $globalRight } | Select-Object -First 1).Y
    $cropLeft = $globalLeft - $edgePadding
    $cropTop = $top - $edgePadding
    $cropWidth = $globalRight - $globalLeft + 1 + 2 * $edgePadding
    $cropHeight = $bottom - $top + 1 + 2 * $edgePadding
    $master = [Drawing.Bitmap]::new($cropWidth, $cropHeight, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $monochromeMaster = [Drawing.Bitmap]::new($cropWidth, $cropHeight, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $preservedPixelCount = 0

    for ($localY = 0; $localY -lt $cropHeight; $localY++) {
        $sourceY = $cropTop + $localY
        $rowIndex = [Math]::Max(0, [Math]::Min($rows.Count - 1, $sourceY - $top))
        $row = $rows[$rowIndex]
        # Below the widest arch, the original straight sides enclose the bright bottom light.
        # A brightness flood-fill would erase that light; keep the complete source interval.
        $rowLeft = if ($sourceY -ge $straightSideStart) { $globalLeft } else { $row.Left }
        $rowRight = if ($sourceY -ge $straightSideStart) { $globalRight } else { $row.Right }
        for ($localX = 0; $localX -lt $cropWidth; $localX++) {
            $sourceX = $cropLeft + $localX
            $originalPixel = $source.GetPixel($sourceX, $sourceY)
            $inside = $sourceX -ge ($rowLeft - $edgePadding) -and $sourceX -le ($rowRight + $edgePadding)
            $alpha = if ($inside) { 255 } else { 0 }
            $master.SetPixel($localX, $localY, [Drawing.Color]::FromArgb($alpha, $originalPixel.R, $originalPixel.G, $originalPixel.B))
            if ($inside) {
                $preservedPixel = $master.GetPixel($localX, $localY)
                if ($preservedPixel.R -ne $originalPixel.R -or $preservedPixel.G -ne $originalPixel.G -or $preservedPixel.B -ne $originalPixel.B) {
                    throw 'Exterior-only extraction changed an original RGB pixel.'
                }
                $preservedPixelCount++
            }
            $monoAlpha = if ($inside -and (($originalPixel.R + $originalPixel.G + $originalPixel.B) / 3.0 -lt $monochromeMeanThreshold)) { 255 } else { 0 }
            $monochromeMaster.SetPixel($localX, $localY, [Drawing.Color]::FromArgb($monoAlpha, 255, 255, 255))
        }
    }
    if ($master.GetPixel(800 - $cropLeft, 850 - $cropTop).A -ne 255) { throw 'The original bottom light was lost.' }
    if ($monochromeMaster.GetPixel(627 - $cropLeft, 615 - $cropTop).A -ne 0) { throw 'Monochrome must preserve source door negative space.' }

    $masterPath = Join-Path $brandDirectory 'mirra-approved-foreground-master.png'
    $monoPath = Join-Path $brandDirectory 'mirra-approved-monochrome-master.png'
    $masterHash = Save-DeterministicPng $master $masterPath
    $monochromeHash = Save-DeterministicPng $monochromeMaster $monoPath
    # Reload the saved master: verify encoded PNG preserves every retained original RGB byte too.
    $savedMaster = [Drawing.Bitmap]::new($masterPath)
    try {
        for ($y = 0; $y -lt $savedMaster.Height; $y++) {
            for ($x = 0; $x -lt $savedMaster.Width; $x++) {
                $pixel = $savedMaster.GetPixel($x, $y)
                if ($pixel.A -eq 0) { continue }
                $originalPixel = $source.GetPixel($cropLeft + $x, $cropTop + $y)
                if ($pixel.A -ne 255 -or $pixel.R -ne $originalPixel.R -or $pixel.G -ne $originalPixel.G -or $pixel.B -ne $originalPixel.B) {
                    throw 'Encoded master changed a retained original source pixel.'
                }
            }
        }
    } finally { $savedMaster.Dispose() }

    $masterRadius = [Math]::Sqrt([Math]::Pow($cropWidth / 2.0, 2) + [Math]::Pow($cropHeight / 2.0, 2))
    $foregroundScale = 29.5 / $masterRadius
    $notificationScale = 10.0 / $masterRadius
    # All density layers share one dp geometry. Check actual filtered alpha, not a rectangle estimate.
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        foreach ($entry in $renderedLayers) { $entry.Bitmap.Dispose() }
        $renderedLayers.Clear()
        $maximumForegroundRadius = 0.0
        $maximumNotificationRadius = 0.0
        foreach ($density in $densities) {
            foreach ($kind in @('foreground', 'monochrome', 'notification')) {
                $isNotification = $kind -eq 'notification'
                $imageMaster = if ($kind -eq 'foreground') { $master } else { $monochromeMaster }
                $size = if ($isNotification) { $density.NotificationPixels } else { $density.ForegroundPixels }
                $scale = if ($isNotification) { $notificationScale } else { $foregroundScale }
                $layer = New-BrandLayer $imageMaster $size $scale $density.Scale
                if ($kind -ne 'foreground') { Set-SingleWhiteInk $layer }
                $geometry = Get-AlphaGeometry $layer $density.Scale
                $renderedLayers.Add([pscustomobject]@{ Kind = $kind; Density = $density.Name; Size = $size; Bitmap = $layer; Geometry = $geometry })
                if ($isNotification) { $maximumNotificationRadius = [Math]::Max($maximumNotificationRadius, $geometry.MaximumRadiusDp) }
                else { $maximumForegroundRadius = [Math]::Max($maximumForegroundRadius, $geometry.MaximumRadiusDp) }
            }
        }
        if ($maximumForegroundRadius -le $adaptiveSafeRadiusDp -and $maximumNotificationRadius -le 11.0) { break }
        if ($maximumForegroundRadius -gt $adaptiveSafeRadiusDp) { $foregroundScale *= 30.9 / $maximumForegroundRadius }
        if ($maximumNotificationRadius -gt 11.0) { $notificationScale *= 10.9 / $maximumNotificationRadius }
    }
    if ($maximumForegroundRadius -gt $adaptiveSafeRadiusDp -or $maximumNotificationRadius -gt 11.0) { throw 'Filtered artwork escaped its safe geometry.' }

    $runtimeAssets = [Collections.Generic.List[object]]::new()
    foreach ($entry in $renderedLayers) {
        $resourceName = switch ($entry.Kind) { 'foreground' { 'ic_mirra_foreground.png' }; 'monochrome' { 'mirra_approved_monochrome.png' }; 'notification' { 'mirra_approved_notification.png' } }
        $relativeName = 'drawable-' + $entry.Density + '/' + $resourceName
        $bytes = ConvertTo-PngBytes $entry.Bitmap
        $assetHash = Get-BytesHash $bytes
        if ($assetHash -ne (Get-BytesHash (ConvertTo-PngBytes $entry.Bitmap))) { throw 'Runtime PNG encoding was not deterministic.' }
        if (-not [string]::IsNullOrWhiteSpace($RuntimeOutputDirectory)) {
            $destinationPath = Join-Path ([IO.Path]::GetFullPath($RuntimeOutputDirectory)) $relativeName
            [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destinationPath))
            [IO.File]::WriteAllBytes($destinationPath, $bytes)
        }
        $runtimeAssets.Add([ordered]@{ resource = $relativeName; pixels = $entry.Size; sha256 = $assetHash; maximumAlphaRadiusDp = [Math]::Round($entry.Geometry.MaximumRadiusDp, 6) })
    }

    $manifest = [ordered]@{
        generatorVersion = 3
        source = 'mirra-approved-original.png'
        sourceSha256 = $sourceHash
        sourcePixels = @(1254, 1254)
        backgroundSample = [ordered]@{ sourceX = 200; sourceY = 200; rgb = '#FDFDF8' }
        outline = [ordered]@{ meanRgbThresholdExclusive = $outlineMeanThreshold; darkBoundsInclusive = @($globalLeft, $top, $globalRight, $bottom); straightSidesStartY = $straightSideStart; exteriorPaddingPixels = $edgePadding }
        master = [ordered]@{ file = 'mirra-approved-foreground-master.png'; sha256 = $masterHash; crop = @($cropLeft, $cropTop, $cropWidth, $cropHeight); retainedRgbPixelsVerified = $preservedPixelCount; bottomLightAnchor = @(800, 850) }
        monochrome = [ordered]@{ file = 'mirra-approved-monochrome-master.png'; sha256 = $monochromeHash; meanRgbThresholdExclusive = $monochromeMeanThreshold; ink = '#FFFFFF'; negativeSpaceSourceAnchor = @(627, 615) }
        adaptive = [ordered]@{ canvasDp = 108; centerDp = @(54, 54); safeAlphaRadiusDp = $adaptiveSafeRadiusDp; sourcePixelsToDp = $foregroundScale; maximumMeasuredAlphaRadiusDp = [Math]::Round($maximumForegroundRadius, 6) }
        notification = [ordered]@{ canvasDp = 24; centerDp = @(12, 12); sourcePixelsToDp = $notificationScale; maximumMeasuredAlphaRadiusDp = [Math]::Round($maximumNotificationRadius, 6) }
        runtimeAssets = @($runtimeAssets.ToArray())
    }
    $manifestJson = ($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine
    [IO.File]::WriteAllText((Join-Path $brandDirectory 'approved-original-manifest.json'), $manifestJson, [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{
        SourceSha256 = $sourceHash
        ForegroundMasterSha256 = $masterHash
        MonochromeMasterSha256 = $monochromeHash
        PreservedSourceRgbPixels = $preservedPixelCount
        AdaptiveMaximumAlphaRadiusDp = [Math]::Round($maximumForegroundRadius, 6)
        RuntimeAssetsWritten = -not [string]::IsNullOrWhiteSpace($RuntimeOutputDirectory)
        RuntimeAssetCount = $runtimeAssets.Count
    }
} finally {
    foreach ($entry in $renderedLayers) { $entry.Bitmap.Dispose() }
    if ($null -ne $monochromeMaster) { $monochromeMaster.Dispose() }
    if ($null -ne $master) { $master.Dispose() }
    $source.Dispose()
}
