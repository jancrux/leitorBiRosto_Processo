@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  Leitura_Bi - instalacao do servidor
echo ============================================
echo.

where python >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Python nao encontrado no PATH. Instala Python 3.10+ e volta a tentar.
    pause
    exit /b 1
)

if not exist ".venv" (
    echo A criar ambiente virtual em .venv ...
    python -m venv .venv
    if errorlevel 1 (
        echo [ERRO] Falha ao criar o ambiente virtual.
        pause
        exit /b 1
    )
)

call ".venv\Scripts\activate.bat"

echo A atualizar pip ...
python -m pip install --upgrade pip --quiet

echo A instalar dependencias (pode demorar alguns minutos) ...
pip install -r requirements.txt
if errorlevel 1 (
    echo.
    echo [AVISO] Alguma dependencia falhou. Se foi o "insightface", ve a seccao
    echo         "Problemas de instalacao" no README.md.
)

if not exist ".env" (
    copy ".env.example" ".env" >nul
    echo.
    echo Criado o ficheiro .env  --  ABRE-O e define a tua API_KEY.
)

echo.
echo Instalacao concluida. Arranca o servidor com:  run_server.bat
pause
