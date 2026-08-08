"""Motor de detecao e reconhecimento facial (InsightFace + onnxruntime, CPU).

O modelo e carregado preguicosamente na primeira utilizacao. Se o insightface
nao estiver instalado (ou os modelos nao descarregarem), o servidor continua a
funcionar sem funcionalidades faciais em vez de rebentar no arranque.
"""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from typing import List, Optional

import numpy as np

import config

log = logging.getLogger("leiturabi.faces")

_lock = threading.Lock()
_analyzer = None
_state = "nao carregado"
_load_error: Optional[str] = None


@dataclass
class DetectedFace:
    x: int
    y: int
    w: int
    h: int
    det_score: float
    embedding: Optional[np.ndarray]
    age: Optional[int]
    gender: Optional[str]


def state() -> str:
    if config.FACE_DISABLED:
        return "desativado"
    return _state


def load_error() -> Optional[str]:
    return _load_error


def available() -> bool:
    if config.FACE_DISABLED:
        return False
    return _get_analyzer() is not None


def _get_analyzer():
    """Carrega o FaceAnalysis uma unica vez (thread-safe)."""
    global _analyzer, _state, _load_error

    if config.FACE_DISABLED:
        _state = "desativado"
        return None
    if _analyzer is not None:
        return _analyzer
    if _load_error is not None:
        return None

    with _lock:
        if _analyzer is not None:
            return _analyzer
        if _load_error is not None:
            return None
        try:
            log.info("A carregar modelo facial '%s' (pode demorar no 1o arranque)...", config.FACE_MODEL)
            from insightface.app import FaceAnalysis

            analyzer = FaceAnalysis(
                name=config.FACE_MODEL,
                providers=["CPUExecutionProvider"],
                allowed_modules=["detection", "recognition", "genderage"],
            )
            analyzer.prepare(
                ctx_id=0,
                det_size=(config.FACE_DET_SIZE, config.FACE_DET_SIZE),
                det_thresh=config.FACE_DET_THRESHOLD,
            )
            _analyzer = analyzer
            _state = "pronto"
            log.info("Modelo facial pronto.")
        except Exception as exc:  # noqa: BLE001 - degradacao controlada
            _load_error = f"{type(exc).__name__}: {exc}"
            _state = "indisponivel"
            log.warning("Motor facial indisponivel (%s). O servidor continua sem detecao de rostos.", _load_error)
            return None
    return _analyzer


def warmup() -> None:
    """Forca o carregamento do modelo (chamado no arranque, em background)."""
    _get_analyzer()


def detect(image_bgr: np.ndarray) -> List[DetectedFace]:
    """Deteta rostos numa imagem BGR (formato OpenCV) e devolve embeddings normalizados."""
    analyzer = _get_analyzer()
    if analyzer is None:
        return []

    try:
        raw = analyzer.get(image_bgr)
    except Exception as exc:  # noqa: BLE001
        log.warning("Falha na detecao facial: %s", exc)
        return []

    height, width = image_bgr.shape[:2]
    results: List[DetectedFace] = []

    for face in raw:
        score = float(getattr(face, "det_score", 0.0) or 0.0)
        if score < config.FACE_DET_THRESHOLD:
            continue

        x1, y1, x2, y2 = [int(round(v)) for v in face.bbox]
        x1 = max(0, min(x1, width - 1))
        y1 = max(0, min(y1, height - 1))
        x2 = max(x1 + 1, min(x2, width))
        y2 = max(y1 + 1, min(y2, height))

        embedding = getattr(face, "normed_embedding", None)
        if embedding is None:
            embedding = getattr(face, "embedding", None)
            if embedding is not None:
                embedding = normalize_vector(np.asarray(embedding, dtype=np.float32))
        if embedding is not None:
            embedding = np.asarray(embedding, dtype=np.float32)

        gender = None
        raw_gender = getattr(face, "gender", None)
        if raw_gender is not None:
            gender = "M" if int(raw_gender) == 1 else "F"

        age = getattr(face, "age", None)
        age = int(age) if age is not None else None

        results.append(
            DetectedFace(
                x=x1,
                y=y1,
                w=x2 - x1,
                h=y2 - y1,
                det_score=score,
                embedding=embedding,
                age=age,
                gender=gender,
            )
        )

    results.sort(key=lambda f: f.w * f.h, reverse=True)
    return results


def normalize_vector(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm == 0.0:
        return vector.astype(np.float32)
    return (vector / norm).astype(np.float32)


def to_blob(embedding: Optional[np.ndarray]) -> Optional[bytes]:
    if embedding is None:
        return None
    return np.asarray(embedding, dtype=np.float32).tobytes()


def from_blob(blob: Optional[bytes]) -> Optional[np.ndarray]:
    if not blob:
        return None
    return np.frombuffer(blob, dtype=np.float32)


def similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Similaridade cosseno entre dois embeddings normalizados (-1 a 1)."""
    if a is None or b is None or a.shape != b.shape:
        return -1.0
    return float(np.dot(a, b))
