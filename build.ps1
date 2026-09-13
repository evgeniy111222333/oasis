# Custom build script for RPChat plugin
# Compiles and deploys the plugin using local JDK 25

$ErrorActionPreference = "Stop"

Write-Host "Cleaning target directories..." -ForegroundColor Cyan
if (Test-Path target) { Remove-Item -Recurse -Force target }
if (Test-Path plugins/RPChat.jar) { Remove-Item -Force plugins/RPChat.jar }

Write-Host "Creating target directories..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path target/classes | Out-Null

Write-Host "Finding Java source files..." -ForegroundColor Cyan
$javaFiles = Get-ChildItem -Recurse -Filter *.java src/main/java | Select-Object -ExpandProperty FullName
if ($javaFiles.Count -eq 0 -or $javaFiles -eq $null) {
    Write-Error "No Java source files found in src/main/java!"
    exit 1
}

Write-Host "Compiling Java sources..." -ForegroundColor Cyan
# Compile using local javac, passing all libraries and server jar in classpath
$jars = (Get-ChildItem -Recurse -Filter *.jar libraries).FullName -join ";"
$classpath = "versions/26.1.2/purpur-26.1.2.jar;$jars"
javac -encoding UTF-8 -cp $classpath -d target/classes $javaFiles

$testJavaFiles = Get-ChildItem -Recurse -Filter *.java src/test/java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
if ($testJavaFiles.Count -gt 0) {
    Write-Host "Compiling and running server core tests..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path target/test-classes | Out-Null
    javac -encoding UTF-8 -cp "target/classes;$classpath" -d target/test-classes $testJavaFiles
    if ($LASTEXITCODE -ne 0) { throw "Server test compilation failed" }
    java -ea -cp "target/classes;target/test-classes;$classpath" ua.rp.chat.microvoxel.MicrovoxelServerCoreTest
    if ($LASTEXITCODE -ne 0) { throw "Server core tests failed" }
}


Write-Host "Copying resources..." -ForegroundColor Cyan
Copy-Item -Recurse -Force src/main/resources/* target/classes/

Write-Host "Packaging into JAR..." -ForegroundColor Cyan
$jarExe = "C:\Program Files\Java\jdk-25.0.3\bin\jar.exe"
if (!(Test-Path $jarExe)) {
    Write-Error "jar.exe not found at $jarExe! Verify JDK installation path."
    exit 1
}
& $jarExe cvf target/RPChat.jar -C target/classes . | Out-Null

Write-Host "Deploying to server plugins/ directory..." -ForegroundColor Cyan
if (!(Test-Path plugins)) { New-Item -ItemType Directory -Path plugins | Out-Null }
Copy-Item target/RPChat.jar plugins/

Write-Host "Build Succeeded! Plugin deployed to plugins/RPChat.jar" -ForegroundColor Green
