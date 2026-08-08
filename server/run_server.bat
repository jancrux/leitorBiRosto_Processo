@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Leitura_Bi - servidor de fotos

if not exist ".venv\Scripts\activate.bat" (
    echo [ERRO] Ambiente virtual nao encontrado. Corre primeiro:  setup.bat
    pause
    exit /b 1
)

call ".venv\Scripts\activate.bat"

if not exist ".env" (
    if exist ".env.example" copy ".env.example" ".env" >nul
)

set PORT=8000
for /f "tokens=1,2 delims==" %%a in ('type ".env" 2^>nul ^| findstr /b /i "PORT="') do set PORT=%%b
set PORT=%PORT: =%

echo ============================================
echo  Leitura_Bi - servidor a arrancar na porta %PORT%
echo ============================================
echo.
echo  Neste PC:       http://localhost:%PORT%
for /f "tokens=2 delims=:" %%i in ('ipconfig ^| findstr /i "IPv4"') do (
    set IP=%%i
    set IP=!IP: =!
    echo  Na rede local:  http://!IP!:%PORT%
)
echo  Documentacao:   http://localhost:%PORT%/docs
echo.
echo  Para acesso fora da rede local, abre tambem:  run_ngrok.bat
echo  (Ctrl+C para parar)
echo.

python -m uvicorn main:app --host 0.0.0.0 --port %PORT%
pause
