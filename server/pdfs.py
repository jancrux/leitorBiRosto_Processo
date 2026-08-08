"""Tratamento de anexos PDF: miniatura da 1a pagina, texto pesquisavel e
rasterizacao de paginas para detecao de rostos.

Usa PyMuPDF (fitz). Se nao estiver instalado, os PDFs continuam a ser guardados
e associados ao registo - apenas sem miniatura, sem texto pesquisavel e sem
detecao de rostos dentro do documento.
"""

from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from typing import List, Optional

from PIL import Image

import config

log = logging.getLogger("leiturabi.pdfs")

_fitz = None
_checked = False


def _module():
    global _fitz, _checked
    if _checked:
        return _fitz
    _checked = True
    try:
        try:
            import pymupdf as module  # PyMuPDF >= 1.24
        except ImportError:
            import fitz as module  # nome antigo

        _fitz = module
        log.info("PyMuPDF %s disponivel - miniaturas, texto e formularios de PDF ativos.",
                 getattr(module, "__version__", "?"))
    except Exception as exc:  # noqa: BLE001
        log.warning("PyMuPDF indisponivel (%s). PDFs serao guardados sem miniatura/texto.", exc)
        _fitz = None
    return _fitz


def available() -> bool:
    return _module() is not None


@dataclass
class PdfInfo:
    page_count: int = 0
    text: str = ""
    cover: Optional[Image.Image] = None
    pages: List[Image.Image] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.pages is None:
            self.pages = []


def inspect(data: bytes, render_pages: int = 0, dpi: int = 150) -> PdfInfo:
    """Le um PDF em memoria: numero de paginas, texto e imagens das paginas.

    render_pages: quantas paginas rasterizar (para detecao de rostos). 0 = so a capa.
    """
    fitz = _module()
    if fitz is None:
        return PdfInfo()

    info = PdfInfo()
    try:
        document = fitz.open(stream=data, filetype="pdf")
    except Exception as exc:  # noqa: BLE001
        log.warning("PDF ilegivel: %s", exc)
        return info

    try:
        info.page_count = document.page_count

        if config.PDF_EXTRACT_TEXT:
            chunks: List[str] = []
            total = 0
            for page in document:
                try:
                    text = page.get_text("text")
                except Exception:  # noqa: BLE001
                    continue
                if not text:
                    continue
                chunks.append(text)
                total += len(text)
                if total >= config.PDF_TEXT_CHARS:
                    break
            info.text = " ".join(" ".join(chunks).split())[: config.PDF_TEXT_CHARS]

        limit = max(1, render_pages) if render_pages else 1
        matrix = fitz.Matrix(dpi / 72.0, dpi / 72.0)
        for number in range(min(limit, document.page_count)):
            try:
                pixmap = document.load_page(number).get_pixmap(matrix=matrix, alpha=False)
                image = Image.open(io.BytesIO(pixmap.tobytes("png"))).convert("RGB")
            except Exception as exc:  # noqa: BLE001
                log.debug("Falha a rasterizar pagina %d: %s", number + 1, exc)
                continue
            if info.cover is None:
                info.cover = image
            info.pages.append(image)
    finally:
        document.close()

    return info
