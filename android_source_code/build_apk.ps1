$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OrigSrcDir = Join-Path $RootDir "app\src\main"
$StageDir = Join-Path $env:TEMP ("dsh-cloud-apk-stage-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$SrcDir = Join-Path $StageDir "main"
$BuildDir = Join-Path $StageDir "build"
$OutDir = Join-Path $RootDir "build-out"
$OutApk = Join-Path $OutDir "DSH-Cloud-V4.0.apk"
$StageOutApk = Join-Path $StageDir "DSH-Cloud-V4.0.apk"

function Resolve-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" }),
        "C:\Android\Sdk"
    ) | Where-Object { $_ -and (Test-Path $_) }
    $picked = $candidates | Select-Object -First 1
    if (-not $picked) {
        throw "Android SDK not found. Please set ANDROID_HOME or ANDROID_SDK_ROOT."
    }
    return $picked
}

function Resolve-JavaBin {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
        return (Join-Path $env:JAVA_HOME "bin")
    }
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCmd) {
        return (Split-Path -Parent $javacCmd.Source)
    }
    $fallback = @("C:\Program Files\Eclipse Adoptium\jdk-17*\bin", "C:\Program Files\Java\jdk-17*\bin")
    foreach ($pattern in $fallback) {
        $hit = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit -and (Test-Path (Join-Path $hit.FullName "javac.exe"))) {
            return $hit.FullName
        }
    }
    throw "JDK 17+ javac not found. Please set JAVA_HOME or add JDK bin to PATH."
}

function Read-SigningProperties {
    param([string]$Path)
    $props = @{}
    if (-not (Test-Path $Path)) { return $props }
    foreach ($line in (Get-Content $Path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -gt 0) {
            $k = $trimmed.Substring(0, $idx).Trim()
            $v = $trimmed.Substring($idx + 1).Trim()
            $props[$k] = $v
        }
    }
    return $props
}

$SdkDir = Resolve-AndroidSdk
$JavaBin = Resolve-JavaBin
$BuildTools = Get-ChildItem (Join-Path $SdkDir "build-tools") | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$PlatformJar = Get-ChildItem (Join-Path $SdkDir "platforms") -Filter "android.jar" -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName

if (-not $BuildTools -or -not $PlatformJar) {
    throw "Missing Android build-tools or android.jar inside SDK: $SdkDir"
}

$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$Javac = Join-Path $JavaBin "javac.exe"
$Keytool = Join-Path $JavaBin "keytool.exe"

Write-Host "Using BuildTools: $BuildTools"
Write-Host "Using PlatformJar: $PlatformJar"
Write-Host "Staging in temp path: $StageDir"

New-Item -ItemType Directory -Force -Path $StageDir, $OutDir, "$BuildDir\compiled-res", "$BuildDir\gen", "$BuildDir\classes", "$BuildDir\dex" | Out-Null
Copy-Item -Recurse -Force $OrigSrcDir $SrcDir

