#Requires -Version 5.1
<#
.SYNOPSIS
Builds and verifies the paired GitHub/Play phone release, then copies signed
artifacts and versioned SHA256SUMS.txt into the repository's builds directory.
Nothing is uploaded. Existing artifacts with different bytes are never replaced.
.PARAMETER SdkPath
Optional Android SDK directory; otherwise uses ANDROID_HOME, ANDROID_SDK_ROOT,
or android-v2/local.properties. Requires installed Android build-tools and a JDK.
#>
[CmdletBinding()]
param([string]$SdkPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $androidRoot
$outputDirectory = Join-Path $repositoryRoot 'builds'

function Invoke-CheckedTool {
    param([string]$Executable, [string[]]$Arguments)
    # Some JDK tools send successful informational output to stderr.
    $ErrorActionPreference = 'Continue'
    $result = & $Executable @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exitCode -ne 0) {
        throw "$(Split-Path -Leaf $Executable) failed (exit $exitCode): $($result -join [Environment]::NewLine)"
    }
    return ($result -join [Environment]::NewLine)
}

function Resolve-JdkTool {
    param([string]$Name)
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/$Name.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "JDK tool $Name is required. Set JAVA_HOME to the JDK used to build Android."
}

$releaseProperties = Get-Content -LiteralPath (Join-Path $androidRoot 'phone-release.properties') -Raw
$versionMatch = [regex]::Match($releaseProperties, '(?m)^versionName\s*=\s*(\d+\.\d+\.\d+)\s*$')
$codeMatch = [regex]::Match($releaseProperties, '(?m)^versionCode\s*=\s*(\d+)\s*$')
if (!$versionMatch.Success -or !$codeMatch.Success) { throw 'Invalid phone-release.properties.' }
$versionName = $versionMatch.Groups[1].Value
$versionCode = [int]$codeMatch.Groups[1].Value

if (!$SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (!$SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (!$SdkPath) {
    $localProperties = Join-Path $androidRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkMatch = [regex]::Match((Get-Content -LiteralPath $localProperties -Raw), '(?m)^sdk\.dir\s*=\s*(.+)$')
        if ($sdkMatch.Success) {
            $SdkPath = [regex]::Replace($sdkMatch.Groups[1].Value.Trim(), '\\([\\: =])', '$1')
        }
    }
}
if (!$SdkPath -or !(Test-Path -LiteralPath $SdkPath -PathType Container)) {
    throw 'Android SDK not found. Supply -SdkPath or configure ANDROID_HOME/local.properties.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe'))
    } | Select-Object -First 1
if (!$buildTools) { throw 'Installed Android build-tools with apksigner and aapt2 are required.' }
$apkSigner = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt2.exe'
$jarSigner = Resolve-JdkTool 'jarsigner'
$keyTool = Resolve-JdkTool 'keytool'

