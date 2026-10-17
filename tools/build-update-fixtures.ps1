param([string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$fixtureDirectory = Join-Path ([IO.Path]::GetTempPath()) 'yomikomi-update-fixtures'
New-Item -ItemType Directory -Force -Path $fixtureDirectory | Out-Null
$otherKey = Join-Path $fixtureDirectory 'other.jks'
if (!(Test-Path -LiteralPath $otherKey)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $otherKey -storepass android -keypass android -alias other -keyalg RSA -keysize 2048 -validity 365 -dname 'CN=Disposable Update Test'
    if ($LASTEXITCODE -ne 0) { throw 'Test key generation failed' }
}
$variants = @(
    @{Name='fixture'; Package='io.github.yomikomi'; Code=5; Version='0.1.4'; Key='debug'},
    @{Name='wrong-signer'; Package='io.github.yomikomi'; Code=5; Version='0.1.4'; Key='other'},
    @{Name='wrong-package'; Package='io.github.anotherapp'; Code=5; Version='0.1.4'; Key='debug'},
    @{Name='old'; Package='io.github.yomikomi'; Code=1; Version='0.1.0'; Key='debug'}
)
foreach ($variant in $variants) {
    $manifest = @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$($variant.Package)" android:versionCode="$($variant.Code)" android:versionName="$($variant.Version)"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/><application android:hasCode="false" android:label="Yomikomi Update Test"/></manifest>
"@
    $manifestPath = Join-Path $fixtureDirectory "$($variant.Name)-manifest.xml"
    $manifest | Set-Content -LiteralPath $manifestPath -Encoding utf8
    $unsigned = Join-Path $fixtureDirectory "$($variant.Name)-unsigned.apk"
    $signed = Join-Path $fixtureDirectory "yomikomi-update-$($variant.Name).apk"
    & "$buildTools/aapt2.exe" link -I $androidJar --manifest $manifestPath -o $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Test APK packaging failed' }
    $keyPath = if ($variant.Key -eq 'debug') { Join-Path $env:USERPROFILE '.android/debug.keystore' } else { $otherKey }
    $keyAlias = if ($variant.Key -eq 'debug') { 'androiddebugkey' } else { 'other' }
    & "$buildTools/apksigner.bat" sign --ks $keyPath --ks-key-alias $keyAlias --ks-pass pass:android --key-pass pass:android --out $signed $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Test APK signing failed' }
    & $adb -s $Device push $signed "/data/local/tmp/yomikomi-update-$($variant.Name).apk"
    if ($LASTEXITCODE -ne 0) { throw 'Test APK upload failed' }
}
Write-Output 'Fixtures have no app code. Use only with UpdateDeviceTest; do not install them.'
