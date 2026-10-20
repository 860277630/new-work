@echo off
setlocal
node "%~dp0scripts\setup-idphoto.js"
exit /b %errorlevel%
