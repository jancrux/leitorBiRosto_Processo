package pt.leiturabi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.leiturabi.AppViewModel
import pt.leiturabi.data.AttachmentDto
import pt.leiturabi.data.FaceDto
import pt.leiturabi.data.RecordDto
import pt.leiturabi.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheScreen(
    viewModel: AppViewModel,
    recordId: Int,
    onVoltar: () -> Unit,
    onMensagem: (String) -> Unit,
) {
    val registo by viewModel.detail.collectAsStateWithLifecycle()

    LaunchedEffect(recordId) { viewModel.abrirRegisto(recordId) }

    var confirmarApagar by remember { mutableStateOf(false) }
    var rostoEmEdicao by remember { mutableStateOf<FaceDto?>(null) }

    val juntarAnexos = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.juntarAnexos(recordId, uris, onMensagem) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        registo?.title ?: "Registo #$recordId",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { juntarAnexos.launch(arrayOf("application/pdf", "image/*")) }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Juntar anexos")
                    }
                    IconButton(onClick = { confirmarApagar = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar",
                            tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        val actual = registo
        if (actual == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingRow("A carregar registo…")
            }
            return@Scaffold
        }

        var notas by remember(actual.id) { mutableStateOf(actual.notas) }
        var tags by remember(actual.id) { mutableStateOf(actual.tags.joinToString(", ")) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { BlocoCabecalho(actual) }

            if (actual.tripulacao.isNotEmpty()) {
                item {
                    Bloco("Tripulação") {
                        actual.tripulacao.forEach { membro ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(membro.funcao, Modifier.width(96.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(membro.nome, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    Text(
                                        listOf(membro.categoria, membro.matricula)
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Bloco("Viatura") {
                    InfoField("Marca / modelo",
                        listOf(actual.viaturaMarca, actual.viaturaModelo).filter { it.isNotBlank() }.joinToString(" "))
                    InfoField("Matrícula", actual.viaturaMatricula)
                    InfoField("Condutor", actual.condutor)
                    InfoField("Data / hora", actual.dataHora)
                    InfoField("Combustível (L)", actual.combustivelLt)
                    actual.kmPercorridos?.let {
                        InfoField("Quilómetros", "${actual.kmIniciais} → ${actual.kmFinais}  ($it km)")
                    }
                }
            }

            val ocorrencias = actual.ocorrencias.filter { it.preenchida }
            if (ocorrencias.isNotEmpty()) {
                item {
                    Bloco("Ocorrências (${ocorrencias.size})") {
                        ocorrencias.forEach { ocorrencia ->
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Text("${ocorrencia.numero}. ${ocorrencia.descricao.ifBlank { ocorrencia.area }}",
                                    style = MaterialTheme.typography.bodyMedium)
                                val detalhes = listOfNotNull(
                                    ocorrencia.area.takeIf { it.isNotBlank() },
                                    listOf(ocorrencia.horaChegada, ocorrencia.horaSaida)
                                        .filter { it.isNotBlank() }.joinToString(" → ").takeIf { it.isNotBlank() },
                                    ocorrencia.nppNuipc.takeIf { it.isNotBlank() },
                                    "expediente".takeIf { ocorrencia.expediente },
                                    "supervisor".takeIf { ocorrencia.supervisor },
                                ).joinToString(" · ")
                                if (detalhes.isNotBlank()) {
                                    Text(detalhes, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            if (actual.autos.isNotEmpty()) {
                item {
                    Bloco("Autos de notícia por contraordenação") {
                        actual.autos.forEach { auto ->
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Text(auto.motivo.ifBlank { "(sem motivo)" },
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    listOf(auto.nppNuipc, auto.responsavel, auto.local)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            val naoConformes = actual.naoConformes
            if (actual.inspecao.isNotEmpty()) {
                item {
                    Bloco("Inspeção da viatura") {
                        if (naoConformes.isEmpty()) {
                            Text("Todos os ${actual.inspecao.size} itens conformes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            naoConformes.forEach { item ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.item, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Chip(item.condicao, if (item.condicao == "NC") ChipTone.Warn else ChipTone.Neutral)
                                }
                            }
                            Text(
                                "Restantes ${actual.inspecao.size - naoConformes.size} itens conformes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            if (actual.observacoes.isNotBlank() || actual.anomalias.isNotBlank()) {
                item {
                    Bloco("Observações e anomalias") {
                        InfoField("Observações", actual.observacoes)
                        InfoField("Anomalias verificadas", actual.anomalias)
                    }
                }
            }

            if (actual.anexos.isNotEmpty()) {
                item {
                    Bloco("Anexos (${actual.anexos.size})") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(actual.anexos.size) { indice -> AnexoCartao(actual.anexos[indice]) }
                        }
                    }
                }
            }

            val rostos = actual.anexos.flatMap { it.faces }
            if (rostos.isNotEmpty()) {
                item {
                    Bloco("Rostos detetados (${rostos.size})") {
                        Text("Toca num rosto para o identificar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(rostos.size) { indice ->
                                val rosto = rostos[indice]
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(78.dp).clickable { rostoEmEdicao = rosto },
                                ) {
                                    ApiImage(
                                        path = rosto.cropUrl,
                                        contentDescription = rosto.label,
                                        modifier = Modifier.size(70.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                    Text(rosto.label, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Bloco("Notas internas") {
                    OutlinedTextField(
                        value = notas, onValueChange = { notas = it },
                        label = { Text("Notas") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = tags, onValueChange = { tags = it },
                        label = { Text("Etiquetas") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Button(
                        onClick = { viewModel.guardarNotasRegisto(actual.id, notas, tags, onMensagem) },
                        modifier = Modifier.padding(top = 10.dp),
                    ) { Text("Guardar alterações") }
                }
            }

            item { Box(Modifier.height(24.dp)) }
        }
    }

    if (confirmarApagar) {
        AlertDialog(
            onDismissRequest = { confirmarApagar = false },
            title = { Text("Eliminar registo") },
            text = { Text("O registo e todos os seus anexos são apagados definitivamente. Continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarApagar = false
                    viewModel.apagarRegisto(recordId) { mensagem -> onMensagem(mensagem); onVoltar() }
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmarApagar = false }) { Text("Cancelar") } },
        )
    }

    rostoEmEdicao?.let { rosto ->
        var nome by remember(rosto.id) { mutableStateOf(rosto.personName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { rostoEmEdicao = null },
            title = { Text("Identificar rosto") },
            text = {
                Column {
                    ApiImage(
                        path = rosto.cropUrl,
                        contentDescription = null,
                        modifier = Modifier.size(90.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    OutlinedTextField(
                        value = nome, onValueChange = { nome = it },
                        label = { Text("Nome da pessoa") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val alvo = rostoEmEdicao
                    rostoEmEdicao = null
                    if (alvo != null && nome.isNotBlank()) {
                        viewModel.identificarRosto(alvo.id, alvo.personId, nome, recordId, onMensagem)
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { rostoEmEdicao = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocoCabecalho(registo: RecordDto) {
    Bloco("Cabeçalho") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Chip(if (registo.source == "pdf") "extraído do PDF" else "manual",
                if (registo.source == "pdf") ChipTone.Ok else ChipTone.Neutral)
            if (registo.photoCount > 0) Chip("${registo.photoCount} foto(s)", ChipTone.Neutral)
            if (registo.pdfCount > 0) Chip("${registo.pdfCount} PDF", ChipTone.Neutral)
            if (registo.faceCount > 0) Chip("${registo.faceCount} rosto(s)", ChipTone.Neutral)
        }
        InfoField("Indicativo", registo.indicativo)
        InfoField("Data", registo.dataOriginal.ifBlank { registo.data.orEmpty() })
        InfoField("Turno", registo.turno)
        InfoField("Divisão", registo.divisao)
        InfoField("Esquadra", registo.esquadra)
        InfoField("Comando", registo.comando)
        InfoField("Arvorado do CP", registo.arvoradoCp)
        InfoField("Graduado de serviço", registo.graduado)
        InfoField("Registado por", registo.author)
        if (registo.pessoas.isNotEmpty()) InfoField("Pessoas reconhecidas", registo.pessoas.joinToString(", "))
    }
}

@Composable
private fun Bloco(titulo: String, conteudo: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.height(6.dp))
            conteudo()
        }
    }
}

@Composable
private fun AnexoCartao(anexo: AttachmentDto) {
    Column(Modifier.width(120.dp)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            ApiImage(path = anexo.thumbUrl, contentDescription = anexo.originalName,
                modifier = Modifier.size(120.dp))
            if (anexo.isPdf) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = "PDF",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                )
            }
        }
        Text(anexo.originalName, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                if (anexo.isPdf) "${anexo.pageCount ?: "?"} pág." else "${anexo.width}×${anexo.height}",
                formatSize(anexo.sizeBytes),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
