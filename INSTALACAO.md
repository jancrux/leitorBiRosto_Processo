# Guia de instalação — Leitura_Bi

Guia completo, do zero até ter a app do telemóvel a falar com o servidor do PC.

**Tempo estimado:** 20–40 minutos (a maior parte é a descarregar dependências).

Índice:
1. [O que vais precisar](#1-o-que-vais-precisar)
2. [Parte A — Servidor no PC Windows](#parte-a--servidor-no-pc-windows)
3. [Parte B — App Android](#parte-b--app-android)
4. [Parte C — Acesso fora da rede local (ngrok)](#parte-c--acesso-fora-da-rede-local-ngrok)
5. [Verificar que está tudo bem](#5-verificar-que-está-tudo-bem)
6. [Resolução de problemas](#6-resolução-de-problemas)
7. [Manutenção](#7-manutenção)

---

## 1. O que vais precisar

### Para o servidor (o PC que guarda tudo)

| Requisito | Versão | Onde obter |
|---|---|---|
| Windows | 10 ou 11 | — |
| Python | 3.10 – 3.12 | <https://www.python.org/downloads/> |
| Espaço em disco | ~2 GB | 1 GB de dependências + modelo facial de ~330 MB |
| RAM | 4 GB mínimo, 8 GB recomendado | — |

> **Importante:** ao instalar o Python, marca a caixa **"Add Python to PATH"** no primeiro
> ecrã do instalador. Sem isso o `setup.bat` não encontra o Python.

Confirma numa linha de comandos:

```bat
python --version
```

Deve responder algo como `Python 3.12.10`.

### Para a app Android

| Requisito | Versão | Notas |
|---|---|---|
| Android Studio | Ladybug (2024.2) ou mais recente | <https://developer.android.com/studio> |
| JDK | 17 | Já vem incluído no Android Studio — **não** uses o JDK 8 do sistema |
| Android SDK | API 35 | Instalado pelo próprio Android Studio |
| Telemóvel | Android 8.0 (API 26) ou superior | Com câmara |

### Rede

O telemóvel e o PC têm de estar **na mesma rede Wi-Fi** — a não ser que uses o ngrok
(ver [Parte C](#parte-c--acesso-fora-da-rede-local-ngrok)).

---

## Parte A — Servidor no PC Windows

### A1. Obter o projeto

```bat
git clone https://github.com/jancrux/leitorBiRosto_Processo.git
cd leitorBiRosto_Processo
```

Se não tens o Git, descarrega o ZIP do GitHub e extrai-o para uma pasta à tua escolha.

### A2. Instalar as dependências

```bat
cd server
setup.bat
```

O `setup.bat` faz três coisas:
1. cria um ambiente virtual isolado em `server\.venv`;
2. instala as bibliotecas do `requirements.txt`;
3. cria o ficheiro `server\.env` a partir do `.env.example`.

Demora **5 a 15 minutos**. É normal ver muito texto a passar.

> Se aparecer um aviso sobre o `insightface`, não interrompas — continua e vê
> [A5. Se o insightface falhar](#a5-se-o-insightface-falhar).

### A3. Definir a chave de acesso

Abre `server\.env` no Bloco de Notas e muda a linha da `API_KEY`:

```ini
API_KEY=troca-esta-chave-por-uma-aleatoria
```

Põe uma chave longa e difícil de adivinhar, por exemplo:

```ini
API_KEY=Kf9x2Lm7Qw4Rt8Zp1Ns6Vb3Yh0Jd5Gc
```

Esta é a mesma chave que vais escrever na app. **Guarda-a**, vais precisar dela no passo B4.

Podes gerar uma assim:

```bat
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### A4. Arrancar o servidor

```bat
run_server.bat
```

A janela mostra algo como:

```
============================================
 Leitura_Bi - servidor a arrancar na porta 8000
============================================

 Neste PC:       http://localhost:8000
 Na rede local:  http://192.168.1.10:8000     <-- ANOTA ESTE ENDEREÇO
 Documentação:   http://localhost:8000/docs
```

**Anota o endereço "Na rede local"** — é o que vais escrever na app.

Abre <http://localhost:8000> no browser: deves ver a interface web do Leitura_Bi.

> **Na primeira vez**, o modelo de reconhecimento facial (~330 MB) é descarregado em
> segundo plano. Até terminar, os rostos não são detetados. Acompanha o progresso na
> janela do servidor. É feito **uma única vez**.

Para parar o servidor: `Ctrl+C` na janela, ou fecha-a.

### A5. Se o insightface falhar

O `insightface` não tem versão pré-compilada para Python 3.12 e às vezes falha a instalar.
Tenta, por esta ordem:

**Opção 1 — instalar as ferramentas de compilação primeiro** (recomendada)

```bat
cd server
.venv\Scripts\activate
pip install cython numpy
pip install insightface --no-build-isolation
```

**Opção 2 — instalar os Visual C++ Build Tools**

Descarrega em <https://visualstudio.microsoft.com/visual-cpp-build-tools/> e seleciona
*"Desenvolvimento para ambiente de trabalho com C++"*. Depois repete a Opção 1.

**Opção 3 — usar Python 3.11 em vez de 3.12**

O `insightface` instala sem compilar em Python 3.11.

**Opção 4 — desligar o reconhecimento facial**

Se não precisas desta funcionalidade agora, edita `server\.env`:

```ini
FACE_DISABLED=1
```

Tudo o resto — criar registos, guardar PDFs, extrair os dados do formulário, pesquisar —
**continua a funcionar**. Podes ligar mais tarde, quando resolveres a instalação.

### A6. Deixar o servidor sempre acessível (opcional)

Para o telemóvel chegar ao PC, a **firewall do Windows** tem de permitir a porta 8000.
Na primeira vez que arrancas o servidor, o Windows pergunta — escolhe **"Permitir acesso"**
em *Redes privadas*.

Se recusaste sem querer, abre uma linha de comandos **como administrador**:

```bat
netsh advfirewall firewall add rule name="Leitura_Bi" dir=in action=allow protocol=TCP localport=8000
```

Convém também dar ao PC um **IP fixo** no router — assim o endereço não muda e não tens
de o corrigir na app de cada vez.

---

## Parte B — App Android

### B1. Abrir o projeto

1. Abre o **Android Studio**.
2. *File → Open…* e escolhe a pasta **`android`** do projeto (não a raiz).
3. Espera pelo *Gradle Sync* — a primeira vez descarrega bastante e demora vários minutos.

### B2. Gerar o Gradle wrapper

O ficheiro `gradle-wrapper.jar` não é versionado (é um binário). O Android Studio
normalmente trata disto sozinho. Se pedir, aceita. Se preferires fazer à mão, com o
Gradle instalado:

```bat
cd android
gradle wrapper --gradle-version 8.11.1
```

### B3. Instalar no telemóvel

1. No telemóvel: *Definições → Acerca do telefone* → toca 7 vezes em **Número de compilação**
   para ativar as *Opções de programador*.
2. *Definições → Opções de programador* → liga a **Depuração USB**.
3. Liga o telemóvel ao PC por cabo e aceita o pedido de autorização que aparece no ecrã.
4. No Android Studio, escolhe o telemóvel na lista de dispositivos e carrega em **Run ▶**.

Em alternativa, gera um APK (*Build → Build Bundle(s)/APK(s) → Build APK(s)*) e copia-o
para o telemóvel.

### B4. Ligar a app ao servidor

Na app, abre o separador **Definições** e preenche:

| Campo | O que pôr | Exemplo |
|---|---|---|
| **Endereço** | O IP mostrado pelo `run_server.bat` | `192.168.1.10:8000` |
| **Chave de API** | A `API_KEY` que definiste em [A3](#a3-definir-a-chave-de-acesso) | `Kf9x2Lm7Qw4…` |
| **O teu nome** | Fica associado aos registos que criares | `Agente J. Cruz` |

Toca em **Guardar e ligar**. Deve aparecer uma mensagem a confirmar, por exemplo:

```
Ligado: 0 registos · facial pronto
```

Se aparecer erro, vê a [secção 6](#6-resolução-de-problemas).

### B5. Autorizações

Na primeira utilização a app pede:
- **Câmara** — obrigatória para tirar fotos.
- **Localização** — opcional; se autorizares, os registos ficam com as coordenadas GPS.

---

## Parte C — Acesso fora da rede local (ngrok)

Só precisas disto se quiseres usar a app **fora do Wi-Fi do PC** (por exemplo, em serviço).

### C1. Instalar o ngrok

```bat
winget install ngrok.ngrok
```

Ou descarrega em <https://ngrok.com/download>.

### C2. Registar o token

Cria uma conta gratuita em <https://dashboard.ngrok.com/signup>, copia o teu *authtoken* e corre:

```bat
ngrok config add-authtoken O_TEU_TOKEN_AQUI
```

### C3. Abrir o túnel

Com o `run_server.bat` já a correr **noutra janela**:

```bat
cd server
run_ngrok.bat
```

Vais ver:

```
Forwarding    https://a1b2-c3d4.ngrok-free.app -> http://localhost:8000
```

### C4. Usar na app

Nas **Definições** da app, substitui o endereço pelo do ngrok:

```
https://a1b2-c3d4.ngrok-free.app
```

Toca em *Guardar e ligar*.

> ⚠️ **Atenção:** este endereço fica acessível a **partir de toda a internet**. A `API_KEY`
> é a única coisa que protege os teus dados — usa uma chave longa e não a partilhes.
>
> ℹ️ No plano gratuito do ngrok o endereço **muda sempre que reinicias o túnel**, e tens de
> o voltar a colar na app. Deixa a janela do ngrok aberta enquanto estiveres a usar a app.

---

## 5. Verificar que está tudo bem

### No PC

Abre <http://localhost:8000/health> no browser. Deves ver:

```json
{
  "status": "ok",
  "face_engine": "pronto",
  "pdf_engine": "pymupdf",
  "auth_required": true,
  "records": 0
}
```

| Campo | Valor esperado | Se estiver diferente |
|---|---|---|
| `face_engine` | `pronto` | `indisponivel` → vê [A5](#a5-se-o-insightface-falhar). `nao carregado` → ainda a descarregar o modelo, espera. `desativado` → tens `FACE_DISABLED=1` no `.env`. |
| `pdf_engine` | `pymupdf` | `indisponivel` → falta instalar: `pip install pymupdf` |
| `auth_required` | `true` | `false` → não definiste a `API_KEY` no `.env` |

### Teste ponta a ponta

1. Na app, separador **Criar registo**.
2. Toca em **Anexar** e escolhe um PDF de relatório preenchido.
3. Ao fim de 1–2 segundos aparece o bloco **"Dados extraídos do PDF"** com o indicativo,
   a data, a tripulação e as ocorrências.
4. Toca em **Tirar foto** e tira uma ou duas fotos.
5. Toca em **Guardar registo**. Deve aparecer:
   `Registo #1 criado · 3 anexo(s) · 2 rosto(s) · dados do PDF`
6. Vai a **Pesquisar registo** — o registo aparece na lista.
7. Escreve o nome de um agente na pesquisa: o registo deve continuar a aparecer.
8. Abre <http://localhost:8000> no PC e confirma que vês o mesmo registo.

---

## 6. Resolução de problemas

### A app diz "Não foi possível ligar ao servidor"

Percorre esta lista por ordem:

1. **O `run_server.bat` está a correr?** A janela tem de estar aberta.
2. **O endereço está certo?** Tem de ser o IP `192.168.x.x` mostrado pelo servidor,
   **não** `localhost` — para o telemóvel, `localhost` é o próprio telemóvel.
3. **Mesma rede Wi-Fi?** Confirma que o telemóvel não está em dados móveis.
4. **Firewall.** Vê [A6](#a6-deixar-o-servidor-sempre-acessível-opcional).
5. **Testa do telemóvel:** abre o browser do telemóvel em `http://192.168.1.10:8000`.
   Se a página aparecer, o problema é a chave de API; se não aparecer, é rede ou firewall.
6. **O IP mudou?** Acontece quando o router reinicia. Corre o `run_server.bat` outra vez e
   confirma o IP.

### A app diz "Chave de API inválida"

A chave na app tem de ser **exatamente igual** à do `server\.env`, sem espaços antes ou
depois. Depois de mudar o `.env`, **reinicia o servidor**.

### O PDF é guardado mas os dados não são extraídos

- Confirma que `pdf_engine` é `pymupdf` em `/health`.
- O PDF tem de ser um **formulário preenchível** (AcroForm). Se for uma digitalização ou
  um PDF "achatado", não há campos para ler — o ficheiro fica guardado na mesma e podes
  preencher os dados à mão.
- Se o formulário for de uma versão diferente do impresso, a app avisa
  *"Formulário não reconhecido"* e extrai o que conseguir.

### Não são detetados rostos

- Vê o `face_engine` em `/health` — tem de estar `pronto`.
- Na primeira utilização o modelo ainda pode estar a descarregar.
- Rostos muito pequenos ou de perfil escapam. Baixa `FACE_DET_THRESHOLD` no `.env`
  (por exemplo para `0.4`) e reinicia o servidor.

### Duas pessoas diferentes agrupadas como a mesma

Sobe o `FACE_MATCH_THRESHOLD` no `.env` (por exemplo de `0.42` para `0.5`) e reinicia.
Valores mais altos = mais exigente. O contrário — a mesma pessoa dividida em várias —
resolve-se baixando o valor.

### O Gradle falha com "invalid source release: 17"

O Android Studio está a usar o JDK 8. Vai a
*File → Settings → Build, Execution, Deployment → Build Tools → Gradle* e em
**Gradle JDK** escolhe o JDK 17 incluído no Android Studio (*jbr-17*).

### O upload falha com "Ficheiro demasiado grande"

Aumenta o limite em `server\.env` e reinicia:

```ini
MAX_UPLOAD_MB=80
```

---

## 7. Manutenção

### Onde ficam os dados

Tudo em `server\data\`:

```
server/data/
  leiturabi.db     base de dados (registos, ocorrências, rostos)
  files/           ficheiros originais (PDFs e fotos)
  thumbs/          miniaturas
  faces/           recortes dos rostos
```

Esta pasta **não** é versionada no Git — contém dados pessoais.

### Cópia de segurança

Com o servidor **parado**, copia a pasta `server\data\` inteira. Para restaurar, volta a
pôr a pasta no mesmo sítio.

```bat
xcopy /E /I server\data D:\backups\leiturabi_2026-08-08
```

### Atualizar o projeto

```bat
git pull
cd server
.venv\Scripts\activate
pip install -r requirements.txt
```

A base de dados é criada e atualizada automaticamente no arranque; os dados existentes
mantêm-se.

### Desinstalar

Apaga a pasta do projeto. Apaga também `%USERPROFILE%\.insightface` para remover os
modelos descarregados.