try {
    # 1. Compile & link resources
    & $Aapt2 compile --dir "$SrcDir\res" -o "$BuildDir\res.zip"
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

    & $Aapt2 link `
        -o "$BuildDir\unaligned-unsigned.apk" `
        --manifest "$SrcDir\AndroidManifest.xml" `
        -I $PlatformJar `
        --java "$BuildDir\gen" `
        --min-sdk-version 24 `
        --target-sdk-version 34 `
        --version-code 1 `
        --version-name "4.0.0" `
        -R "$BuildDir\res.zip" `
        --auto-add-overlay
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

    $Utf8NoBom = New-Object System.Text.UTF8Encoding $false

    # 2. Collect Java sources and compile
    $JavaFiles = @()
    $JavaFiles += Get-ChildItem "$BuildDir\gen" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
    $JavaFiles += Get-ChildItem "$SrcDir\java" -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
    $SourcesTxt = Join-Path $BuildDir "sources.txt"
    [System.IO.File]::WriteAllLines($SourcesTxt, ($JavaFiles | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }), $Utf8NoBom)

    & $Javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:deprecation -cp $PlatformJar -d "$BuildDir\classes" "@$SourcesTxt"
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    # 3. Dex classes
    $ClassFiles = Get-ChildItem "$BuildDir\classes" -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName
    $ClassesTxt = Join-Path $BuildDir "classes.txt"
    [System.IO.File]::WriteAllLines($ClassesTxt, ($ClassFiles | ForEach-Object { $_ -replace '\\', '/' }), $Utf8NoBom)

    & $D8 --min-api 24 --lib $PlatformJar --output "$BuildDir\dex" "@$ClassesTxt"
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    # 4. Inject classes.dex into APK zip without altering resources.arsc alignment/compression
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $UnalignedApk = Join-Path $BuildDir "unaligned-unsigned.apk"
    $Zip = [System.IO.Compression.ZipFile]::Open($UnalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, "$BuildDir\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    } finally {
        $Zip.Dispose()
    }

    # 5. Zipalign
    $AlignedUnsigned = Join-Path $BuildDir "aligned-unsigned.apk"
    & $ZipAlign -f -p 4 $UnalignedApk $AlignedUnsigned
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    # 6. Resolve signing keystore (env / signing.properties / ephemeral local debug keystore)
    $SigningProps = Read-SigningProperties (Join-Path $RootDir "signing.properties")
    $KeystorePath = if ($env:DSH_KEYSTORE_PATH) { $env:DSH_KEYSTORE_PATH } elseif ($SigningProps["keystorePath"]) { $SigningProps["keystorePath"] } else { "" }
    $KsPass = if ($env:DSH_KEYSTORE_PASS) { $env:DSH_KEYSTORE_PASS } elseif ($SigningProps["keystorePass"]) { $SigningProps["keystorePass"] } else { "" }
    $KeyAlias = if ($env:DSH_KEY_ALIAS) { $env:DSH_KEY_ALIAS } elseif ($SigningProps["keyAlias"]) { $SigningProps["keyAlias"] } else { "dshdebug" }
    $KeyPass = if ($env:DSH_KEY_PASS) { $env:DSH_KEY_PASS } elseif ($SigningProps["keyPass"]) { $SigningProps["keyPass"] } else { $KsPass }

    if ($KeystorePath -and (Test-Path $KeystorePath)) {
        $StageKs = Join-Path $StageDir "signing-input.jks"
        Copy-Item -Force $KeystorePath $StageKs
        $KeystoreToSign = $StageKs
    } else {
        Write-Host "No custom signing keystore provided; generating ephemeral debug keystore..."
        $KeystoreToSign = Join-Path $StageDir "debug-ephemeral.jks"
        $KsPass = [guid]::NewGuid().ToString("N")
        $KeyPass = $KsPass
        $KeyAlias = "dshdebug"
        & $Keytool -genkeypair `
            -keystore $KeystoreToSign `
            -storetype PKCS12 `
            -alias $KeyAlias `
            -keyalg RSA `
            -keysize 2048 `
            -validity 3650 `
            -storepass $KsPass `
            -keypass $KeyPass `
            -dname "CN=DSH Cloud Debug, OU=Community, O=DSH Cloud"
        if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    }

    # 7. Sign APK (v1 + v2 + v3)
    & $ApkSigner sign `
        --ks $KeystoreToSign `
        --ks-key-alias $KeyAlias `
        --ks-pass "pass:$KsPass" `
        --key-pass "pass:$KeyPass" `
        --v1-signing-enabled true `
        --v2-signing-enabled true `
        --v3-signing-enabled true `
        --out $StageOutApk `
        $AlignedUnsigned
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

    # 8. Verify alignment & APK signature
    & $ZipAlign -c -p 4 $StageOutApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign check failed" }

    & $ApkSigner verify --print-certs $StageOutApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

    Copy-Item -Force $StageOutApk $OutApk
    Write-Host "`nSUCCESS: Built signed APK -> $OutApk ($((Get-Item $OutApk).Length) bytes)"
} finally {
    if (Test-Path $StageDir) {
        Remove-Item -Recurse -Force $StageDir -ErrorAction SilentlyContinue
    }
}
