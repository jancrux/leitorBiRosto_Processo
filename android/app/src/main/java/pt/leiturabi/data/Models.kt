package pt.leiturabi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ------------------------------------------------------------------ */
/* Blocos do Relatorio de Viatura Policial                             */
/* ------------------------------------------------------------------ */

@Serializable
data class CrewDto(
    val id: Int? = null,
    val funcao: String = "",
    val categoria: String = "",
    val matricula: String = "",
    val nome: String = "",
    @SerialName("person_id") val personId: Int? = null,
)

@Serializable
data class MaterialDto(
    val id: Int? = null,
    val item: String = "",
    val verificado: Boolean = false,
    val quantidade: String = "",
)

@Serializable
data class OccurrenceDto(
    val id: Int? = null,
    val numero: Int = 0,
    val area: String = "",
    val expediente: Boolean = false,
    @SerialName("npp_nuipc") val nppNuipc: String = "",
    @SerialName("hora_chegada") val horaChegada: String = "",
    @SerialName("hora_saida") val horaSaida: String = "",
    val supervisor: Boolean = false,
    val descricao: String = "",
) {
    val preenchida: Boolean get() = area.isNotBlank() || descricao.isNotBlank() || nppNuipc.isNotBlank()
}

@Serializable
data class OffenceDto(
    val id: Int? = null,
    val numero: Int = 0,
    @SerialName("npp_nuipc") val nppNuipc: String = "",
    val responsavel: String = "",
    val motivo: String = "",
    val local: String = "",
)

@Serializable
data class InspectionDto(
    val id: Int? = null,
    val item: String = "",
    val condicao: String = "",
)

/* ------------------------------------------------------------------ */
/* Rostos e anexos                                                     */
/* ------------------------------------------------------------------ */

@Serializable
data class FaceDto(
    val id: Int,
    @SerialName("record_id") val recordId: Int = 0,
    @SerialName("attachment_id") val attachmentId: Int = 0,
    @SerialName("person_id") val personId: Int? = null,
    @SerialName("person_name") val personName: String? = null,
    val page: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    @SerialName("det_score") val detScore: Float = 0f,
    val age: Int? = null,
    val gender: String? = null,
    @SerialName("crop_url") val cropUrl: String? = null,
    val similarity: Float? = null,
) {
    val label: String get() = personName?.takeIf { it.isNotBlank() } ?: "Sem nome"
}

@Serializable
data class AttachmentDto(
    val id: Int,
    @SerialName("record_id") val recordId: Int = 0,
    val kind: String = "photo",
    @SerialName("original_name") val originalName: String = "",
    val mime: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("face_count") val faceCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("file_url") val fileUrl: String = "",
    @SerialName("thumb_url") val thumbUrl: String = "",
    val faces: List<FaceDto> = emptyList(),
) {
    val isPdf: Boolean get() = kind == "pdf"
}

/* ------------------------------------------------------------------ */
/* Registo                                                             */
/* ------------------------------------------------------------------ */

@Serializable
data class RecordDto(
    val id: Int = 0,
    val title: String = "",
    val source: String = "manual",
    val template: String = "",
    val indicativo: String = "",
    val data: String? = null,
    @SerialName("data_original") val dataOriginal: String = "",
    val turno: String = "",
    val divisao: String = "",
    val esquadra: String = "",
    val comando: String = "",
    @SerialName("viatura_marca") val viaturaMarca: String = "",
    @SerialName("viatura_modelo") val viaturaModelo: String = "",
    @SerialName("viatura_matricula") val viaturaMatricula: String = "",
    val condutor: String = "",
    @SerialName("data_hora") val dataHora: String = "",
    @SerialName("combustivel_lt") val combustivelLt: String = "",
    @SerialName("km_iniciais") val kmIniciais: Int? = null,
    @SerialName("km_finais") val kmFinais: Int? = null,
    @SerialName("km_percorridos") val kmPercorridos: Int? = null,
    val observacoes: String = "",
    val anomalias: String = "",
    val notas: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("arvorado_cp") val arvoradoCp: String = "",
    val graduado: String = "",
    @SerialName("entregue_por") val entreguePor: String = "",
    @SerialName("recebido_por") val recebidoPor: String = "",
    val author: String = "",
    val device: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("photo_count") val photoCount: Int = 0,
    @SerialName("pdf_count") val pdfCount: Int = 0,
    @SerialName("face_count") val faceCount: Int = 0,
    val score: Float? = null,
    val tripulacao: List<CrewDto> = emptyList(),
    val material: List<MaterialDto> = emptyList(),
    val ocorrencias: List<OccurrenceDto> = emptyList(),
    val autos: List<OffenceDto> = emptyList(),
    val inspecao: List<InspectionDto> = emptyList(),
    val anexos: List<AttachmentDto> = emptyList(),
    val pessoas: List<String> = emptyList(),
) {
    val naoConformes: List<InspectionDto>
        get() = inspecao.filter { it.condicao.isNotBlank() && it.condicao != "C" }
}

