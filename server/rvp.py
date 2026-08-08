"""Extracao de dados do formulario "Relatorio de Viatura Policial" (PSP).

Modelo do impresso: Mod/RVP/COMETPOR/NO/V1.0/2022

O PDF e um AcroForm: os campos de texto tem nomes estaveis e sao lidos
diretamente. As 88 checkboxes tem nomes opacos e baralhados ("Check Box34"
aparece na 7a linha, nao na 5a), por isso sao mapeadas pela GEOMETRIA dos
widgets - ordenando por linha/coluna dentro de cada tabela do impresso.
Isso torna a leitura independente da numeracao interna dos campos.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, List, Optional, Tuple

log = logging.getLogger("leiturabi.rvp")

TEMPLATE_ID = "Mod/RVP/COMETPOR/NO/V1.0/2022"

# Ordem de leitura do bloco MATERIAL: linha 1 (4 colunas), depois linha 2.
MATERIAL_ITEMS: Tuple[str, ...] = (
    "Shotgun", "Placa STOP", "Drager", "Cartao Galp Frota",
    "Municoes", "Lanternas", "Colete balistico", "Livro Registo diario",
)

# Nome do campo de quantidade correspondente a cada item (tal como esta no PDF).
MATERIAL_QUANTITY_FIELD: Dict[str, str] = {
    "Shotgun": "QuantShotgun",
    "Placa STOP": "QuantPlaca STOP",
    "Drager": "QuantDrager",
    "Cartao Galp Frota": "QuantCartão Galp Frota",
    "Municoes": "QuantMunições",
    "Lanternas": "QuantLanternas",
    "Colete balistico": "QuantColete balístico",
    "Livro Registo diario": "QuantLivro Registo diário",
}

# Coluna esquerda e direita da tabela de inspecao da pagina 2, por ordem de linha.
INSPECTION_ITEMS_LEFT: Tuple[str, ...] = (
    "Pneus/rodas (estado visual)",
    "Carrocaria (estado geral)",
    "Espelhos retrovisores",
    "Vidros",
    "Escovas limpa para-brisas e oculo",
    "Estado dos farois e farolins",
    "Pneu suplente",
    "Macaco, triangulo, colete e chave de rodas",
    "Tacografo",
    "Extintor",
)

INSPECTION_ITEMS_RIGHT: Tuple[str, ...] = (
    "Documentos",
    "Estado geral de limpeza da viatura",
    "Nivel de oleo do motor",
    "Liquido de limpeza do para-brisas",
    "Estado dos bancos e cintos",
    "Curso do pedal de travoes",
    "Travao de estacionamento",
    "Volante da viatura e canhao de ignicao",
    "Indicadores do painel de instrumentos",
    "Nivel de combustivel",
)

CONDITION_LABELS = ("C", "NC", "N/A")

CREW_ROLES = ("MOTORISTA", "ARVORADO", "TRIPULANTE")

OCCURRENCE_ROWS = 10
OFFENCE_ROWS = 6
OBSERVATION_ROWS = 5
ANOMALY_ROWS = 10


@dataclass
class CrewMember:
    funcao: str = ""
    categoria: str = ""
    matricula: str = ""
    nome: str = ""

    def is_empty(self) -> bool:
        return not (self.categoria or self.matricula or self.nome)


@dataclass
class MaterialItem:
    item: str = ""
    verificado: bool = False
    quantidade: str = ""


@dataclass
class Occurrence:
    numero: int = 0
    area: str = ""
    expediente: bool = False
    npp_nuipc: str = ""
    hora_chegada: str = ""
    hora_saida: str = ""
    supervisor: bool = False
    descricao: str = ""

    def is_empty(self) -> bool:
        return not (self.area or self.npp_nuipc or self.hora_chegada or self.hora_saida or self.descricao)


@dataclass
class Offence:
    numero: int = 0
    npp_nuipc: str = ""
    responsavel: str = ""
    motivo: str = ""
    local: str = ""

    def is_empty(self) -> bool:
        return not (self.npp_nuipc or self.responsavel or self.motivo or self.local)


@dataclass
class InspectionItem:
    item: str = ""
    condicao: str = ""  # "C" | "NC" | "N/A" | ""


@dataclass
class RvpData:
    template: str = ""
    matched: bool = False

    data: str = ""              # ISO AAAA-MM-DD
    data_original: str = ""     # como aparece no impresso
    turno: str = ""
    indicativo: str = ""
    divisao: str = ""
    esquadra: str = ""
    comando: str = ""

    tripulacao: List[CrewMember] = field(default_factory=list)
    material: List[MaterialItem] = field(default_factory=list)
    ocorrencias: List[Occurrence] = field(default_factory=list)
    autos: List[Offence] = field(default_factory=list)
    inspecao: List[InspectionItem] = field(default_factory=list)

    observacoes: str = ""
    anomalias: str = ""

    viatura_marca: str = ""
    viatura_modelo: str = ""
    viatura_matricula: str = ""
    condutor: str = ""
    data_hora: str = ""
    combustivel_lt: str = ""
    km_iniciais: Optional[int] = None
    km_finais: Optional[int] = None

    arvorado_cp: str = ""
    graduado: str = ""
    entregue_por: str = ""

    @property
    def km_percorridos(self) -> Optional[int]:
        if self.km_iniciais is None or self.km_finais is None:
            return None
        delta = self.km_finais - self.km_iniciais
        return delta if delta >= 0 else None

    @property
    def titulo(self) -> str:
        partes = [p for p in (self.indicativo, self.data_original or self.data, self.turno) if p]
        return " · ".join(partes) if partes else "Relatorio de Viatura Policial"

    def nomes_tripulacao(self) -> List[str]:
        return [c.nome for c in self.tripulacao if c.nome]


# --------------------------------------------------------------------------- #
# Leitura do PDF
# --------------------------------------------------------------------------- #

def _clean(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "Sim" if value else ""
    return " ".join(str(value).split()).strip()


def _checked(value) -> bool:
    if value is None or value is False:
        return False
    if value is True:
        return True
    return _clean(value).lstrip("/").lower() not in ("", "off", "nao", "não")


def _to_int(value: str) -> Optional[int]:
    digits = re.sub(r"[^\d-]", "", value or "")
    try:
        return int(digits)
    except ValueError:
        return None


def _iso_date(raw: str) -> str:
    raw = _clean(raw)
    for fmt in ("%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y", "%Y-%m-%d", "%d/%m/%y"):
        try:
            return datetime.strptime(raw, fmt).date().isoformat()
        except ValueError:
            continue
    return ""


def parse(data: bytes) -> Optional[RvpData]:
    """Le um PDF em memoria e devolve os dados do RVP, ou None se nao for um AcroForm."""
    import pdfs  # importacao tardia: partilha o carregamento de PyMuPDF

    fitz = pdfs._module()  # noqa: SLF001 - modulo interno intencional
    if fitz is None:
        return None

    try:
        document = fitz.open(stream=data, filetype="pdf")
    except Exception as exc:  # noqa: BLE001
        log.warning("PDF ilegivel para extracao RVP: %s", exc)
        return None

    try:
        texts: Dict[str, str] = {}
        checkboxes: List[Tuple[int, float, float, bool]] = []  # (pagina, x, y, marcado)

        for page_number, page in enumerate(document, start=1):
            try:
                widgets = list(page.widgets())
            except Exception:  # noqa: BLE001
                widgets = []
            for widget in widgets:
                name = (widget.field_name or "").strip()
                if widget.field_type_string in ("CheckBox", "RadioButton"):
                    rect = widget.rect
                    checkboxes.append((page_number, float(rect.x0), float(rect.y0), _checked(widget.field_value)))
                elif name:
                    texts[name] = _clean(widget.field_value)

        if not texts and not checkboxes:
            return None

        raw_text = ""
        try:
            raw_text = document[0].get_text("text")
        except Exception:  # noqa: BLE001
            pass
    finally:
        document.close()

    result = RvpData()
    result.template = TEMPLATE_ID if TEMPLATE_ID.lower() in raw_text.lower() else ""
    result.matched = bool(result.template) or ("RELATÓRIO DE VIATURA POLICIAL" in raw_text.upper())

    if not result.matched:
        log.info("PDF com formulario mas sem cabecalho RVP reconhecido - extracao parcial.")

    _fill_texts(result, texts, raw_text)
    _fill_checkboxes(result, texts, checkboxes)
    return result


def _fill_texts(result: RvpData, texts: Dict[str, str], raw_text: str) -> None:
    get = lambda name: texts.get(name, "")  # noqa: E731

    result.data_original = get("DATA")
    result.data = _iso_date(result.data_original)
    result.turno = get("TURNO")
    result.indicativo = get("INDICATIVO")
    result.divisao = get("Divisão")
    result.esquadra = get("Esquadra")

    for line in raw_text.splitlines():
        if "COMANDO" in line.upper():
            result.comando = _clean(line)
            break

    for role in CREW_ROLES:
        member = CrewMember(
            funcao=role.capitalize(),
            categoria=get(f"CATEGORIA{role}"),
            matricula=get(f"MATRÍCULA{role}"),
            nome=get(f"NOME{role}"),
        )
        if not member.is_empty():
            result.tripulacao.append(member)

    for number in range(1, OCCURRENCE_ROWS + 1):
        occurrence = Occurrence(
            numero=number,
            area=get(f"AreaOcorr{number}"),
            npp_nuipc=get(f"NPPNUIPC{number}"),
            hora_chegada=get(f"HORA DE CHEGADA{number}"),
            hora_saida=get(f"HORA DE SAÍDA{number}"),
            descricao=get(str(number)),
        )
        result.ocorrencias.append(occurrence)

    for number in range(1, OFFENCE_ROWS + 1):
        offence = Offence(
            numero=number,
            npp_nuipc=get(f"NPPNUIPCRow{number}"),
            responsavel=get(f"ResponsávelRow{number}"),
            motivo=get(f"MotivoRow{number}"),
            local=get(f"LocalRow{number}"),
        )
        if not offence.is_empty():
            result.autos.append(offence)

    prefix = "OBSERVAÇÕES incluir informações pertinentes para os turnos seguintesRow"
    result.observacoes = "\n".join(
        line for line in (get(f"{prefix}{n}") for n in range(1, OBSERVATION_ROWS + 1)) if line
    )

    result.anomalias = "\n".join(
        line for line in (
            get(f"INFORMAÇÃO DAS ANOMALIAS VERIFICADASRow{n}") for n in range(1, ANOMALY_ROWS + 1)
        ) if line
    )

    result.viatura_marca = get("Marca")
    result.viatura_modelo = get("Modelo")
    result.viatura_matricula = get("Matrícula")
    result.condutor = get("CondutorTripulante")
    result.data_hora = get("DATAHORA")
    result.combustivel_lt = get("Abast Combustível Lt")
    result.km_iniciais = _to_int(get("KM iniciais"))
    result.km_finais = _to_int(get("KM finais"))

    result.arvorado_cp = get("ArvoradoCP")
    result.graduado = get("Graduado")
    result.entregue_por = get("Entregue")


def _fill_checkboxes(
    result: RvpData,
    texts: Dict[str, str],
    checkboxes: List[Tuple[int, float, float, bool]],
) -> None:
    """Mapeia as checkboxes pela posicao na pagina (ver docstring do modulo)."""
    page1 = sorted([c for c in checkboxes if c[0] == 1], key=lambda c: (round(c[2], 0), c[1]))
    page2 = sorted([c for c in checkboxes if c[0] == 2], key=lambda c: (round(c[2], 0), c[1]))

    # --- MATERIAL: 2 linhas x 4 colunas, no topo da pagina 1 ---
    material_boxes = page1[:8]
    if len(material_boxes) == 8:
        for position, item in enumerate(MATERIAL_ITEMS):
            result.material.append(
                MaterialItem(
                    item=item,
                    verificado=material_boxes[position][3],
                    quantidade=texts.get(MATERIAL_QUANTITY_FIELD.get(item, ""), ""),
                )
            )
    else:
        log.warning("Bloco MATERIAL com %d checkboxes (esperadas 8) - ignorado.", len(material_boxes))
        for item in MATERIAL_ITEMS:
            result.material.append(MaterialItem(item=item, quantidade=texts.get(MATERIAL_QUANTITY_FIELD.get(item, ""), "")))

    # --- OCORRENCIAS: 10 linhas x 2 colunas (expediente, supervisor) ---
    occurrence_boxes = page1[8:28]
    if len(occurrence_boxes) == 20:
        for row in range(OCCURRENCE_ROWS):
            pair = sorted(occurrence_boxes[row * 2:row * 2 + 2], key=lambda c: c[1])
            result.ocorrencias[row].expediente = pair[0][3]
            result.ocorrencias[row].supervisor = pair[1][3]
    else:
        log.warning("Bloco OCORRENCIAS com %d checkboxes (esperadas 20) - ignorado.", len(occurrence_boxes))

    result.ocorrencias = [o for o in result.ocorrencias if not o.is_empty() or o.expediente or o.supervisor]

    # --- INSPECAO: duas tabelas lado a lado, 10 linhas x 3 condicoes ---
    if len(page2) == 60:
        middle = (min(c[1] for c in page2) + max(c[1] for c in page2)) / 2
        left = sorted([c for c in page2 if c[1] < middle], key=lambda c: (round(c[2], 0), c[1]))
        right = sorted([c for c in page2 if c[1] >= middle], key=lambda c: (round(c[2], 0), c[1]))

        for boxes, items in ((left, INSPECTION_ITEMS_LEFT), (right, INSPECTION_ITEMS_RIGHT)):
            if len(boxes) != len(items) * 3:
                log.warning("Tabela de inspecao com %d checkboxes (esperadas %d).", len(boxes), len(items) * 3)
                continue
            for row, item in enumerate(items):
                triple = sorted(boxes[row * 3:row * 3 + 3], key=lambda c: c[1])
                condition = ""
                for position, box in enumerate(triple):
                    if box[3]:
                        condition = CONDITION_LABELS[position]
                        break
                result.inspecao.append(InspectionItem(item=item, condicao=condition))
    else:
        log.warning("Bloco INSPECAO com %d checkboxes (esperadas 60) - ignorado.", len(page2))
        for item in INSPECTION_ITEMS_LEFT + INSPECTION_ITEMS_RIGHT:
            result.inspecao.append(InspectionItem(item=item, condicao=""))
