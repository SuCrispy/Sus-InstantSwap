@echo off
setlocal

set DIR=%~dp0
java -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