@Serializable
data class RecordSummaryDto(
    val id: Int,
    val title: String = "",
    val indicativo: String = "",
    val data: String? = null,
    @SerialName("data_original") val dataOriginal: String = "",
    val turno: String = "",
    val divisao: String = "",
    val esquadra: String = "",
    @SerialName("viatura_matricula") val viaturaMatricula: String = "",
    @SerialName("viatura_marca") val viaturaMarca: String = "",
    val condutor: String = "",
    @SerialName("km_percorridos") val kmPercorridos: Int? = null,
    val source: String = "manual",
    val author: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("photo_count") val photoCount: Int = 0,
    @SerialName("pdf_count") val pdfCount: Int = 0,
    @SerialName("face_count") val faceCount: Int = 0,
    @SerialName("occurrence_count") val occurrenceCount: Int = 0,
    @SerialName("offence_count") val offenceCount: Int = 0,
    @SerialName("nao_conformes") val naoConformes: Int = 0,
    val tripulacao: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    val score: Float? = null,
    @SerialName("matched_faces") val matchedFaces: List<FaceDto> = emptyList(),
)

@Serializable
data class RecordPageDto(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val items: List<RecordSummaryDto> = emptyList(),
)

/** Campos enviados ao criar/editar. Apenas os nao-nulos sao aplicados no servidor. */
@Serializable
data class RecordUpdateDto(
    val title: String? = null,
    val indicativo: String? = null,
    val data: String? = null,
    @SerialName("data_original") val dataOriginal: String? = null,
    val turno: String? = null,
    val divisao: String? = null,
    val esquadra: String? = null,
    @SerialName("viatura_marca") val viaturaMarca: String? = null,
    @SerialName("viatura_modelo") val viaturaModelo: String? = null,
    @SerialName("viatura_matricula") val viaturaMatricula: String? = null,
    val condutor: String? = null,
    @SerialName("km_iniciais") val kmIniciais: Int? = null,
    @SerialName("km_finais") val kmFinais: Int? = null,
    val observacoes: String? = null,
    val anomalias: String? = null,
    val notas: String? = null,
    val tags: List<String>? = null,
    val author: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val tripulacao: List<CrewDto>? = null,
    val ocorrencias: List<OccurrenceDto>? = null,
    val autos: List<OffenceDto>? = null,
)

@Serializable
data class ExtractResultDto(
    val matched: Boolean = false,
    val template: String = "",
    val filename: String = "",
    @SerialName("page_count") val pageCount: Int = 0,
    val dados: RecordDto? = null,
    val aviso: String = "",
)

@Serializable
data class UploadResultDto(
    val record: RecordDto,
    val created: Boolean = true,
    @SerialName("anexos_novos") val anexosNovos: Int = 0,
    @SerialName("rostos_detetados") val rostosDetetados: Int = 0,
    @SerialName("extraido_do_pdf") val extraidoDoPdf: Boolean = false,
    val avisos: List<String> = emptyList(),
)

@Serializable
data class PersonDto(
    val id: Int,
    val name: String = "",
    val named: Boolean = false,
    val matricula: String = "",
    val notes: String = "",
    @SerialName("record_count") val recordCount: Int = 0,
    @SerialName("face_count") val faceCount: Int = 0,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Pessoa #$id"
}

@Serializable
data class PersonUpdateDto(val name: String? = null, val matricula: String? = null, val notes: String? = null)

@Serializable
data class FaceAssignDto(
    @SerialName("person_id") val personId: Int? = null,
    @SerialName("new_person_name") val newPersonName: String? = null,
)

@Serializable
data class FiltersDto(
    val indicativos: List<String> = emptyList(),
    val matriculas: List<String> = emptyList(),
    val esquadras: List<String> = emptyList(),
    val divisoes: List<String> = emptyList(),
    val turnos: List<String> = emptyList(),
    val agentes: List<String> = emptyList(),
    val tags: List<TagDto> = emptyList(),
)

@Serializable
data class TagDto(val tag: String = "", val count: Int = 0)

@Serializable
data class HealthDto(
    val status: String = "",
    val version: String = "",
    @SerialName("face_engine") val faceEngine: String = "",
    @SerialName("face_model") val faceModel: String = "",
    @SerialName("pdf_engine") val pdfEngine: String = "",
    @SerialName("auth_required") val authRequired: Boolean = false,
    val records: Int = 0,
    val attachments: Int = 0,
    val persons: Int = 0,
    val faces: Int = 0,
)

@Serializable
data class DeletedDto(val deleted: Int = 0)

/* ------------------------------------------------------------------ */
/* Estado local (nao vem do servidor)                                  */
/* ------------------------------------------------------------------ */

/** Ficheiro escolhido no telemovel, ainda por enviar. */
data class PendingFile(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val localPath: String,
) {
    val isPdf: Boolean get() = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
}

/** Filtros do ecra de pesquisa. */
data class SearchFilters(
    val q: String = "",
    val indicativo: String = "",
    val matricula: String = "",
    val agente: String = "",
    val esquadra: String = "",
    val nuipc: String = "",
    val dataDe: String = "",
    val dataAte: String = "",
    val comFotos: Boolean = false,
    val comNaoConformes: Boolean = false,
    val personId: Int? = null,
    val order: String = "recent",
) {
    val ativos: Int
        get() = listOf(indicativo, matricula, agente, esquadra, nuipc, dataDe, dataAte)
            .count { it.isNotBlank() } + (if (comFotos) 1 else 0) + (if (comNaoConformes) 1 else 0) +
            (if (personId != null) 1 else 0)
}
