"""Modelos Pydantic para entrada/saida da API."""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


# --------------------------------------------------------------------------- #
# Blocos do relatorio
# --------------------------------------------------------------------------- #

class CrewOut(BaseModel):
    id: Optional[int] = None
    funcao: str = ""
    categoria: str = ""
    matricula: str = ""
    nome: str = ""
    person_id: Optional[int] = None


class MaterialOut(BaseModel):
    id: Optional[int] = None
    item: str = ""
    verificado: bool = False
    quantidade: str = ""


class OccurrenceOut(BaseModel):
    id: Optional[int] = None
    numero: int = 0
    area: str = ""
    expediente: bool = False
    npp_nuipc: str = ""
    hora_chegada: str = ""
    hora_saida: str = ""
    supervisor: bool = False
    descricao: str = ""


class OffenceOut(BaseModel):
    id: Optional[int] = None
    numero: int = 0
    npp_nuipc: str = ""
    responsavel: str = ""
    motivo: str = ""
    local: str = ""


class InspectionOut(BaseModel):
    id: Optional[int] = None
    item: str = ""
    condicao: str = ""


# --------------------------------------------------------------------------- #
# Rostos e anexos
# --------------------------------------------------------------------------- #

class FaceOut(BaseModel):
    id: int
    record_id: int
    attachment_id: int
    person_id: Optional[int] = None
    person_name: Optional[str] = None
    page: int = 0
    x: int = 0
    y: int = 0
    w: int = 0
    h: int = 0
    det_score: float = 0.0
    age: Optional[int] = None
    gender: Optional[str] = None
    crop_url: Optional[str] = None
    similarity: Optional[float] = None


class AttachmentOut(BaseModel):
    id: int
    record_id: int
    kind: str = "photo"
    original_name: str = ""
    mime: str = ""
    size_bytes: int = 0
    width: Optional[int] = None
    height: Optional[int] = None
    page_count: Optional[int] = None
    face_count: int = 0
    created_at: str = ""
    file_url: str = ""
    thumb_url: str = ""
    faces: List[FaceOut] = Field(default_factory=list)


# --------------------------------------------------------------------------- #
# Registo
# --------------------------------------------------------------------------- #

class RecordBase(BaseModel):
    title: str = ""
    indicativo: str = ""
    data: Optional[str] = None
    data_original: str = ""
    turno: str = ""
    divisao: str = ""
    esquadra: str = ""
    comando: str = ""
    viatura_marca: str = ""
    viatura_modelo: str = ""
    viatura_matricula: str = ""
    condutor: str = ""
    data_hora: str = ""
    combustivel_lt: str = ""
    km_iniciais: Optional[int] = None
    km_finais: Optional[int] = None
    observacoes: str = ""
    anomalias: str = ""
    notas: str = ""
    tags: List[str] = Field(default_factory=list)
    arvorado_cp: str = ""
    graduado: str = ""
    entregue_por: str = ""
    recebido_por: str = ""
    author: str = ""
    device: str = ""
    latitude: Optional[float] = None
    longitude: Optional[float] = None


class RecordOut(RecordBase):
    id: int
    source: str = "manual"
    template: str = ""
    created_at: str = ""
    updated_at: str = ""
    photo_count: int = 0
    pdf_count: int = 0
    face_count: int = 0
    km_percorridos: Optional[int] = None
    score: Optional[float] = None

    tripulacao: List[CrewOut] = Field(default_factory=list)
    material: List[MaterialOut] = Field(default_factory=list)
    ocorrencias: List[OccurrenceOut] = Field(default_factory=list)
    autos: List[OffenceOut] = Field(default_factory=list)
    inspecao: List[InspectionOut] = Field(default_factory=list)
    anexos: List[AttachmentOut] = Field(default_factory=list)
    pessoas: List[str] = Field(default_factory=list)


class RecordSummary(BaseModel):
    """Versao leve para listagens e resultados de pesquisa."""

    id: int
    title: str = ""
    indicativo: str = ""
    data: Optional[str] = None
    data_original: str = ""
    turno: str = ""
    divisao: str = ""
    esquadra: str = ""
    viatura_matricula: str = ""
    viatura_marca: str = ""
    condutor: str = ""
    km_percorridos: Optional[int] = None
    source: str = "manual"
    author: str = ""
    created_at: str = ""
    photo_count: int = 0
    pdf_count: int = 0
    face_count: int = 0
    occurrence_count: int = 0
    offence_count: int = 0
    nao_conformes: int = 0
    tripulacao: List[str] = Field(default_factory=list)
    tags: List[str] = Field(default_factory=list)
    cover_url: Optional[str] = None
    score: Optional[float] = None
    matched_faces: List[FaceOut] = Field(default_factory=list)


class RecordPage(BaseModel):
    total: int = 0
    limit: int = 0
    offset: int = 0
    items: List[RecordSummary] = Field(default_factory=list)


class RecordUpdate(BaseModel):
    """Todos os campos opcionais: so os enviados sao alterados."""

    title: Optional[str] = None
    indicativo: Optional[str] = None
    data: Optional[str] = None
    data_original: Optional[str] = None
    turno: Optional[str] = None
    divisao: Optional[str] = None
    esquadra: Optional[str] = None
    comando: Optional[str] = None
    viatura_marca: Optional[str] = None
    viatura_modelo: Optional[str] = None
    viatura_matricula: Optional[str] = None
    condutor: Optional[str] = None
    data_hora: Optional[str] = None
    combustivel_lt: Optional[str] = None
    km_iniciais: Optional[int] = None
    km_finais: Optional[int] = None
    observacoes: Optional[str] = None
    anomalias: Optional[str] = None
    notas: Optional[str] = None
    tags: Optional[List[str]] = None
    arvorado_cp: Optional[str] = None
    graduado: Optional[str] = None
    entregue_por: Optional[str] = None
    recebido_por: Optional[str] = None
    author: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None

    tripulacao: Optional[List[CrewOut]] = None
    material: Optional[List[MaterialOut]] = None
    ocorrencias: Optional[List[OccurrenceOut]] = None
    autos: Optional[List[OffenceOut]] = None
    inspecao: Optional[List[InspectionOut]] = None


class RecordCreate(RecordUpdate):
    """Criacao manual de registo (sem PDF)."""


class ExtractResult(BaseModel):
    """Pre-visualizacao da extracao de um PDF, antes de gravar."""

    matched: bool = False
    template: str = ""
    filename: str = ""
    page_count: int = 0
    dados: Optional[RecordOut] = None
    aviso: str = ""


class UploadResult(BaseModel):
    record: RecordOut
    created: bool = True
    anexos_novos: int = 0
    rostos_detetados: int = 0
    extraido_do_pdf: bool = False
    avisos: List[str] = Field(default_factory=list)


class PersonOut(BaseModel):
    id: int
    name: str = ""
    named: bool = False
    matricula: str = ""
    notes: str = ""
    record_count: int = 0
    face_count: int = 0
    cover_url: Optional[str] = None
    created_at: str = ""


class PersonUpdate(BaseModel):
    name: Optional[str] = None
    matricula: Optional[str] = None
    notes: Optional[str] = None


class FaceAssign(BaseModel):
    person_id: Optional[int] = None
    new_person_name: Optional[str] = None


class HealthOut(BaseModel):
    status: str = "ok"
    version: str = ""
    face_engine: str = ""
    face_model: str = ""
    pdf_engine: str = ""
    auth_required: bool = False
    records: int = 0
    attachments: int = 0
    persons: int = 0
    faces: int = 0
