"""Leitura_Bi - API local de registos (Relatorio de Viatura Policial).

Duas funcoes principais:
  * CRIAR REGISTO    - envia fotos e/ou PDF; os dados do PDF sao extraidos
                       automaticamente e o ficheiro original fica guardado.
  * PESQUISAR REGISTO- por texto/filtros estruturados ou por rosto numa foto.

Arranque:  run_server.bat   (ou: python -m uvicorn main:app --host 0.0.0.0 --port 8000)
"""

from __future__ import annotations

import hashlib
import json
import logging
import sqlite3
import threading
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from fastapi import Depends, FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

import config
import db
import faces as face_engine
import imaging
import pdfs
import rvp
from index import face_index
from schemas import (
    AttachmentOut,
    CrewOut,
    ExtractResult,
    FaceAssign,
    FaceOut,
    HealthOut,
    InspectionOut,
    MaterialOut,
    OccurrenceOut,
    OffenceOut,
    PersonOut,
    PersonUpdate,
    RecordCreate,
    RecordOut,
    RecordPage,
    RecordSummary,
    RecordUpdate,
    UploadResult,
)

VERSION = "1.0.0"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s %(name)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("leiturabi")

STATIC_DIR = Path(__file__).resolve().parent / "static"


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    log.info("Base de dados: %s", config.DB_PATH)
    log.info("Ficheiros em:  %s", config.FILES_DIR)
    if not config.API_KEY:
        log.warning("API_KEY vazia - o servidor esta ABERTO a qualquer cliente. Define API_KEY no .env.")
    if config.FACE_DISABLED:
        log.warning("Motor facial desativado por configuracao (FACE_DISABLED=1).")
    else:
        threading.Thread(target=face_engine.warmup, name="face-warmup", daemon=True).start()
    pdfs.available()
    yield


