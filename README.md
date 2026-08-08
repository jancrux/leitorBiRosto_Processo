# Leitura_Bi — Registos de Viatura Policial

Sistema para **criar** e **pesquisar** registos a partir de fotos e PDFs, com extração
automática dos campos do formulário *Relatório de Viatura Policial* (PSP,
`Mod/RVP/COMETPOR/NO/V1.0/2022`) e reconhecimento facial nas fotos.

```
┌──────────────────────┐        Wi-Fi local  ou  túnel ngrok (HTTPS)
│  App Android         │  ───────────────────────────────────────────►  ┌────────────────────┐
│  Kotlin + Compose    │                                                │  Servidor Python   │
│  • Criar registo     │  fotos (câmara) + PDF ──────────────────────►  │  FastAPI + SQLite  │
│  • Pesquisar registo │  ◄────────── registos, rostos, miniaturas ───  │  InsightFace       │
└──────────────────────┘                                                │  PyMuPDF           │
                                                                        └────────────────────┘
                                                            UI web em http://localhost:8000
```

---

## O que faz

**Criar registo**
- Anexa fotos (tiradas na câmara ou da galeria) e o PDF do relatório.
- O servidor lê os **210 campos AcroForm** do PDF e preenche o registo sozinho:
  cabeçalho, tripulação, material, 10 ocorrências, autos de notícia, 20 itens de
  inspeção (C / NC / N/A), quilómetros, assinaturas.
- O **PDF original fica guardado** tal como foi enviado; o texto é indexado para pesquisa.
- Rostos nas fotos (e nas primeiras páginas do PDF) são detetados e agrupados por pessoa.

**Pesquisar registo**
- Texto livre sobre tudo — incluindo o texto extraído do PDF — sem sensibilidade a acentos.
- Filtros: indicativo, matrícula da viatura, agente (nome ou nº), esquadra, divisão,
  turno, NPP/NUIPC, intervalo de datas, com fotos, com itens não conformes.
- **Pesquisa por rosto**: envia uma foto e recebe os registos onde essa pessoa aparece,
  ordenados por semelhança.

---

## Como as checkboxes do PDF são lidas

Os campos de texto do formulário têm nomes estáveis (`INDICATIVO`, `NOMEMOTORISTA`, …),
mas as 88 checkboxes têm nomes opacos **e fora de ordem** — `Check Box34` está na 7.ª linha
da tabela, não na 5.ª. Por isso são mapeadas pela **geometria** dos widgets: ordenam-se por
linha e coluna dentro de cada tabela do impresso ([`server/rvp.py`](server/rvp.py)).

Validação no relatório de exemplo: `Tacógrafo` não tem marca na coluna "C" e é
corretamente lido como **N/A**; `Estado dos bancos e cintos` é lido como **NC**.

---

## Instalação

> 📖 **[Guia de instalação passo a passo → INSTALACAO.md](INSTALACAO.md)** — do zero até ter
> a app a falar com o servidor, incluindo resolução de problemas.
>
> O resumo abaixo serve para quem já tem o ambiente montado.

### Servidor (PC Windows)

```bat
cd server
setup.bat          :: cria .venv, instala dependências, gera o .env
```

Abre `server\.env` e define a tua `API_KEY`. Depois:

```bat
run_server.bat     :: arranca; mostra o IP a usar na app e a UI web
```

- UI web no PC: <http://localhost:8000>
- Documentação da API: <http://localhost:8000/docs>

#### Acesso fora da rede local (ngrok)

```bat
run_ngrok.bat
```

Copia o endereço `https://….ngrok-free.app` para as Definições da app.
**Define sempre uma `API_KEY`** antes de expor o servidor à internet.

#### Problemas de instalação

