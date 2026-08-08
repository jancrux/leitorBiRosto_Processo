package pt.leiturabi

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.leiturabi.data.AppSettings
import pt.leiturabi.data.ExtractResultDto
import pt.leiturabi.data.FiltersDto
import pt.leiturabi.data.HealthDto
import pt.leiturabi.data.Net
import pt.leiturabi.data.PendingFile
import pt.leiturabi.data.PersonDto
import pt.leiturabi.data.RecordDto
import pt.leiturabi.data.RecordRepository
import pt.leiturabi.data.RecordSummaryDto
import pt.leiturabi.data.RecordUpdateDto
import pt.leiturabi.data.SearchFilters
import pt.leiturabi.data.SettingsStore
import pt.leiturabi.util.copyToCache
import pt.leiturabi.util.deviceName
import pt.leiturabi.util.fileToPending
import pt.leiturabi.util.lastKnownLocation
import java.io.File

private const val PAGE_SIZE = 20

/** Estado do ecrã "Criar registo". */
data class CreateState(
    val ficheiros: List<PendingFile> = emptyList(),
    val extracao: ExtractResultDto? = null,
    val aLer: Boolean = false,
    val aGuardar: Boolean = false,
    val notas: String = "",
    val tags: String = "",
    val ultimoRegisto: Int? = null,
    val mensagem: String? = null,
    val erro: String? = null,
) {
    val temPdf: Boolean get() = ficheiros.any { it.isPdf }
    val dados: RecordDto? get() = extracao?.dados
}

