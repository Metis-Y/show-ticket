@echo off
rem 用桌面已有的 nginx 以本目录为 prefix 启动前端（8080 端口）
rem 依赖：conf/nginx.conf、conf/mime.types、../dist（npm run build 产物）
set NGINX=C:\Users\35406\Desktop\redis_study\nginx-1.18.0\nginx.exe
set PREFIX=%~dp0.
if not exist "%PREFIX%\conf\mime.types" (
    echo [缺少 mime.types] 正在从 nginx 安装目录复制...
    copy "C:\Users\35406\Desktop\redis_study\nginx-1.18.0\conf\mime.types" "%PREFIX%\conf\mime.types"
)
if not exist "..\dist\index.html" (
    echo [缺少 dist] 请先在前端目录执行 npm run build
    pause
    exit /b 1
)
"%NGINX%" -p "%PREFIX%"
echo 前端已启动: http://127.0.0.1:8080
pause
