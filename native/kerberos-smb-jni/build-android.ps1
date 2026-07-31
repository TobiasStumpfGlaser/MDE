param(
    [string]$NdkVersion = "28.2.13676358"
)

$ErrorActionPreference = "Stop"

$toolRoot = Join-Path $env:LOCALAPPDATA "CodexTools\kerberos-smb-rust"
$cargoHome = Join-Path $toolRoot "cargo"
$rustupHome = Join-Path $toolRoot "rustup"
$fallbackCargo = Join-Path $cargoHome "bin\cargo.exe"

$cargoCommand = Get-Command cargo -ErrorAction SilentlyContinue
if ($cargoCommand) {
    $cargo = $cargoCommand.Source
    $rustup = (Get-Command rustup -ErrorAction Stop).Source
} elseif (Test-Path $fallbackCargo) {
    $env:CARGO_HOME = $cargoHome
    $env:RUSTUP_HOME = $rustupHome
    $cargo = $fallbackCargo
    $rustup = Join-Path $cargoHome "bin\rustup.exe"
} else {
    throw "Rust/Cargo wurde nicht gefunden. Bitte zuerst rustup installieren."
}

$ndkCandidates = @(@(
    $env:ANDROID_NDK_HOME,
    $env:ANDROID_NDK_ROOT,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\$NdkVersion")
) | Where-Object { $_ -and (Test-Path $_) })

if ($ndkCandidates.Count -eq 0) {
    throw "Android NDK $NdkVersion wurde nicht gefunden."
}

$ndkRoot = [System.IO.Path]::GetFullPath($ndkCandidates[0])
$toolchainBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$jniLibsRoot = Join-Path $repositoryRoot "app\src\main\jniLibs"

$targets = @(
    @{
        Rust = "x86_64-linux-android"
        Abi = "x86_64"
        Linker = "x86_64-linux-android24-clang.cmd"
        Environment = "X86_64_LINUX_ANDROID"
    },
    @{
        Rust = "aarch64-linux-android"
        Abi = "arm64-v8a"
        Linker = "aarch64-linux-android24-clang.cmd"
        Environment = "AARCH64_LINUX_ANDROID"
    },
    @{
        Rust = "armv7-linux-androideabi"
        Abi = "armeabi-v7a"
        Linker = "armv7a-linux-androideabi24-clang.cmd"
        Environment = "ARMV7_LINUX_ANDROIDEABI"
    }
)

Push-Location $PSScriptRoot
try {
    foreach ($target in $targets) {
        & $rustup target add $target.Rust
        if ($LASTEXITCODE -ne 0) {
            throw "Rust-Ziel $($target.Rust) konnte nicht installiert werden."
        }

        $linker = Join-Path $toolchainBin $target.Linker
        if (!(Test-Path $linker)) {
            throw "NDK-Linker wurde nicht gefunden: $linker"
        }

        [Environment]::SetEnvironmentVariable(
            "CARGO_TARGET_$($target.Environment)_LINKER",
            $linker,
            "Process"
        )
        [Environment]::SetEnvironmentVariable(
            "CC_$($target.Environment)",
            $linker,
            "Process"
        )

        & $cargo build --locked --release --target $target.Rust
        if ($LASTEXITCODE -ne 0) {
            throw "Native Build für $($target.Abi) fehlgeschlagen."
        }

        $source = Join-Path $PSScriptRoot "target\$($target.Rust)\release\libmde_kerberos_smb.so"
        $destinationDirectory = Join-Path $jniLibsRoot $target.Abi
        New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
        Copy-Item -LiteralPath $source -Destination (
            Join-Path $destinationDirectory "libmde_kerberos_smb.so"
        ) -Force
    }
} finally {
    Pop-Location
}

Write-Output "Native Bibliotheken wurden nach $jniLibsRoot kopiert."