/** Estado do ecrã "Pesquisar registo". */
data class SearchState(
    val filtros: SearchFilters = SearchFilters(),
    val resultados: List<RecordSummaryDto> = emptyList(),
    val total: Int = 0,
    val aCarregar: Boolean = false,
    val modoRosto: Boolean = false,
    val erro: String? = null,
    val opcoes: FiltersDto = FiltersDto(),
) {
    val podeCarregarMais: Boolean get() = !modoRosto && resultados.size < total
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication<Application>()
    private val store = SettingsStore(app)
    private val repository = RecordRepository()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _health = MutableStateFlow<HealthDto?>(null)
    val health: StateFlow<HealthDto?> = _health.asStateFlow()

    private val _create = MutableStateFlow(CreateState())
    val create: StateFlow<CreateState> = _create.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _persons = MutableStateFlow<List<PersonDto>>(emptyList())
    val persons: StateFlow<List<PersonDto>> = _persons.asStateFlow()

    private val _detail = MutableStateFlow<RecordDto?>(null)
    val detail: StateFlow<RecordDto?> = _detail.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            store.settings.collect { loaded ->
                Net.configure(loaded.serverUrl, loaded.apiKey)
                val primeiraVez = _settings.value.serverUrl.isBlank()
                _settings.value = loaded
                if (loaded.isConfigured && primeiraVez) {
                    verificarLigacao()
                    pesquisar(reiniciar = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Definições
    // ------------------------------------------------------------------ //

    fun guardarDefinicoes(serverUrl: String, apiKey: String, author: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            store.save(AppSettings(serverUrl, apiKey, author))
            val resultado = repository.health()
            resultado.onSuccess {
                _health.value = it
                onDone("Ligado: ${it.records} registos · facial ${it.faceEngine}")
                pesquisar(reiniciar = true)
            }.onFailure { onDone(it.message ?: "Não foi possível ligar.") }
        }
    }

    fun verificarLigacao(onDone: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            repository.health()
                .onSuccess { _health.value = it; onDone?.invoke("Ligado · ${it.records} registos") }
                .onFailure { _health.value = null; onDone?.invoke(it.message ?: "Sem ligação") }
        }
    }

    // ------------------------------------------------------------------ //
    // Criar registo
    // ------------------------------------------------------------------ //

    fun juntarFicheiros(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val novos = uris.mapNotNull { copyToCache(context, it) }
            if (novos.isEmpty()) {
                _create.update { it.copy(erro = "Não foi possível ler os ficheiros escolhidos.") }
                return@launch
            }
            _create.update { it.copy(ficheiros = it.ficheiros + novos, erro = null) }
            novos.firstOrNull { it.isPdf }?.let { extrairPdf(it) }
        }
    }

    fun juntarFotoCapturada(file: File) {
        _create.update { it.copy(ficheiros = it.ficheiros + fileToPending(file), erro = null) }
    }

    fun removerFicheiro(file: PendingFile) {
        runCatching { File(file.localPath).delete() }
        _create.update { estado ->
            val restantes = estado.ficheiros - file
            estado.copy(
                ficheiros = restantes,
                extracao = if (restantes.none { it.isPdf }) null else estado.extracao,
            )
        }
    }

    private fun extrairPdf(pdf: PendingFile) {
        viewModelScope.launch {
            _create.update { it.copy(aLer = true, erro = null) }
            repository.extract(pdf)
                .onSuccess { resultado ->
                    _create.update {
                        it.copy(
                            aLer = false,
                            extracao = resultado,
                            mensagem = if (resultado.matched) "Dados extraídos do relatório."
                            else resultado.aviso.ifBlank { "Formulário não reconhecido." },
                        )
                    }
                }
                .onFailure { erro -> _create.update { it.copy(aLer = false, erro = erro.message) } }
        }
    }

    fun atualizarNotas(valor: String) = _create.update { it.copy(notas = valor) }
    fun atualizarTags(valor: String) = _create.update { it.copy(tags = valor) }
    fun limparMensagens() = _create.update { it.copy(mensagem = null, erro = null) }

    fun descartarRascunho() {
        _create.value.ficheiros.forEach { runCatching { File(it.localPath).delete() } }
        _create.value = CreateState()
    }

    fun guardarRegisto(onDone: (Boolean, String) -> Unit) {
        val estado = _create.value
        if (estado.ficheiros.isEmpty()) {
            onDone(false, "Junta pelo menos uma foto ou um PDF.")
            return
        }
        viewModelScope.launch {
            _create.update { it.copy(aGuardar = true) }
            val posicao = lastKnownLocation(context)
            val resultado = repository.createRecord(
                files = estado.ficheiros,
                author = _settings.value.author,
                device = deviceName,
                notas = estado.notas,
                tags = estado.tags,
                latitude = posicao?.latitude,
                longitude = posicao?.longitude,
                metadata = null,
            )
            _create.update { it.copy(aGuardar = false) }
            resultado.onSuccess { upload ->
                descartarRascunho()
                _create.update { it.copy(ultimoRegisto = upload.record.id) }
                val extras = buildList {
                    add("${upload.anexosNovos} anexo(s)")
                    if (upload.rostosDetetados > 0) add("${upload.rostosDetetados} rosto(s)")
                    if (upload.extraidoDoPdf) add("dados do PDF")
                }
                onDone(true, "Registo #${upload.record.id} criado · ${extras.joinToString(" · ")}")
                pesquisar(reiniciar = true)
            }.onFailure { onDone(false, it.message ?: "Falha ao guardar.") }
        }
    }

    // ------------------------------------------------------------------ //
    // Pesquisar registo
    // ------------------------------------------------------------------ //

    fun atualizarFiltros(transform: (SearchFilters) -> SearchFilters) {
        _search.update { it.copy(filtros = transform(it.filtros)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(320)
            pesquisar(reiniciar = true)
        }
    }

    fun limparFiltros() {
        _search.update { it.copy(filtros = SearchFilters(), modoRosto = false) }
        pesquisar(reiniciar = true)
    }

    fun pesquisar(reiniciar: Boolean) {
        if (!_settings.value.isConfigured) return
        viewModelScope.launch {
            val estado = _search.value
            val offset = if (reiniciar) 0 else estado.resultados.size
            _search.update { it.copy(aCarregar = true, erro = null) }

            repository.search(estado.filtros, PAGE_SIZE, offset)
                .onSuccess { page ->
                    _search.update {
                        it.copy(
                            aCarregar = false,
                            modoRosto = false,
                            total = page.total,
                            resultados = if (reiniciar) page.items else it.resultados + page.items,
                        )
                    }
                }
                .onFailure { erro -> _search.update { it.copy(aCarregar = false, erro = erro.message) } }
        }
        if (reiniciar) carregarOpcoes()
    }

    fun pesquisarPorRosto(uri: Uri) {
        viewModelScope.launch {
            val ficheiro = copyToCache(context, uri) ?: run {
                _search.update { it.copy(erro = "Não foi possível ler a imagem.") }
                return@launch
            }
            pesquisarPorRosto(ficheiro)
        }
    }

    fun pesquisarPorRosto(ficheiro: PendingFile) {
        viewModelScope.launch {
            _search.update { it.copy(aCarregar = true, erro = null) }
            repository.searchByFace(ficheiro)
                .onSuccess { page ->
                    _search.update {
                        it.copy(aCarregar = false, modoRosto = true, total = page.total, resultados = page.items)
                    }
                }
                .onFailure { erro -> _search.update { it.copy(aCarregar = false, erro = erro.message) } }
            runCatching { File(ficheiro.localPath).delete() }
        }
    }

    private fun carregarOpcoes() {
        viewModelScope.launch {
            repository.filters().onSuccess { opcoes -> _search.update { it.copy(opcoes = opcoes) } }
        }
    }

    fun filtrarPorPessoa(personId: Int) {
        _search.update { it.copy(filtros = SearchFilters(personId = personId), modoRosto = false) }
        pesquisar(reiniciar = true)
    }

    // ------------------------------------------------------------------ //
    // Detalhe
    // ------------------------------------------------------------------ //

    fun abrirRegisto(id: Int) {
        _detail.value = null
        viewModelScope.launch {
            repository.record(id).onSuccess { _detail.value = it }
        }
    }

    fun guardarNotasRegisto(id: Int, notas: String, tags: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val update = RecordUpdateDto(
                notas = notas,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            )
            repository.updateRecord(id, update)
                .onSuccess { _detail.value = it; onDone("Alterações guardadas."); pesquisar(reiniciar = true) }
                .onFailure { onDone(it.message ?: "Falha ao guardar.") }
        }
    }

    fun apagarRegisto(id: Int, onDone: (String) -> Unit) {
        viewModelScope.launch {
            repository.deleteRecord(id)
                .onSuccess { _detail.value = null; onDone("Registo eliminado."); pesquisar(reiniciar = true) }
                .onFailure { onDone(it.message ?: "Falha ao eliminar.") }
        }
    }

    fun juntarAnexos(recordId: Int, uris: List<Uri>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val novos = uris.mapNotNull { copyToCache(context, it) }
            if (novos.isEmpty()) return@launch onDone("Nada para juntar.")
            repository.addAttachments(recordId, novos)
                .onSuccess {
                    _detail.value = it.record
                    onDone("${it.anexosNovos} anexo(s) juntos · ${it.rostosDetetados} rosto(s)")
                }
                .onFailure { onDone(it.message ?: "Falha ao juntar anexos.") }
            novos.forEach { runCatching { File(it.localPath).delete() } }
        }
    }

    // ------------------------------------------------------------------ //
    // Pessoas
    // ------------------------------------------------------------------ //

    fun carregarPessoas() {
        viewModelScope.launch {
            repository.persons().onSuccess { _persons.value = it }
        }
    }

    fun identificarRosto(faceId: Int, personId: Int?, nome: String?, recordId: Int?, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val resultado = if (personId != null && nome != null) {
                repository.renamePerson(personId, nome).map { }
            } else {
                repository.assignFace(faceId, personId, nome).map { }
            }
            resultado
                .onSuccess {
                    onDone(if (nome.isNullOrBlank()) "Rosto atualizado." else "Identificado como $nome.")
                    recordId?.let { abrirRegisto(it) }
                    carregarPessoas()
                }
                .onFailure { onDone(it.message ?: "Falha ao identificar.") }
        }
    }

    fun renomearPessoa(id: Int, nome: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            repository.renamePerson(id, nome)
                .onSuccess { carregarPessoas(); onDone("Pessoa atualizada.") }
                .onFailure { onDone(it.message ?: "Falha ao renomear.") }
        }
    }
}
