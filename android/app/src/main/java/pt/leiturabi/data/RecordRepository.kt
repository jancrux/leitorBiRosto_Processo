package pt.leiturabi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RecordRepository(private val api: ApiService = Net.service) {

    suspend fun health(): Result<HealthDto> = call { api.health() }

    // ---------- criar ----------

    /** Le o PDF no servidor e devolve os dados extraidos, sem gravar nada. */
    suspend fun extract(file: PendingFile): Result<ExtractResultDto> = call {
        api.extract(part(file))
    }

    suspend fun createRecord(
        files: List<PendingFile>,
        author: String,
        device: String,
        notas: String,
        tags: String,
        latitude: Double?,
        longitude: Double?,
        metadata: RecordUpdateDto? = null,
    ): Result<UploadResultDto> = call {
        val fields = buildMap<String, RequestBody> {
            put("author", author.text())
            put("device", device.text())
            put("notas", notas.text())
            put("tags", tags.text())
            put("extrair_pdf", "true".text())
            latitude?.let { put("latitude", it.toString().text()) }
            longitude?.let { put("longitude", it.toString().text()) }
            metadata?.let { put("metadata", Net.json.encodeToString(RecordUpdateDto.serializer(), it).text()) }
        }
        api.createRecord(parts(files), fields)
    }

    suspend fun addAttachments(recordId: Int, files: List<PendingFile>): Result<UploadResultDto> = call {
        api.addAttachments(recordId, parts(files))
    }

    suspend fun updateRecord(id: Int, update: RecordUpdateDto): Result<RecordDto> = call {
        api.updateRecord(id, update)
    }

    suspend fun deleteRecord(id: Int): Result<Int> = call { api.deleteRecord(id).deleted }

    suspend fun deleteAttachment(id: Int): Result<Int> = call { api.deleteAttachment(id).deleted }

    // ---------- pesquisar ----------

    suspend fun search(filters: SearchFilters, limit: Int, offset: Int): Result<RecordPageDto> = call {
        val params = buildMap {
            put("order", filters.order)
            put("limit", limit.toString())
            put("offset", offset.toString())
            if (filters.q.isNotBlank()) put("q", filters.q.trim())
            if (filters.indicativo.isNotBlank()) put("indicativo", filters.indicativo.trim())
            if (filters.matricula.isNotBlank()) put("matricula", filters.matricula.trim())
            if (filters.agente.isNotBlank()) put("agente", filters.agente.trim())
            if (filters.esquadra.isNotBlank()) put("esquadra", filters.esquadra.trim())
            if (filters.nuipc.isNotBlank()) put("nuipc", filters.nuipc.trim())
            if (filters.dataDe.isNotBlank()) put("data_de", filters.dataDe)
            if (filters.dataAte.isNotBlank()) put("data_ate", filters.dataAte)
            if (filters.comFotos) put("com_fotos", "true")
            if (filters.comNaoConformes) put("com_nao_conformes", "true")
            filters.personId?.let { put("person_id", it.toString()) }
        }
        api.searchRecords(params)
    }

    suspend fun record(id: Int): Result<RecordDto> = call { api.getRecord(id) }

    suspend fun searchByFace(file: PendingFile): Result<RecordPageDto> = call { api.searchByFace(part(file)) }

    suspend fun filters(): Result<FiltersDto> = call { api.filters() }

    // ---------- pessoas ----------

    suspend fun persons(): Result<List<PersonDto>> = call { api.listPersons() }

    suspend fun renamePerson(id: Int, name: String): Result<PersonDto> =
        call { api.updatePerson(id, PersonUpdateDto(name = name)) }

    suspend fun assignFace(faceId: Int, personId: Int?, newName: String?): Result<FaceDto> =
        call { api.assignFace(faceId, FaceAssignDto(personId = personId, newPersonName = newName)) }

    // ---------- infraestrutura ----------

    /** O servidor espera "file" nos endpoints de ficheiro único e "files" na criação de registos. */
    private fun part(file: PendingFile, fieldName: String = "file"): MultipartBody.Part {
        val body = File(file.localPath).asRequestBody(file.mime.toMediaTypeOrNull())
        return MultipartBody.Part.createFormData(fieldName, file.name, body)
    }

    private fun parts(files: List<PendingFile>): List<MultipartBody.Part> = files.map { part(it, "files") }

    private fun String.text(): RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())

    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (error: Throwable) {
            Result.failure(IllegalStateException(describe(error), error))
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "Servidor não encontrado. Verifica o endereço nas Definições."
        is ConnectException -> "Não foi possível ligar ao servidor. Está a correr o run_server.bat?"
        is SocketTimeoutException -> "O servidor demorou demasiado a responder."
        is HttpException -> when (error.code()) {
            401 -> "Chave de API inválida."
            404 -> "Não encontrado no servidor."
            413 -> "Ficheiro demasiado grande."
            415 -> "Formato de ficheiro não suportado."
            503 -> "Funcionalidade indisponível no servidor (motor facial ou PDF em falta)."
            else -> "Erro do servidor (HTTP ${error.code()})."
        }
        is IOException -> "Falha de rede: ${error.message ?: "ligação interrompida"}"
        else -> error.message ?: "Erro inesperado."
    }
}