| Sintoma | Causa e solução |
|---|---|
| `insightface` falha a compilar | Não há wheel para Python 3.12. Instala primeiro `pip install cython numpy`, ou os *Visual C++ Build Tools*. Em alternativa põe `FACE_DISABLED=1` no `.env` — tudo o resto continua a funcionar. |
| Arranque lento na 1.ª vez | O modelo `buffalo_l` (~330 MB) é descarregado uma única vez para `~/.insightface`. |
| PDF guardado sem dados extraídos | Falta o `pymupdf`, ou o PDF não tem campos preenchíveis (ex.: digitalização). O ficheiro fica na mesma guardado. |
| Deteção facial lenta | Baixa `FACE_DET_SIZE` para `480` ou usa `FACE_MODEL=buffalo_s`. |

O servidor **degrada com elegância**: se o motor facial ou o PyMuPDF faltarem, arranca à
mesma e desativa só essas funcionalidades — o estado aparece em `/health` e nas Definições da app.

---

### App Android

Requer **Android Studio** (JDK 17; o JDK 8 do sistema não chega) e **Android SDK 35**.

1. Abre a pasta `android/` no Android Studio.
2. Aceita a criação do Gradle wrapper quando for pedido (`gradle-wrapper.jar` não é versionado).
3. *Sync Gradle* e depois *Run*.
4. Na app: **Definições** → endereço do servidor, chave de API e o teu nome → *Guardar e ligar*.

`minSdk 26` · `targetSdk 35` · Kotlin 2.0.21 · Compose BOM 2024.12.01

---

## API

Autenticação: header `X-API-Key` (ou `?api_key=` para imagens em `<img src>`).

| Método | Rota | Função |
|---|---|---|
| `GET` | `/health` | Estado do servidor e dos motores |
| `POST` | `/api/extract` | Lê um PDF e devolve os dados **sem gravar** (pré-visualização) |
| `POST` | `/api/records` | **Criar registo** com fotos e/ou PDF (extração automática) |
| `POST` | `/api/records/{id}/attachments` | Juntar anexos a um registo |
| `GET` | `/api/records` | **Pesquisar** por texto e filtros estruturados |
| `POST` | `/api/search/face` | **Pesquisar por rosto** numa foto |
| `GET` | `/api/records/{id}` | Registo completo |
| `PATCH` | `/api/records/{id}` | Editar campos e blocos |
| `DELETE` | `/api/records/{id}` | Eliminar registo e anexos |
| `GET` | `/api/attachments/{id}/file` \| `/thumb` | Ficheiro original / miniatura |
| `GET` | `/api/filters` | Valores distintos para preencher os filtros |
| `GET` | `/api/persons` · `PATCH /api/persons/{id}` | Pessoas reconhecidas |
| `POST` | `/api/faces/{id}/assign` | Atribuir um rosto a uma pessoa |

Exemplo:

```bash
curl -X POST http://localhost:8000/api/records \
  -H "X-API-Key: a-tua-chave" \
  -F "files=@relatorio.pdf;type=application/pdf" \
  -F "files=@foto.jpg;type=image/jpeg" \
  -F "author=Nome do agente"
```

---

## Estrutura

```
server/
  main.py       API FastAPI: criar, pesquisar, anexos, pessoas
  rvp.py        extração do formulário RVP (campos + geometria das checkboxes)
  pdfs.py       PyMuPDF: miniatura, texto, rasterização de páginas
  faces.py      InsightFace: deteção + embeddings de 512 dim
  index.py      índice em memória dos embeddings (pesquisa por produto matricial)
  db.py         esquema SQLite
  imaging.py    miniaturas, EXIF, recortes de rosto
  static/       UI web local
android/
  app/src/main/java/pt/leiturabi/
    data/       Retrofit, modelos, definições (DataStore)
    ui/         Compose: criar, pesquisar, detalhe, pessoas, câmara, definições
    util/       ficheiros, localização
```

---

## Privacidade

Os dados ficam **apenas no PC** onde o servidor corre (`server/data/`) — não há serviços externos.
Essa pasta e o `.env` estão no `.gitignore` e **não** são versionados: contêm nomes,
números de matrícula de agentes e imagens de rostos.

Ao expor o servidor por ngrok, o endereço fica acessível a partir da internet — a `API_KEY`
é a única proteção, por isso usa uma chave longa e aleatória.
