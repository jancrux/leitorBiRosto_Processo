"""Camada de acesso a base de dados SQLite.

Modelo de dados
---------------
registo (records) 1--N anexos (attachments)   [kind = 'photo' | 'pdf']
registo           1--N tripulacao / material / ocorrencias / autos / inspecao
anexo (foto)      1--N rostos (faces)         N--1 pessoa (persons)

Os blocos do Relatorio de Viatura Policial ficam em tabelas proprias para
permitir pesquisa estruturada (por agente, matricula, NUIPC, item nao conforme).
"""

from __future__ import annotations

import sqlite3
import threading
import unicodedata
from contextlib import contextmanager
from typing import Iterator

import config

_local = threading.local()

SCHEMA = """
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS records (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    title             TEXT    NOT NULL DEFAULT '',
    source            TEXT    NOT NULL DEFAULT 'manual',
    template          TEXT    NOT NULL DEFAULT '',

    -- cabecalho do relatorio
    indicativo        TEXT    NOT NULL DEFAULT '',
    data              TEXT,
    data_original     TEXT    NOT NULL DEFAULT '',
    turno             TEXT    NOT NULL DEFAULT '',
    divisao           TEXT    NOT NULL DEFAULT '',
    esquadra          TEXT    NOT NULL DEFAULT '',
    comando           TEXT    NOT NULL DEFAULT '',

    -- viatura (pagina 2)
    viatura_marca     TEXT    NOT NULL DEFAULT '',
    viatura_modelo    TEXT    NOT NULL DEFAULT '',
    viatura_matricula TEXT    NOT NULL DEFAULT '',
    condutor          TEXT    NOT NULL DEFAULT '',
    data_hora         TEXT    NOT NULL DEFAULT '',
    combustivel_lt    TEXT    NOT NULL DEFAULT '',
    km_iniciais       INTEGER,
    km_finais         INTEGER,

    -- texto livre
    observacoes       TEXT    NOT NULL DEFAULT '',
    anomalias         TEXT    NOT NULL DEFAULT '',
    notas             TEXT    NOT NULL DEFAULT '',
    tags              TEXT    NOT NULL DEFAULT '',

    -- assinaturas
    arvorado_cp       TEXT    NOT NULL DEFAULT '',
    graduado          TEXT    NOT NULL DEFAULT '',
    entregue_por      TEXT    NOT NULL DEFAULT '',
    recebido_por      TEXT    NOT NULL DEFAULT '',

    -- origem do registo (app)
    author            TEXT    NOT NULL DEFAULT '',
    device            TEXT    NOT NULL DEFAULT '',
    latitude          REAL,
    longitude         REAL,

    created_at        TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT    NOT NULL DEFAULT (datetime('now')),
    photo_count       INTEGER NOT NULL DEFAULT 0,
    pdf_count         INTEGER NOT NULL DEFAULT 0,
    face_count        INTEGER NOT NULL DEFAULT 0,
    search_text       TEXT    NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_records_created    ON records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_records_data       ON records(data DESC);
CREATE INDEX IF NOT EXISTS idx_records_indicativo ON records(indicativo);
CREATE INDEX IF NOT EXISTS idx_records_matricula  ON records(viatura_matricula);
CREATE INDEX IF NOT EXISTS idx_records_esquadra   ON records(esquadra);

CREATE TABLE IF NOT EXISTS persons (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL DEFAULT '',
    named      INTEGER NOT NULL DEFAULT 0,
    matricula  TEXT    NOT NULL DEFAULT '',
    notes      TEXT    NOT NULL DEFAULT '',
    cover_face INTEGER,
    created_at TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS record_crew (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    funcao    TEXT    NOT NULL DEFAULT '',
    categoria TEXT    NOT NULL DEFAULT '',
    matricula TEXT    NOT NULL DEFAULT '',
    nome      TEXT    NOT NULL DEFAULT '',
    person_id INTEGER REFERENCES persons(id) ON DELETE SET NULL,
    position  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_crew_record    ON record_crew(record_id, position);
CREATE INDEX IF NOT EXISTS idx_crew_matricula ON record_crew(matricula);
CREATE INDEX IF NOT EXISTS idx_crew_nome      ON record_crew(nome);

CREATE TABLE IF NOT EXISTS record_material (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id  INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    item       TEXT    NOT NULL DEFAULT '',
    verificado INTEGER NOT NULL DEFAULT 0,
    quantidade TEXT    NOT NULL DEFAULT '',
    position   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_material_record ON record_material(record_id, position);

CREATE TABLE IF NOT EXISTS record_occurrences (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id     INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    numero        INTEGER NOT NULL DEFAULT 0,
    area          TEXT    NOT NULL DEFAULT '',
    expediente    INTEGER NOT NULL DEFAULT 0,
    npp_nuipc     TEXT    NOT NULL DEFAULT '',
    hora_chegada  TEXT    NOT NULL DEFAULT '',
    hora_saida    TEXT    NOT NULL DEFAULT '',
    supervisor    INTEGER NOT NULL DEFAULT 0,
    descricao     TEXT    NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_occurrences_record ON record_occurrences(record_id, numero);
CREATE INDEX IF NOT EXISTS idx_occurrences_nuipc  ON record_occurrences(npp_nuipc);

CREATE TABLE IF NOT EXISTS record_offences (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id   INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    numero      INTEGER NOT NULL DEFAULT 0,
    npp_nuipc   TEXT    NOT NULL DEFAULT '',
    responsavel TEXT    NOT NULL DEFAULT '',
    motivo      TEXT    NOT NULL DEFAULT '',
    local       TEXT    NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_offences_record ON record_offences(record_id, numero);
CREATE INDEX IF NOT EXISTS idx_offences_nuipc  ON record_offences(npp_nuipc);

CREATE TABLE IF NOT EXISTS record_inspection (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    item      TEXT    NOT NULL DEFAULT '',
    condicao  TEXT    NOT NULL DEFAULT '',
    position  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_inspection_record   ON record_inspection(record_id, position);
CREATE INDEX IF NOT EXISTS idx_inspection_condicao ON record_inspection(condicao);

CREATE TABLE IF NOT EXISTS attachments (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id     INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    kind          TEXT    NOT NULL,
    filename      TEXT    NOT NULL,
    thumb_name    TEXT,
    original_name TEXT    NOT NULL DEFAULT '',
    mime          TEXT    NOT NULL DEFAULT '',
    sha256        TEXT    NOT NULL,
    size_bytes    INTEGER NOT NULL DEFAULT 0,
    width         INTEGER,
    height        INTEGER,
    page_count    INTEGER,
    text_excerpt  TEXT    NOT NULL DEFAULT '',
    face_count    INTEGER NOT NULL DEFAULT 0,
    position      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_attachments_record ON attachments(record_id, position);
CREATE INDEX IF NOT EXISTS idx_attachments_sha    ON attachments(sha256);

CREATE TABLE IF NOT EXISTS faces (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id     INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    attachment_id INTEGER NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    person_id     INTEGER REFERENCES persons(id) ON DELETE SET NULL,
    page          INTEGER NOT NULL DEFAULT 0,
    x             INTEGER NOT NULL,
    y             INTEGER NOT NULL,
    w             INTEGER NOT NULL,
    h             INTEGER NOT NULL,
    det_score     REAL    NOT NULL DEFAULT 0,
    age           INTEGER,
    gender        TEXT,
    crop_name     TEXT,
    embedding     BLOB,
    similarity    REAL,
    created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_faces_record     ON faces(record_id);
CREATE INDEX IF NOT EXISTS idx_faces_attachment ON faces(attachment_id);
CREATE INDEX IF NOT EXISTS idx_faces_person     ON faces(person_id);
"""


def get_conn() -> sqlite3.Connection:
    """Ligacao por thread (uvicorn corre handlers sincronos num pool de threads)."""
    conn = getattr(_local, "conn", None)
    if conn is None:
        conn = sqlite3.connect(config.DB_PATH, timeout=30, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA busy_timeout = 30000")
        _local.conn = conn
    return conn


@contextmanager
def transaction() -> Iterator[sqlite3.Connection]:
    conn = get_conn()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def init_db() -> None:
    config.ensure_dirs()
    conn = get_conn()
    conn.executescript(SCHEMA)
    conn.commit()


def normalize(text: str | None) -> str:
    """Minusculas sem acentos, para pesquisa insensivel a acentuacao."""
    if not text:
        return ""
    decomposed = unicodedata.normalize("NFKD", str(text))
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return stripped.lower().strip()


def build_search_text(*parts) -> str:
    chunks = []
    for part in parts:
        if part is None:
            continue
        if isinstance(part, (list, tuple)):
            chunks.extend(normalize(p) for p in part if p)
        else:
            chunks.append(normalize(part))
    return " ".join(c for c in chunks if c)
