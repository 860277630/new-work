@echo off
setlocal
set "HOST=0.0.0.0"
set "AUTH_MODE=development"
call "%~dp0run.cmd" %*
exit /b %errorlevel%
