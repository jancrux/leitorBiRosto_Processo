"""Indice em memoria dos embeddings faciais, para pesquisa e agrupamento rapidos."""

from __future__ import annotations

import logging
import threading
from typing import List, Optional, Tuple

import numpy as np

import db
import faces as face_engine

log = logging.getLogger("leiturabi.index")


class FaceIndex:
    """Matriz (N x 512) de embeddings normalizados mantida em memoria.

    A pesquisa e um unico produto matricial, o que torna a comparacao contra
    dezenas de milhares de rostos praticamente instantanea em CPU.
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._face_ids: np.ndarray = np.empty(0, dtype=np.int64)
        self._record_ids: np.ndarray = np.empty(0, dtype=np.int64)
        self._person_ids: np.ndarray = np.empty(0, dtype=np.int64)  # -1 = sem pessoa
        self._matrix: Optional[np.ndarray] = None
        self._dirty = True

    def mark_dirty(self) -> None:
        with self._lock:
            self._dirty = True

    def _ensure(self) -> None:
        if not self._dirty and self._matrix is not None:
            return
        with self._lock:
            if not self._dirty and self._matrix is not None:
                return

            rows = db.get_conn().execute(
                "SELECT id, record_id, person_id, embedding FROM faces WHERE embedding IS NOT NULL"
            ).fetchall()

            entries: List[Tuple[np.ndarray, int, int, int]] = []
            for row in rows:
                vector = face_engine.from_blob(row["embedding"])
                if vector is None or vector.size == 0:
                    continue
                entries.append((
                    vector,
                    int(row["id"]),
                    int(row["record_id"]),
                    int(row["person_id"]) if row["person_id"] is not None else -1,
                ))

            if entries:
                dimension = max(e[0].size for e in entries)
                usable = [e for e in entries if e[0].size == dimension]
                self._matrix = np.vstack([e[0] for e in usable]).astype(np.float32)
                self._face_ids = np.array([e[1] for e in usable], dtype=np.int64)
                self._record_ids = np.array([e[2] for e in usable], dtype=np.int64)
                self._person_ids = np.array([e[3] for e in usable], dtype=np.int64)
            else:
                self._matrix = None
                self._face_ids = np.empty(0, dtype=np.int64)
                self._record_ids = np.empty(0, dtype=np.int64)
                self._person_ids = np.empty(0, dtype=np.int64)

            self._dirty = False
            log.debug("Indice facial reconstruido: %d rostos", len(self._face_ids))

    def size(self) -> int:
        self._ensure()
        return int(self._face_ids.size)

    def search(
        self, embedding: np.ndarray, min_similarity: float, limit: int = 400
    ) -> List[Tuple[int, int, Optional[int], float]]:
        """Devolve [(face_id, record_id, person_id, similaridade)] ordenado por similaridade."""
        self._ensure()
        if self._matrix is None or embedding is None:
            return []

        query = np.asarray(embedding, dtype=np.float32)
        if query.size != self._matrix.shape[1]:
            return []

        scores = self._matrix @ query
        keep = np.where(scores >= min_similarity)[0]
        if keep.size == 0:
            return []
        order = keep[np.argsort(-scores[keep])][:limit]

        return [
            (
                int(self._face_ids[i]),
                int(self._record_ids[i]),
                int(self._person_ids[i]) if self._person_ids[i] >= 0 else None,
                float(scores[i]),
            )
            for i in order
        ]

    def best_person(self, embedding: np.ndarray, threshold: float) -> Tuple[Optional[int], float]:
        """Melhor pessoa ja conhecida para este embedding, acima do limiar."""
        self._ensure()
        if self._matrix is None or embedding is None:
            return None, 0.0

        query = np.asarray(embedding, dtype=np.float32)
        if query.size != self._matrix.shape[1]:
            return None, 0.0

        known = np.where(self._person_ids >= 0)[0]
        if known.size == 0:
            return None, 0.0

        scores = self._matrix[known] @ query
        best = int(np.argmax(scores))
        best_score = float(scores[best])
        if best_score < threshold:
            return None, best_score
        return int(self._person_ids[known[best]]), best_score


face_index = FaceIndex()
