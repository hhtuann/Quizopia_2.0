@echo off
setlocal
set "MAVEN_VERSION=3.9.11"
set "WRAPPER_CACHE=%USERPROFILE%\.m2\wrapper\dists"
set "MAVEN_HOME=%WRAPPER_CACHE%\apache-maven-%MAVEN_VERSION%"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $d = '%WRAPPER_CACHE%'; New-Item -ItemType Directory -Force -Path $d | Out-Null; $z = Join-Path $d 'apache-maven-%MAVEN_VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile $z; Expand-Archive -Force $z $d"
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
endlocal