app = FastAPI(title="Leitura_Bi - Registos RVP", version=VERSION, lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# --------------------------------------------------------------------------- #
# Autenticacao
# --------------------------------------------------------------------------- #

def require_key(request: Request) -> None:
    """Aceita a chave no header X-API-Key ou no parametro ?api_key= (para <img src>)."""
    if not config.API_KEY:
        return
    provided = request.headers.get("x-api-key") or request.query_params.get("api_key") or ""
    if not provided:
        authorization = request.headers.get("authorization", "")
        if authorization.lower().startswith("bearer "):
            provided = authorization[7:].strip()
    if provided != config.API_KEY:
        raise HTTPException(status_code=401, detail="Chave de API invalida ou em falta.")


Auth = Depends(require_key)


# --------------------------------------------------------------------------- #
# Utilitarios
# --------------------------------------------------------------------------- #

def _tags_to_list(raw: Optional[str]) -> List[str]:
    if not raw:
        return []
    return [t.strip() for t in str(raw).split(",") if t.strip()]


def _tags_to_str(tags) -> str:
    if not tags:
        return ""
    if isinstance(tags, str):
        tags = tags.split(",")
    cleaned: List[str] = []
    for tag in tags:
        for part in str(tag).split(","):
            part = part.strip()
            if part and part not in cleaned:
                cleaned.append(part)
    return ",".join(cleaned)


def _store_bytes(data: bytes, digest: str, extension: str) -> str:
    """Guarda o ficheiro pelo seu hash (mesmo conteudo = um so ficheiro em disco)."""
    filename = f"{digest}{extension}"
    path = config.FILES_DIR / filename
    if not path.exists():
        path.write_bytes(data)
    return filename


def _get_record_row(record_id: int) -> sqlite3.Row:
    row = db.get_conn().execute("SELECT * FROM records WHERE id = ?", (record_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Registo nao encontrado.")
    return row


def _km_percorridos(row) -> Optional[int]:
    inicio, fim = row["km_iniciais"], row["km_finais"]
    if inicio is None or fim is None:
        return None
    delta = fim - inicio
    return delta if delta >= 0 else None


# --------------------------------------------------------------------------- #
# Conversao linha -> schema
# --------------------------------------------------------------------------- #

def _face_out(row: sqlite3.Row, similarity: Optional[float] = None) -> FaceOut:
    keys = row.keys()
    return FaceOut(
        id=row["id"],
        record_id=row["record_id"],
        attachment_id=row["attachment_id"],
        person_id=row["person_id"],
        person_name=row["person_name"] if "person_name" in keys else None,
        page=row["page"] or 0,
        x=row["x"], y=row["y"], w=row["w"], h=row["h"],
        det_score=round(float(row["det_score"] or 0.0), 4),
        age=row["age"],
        gender=row["gender"],
        crop_url=f"/api/faces/{row['id']}/crop" if row["crop_name"] else None,
        similarity=round(similarity, 4) if similarity is not None else None,
    )


def _faces_of_attachment(attachment_id: int) -> List[FaceOut]:
    rows = db.get_conn().execute(
        """
        SELECT f.*, p.name AS person_name
        FROM faces f LEFT JOIN persons p ON p.id = f.person_id
        WHERE f.attachment_id = ?
        ORDER BY (f.w * f.h) DESC
        """,
        (attachment_id,),
    ).fetchall()
    return [_face_out(row) for row in rows]


def _attachment_out(row: sqlite3.Row, with_faces: bool = True) -> AttachmentOut:
    return AttachmentOut(
        id=row["id"],
        record_id=row["record_id"],
        kind=row["kind"],
        original_name=row["original_name"] or "",
        mime=row["mime"] or "",
        size_bytes=row["size_bytes"] or 0,
        width=row["width"],
        height=row["height"],
        page_count=row["page_count"],
        face_count=row["face_count"] or 0,
        created_at=row["created_at"] or "",
        file_url=f"/api/attachments/{row['id']}/file",
        thumb_url=f"/api/attachments/{row['id']}/thumb",
        faces=_faces_of_attachment(row["id"]) if with_faces else [],
    )


def _record_out(row: sqlite3.Row, score: Optional[float] = None) -> RecordOut:
    conn = db.get_conn()
    record_id = row["id"]

    crew = [
        CrewOut(id=r["id"], funcao=r["funcao"], categoria=r["categoria"],
                matricula=r["matricula"], nome=r["nome"], person_id=r["person_id"])
        for r in conn.execute(
            "SELECT * FROM record_crew WHERE record_id = ? ORDER BY position, id", (record_id,)
        )
    ]
    material = [
        MaterialOut(id=r["id"], item=r["item"], verificado=bool(r["verificado"]), quantidade=r["quantidade"])
        for r in conn.execute(
            "SELECT * FROM record_material WHERE record_id = ? ORDER BY position, id", (record_id,)
        )
    ]
    occurrences = [
        OccurrenceOut(id=r["id"], numero=r["numero"], area=r["area"], expediente=bool(r["expediente"]),
                      npp_nuipc=r["npp_nuipc"], hora_chegada=r["hora_chegada"], hora_saida=r["hora_saida"],
                      supervisor=bool(r["supervisor"]), descricao=r["descricao"])
        for r in conn.execute(
            "SELECT * FROM record_occurrences WHERE record_id = ? ORDER BY numero, id", (record_id,)
        )
    ]
    offences = [
        OffenceOut(id=r["id"], numero=r["numero"], npp_nuipc=r["npp_nuipc"], responsavel=r["responsavel"],
                   motivo=r["motivo"], local=r["local"])
        for r in conn.execute(
            "SELECT * FROM record_offences WHERE record_id = ? ORDER BY numero, id", (record_id,)
        )
    ]
    inspection = [
        InspectionOut(id=r["id"], item=r["item"], condicao=r["condicao"])
        for r in conn.execute(
            "SELECT * FROM record_inspection WHERE record_id = ? ORDER BY position, id", (record_id,)
        )
    ]
    attachments = [
        _attachment_out(r)
        for r in conn.execute(
            "SELECT * FROM attachments WHERE record_id = ? ORDER BY position, id", (record_id,)
        )
    ]
    people = [
        r["name"] for r in conn.execute(
            """
            SELECT DISTINCT p.name FROM faces f JOIN persons p ON p.id = f.person_id
            WHERE f.record_id = ? AND p.named = 1 ORDER BY p.name
            """,
            (record_id,),
        )
    ]

    return RecordOut(
        id=record_id,
        title=row["title"] or "",
        source=row["source"] or "manual",
        template=row["template"] or "",
        indicativo=row["indicativo"] or "",
        data=row["data"],
        data_original=row["data_original"] or "",
        turno=row["turno"] or "",
        divisao=row["divisao"] or "",
        esquadra=row["esquadra"] or "",
        comando=row["comando"] or "",
        viatura_marca=row["viatura_marca"] or "",
        viatura_modelo=row["viatura_modelo"] or "",
        viatura_matricula=row["viatura_matricula"] or "",
        condutor=row["condutor"] or "",
        data_hora=row["data_hora"] or "",
        combustivel_lt=row["combustivel_lt"] or "",
        km_iniciais=row["km_iniciais"],
        km_finais=row["km_finais"],
        km_percorridos=_km_percorridos(row),
        observacoes=row["observacoes"] or "",
        anomalias=row["anomalias"] or "",
        notas=row["notas"] or "",
        tags=_tags_to_list(row["tags"]),
        arvorado_cp=row["arvorado_cp"] or "",
        graduado=row["graduado"] or "",
        entregue_por=row["entregue_por"] or "",
        recebido_por=row["recebido_por"] or "",
        author=row["author"] or "",
        device=row["device"] or "",
        latitude=row["latitude"],
        longitude=row["longitude"],
        created_at=row["created_at"] or "",
        updated_at=row["updated_at"] or "",
        photo_count=row["photo_count"] or 0,
        pdf_count=row["pdf_count"] or 0,
        face_count=row["face_count"] or 0,
        score=round(score, 4) if score is not None else None,
        tripulacao=crew,
        material=material,
        ocorrencias=occurrences,
        autos=offences,
        inspecao=inspection,
        anexos=attachments,
        pessoas=people,
    )


def _record_summary(
    row: sqlite3.Row,
    score: Optional[float] = None,
    matched_faces: Optional[List[FaceOut]] = None,
) -> RecordSummary:
    conn = db.get_conn()
    record_id = row["id"]

    crew = [
        r["nome"] for r in conn.execute(
            "SELECT nome FROM record_crew WHERE record_id = ? AND nome <> '' ORDER BY position, id", (record_id,)
        )
    ]
    counts = conn.execute(
        """
        SELECT
          (SELECT COUNT(*) FROM record_occurrences WHERE record_id = ? AND
                 (area <> '' OR descricao <> '' OR npp_nuipc <> '')) AS ocorrencias,
          (SELECT COUNT(*) FROM record_offences    WHERE record_id = ?) AS autos,
          (SELECT COUNT(*) FROM record_inspection  WHERE record_id = ? AND condicao = 'NC') AS nc
        """,
        (record_id, record_id, record_id),
    ).fetchone()

    cover = conn.execute(
        """
        SELECT id FROM attachments WHERE record_id = ? AND thumb_name IS NOT NULL
        ORDER BY CASE kind WHEN 'photo' THEN 0 ELSE 1 END, position, id LIMIT 1
        """,
        (record_id,),
    ).fetchone()

    return RecordSummary(
        id=record_id,
        title=row["title"] or "",
        indicativo=row["indicativo"] or "",
        data=row["data"],
        data_original=row["data_original"] or "",
        turno=row["turno"] or "",
        divisao=row["divisao"] or "",
        esquadra=row["esquadra"] or "",
        viatura_matricula=row["viatura_matricula"] or "",
        viatura_marca=row["viatura_marca"] or "",
        condutor=row["condutor"] or "",
        km_percorridos=_km_percorridos(row),
        source=row["source"] or "manual",
        author=row["author"] or "",
        created_at=row["created_at"] or "",
        photo_count=row["photo_count"] or 0,
        pdf_count=row["pdf_count"] or 0,
        face_count=row["face_count"] or 0,
        occurrence_count=counts["ocorrencias"] or 0,
        offence_count=counts["autos"] or 0,
        nao_conformes=counts["nc"] or 0,
        tripulacao=crew,
        tags=_tags_to_list(row["tags"]),
        cover_url=f"/api/attachments/{cover['id']}/thumb" if cover else None,
        score=round(score, 4) if score is not None else None,
        matched_faces=matched_faces or [],
    )


# --------------------------------------------------------------------------- #
# Escrita de registos
# --------------------------------------------------------------------------- #

RECORD_TEXT_FIELDS = (
    "title", "indicativo", "data", "data_original", "turno", "divisao", "esquadra", "comando",
    "viatura_marca", "viatura_modelo", "viatura_matricula", "condutor", "data_hora", "combustivel_lt",
    "observacoes", "anomalias", "notas", "arvorado_cp", "graduado", "entregue_por", "recebido_por",
    "author", "device",
)
RECORD_NUMBER_FIELDS = ("km_iniciais", "km_finais", "latitude", "longitude")


def _rvp_to_fields(data: rvp.RvpData) -> Dict[str, object]:
    return {
        "title": data.titulo,
        "template": data.template,
        "indicativo": data.indicativo,
        "data": data.data or None,
        "data_original": data.data_original,
        "turno": data.turno,
        "divisao": data.divisao,
        "esquadra": data.esquadra,
        "comando": data.comando,
        "viatura_marca": data.viatura_marca,
        "viatura_modelo": data.viatura_modelo,
        "viatura_matricula": data.viatura_matricula,
        "condutor": data.condutor,
        "data_hora": data.data_hora,
        "combustivel_lt": data.combustivel_lt,
        "km_iniciais": data.km_iniciais,
        "km_finais": data.km_finais,
        "observacoes": data.observacoes,
        "anomalias": data.anomalias,
        "arvorado_cp": data.arvorado_cp,
        "graduado": data.graduado,
        "entregue_por": data.entregue_por,
    }


def _write_blocks(conn: sqlite3.Connection, record_id: int, data: rvp.RvpData) -> None:
    """Substitui os blocos estruturados do registo pelos dados extraidos."""
    for table in ("record_crew", "record_material", "record_occurrences", "record_offences", "record_inspection"):
        conn.execute(f"DELETE FROM {table} WHERE record_id = ?", (record_id,))

    for position, member in enumerate(data.tripulacao):
        conn.execute(
            "INSERT INTO record_crew (record_id, funcao, categoria, matricula, nome, position) VALUES (?,?,?,?,?,?)",
            (record_id, member.funcao, member.categoria, member.matricula, member.nome, position),
        )
    for position, item in enumerate(data.material):
        conn.execute(
            "INSERT INTO record_material (record_id, item, verificado, quantidade, position) VALUES (?,?,?,?,?)",
            (record_id, item.item, int(item.verificado), item.quantidade, position),
        )
    for occurrence in data.ocorrencias:
        conn.execute(
            """
            INSERT INTO record_occurrences
                (record_id, numero, area, expediente, npp_nuipc, hora_chegada, hora_saida, supervisor, descricao)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,
            (record_id, occurrence.numero, occurrence.area, int(occurrence.expediente), occurrence.npp_nuipc,
             occurrence.hora_chegada, occurrence.hora_saida, int(occurrence.supervisor), occurrence.descricao),
        )
    for offence in data.autos:
        conn.execute(
            "INSERT INTO record_offences (record_id, numero, npp_nuipc, responsavel, motivo, local) VALUES (?,?,?,?,?,?)",
            (record_id, offence.numero, offence.npp_nuipc, offence.responsavel, offence.motivo, offence.local),
        )
    for position, item in enumerate(data.inspecao):
        conn.execute(
            "INSERT INTO record_inspection (record_id, item, condicao, position) VALUES (?,?,?,?)",
            (record_id, item.item, item.condicao, position),
        )


def _write_blocks_from_update(conn: sqlite3.Connection, record_id: int, payload: RecordUpdate) -> None:
    """Aplica blocos enviados manualmente (edicao na app / UI web)."""
    if payload.tripulacao is not None:
        conn.execute("DELETE FROM record_crew WHERE record_id = ?", (record_id,))
        for position, member in enumerate(payload.tripulacao):
            conn.execute(
                "INSERT INTO record_crew (record_id, funcao, categoria, matricula, nome, person_id, position)"
                " VALUES (?,?,?,?,?,?,?)",
                (record_id, member.funcao, member.categoria, member.matricula, member.nome, member.person_id, position),
            )
    if payload.material is not None:
        conn.execute("DELETE FROM record_material WHERE record_id = ?", (record_id,))
        for position, item in enumerate(payload.material):
            conn.execute(
                "INSERT INTO record_material (record_id, item, verificado, quantidade, position) VALUES (?,?,?,?,?)",
                (record_id, item.item, int(item.verificado), item.quantidade, position),
            )
    if payload.ocorrencias is not None:
        conn.execute("DELETE FROM record_occurrences WHERE record_id = ?", (record_id,))
        for occurrence in payload.ocorrencias:
            conn.execute(
                """
                INSERT INTO record_occurrences
                    (record_id, numero, area, expediente, npp_nuipc, hora_chegada, hora_saida, supervisor, descricao)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                (record_id, occurrence.numero, occurrence.area, int(occurrence.expediente), occurrence.npp_nuipc,
                 occurrence.hora_chegada, occurrence.hora_saida, int(occurrence.supervisor), occurrence.descricao),
            )
    if payload.autos is not None:
        conn.execute("DELETE FROM record_offences WHERE record_id = ?", (record_id,))
        for offence in payload.autos:
            conn.execute(
                "INSERT INTO record_offences (record_id, numero, npp_nuipc, responsavel, motivo, local)"
                " VALUES (?,?,?,?,?,?)",
                (record_id, offence.numero, offence.npp_nuipc, offence.responsavel, offence.motivo, offence.local),
            )
    if payload.inspecao is not None:
        conn.execute("DELETE FROM record_inspection WHERE record_id = ?", (record_id,))
        for position, item in enumerate(payload.inspecao):
            conn.execute(
                "INSERT INTO record_inspection (record_id, item, condicao, position) VALUES (?,?,?,?)",
                (record_id, item.item, item.condicao, position),
            )


def _link_crew_to_persons(conn: sqlite3.Connection, record_id: int) -> None:
    """Liga cada tripulante a uma pessoa reconhecida com o mesmo nome."""
    people = {
        db.normalize(r["name"]): r["id"]
        for r in conn.execute("SELECT id, name FROM persons WHERE named = 1 AND name <> ''")
    }
    if not people:
        return
    for member in conn.execute(
        "SELECT id, nome FROM record_crew WHERE record_id = ? AND nome <> ''", (record_id,)
    ).fetchall():
        person_id = people.get(db.normalize(member["nome"]))
        if person_id:
            conn.execute("UPDATE record_crew SET person_id = ? WHERE id = ?", (person_id, member["id"]))


def _refresh_record(conn: sqlite3.Connection, record_id: int) -> None:
    """Recalcula contadores e o texto de pesquisa do registo."""
    row = conn.execute("SELECT * FROM records WHERE id = ?", (record_id,)).fetchone()
    if row is None:
        return

    counts = conn.execute(
        """
        SELECT
          (SELECT COUNT(*) FROM attachments WHERE record_id = ? AND kind = 'photo') AS fotos,
          (SELECT COUNT(*) FROM attachments WHERE record_id = ? AND kind = 'pdf')   AS pdfs,
          (SELECT COUNT(*) FROM faces       WHERE record_id = ?)                    AS rostos
        """,
        (record_id, record_id, record_id),
    ).fetchone()

    crew = conn.execute(
        "SELECT categoria, matricula, nome FROM record_crew WHERE record_id = ?", (record_id,)
    ).fetchall()
    occurrences = conn.execute(
        "SELECT area, npp_nuipc, descricao FROM record_occurrences WHERE record_id = ?", (record_id,)
    ).fetchall()
    offences = conn.execute(
        "SELECT npp_nuipc, responsavel, motivo, local FROM record_offences WHERE record_id = ?", (record_id,)
    ).fetchall()
    people = conn.execute(
        """
        SELECT DISTINCT p.name FROM faces f JOIN persons p ON p.id = f.person_id
        WHERE f.record_id = ? AND p.named = 1
        """,
        (record_id,),
    ).fetchall()
    excerpts = conn.execute(
        "SELECT text_excerpt, original_name FROM attachments WHERE record_id = ?", (record_id,)
    ).fetchall()

    parts: List[str] = [
        row["title"], row["indicativo"], row["data"], row["data_original"], row["turno"],
        row["divisao"], row["esquadra"], row["comando"], row["viatura_marca"], row["viatura_modelo"],
        row["viatura_matricula"], row["condutor"], row["observacoes"], row["anomalias"], row["notas"],
        (row["tags"] or "").replace(",", " "), row["arvorado_cp"], row["graduado"],
        row["entregue_por"], row["recebido_por"], row["author"],
    ]
    for member in crew:
        parts += [member["categoria"], member["matricula"], member["nome"]]
    for occurrence in occurrences:
        parts += [occurrence["area"], occurrence["npp_nuipc"], occurrence["descricao"]]
    for offence in offences:
        parts += [offence["npp_nuipc"], offence["responsavel"], offence["motivo"], offence["local"]]
    parts += [person["name"] for person in people]
    for excerpt in excerpts:
        parts += [excerpt["original_name"], excerpt["text_excerpt"]]

    conn.execute(
        """
        UPDATE records
        SET photo_count = ?, pdf_count = ?, face_count = ?, search_text = ?, updated_at = datetime('now')
        WHERE id = ?
        """,
        (counts["fotos"], counts["pdfs"], counts["rostos"], db.build_search_text(*parts), record_id),
    )


# --------------------------------------------------------------------------- #
# Anexos e rostos
# --------------------------------------------------------------------------- #

def _read_upload(upload: UploadFile) -> Tuple[bytes, str, str]:
    data = upload.file.read()
    if not data:
        raise HTTPException(status_code=400, detail=f"Ficheiro vazio: {upload.filename or 'sem nome'}")
    if len(data) > config.MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"'{upload.filename}' excede o maximo de {config.MAX_UPLOAD_BYTES // (1024 * 1024)} MB.",
        )

    mime = (upload.content_type or "").lower().split(";")[0].strip()
    kind = config.kind_for_mime(mime)
    if kind is None and (upload.filename or "").lower().endswith(".pdf"):
        mime, kind = "application/pdf", "pdf"
    if kind is None:
        raise HTTPException(
            status_code=415,
            detail=f"Tipo nao suportado em '{upload.filename}': {mime or 'desconhecido'}. Aceites: imagens e PDF.",
        )
    return data, mime, kind


def _add_attachment(record_id: int, data: bytes, mime: str, kind: str, original_name: str, position: int) -> int:
    """Guarda o ficheiro, gera miniatura, extrai texto (PDF) e deteta rostos."""
    digest = hashlib.sha256(data).hexdigest()
    extension = config.ALLOWED_MIME.get(mime, ".bin")
    filename = _store_bytes(data, digest, extension)

    thumb_name: Optional[str] = None
    width = height = None
    page_count = None
    text_excerpt = ""
    images_for_faces: List[Tuple[int, "imaging.Image.Image"]] = []

    if kind == "photo":
        try:
            image = imaging.open_image(data)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=400, detail=f"Imagem ilegivel '{original_name}': {exc}") from exc
        width, height = image.width, image.height
        thumb_name = f"{digest}.jpg"
        imaging.make_thumbnail(image, config.THUMBS_DIR / thumb_name)
        images_for_faces.append((0, image))
    else:
        render = config.PDF_FACE_PAGES if (config.PDF_DETECT_FACES and face_engine.available()) else 0
        info = pdfs.inspect(data, render_pages=render)
        page_count = info.page_count or None
        text_excerpt = info.text
        if info.cover is not None:
            thumb_name = f"{digest}.jpg"
            imaging.make_thumbnail(info.cover, config.THUMBS_DIR / thumb_name)
            width, height = info.cover.width, info.cover.height
        for number, page_image in enumerate(info.pages, start=1):
            images_for_faces.append((number, page_image))

    with db.transaction() as tx:
        cursor = tx.execute(
            """
            INSERT INTO attachments (record_id, kind, filename, thumb_name, original_name, mime, sha256,
                                     size_bytes, width, height, page_count, text_excerpt, position)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (record_id, kind, filename, thumb_name, original_name, mime, digest,
             len(data), width, height, page_count, text_excerpt, position),
        )
        attachment_id = int(cursor.lastrowid)

    detected = 0
    for page_number, image in images_for_faces:
        detected += _process_faces(record_id, attachment_id, image, page_number)

    if detected:
        with db.transaction() as tx:
            tx.execute("UPDATE attachments SET face_count = ? WHERE id = ?", (detected, attachment_id))

    return attachment_id


def _process_faces(record_id: int, attachment_id: int, image, page: int) -> int:
    if not face_engine.available():
        return 0

    try:
        detections = face_engine.detect(imaging.to_bgr(image))
    except Exception as exc:  # noqa: BLE001
        log.warning("Detecao facial falhou (anexo #%s): %s", attachment_id, exc)
        return 0
    if not detections:
        return 0

    for detection in detections:
        person_id: Optional[int] = None
        similarity: Optional[float] = None

        if config.FACE_AUTO_CLUSTER and detection.embedding is not None:
            person_id, similarity = face_index.best_person(detection.embedding, config.FACE_MATCH_THRESHOLD)

        crop_name: Optional[str] = f"{uuid.uuid4().hex}.jpg"
        try:
            imaging.save_face_crop(
                image, (detection.x, detection.y, detection.w, detection.h), config.FACES_DIR / crop_name
            )
        except Exception as exc:  # noqa: BLE001
            log.debug("Nao foi possivel guardar recorte de rosto: %s", exc)
            crop_name = None

        with db.transaction() as tx:
            if person_id is None and config.FACE_AUTO_CLUSTER and detection.embedding is not None:
                total = tx.execute("SELECT COUNT(*) AS n FROM persons").fetchone()["n"]
                cursor = tx.execute("INSERT INTO persons (name, named) VALUES (?, 0)", (f"Pessoa {total + 1}",))
                person_id = int(cursor.lastrowid)

            cursor = tx.execute(
                """
                INSERT INTO faces (record_id, attachment_id, person_id, page, x, y, w, h,
                                   det_score, age, gender, crop_name, embedding, similarity)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (record_id, attachment_id, person_id, page, detection.x, detection.y, detection.w, detection.h,
                 detection.det_score, detection.age, detection.gender, crop_name,
                 face_engine.to_blob(detection.embedding), round(similarity, 4) if similarity else None),
            )
            face_id = int(cursor.lastrowid)

            if person_id is not None and crop_name:
                tx.execute(
                    "UPDATE persons SET cover_face = COALESCE(cover_face, ?), updated_at = datetime('now') WHERE id = ?",
                    (face_id, person_id),
                )

        face_index.mark_dirty()

    return len(detections)


def _delete_attachment_files(rows: List[sqlite3.Row]) -> None:
    """Apaga ficheiros do disco apenas se nenhum outro anexo partilhar o mesmo hash."""
    conn = db.get_conn()
    for row in rows:
        remaining = conn.execute(
            "SELECT COUNT(*) AS n FROM attachments WHERE sha256 = ?", (row["sha256"],)
        ).fetchone()["n"]
        if remaining == 0:
            (config.FILES_DIR / row["filename"]).unlink(missing_ok=True)
            if row["thumb_name"]:
                (config.THUMBS_DIR / row["thumb_name"]).unlink(missing_ok=True)


def _cleanup_orphan_persons(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        DELETE FROM persons
        WHERE named = 0 AND id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)
        """
    )


# --------------------------------------------------------------------------- #
# Estado
# --------------------------------------------------------------------------- #

@app.get("/health", response_model=HealthOut)
def health() -> HealthOut:
    conn = db.get_conn()
    return HealthOut(
        status="ok",
        version=VERSION,
        face_engine=face_engine.state(),
        face_model=config.FACE_MODEL,
        pdf_engine="pymupdf" if pdfs.available() else "indisponivel",
        auth_required=bool(config.API_KEY),
        records=conn.execute("SELECT COUNT(*) AS n FROM records").fetchone()["n"],
        attachments=conn.execute("SELECT COUNT(*) AS n FROM attachments").fetchone()["n"],
        persons=conn.execute("SELECT COUNT(*) AS n FROM persons").fetchone()["n"],
        faces=conn.execute("SELECT COUNT(*) AS n FROM faces").fetchone()["n"],
    )


# --------------------------------------------------------------------------- #
# 1) CRIAR REGISTO
# --------------------------------------------------------------------------- #

@app.post("/api/extract", response_model=ExtractResult, dependencies=[Auth])
def extract_pdf(file: UploadFile = File(..., description="PDF do relatorio")) -> ExtractResult:
    """Le um PDF e devolve os dados extraidos SEM gravar nada.

    Serve para a app mostrar o formulario pre-preenchido e o utilizador confirmar.
    """
    data, mime, kind = _read_upload(file)
    if kind != "pdf":
        raise HTTPException(status_code=415, detail="Este endpoint aceita apenas PDF.")
    if not pdfs.available():
        raise HTTPException(status_code=503, detail="PyMuPDF nao instalado no servidor - extracao indisponivel.")

    parsed = rvp.parse(data)
    if parsed is None:
        return ExtractResult(
            matched=False, filename=file.filename or "",
            aviso="O PDF nao contem campos de formulario preenchiveis. Preenche os dados manualmente.",
        )

    info = pdfs.inspect(data, render_pages=0)
    preview = RecordOut(id=0, source="pdf", **_preview_fields(parsed))
    preview.tripulacao = [
        CrewOut(funcao=c.funcao, categoria=c.categoria, matricula=c.matricula, nome=c.nome)
        for c in parsed.tripulacao
    ]
    preview.material = [
        MaterialOut(item=m.item, verificado=m.verificado, quantidade=m.quantidade) for m in parsed.material
    ]
    preview.ocorrencias = [
        OccurrenceOut(numero=o.numero, area=o.area, expediente=o.expediente, npp_nuipc=o.npp_nuipc,
                      hora_chegada=o.hora_chegada, hora_saida=o.hora_saida, supervisor=o.supervisor,
                      descricao=o.descricao)
        for o in parsed.ocorrencias
    ]
    preview.autos = [
        OffenceOut(numero=a.numero, npp_nuipc=a.npp_nuipc, responsavel=a.responsavel, motivo=a.motivo, local=a.local)
        for a in parsed.autos
    ]
    preview.inspecao = [InspectionOut(item=i.item, condicao=i.condicao) for i in parsed.inspecao]

    return ExtractResult(
        matched=parsed.matched,
        template=parsed.template,
        filename=file.filename or "",
        page_count=info.page_count,
        dados=preview,
        aviso="" if parsed.matched else "Formulario nao reconhecido como RVP - confere os campos extraidos.",
    )


def _preview_fields(parsed: rvp.RvpData) -> Dict[str, object]:
    fields = _rvp_to_fields(parsed)
    fields["km_percorridos"] = parsed.km_percorridos
    return fields


@app.post("/api/records", response_model=UploadResult, dependencies=[Auth])
def create_record(
    files: Optional[List[UploadFile]] = File(None, description="Fotos e/ou PDFs"),
    metadata: str = Form("", description="JSON opcional com campos do registo (sobrepoe-se ao PDF)"),
    author: str = Form(""),
    device: str = Form(""),
    notas: str = Form(""),
    tags: str = Form(""),
    latitude: Optional[float] = Form(None),
    longitude: Optional[float] = Form(None),
    extrair_pdf: bool = Form(True, description="Extrair automaticamente os dados do 1o PDF"),
) -> UploadResult:
    """CRIAR REGISTO: guarda os ficheiros e, se houver PDF, extrai os dados do formulario."""
    uploads = [f for f in (files or []) if f is not None and (f.filename or "").strip()]

    payload = RecordCreate()
    if metadata.strip():
        try:
            payload = RecordCreate(**json.loads(metadata))
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=400, detail=f"Campo 'metadata' invalido: {exc}") from exc

    prepared: List[Tuple[bytes, str, str, str]] = []
    for upload in uploads:
        data, mime, kind = _read_upload(upload)
        prepared.append((data, mime, kind, upload.filename or f"ficheiro{config.ALLOWED_MIME.get(mime, '')}"))

    avisos: List[str] = []
    parsed: Optional[rvp.RvpData] = None
    if extrair_pdf:
        for data, _mime, kind, name in prepared:
            if kind != "pdf":
                continue
            if not pdfs.available():
                avisos.append("PyMuPDF nao instalado: o PDF foi guardado mas os dados nao foram extraidos.")
                break
            parsed = rvp.parse(data)
            if parsed is None:
                avisos.append(f"'{name}' nao tem campos de formulario preenchiveis - dados nao extraidos.")
            elif not parsed.matched:
                avisos.append(f"'{name}' nao foi reconhecido como RVP - confere os dados extraidos.")
            break

    fields: Dict[str, object] = {"source": "pdf" if parsed else "manual"}
    if parsed is not None:
        fields.update(_rvp_to_fields(parsed))

    for name in RECORD_TEXT_FIELDS:
        value = getattr(payload, name, None)
        if value is not None and str(value).strip():
            fields[name] = str(value).strip()
    for name in RECORD_NUMBER_FIELDS:
        value = getattr(payload, name, None)
        if value is not None:
            fields[name] = value

    if author.strip():
        fields["author"] = author.strip()
    if device.strip():
        fields["device"] = device.strip()
    if notas.strip():
        fields["notas"] = notas.strip()
    if latitude is not None:
        fields["latitude"] = latitude
    if longitude is not None:
        fields["longitude"] = longitude

    combined_tags = _tags_to_str((payload.tags or []) + _tags_to_list(tags))
    if combined_tags:
        fields["tags"] = combined_tags

    if not str(fields.get("title", "")).strip():
        fields["title"] = str(fields.get("indicativo") or "").strip() or "Registo sem titulo"

    columns = ", ".join(fields.keys())
    placeholders = ", ".join("?" for _ in fields)
    with db.transaction() as tx:
        cursor = tx.execute(f"INSERT INTO records ({columns}) VALUES ({placeholders})", list(fields.values()))
        record_id = int(cursor.lastrowid)
        if parsed is not None:
            _write_blocks(tx, record_id, parsed)
        _write_blocks_from_update(tx, record_id, payload)
        _link_crew_to_persons(tx, record_id)

    rostos = 0
    for position, (data, mime, kind, name) in enumerate(prepared):
        attachment_id = _add_attachment(record_id, data, mime, kind, name, position)
        rostos += db.get_conn().execute(
            "SELECT face_count AS n FROM attachments WHERE id = ?", (attachment_id,)
        ).fetchone()["n"]

    with db.transaction() as tx:
        _refresh_record(tx, record_id)

    log.info(
        "Registo #%s criado (%d anexos, %d rostos)%s",
        record_id, len(prepared), rostos, " [dados extraidos do PDF]" if parsed else "",
    )
    return UploadResult(
        record=_record_out(_get_record_row(record_id)),
        created=True,
        anexos_novos=len(prepared),
        rostos_detetados=rostos,
        extraido_do_pdf=parsed is not None,
        avisos=avisos,
    )


@app.post("/api/records/{record_id}/attachments", response_model=UploadResult, dependencies=[Auth])
def add_attachments(
    record_id: int,
    files: List[UploadFile] = File(..., description="Fotos e/ou PDFs a acrescentar"),
) -> UploadResult:
    _get_record_row(record_id)
    conn = db.get_conn()
    start = conn.execute(
        "SELECT COALESCE(MAX(position), -1) + 1 AS n FROM attachments WHERE record_id = ?", (record_id,)
    ).fetchone()["n"]

    rostos = 0
    for offset, upload in enumerate(files):
        data, mime, kind = _read_upload(upload)
        attachment_id = _add_attachment(record_id, data, mime, kind, upload.filename or "", start + offset)
        rostos += conn.execute(
            "SELECT face_count AS n FROM attachments WHERE id = ?", (attachment_id,)
        ).fetchone()["n"]

    with db.transaction() as tx:
        _refresh_record(tx, record_id)

    return UploadResult(
        record=_record_out(_get_record_row(record_id)),
        created=False,
        anexos_novos=len(files),
        rostos_detetados=rostos,
    )


@app.patch("/api/records/{record_id}", response_model=RecordOut, dependencies=[Auth])
def update_record(record_id: int, payload: RecordUpdate) -> RecordOut:
    _get_record_row(record_id)

    fields: Dict[str, object] = {}
    for name in RECORD_TEXT_FIELDS:
        value = getattr(payload, name, None)
        if value is not None:
            fields[name] = str(value).strip()
    for name in RECORD_NUMBER_FIELDS:
        value = getattr(payload, name, None)
        if value is not None:
            fields[name] = value
    if payload.tags is not None:
        fields["tags"] = _tags_to_str(payload.tags)

    with db.transaction() as tx:
        if fields:
            assignments = ", ".join(f"{key} = ?" for key in fields)
            tx.execute(f"UPDATE records SET {assignments} WHERE id = ?", [*fields.values(), record_id])
        _write_blocks_from_update(tx, record_id, payload)
        _link_crew_to_persons(tx, record_id)
        _refresh_record(tx, record_id)

    return _record_out(_get_record_row(record_id))


@app.delete("/api/records/{record_id}", dependencies=[Auth])
def delete_record(record_id: int) -> JSONResponse:
    _get_record_row(record_id)
    conn = db.get_conn()

    attachments = conn.execute(
        "SELECT sha256, filename, thumb_name FROM attachments WHERE record_id = ?", (record_id,)
    ).fetchall()
    crops = conn.execute(
        "SELECT crop_name FROM faces WHERE record_id = ? AND crop_name IS NOT NULL", (record_id,)
    ).fetchall()

    with db.transaction() as tx:
        tx.execute(
            "UPDATE persons SET cover_face = NULL WHERE cover_face IN (SELECT id FROM faces WHERE record_id = ?)",
            (record_id,),
        )
        tx.execute("DELETE FROM records WHERE id = ?", (record_id,))
        _cleanup_orphan_persons(tx)

    _delete_attachment_files(attachments)
    for crop in crops:
        (config.FACES_DIR / crop["crop_name"]).unlink(missing_ok=True)

    face_index.mark_dirty()
    return JSONResponse({"deleted": record_id})


@app.delete("/api/attachments/{attachment_id}", dependencies=[Auth])
def delete_attachment(attachment_id: int) -> JSONResponse:
    conn = db.get_conn()
    row = conn.execute("SELECT * FROM attachments WHERE id = ?", (attachment_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Anexo nao encontrado.")

    crops = conn.execute(
        "SELECT crop_name FROM faces WHERE attachment_id = ? AND crop_name IS NOT NULL", (attachment_id,)
    ).fetchall()

    with db.transaction() as tx:
        tx.execute(
            "UPDATE persons SET cover_face = NULL WHERE cover_face IN (SELECT id FROM faces WHERE attachment_id = ?)",
            (attachment_id,),
        )
        tx.execute("DELETE FROM attachments WHERE id = ?", (attachment_id,))
        _cleanup_orphan_persons(tx)
        _refresh_record(tx, row["record_id"])

    _delete_attachment_files([row])
    for crop in crops:
        (config.FACES_DIR / crop["crop_name"]).unlink(missing_ok=True)

    face_index.mark_dirty()
    return JSONResponse({"deleted": attachment_id, "record_id": row["record_id"]})


# --------------------------------------------------------------------------- #
# 2) PESQUISAR REGISTO
# --------------------------------------------------------------------------- #

@app.get("/api/records", response_model=RecordPage, dependencies=[Auth])
def search_records(
    q: str = Query("", description="Texto livre em todo o registo (inclui texto dos PDFs)"),
    indicativo: str = Query(""),
    matricula: str = Query("", description="Matricula da viatura"),
    agente: str = Query("", description="Nome ou numero de matricula de um tripulante"),
    esquadra: str = Query(""),
    divisao: str = Query(""),
    turno: str = Query(""),
    nuipc: str = Query("", description="NPP/NUIPC em ocorrencias ou autos"),
    tag: str = Query(""),
    person_id: Optional[int] = Query(None, description="Registos onde esta pessoa foi reconhecida"),
    data_de: str = Query("", description="AAAA-MM-DD"),
    data_ate: str = Query("", description="AAAA-MM-DD"),
    com_fotos: Optional[bool] = Query(None),
    com_pdf: Optional[bool] = Query(None),
    com_nao_conformes: Optional[bool] = Query(None),
    order: str = Query("recent", pattern="^(recent|oldest|data|indicativo|matricula)$"),
    limit: int = Query(40, ge=1, le=200),
    offset: int = Query(0, ge=0),
) -> RecordPage:
    """PESQUISAR REGISTO por texto livre e/ou filtros estruturados."""
    where: List[str] = []
    params: List[object] = []

    for token in db.normalize(q).split():
        where.append("r.search_text LIKE ?")
        params.append(f"%{token}%")

    for column, value in (
        ("indicativo", indicativo), ("viatura_matricula", matricula),
        ("esquadra", esquadra), ("divisao", divisao), ("turno", turno),
    ):
        if value.strip():
            where.append(f"LOWER(r.{column}) LIKE ?")
            params.append(f"%{value.strip().lower()}%")

    if agente.strip():
        where.append(
            "EXISTS (SELECT 1 FROM record_crew c WHERE c.record_id = r.id"
            " AND (LOWER(c.nome) LIKE ? OR c.matricula LIKE ?))"
        )
        params += [f"%{agente.strip().lower()}%", f"%{agente.strip()}%"]

    if nuipc.strip():
        pattern = f"%{nuipc.strip().lower()}%"
        where.append(
            "(EXISTS (SELECT 1 FROM record_occurrences o WHERE o.record_id = r.id AND LOWER(o.npp_nuipc) LIKE ?)"
            " OR EXISTS (SELECT 1 FROM record_offences a WHERE a.record_id = r.id AND LOWER(a.npp_nuipc) LIKE ?))"
        )
        params += [pattern, pattern]

    if tag.strip():
        where.append("(',' || LOWER(r.tags) || ',') LIKE ?")
        params.append(f"%,{tag.strip().lower()},%")

    if person_id is not None:
        where.append("EXISTS (SELECT 1 FROM faces f WHERE f.record_id = r.id AND f.person_id = ?)")
        params.append(person_id)

    if data_de.strip():
        where.append("COALESCE(r.data, date(r.created_at)) >= ?")
        params.append(data_de.strip())
    if data_ate.strip():
        where.append("COALESCE(r.data, date(r.created_at)) <= ?")
        params.append(data_ate.strip())

    if com_fotos is not None:
        where.append("r.photo_count > 0" if com_fotos else "r.photo_count = 0")
    if com_pdf is not None:
        where.append("r.pdf_count > 0" if com_pdf else "r.pdf_count = 0")
    if com_nao_conformes:
        where.append("EXISTS (SELECT 1 FROM record_inspection i WHERE i.record_id = r.id AND i.condicao = 'NC')")

    clause = f"WHERE {' AND '.join(where)}" if where else ""
    order_sql = {
        "recent": "r.created_at DESC, r.id DESC",
        "oldest": "r.created_at ASC, r.id ASC",
        "data": "COALESCE(r.data, date(r.created_at)) DESC, r.id DESC",
        "indicativo": "r.indicativo COLLATE NOCASE ASC, r.data DESC",
        "matricula": "r.viatura_matricula COLLATE NOCASE ASC, r.data DESC",
    }[order]

    conn = db.get_conn()
    total = conn.execute(f"SELECT COUNT(*) AS n FROM records r {clause}", params).fetchone()["n"]
    rows = conn.execute(
        f"SELECT r.* FROM records r {clause} ORDER BY {order_sql} LIMIT ? OFFSET ?",
        [*params, limit, offset],
    ).fetchall()

    return RecordPage(total=total, limit=limit, offset=offset, items=[_record_summary(row) for row in rows])


@app.post("/api/search/face", response_model=RecordPage, dependencies=[Auth])
def search_by_face(
    file: UploadFile = File(..., description="Foto com o rosto a procurar"),
    threshold: Optional[float] = Form(None),
    limit: int = Form(40),
) -> RecordPage:
    """PESQUISAR REGISTO POR FOTO: extrai o rosto maior e devolve registos semelhantes."""
    if not face_engine.available():
        raise HTTPException(status_code=503, detail=f"Motor facial indisponivel ({face_engine.state()}).")

    data, _mime, kind = _read_upload(file)
    if kind != "photo":
        raise HTTPException(status_code=415, detail="Envia uma imagem com o rosto a procurar.")

    try:
        image = imaging.open_image(data)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"Imagem invalida: {exc}") from exc

    detections = [d for d in face_engine.detect(imaging.to_bgr(image)) if d.embedding is not None]
    if not detections:
        raise HTTPException(status_code=404, detail="Nenhum rosto detetado na imagem enviada.")

    minimum = config.FACE_MATCH_THRESHOLD if threshold is None else float(threshold)
    matches = face_index.search(detections[0].embedding, minimum, limit=limit * 8)
    if not matches:
        return RecordPage(total=0, limit=limit, offset=0, items=[])

    best: Dict[int, float] = {}
    face_by_record: Dict[int, int] = {}
    for face_id, record_id, _person_id, score in matches:
        if score > best.get(record_id, -1.0):
            best[record_id] = score
            face_by_record[record_id] = face_id

    ordered = sorted(best.items(), key=lambda kv: -kv[1])[:limit]
    conn = db.get_conn()
    items: List[RecordSummary] = []
    for record_id, score in ordered:
        row = conn.execute("SELECT * FROM records WHERE id = ?", (record_id,)).fetchone()
        if row is None:
            continue
        face_row = conn.execute(
            "SELECT f.*, p.name AS person_name FROM faces f LEFT JOIN persons p ON p.id = f.person_id WHERE f.id = ?",
            (face_by_record[record_id],),
        ).fetchone()
        matched = [_face_out(face_row, similarity=score)] if face_row else []
        items.append(_record_summary(row, score=score, matched_faces=matched))

    return RecordPage(total=len(items), limit=limit, offset=0, items=items)


@app.get("/api/records/{record_id}", response_model=RecordOut, dependencies=[Auth])
def get_record(record_id: int) -> RecordOut:
    return _record_out(_get_record_row(record_id))


@app.get("/api/filters", dependencies=[Auth])
def list_filters() -> JSONResponse:
    """Valores distintos para preencher os filtros de pesquisa na app."""
    conn = db.get_conn()

    def distinct(column: str, table: str = "records") -> List[str]:
        rows = conn.execute(
            f"SELECT DISTINCT {column} AS v FROM {table} WHERE {column} <> '' ORDER BY {column} COLLATE NOCASE"
        ).fetchall()
        return [r["v"] for r in rows]

    tag_counts: Dict[str, int] = {}
    for row in conn.execute("SELECT tags FROM records WHERE tags <> ''"):
        for tag in _tags_to_list(row["tags"]):
            tag_counts[tag] = tag_counts.get(tag, 0) + 1

    return JSONResponse({
        "indicativos": distinct("indicativo"),
        "matriculas": distinct("viatura_matricula"),
        "esquadras": distinct("esquadra"),
        "divisoes": distinct("divisao"),
        "turnos": distinct("turno"),
        "agentes": distinct("nome", "record_crew"),
        "tags": [{"tag": t, "count": c} for t, c in sorted(tag_counts.items(), key=lambda kv: (-kv[1], kv[0].lower()))],
    })


# --------------------------------------------------------------------------- #
# Ficheiros
# --------------------------------------------------------------------------- #

def _attachment_row(attachment_id: int) -> sqlite3.Row:
    row = db.get_conn().execute("SELECT * FROM attachments WHERE id = ?", (attachment_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Anexo nao encontrado.")
    return row


@app.get("/api/attachments/{attachment_id}/file", dependencies=[Auth])
def get_attachment_file(attachment_id: int, download: bool = Query(False)) -> FileResponse:
    row = _attachment_row(attachment_id)
    path = config.FILES_DIR / row["filename"]
    if not path.exists():
        raise HTTPException(status_code=404, detail="Ficheiro em falta no disco.")

    name = row["original_name"] or row["filename"]
    headers = {"Cache-Control": "public, max-age=31536000"}
    if download:
        headers["Content-Disposition"] = f'attachment; filename="{name}"'
    else:
        headers["Content-Disposition"] = f'inline; filename="{name}"'
    return FileResponse(path, media_type=row["mime"] or "application/octet-stream", headers=headers)


@app.get("/api/attachments/{attachment_id}/thumb", dependencies=[Auth])
def get_attachment_thumb(attachment_id: int) -> FileResponse:
    row = _attachment_row(attachment_id)
    if not row["thumb_name"]:
        raise HTTPException(status_code=404, detail="Este anexo nao tem miniatura.")
    path = config.THUMBS_DIR / row["thumb_name"]
    if not path.exists():
        raise HTTPException(status_code=404, detail="Miniatura em falta no disco.")
    return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=31536000"})


@app.get("/api/faces/{face_id}/crop", dependencies=[Auth])
def get_face_crop(face_id: int) -> FileResponse:
    row = db.get_conn().execute("SELECT crop_name FROM faces WHERE id = ?", (face_id,)).fetchone()
    if row is None or not row["crop_name"]:
        raise HTTPException(status_code=404, detail="Recorte de rosto nao encontrado.")
    path = config.FACES_DIR / row["crop_name"]
    if not path.exists():
        raise HTTPException(status_code=404, detail="Ficheiro do recorte em falta.")
    return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "public, max-age=31536000"})


# --------------------------------------------------------------------------- #
# Pessoas
# --------------------------------------------------------------------------- #

@app.get("/api/persons", response_model=List[PersonOut], dependencies=[Auth])
def list_persons(
    named_only: bool = Query(False),
    min_faces: int = Query(1, ge=0),
    limit: int = Query(300, ge=1, le=2000),
) -> List[PersonOut]:
    clause = "WHERE p.named = 1" if named_only else ""
    rows = db.get_conn().execute(
        f"""
        SELECT p.*,
               (SELECT COUNT(*) FROM faces f WHERE f.person_id = p.id) AS face_count,
               (SELECT COUNT(DISTINCT f.record_id) FROM faces f WHERE f.person_id = p.id) AS record_count
        FROM persons p
        {clause}
        ORDER BY p.named DESC, face_count DESC, p.id ASC
        LIMIT ?
        """,
        (limit,),
    ).fetchall()

    return [
        PersonOut(
            id=row["id"],
            name=row["name"] or "",
            named=bool(row["named"]),
            matricula=row["matricula"] or "",
            notes=row["notes"] or "",
            face_count=row["face_count"],
            record_count=row["record_count"],
            cover_url=f"/api/faces/{row['cover_face']}/crop" if row["cover_face"] else None,
            created_at=row["created_at"] or "",
        )
        for row in rows
        if row["face_count"] >= min_faces
    ]


def _person_by_id(person_id: int) -> PersonOut:
    for person in list_persons(named_only=False, min_faces=0, limit=2000):
        if person.id == person_id:
            return person
    raise HTTPException(status_code=404, detail="Pessoa nao encontrada.")


@app.patch("/api/persons/{person_id}", response_model=PersonOut, dependencies=[Auth])
def update_person(person_id: int, payload: PersonUpdate) -> PersonOut:
    conn = db.get_conn()
    if conn.execute("SELECT 1 FROM persons WHERE id = ?", (person_id,)).fetchone() is None:
        raise HTTPException(status_code=404, detail="Pessoa nao encontrada.")

    with db.transaction() as tx:
        if payload.name is not None:
            name = payload.name.strip()
            tx.execute(
                "UPDATE persons SET name = ?, named = ?, updated_at = datetime('now') WHERE id = ?",
                (name, 1 if name else 0, person_id),
            )
        if payload.matricula is not None:
            tx.execute("UPDATE persons SET matricula = ? WHERE id = ?", (payload.matricula.strip(), person_id))
        if payload.notes is not None:
            tx.execute("UPDATE persons SET notes = ? WHERE id = ?", (payload.notes.strip(), person_id))

        records = tx.execute("SELECT DISTINCT record_id FROM faces WHERE person_id = ?", (person_id,)).fetchall()
        for entry in records:
            _link_crew_to_persons(tx, entry["record_id"])
            _refresh_record(tx, entry["record_id"])

    return _person_by_id(person_id)


@app.post("/api/persons/{person_id}/merge/{other_id}", response_model=PersonOut, dependencies=[Auth])
def merge_persons(person_id: int, other_id: int) -> PersonOut:
    """Junta todos os rostos de `other_id` em `person_id` (corrige agrupamento errado)."""
    if person_id == other_id:
        raise HTTPException(status_code=400, detail="Nao e possivel juntar uma pessoa a si propria.")

    conn = db.get_conn()
    for identifier in (person_id, other_id):
        if conn.execute("SELECT 1 FROM persons WHERE id = ?", (identifier,)).fetchone() is None:
            raise HTTPException(status_code=404, detail=f"Pessoa {identifier} nao encontrada.")

    with db.transaction() as tx:
        records = tx.execute("SELECT DISTINCT record_id FROM faces WHERE person_id = ?", (other_id,)).fetchall()
        tx.execute("UPDATE faces SET person_id = ? WHERE person_id = ?", (person_id, other_id))
        tx.execute("UPDATE record_crew SET person_id = ? WHERE person_id = ?", (person_id, other_id))
        tx.execute("DELETE FROM persons WHERE id = ?", (other_id,))
        for entry in records:
            _refresh_record(tx, entry["record_id"])

    face_index.mark_dirty()
    return _person_by_id(person_id)


@app.delete("/api/persons/{person_id}", dependencies=[Auth])
def delete_person(person_id: int) -> JSONResponse:
    with db.transaction() as tx:
        tx.execute("UPDATE faces SET person_id = NULL WHERE person_id = ?", (person_id,))
        tx.execute("UPDATE record_crew SET person_id = NULL WHERE person_id = ?", (person_id,))
        tx.execute("DELETE FROM persons WHERE id = ?", (person_id,))
    face_index.mark_dirty()
    return JSONResponse({"deleted": person_id})


@app.post("/api/faces/{face_id}/assign", response_model=FaceOut, dependencies=[Auth])
def assign_face(face_id: int, payload: FaceAssign) -> FaceOut:
    """Atribui um rosto a uma pessoa existente ou cria uma nova com nome."""
    conn = db.get_conn()
    row = conn.execute("SELECT * FROM faces WHERE id = ?", (face_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Rosto nao encontrado.")

    with db.transaction() as tx:
        person_id = payload.person_id
        if payload.new_person_name and payload.new_person_name.strip():
            cursor = tx.execute(
                "INSERT INTO persons (name, named) VALUES (?, 1)", (payload.new_person_name.strip(),)
            )
            person_id = int(cursor.lastrowid)
        elif person_id is not None:
            if tx.execute("SELECT 1 FROM persons WHERE id = ?", (person_id,)).fetchone() is None:
                raise HTTPException(status_code=404, detail="Pessoa nao encontrada.")

        tx.execute("UPDATE faces SET person_id = ? WHERE id = ?", (person_id, face_id))
        if person_id is not None and row["crop_name"]:
            tx.execute(
                "UPDATE persons SET cover_face = COALESCE(cover_face, ?), updated_at = datetime('now') WHERE id = ?",
                (face_id, person_id),
            )
        _cleanup_orphan_persons(tx)
        _link_crew_to_persons(tx, row["record_id"])
        _refresh_record(tx, row["record_id"])

    face_index.mark_dirty()
    updated = conn.execute(
        "SELECT f.*, p.name AS person_name FROM faces f LEFT JOIN persons p ON p.id = f.person_id WHERE f.id = ?",
        (face_id,),
    ).fetchone()
    return _face_out(updated)


# --------------------------------------------------------------------------- #
# UI web local
# --------------------------------------------------------------------------- #

@app.get("/", response_class=HTMLResponse, include_in_schema=False)
def web_ui() -> HTMLResponse:
    index_file = STATIC_DIR / "index.html"
    if not index_file.exists():
        return HTMLResponse("<h1>Leitura_Bi API</h1><p>Documentacao em <a href='/docs'>/docs</a>.</p>")
    return HTMLResponse(index_file.read_text(encoding="utf-8"))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=config.HOST, port=config.PORT, reload=False)
