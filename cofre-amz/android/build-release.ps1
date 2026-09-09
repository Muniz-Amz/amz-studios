param(
    [Parameter(Mandatory=$true)][string]$Gradle,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$SigningDirectory = "$env:USERPROFILE\.android\cofre-amz-signing"
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
$keyPath = Join-Path $SigningDirectory 'cofre-amz-release.p12'
$passwordPath = Join-Path $SigningDirectory 'password.dpapi.xml'
if (-not (Test-Path -LiteralPath $keyPath)) {
    if (Test-Path -LiteralPath $passwordPath) { throw 'A senha já existe sem a chave. Restaure a chave de assinatura antes de continuar.' }
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secret = [Convert]::ToBase64String($bytes)
    ConvertTo-SecureString -String $secret -AsPlainText -Force | Export-Clixml -LiteralPath $passwordPath
    $env:COFRE_STORE_PASSWORD = $secret
    $env:COFRE_KEY_PASSWORD = $secret
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $keyPath -storetype PKCS12 -storepass:env COFRE_STORE_PASSWORD -keypass:env COFRE_KEY_PASSWORD -alias cofre-amz -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=AMZ Studios, OU=Cofre AMZ, O=AMZ Studios, C=BR'
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao criar a chave de assinatura.' }
}
try {
    $storedSecret = Import-Clixml -LiteralPath $passwordPath
    $credential = New-Object System.Management.Automation.PSCredential('cofre-amz', $storedSecret)
    $env:COFRE_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:COFRE_KEY_PASSWORD = $env:COFRE_STORE_PASSWORD
    $env:COFRE_KEYSTORE = $keyPath
    Push-Location $PSScriptRoot
    try {
        & $Gradle :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
        if ($LASTEXITCODE -ne 0) { throw 'A compilação ou validação falhou.' }
    } finally { Pop-Location }
} finally {
    $env:COFRE_STORE_PASSWORD = $null
    $env:COFRE_KEY_PASSWORD = $null
    $env:COFRE_KEYSTORE = $null
    $secret = $null
    $credential = $null
}
