@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Leitura_Bi - tunel ngrok

where ngrok >nul 2>&1
if errorlevel 1 (
    echo [ERRO] ngrok nao encontrado no PATH.
    echo.
    echo   1. Descarrega em https://ngrok.com/download  ^(ou: winget install ngrok.ngrok^)
    echo   2. Cria conta gratuita e copia o authtoken
    echo   3. Corre:  ngrok config add-authtoken SEU_TOKEN
    echo.
    pause
    exit /b 1
)

set PORT=8000
for /f "tokens=1,2 delims==" %%a in ('type ".env" 2^>nul ^| findstr /b /i "PORT="') do set PORT=%%b
set PORT=%PORT: =%

echo ============================================
echo  Tunel ngrok para a porta %PORT%
echo ============================================
echo.
echo  Copia o endereco "Forwarding" https://....ngrok-free.app
echo  e cola-o nas Definicoes da app Android.
echo.
echo  IMPORTANTE: garante que o .env tem uma API_KEY definida --
echo  este endereco fica acessivel a partir de toda a internet.
echo.

ngrok http %PORT%
pause
