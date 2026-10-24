# Builds out\uvp-renderer.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: .\build.ps1 [-Install]   (-Install pushes it to the adb-connected phone)
param([switch]$Install)
$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\34.0.0"
$androidJar = "$sdk\platforms\android-19\android.jar"
$root = $PSScriptRoot
$out = "$root\out"

function Check($what) { if ($LASTEXITCODE) { throw "$what failed ($LASTEXITCODE)" } }

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest "$root\AndroidManifest.xml"; Check 'aapt2'

$src = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object FullName
# android-19's android.jar lacks java.lang.invoke; the stubs let javac emit lambdas, which d8 then desugars.
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$androidJar;$bt\core-lambda-stubs.jar" -d "$out\classes" @src; Check 'javac'

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 19 --lib $androidJar --output "$out\dex" @classes; Check 'd8'

Copy-Item "$out\base.apk" "$out\unsigned.apk"
jar -uMf "$out\unsigned.apk" -C "$out\dex" classes.dex; Check 'jar'
& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"; Check 'zipalign'

# Kept next to the sources: updates must be signed with the same key to install over the old version.
$ks = "$root\release.keystore"
if (-not (Test-Path $ks)) {
    keytool -genkeypair -keystore $ks -storepass uvprenderer -keypass uvprenderer -alias uvp `
        -keyalg RSA -keysize 2048 -validity 20000 -dname 'CN=UVP Renderer'; Check 'keytool'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:uvprenderer --out "$out\uvp-renderer.apk" "$out\aligned.apk"; Check 'apksigner'
Write-Host "Built $out\uvp-renderer.apk"

if ($Install) { adb install -r "$out\uvp-renderer.apk"; Check 'adb install' }