Push-Location $androidRoot
try {
    # This task includes both channels' tests/lint and the Play/Auto boundary checks.
    & (Join-Path $androidRoot 'gradlew.bat') ':app:assemblePhoneReleasePair' '--no-daemon' '--max-workers=2' '--no-parallel'
    if ($LASTEXITCODE -ne 0) { throw "Paired release build failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}

function Test-ReleaseApk {
    param([string]$Flavor, [string]$ExpectedId, [bool]$RequiresAuto)
    $apkDirectory = Join-Path $androidRoot "app/build/outputs/apk/$Flavor/release"
    $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne $ExpectedId -or $metadata.variantName -ne "${Flavor}Release") {
        throw "$Flavor APK metadata has the wrong application ID or variant."
    }
    if (@($metadata.elements).Count -ne 1) { throw "$Flavor requires one universal release APK." }
    $element = $metadata.elements[0]
    if ($element.versionName -ne $versionName -or $element.versionCode -ne $versionCode) {
        throw "$Flavor APK does not match phone-release.properties."
    }
    if ([IO.Path]::GetFileName($element.outputFile) -ne $element.outputFile) {
        throw "$Flavor APK metadata has an unexpected output path."
    }
    $apk = Join-Path $apkDirectory $element.outputFile
    $buildConfig = Get-Content -LiteralPath (Join-Path $androidRoot "app/build/generated/source/buildConfig/$Flavor/release/com/icy/lyrics/BuildConfig.java") -Raw
    if ($buildConfig -notmatch 'TEST_BUILD_LABEL\s*=\s*""\s*;' -or
        $buildConfig -notmatch 'boolean DEBUG\s*=\s*false\s*;') {
        throw "$Flavor is labelled as a test/debug build; clear icyTestBuildLabel before packaging."
    }
    $badging = Invoke-CheckedTool $aapt @('dump', 'badging', $apk)
    $package = [regex]::Match($badging, "(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
    if (!$package.Success -or $package.Groups[1].Value -ne $ExpectedId -or
        $package.Groups[2].Value -ne "$versionCode" -or $package.Groups[3].Value -ne $versionName -or
        $badging -match '(?m)^application-debuggable') {
        throw "$Flavor APK manifest version, identity, or debuggable state is invalid."
    }
    $manifest = Invoke-CheckedTool $aapt @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $apk)
    $autoMarkers = @('com.google.android.gms.car.application',
        'android.intent.category.CAR_LAUNCHER', 'com.icy.lyrics.androidauto.IcyLyricsCarMediaService')
    foreach ($marker in $autoMarkers) {
        if ($manifest.Contains($marker) -ne $RequiresAuto) {
            throw "$Flavor Android Auto boundary failed for $marker."
        }
    }
    $signature = Invoke-CheckedTool $apkSigner @('verify', '--verbose', '--print-certs', $apk)
    if ($signature -match '(?i)CN\s*=\s*Android Debug') { throw "$Flavor is signed with an Android debug certificate." }
    $certificates = [regex]::Matches($signature, '(?m)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$')
    if ($certificates.Count -ne 1) { throw "$Flavor APK must have one verified release signer." }
    return [pscustomobject]@{
        Path = $apk
        Certificate = $certificates[0].Groups[1].Value.ToUpperInvariant()
    }
}

$github = Test-ReleaseApk 'github' 'com.icy.lyrics.github' $true
$play = Test-ReleaseApk 'play' 'com.icy.lyrics' $false
if ($github.Certificate -ne $play.Certificate) { throw 'GitHub and Play APKs have different signing certificates.' }

$bundle = Join-Path $androidRoot 'app/build/outputs/bundle/playRelease/app-play-release.aab'
if (!(Test-Path -LiteralPath $bundle -PathType Leaf)) { throw 'Paired build did not produce a Play AAB.' }
$jarVerification = Invoke-CheckedTool $jarSigner @('-J-Duser.language=en', '-J-Duser.country=US', '-verify', $bundle)
# Release upload keys are often self-signed; trust-chain warnings are not signature failures.
if ($jarVerification -notmatch 'jar verified\.' -or $jarVerification -match '(?i)unsigned entries|jar is unsigned') {
    throw 'Play AAB is unsigned or contains unverified entries.'
}
$bundleCertificate = Invoke-CheckedTool $keyTool @('-J-Duser.language=en', '-J-Duser.country=US', '-printcert', '-jarfile', $bundle)
$bundleDigest = [regex]::Match($bundleCertificate, '(?m)^\s*SHA256:\s*([0-9A-Fa-f:]+)\s*$')
if (!$bundleDigest.Success -or $bundleCertificate -match '(?i)CN\s*=\s*Android Debug' -or
    $bundleDigest.Groups[1].Value.Replace(':', '').ToUpperInvariant() -ne $play.Certificate) {
    throw 'Play AAB must use the same release signing certificate as the APKs.'
}

$prefix = "Icy-Lyrics-Android-v$versionName-build$versionCode"
$artifacts = @(
    [pscustomobject]@{ Source = $github.Path; Name = "$prefix-GitHub.apk" },
    [pscustomobject]@{ Source = $play.Path; Name = "$prefix-Play.apk" },
    [pscustomobject]@{ Source = $bundle; Name = "$prefix-Play.aab" }
)
$checksumLines = @()
foreach ($artifact in $artifacts) {
    $hash = (Get-FileHash -LiteralPath $artifact.Source -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumLines += "$hash  $($artifact.Name)"
    $destination = Join-Path $outputDirectory $artifact.Name
    if ((Test-Path -LiteralPath $destination) -and
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash) {
        throw "Refusing to replace different existing artifact: $destination. Increment the release version/code."
    }
}
$checksumPath = Join-Path $outputDirectory "$prefix-SHA256SUMS.txt"
$checksumText = ($checksumLines -join "`n") + "`n"
if ((Test-Path -LiteralPath $checksumPath) -and
    (Get-Content -LiteralPath $checksumPath -Raw) -cne $checksumText) {
    throw "Refusing to replace different existing checksums: $checksumPath."
}

[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
foreach ($artifact in $artifacts) {
    $destination = Join-Path $outputDirectory $artifact.Name
    if (!(Test-Path -LiteralPath $destination)) { [IO.File]::Copy($artifact.Source, $destination, $false) }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $artifact.Source -Algorithm SHA256).Hash) {
        throw "Copied artifact checksum mismatch: $destination"
    }
    Write-Output $destination
}
if (!(Test-Path -LiteralPath $checksumPath)) {
    $stream = [IO.File]::Open($checksumPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($checksumText)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}
Write-Output $checksumPath
Write-Output "Verified phone release pair $versionName ($versionCode). Nothing was uploaded."
