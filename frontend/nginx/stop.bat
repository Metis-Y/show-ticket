@echo off
rem 停止以本目录为 prefix 启动的 nginx
set NGINX=C:\Users\35406\Desktop\redis_study\nginx-1.18.0\nginx.exe
set PREFIX=%~dp0.
"%NGINX%" -p "%PREFIX%" -s stop
echo 已停止
pause
