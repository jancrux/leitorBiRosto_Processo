package pt.leiturabi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.leiturabi.AppViewModel
import pt.leiturabi.data.PendingFile
import pt.leiturabi.data.RecordDto
import pt.leiturabi.util.formatSize

@Composable
fun CriarRegistoScreen(
    viewModel: AppViewModel,
    onAbrirCamara: () -> Unit,
    onMensagem: (String) -> Unit,
) {
    val estado by viewModel.create.collectAsStateWithLifecycle()
    val definicoes by viewModel.settings.collectAsStateWithLifecycle()

    val escolherFicheiros = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.juntarFicheiros(uris) }

    if (!definicoes.isConfigured) {
        EmptyState("Configura primeiro o endereço do servidor nas Definições.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("1 · Ficheiros", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Anexa o PDF do relatório e as fotos. Os dados do PDF são lidos automaticamente " +
                            "e o ficheiro original fica guardado no servidor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onAbrirCamara, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                            Text("Tirar foto", Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            onClick = { escolherFicheiros.launch(arrayOf("application/pdf", "image/*")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                            Text("Anexar", Modifier.padding(start = 6.dp))
                        }
                    }

                    if (estado.ficheiros.isEmpty()) {
                        Text(
                            "Nenhum ficheiro anexado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        Column(Modifier.padding(top = 10.dp)) {
                            estado.ficheiros.forEach { ficheiro ->
                                FicheiroLinha(ficheiro) { viewModel.removerFicheiro(ficheiro) }
                            }
                        }
                    }
                }
            }
        }

        if (estado.aLer) {
            item { LoadingRow("A ler os campos do PDF…") }
        }

        estado.dados?.let { dados ->
            item { PreVisualizacaoPdf(dados, estado.extracao?.template.orEmpty()) }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${if (estado.dados != null) 3 else 2} · Informação adicional",
                        style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = estado.tags,
                        onValueChange = viewModel::atualizarTags,
                        label = { Text("Etiquetas (separadas por vírgula)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    OutlinedTextField(
                        value = estado.notas,
                        onValueChange = viewModel::atualizarNotas,
                        label = { Text("Notas internas") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Text(
                        "Registado por: ${definicoes.author.ifBlank { "(por definir nas Definições)" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 30.dp),
            ) {
                Button(
                    enabled = estado.ficheiros.isNotEmpty() && !estado.aGuardar,
                    onClick = { viewModel.guardarRegisto { _, mensagem -> onMensagem(mensagem) } },
                    modifier = Modifier.weight(1f),
                ) {
                    if (estado.aGuardar) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("A guardar…", Modifier.padding(start = 8.dp))
                    } else {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Text("Guardar registo", Modifier.padding(start = 6.dp))
                    }
                }
                TextButton(
                    enabled = estado.ficheiros.isNotEmpty() && !estado.aGuardar,
                    onClick = { viewModel.descartarRascunho(); onMensagem("Rascunho descartado.") },
                ) { Text("Descartar") }
            }
        }
    }
}

@Composable
private fun FicheiroLinha(ficheiro: PendingFile, onRemover: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ficheiro.isPdf) Icons.Default.PictureAsPdf else Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(ficheiro.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                formatSize(ficheiro.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemover) {
            Icon(Icons.Default.Close, contentDescription = "Remover", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PreVisualizacaoPdf(dados: RecordDto, template: String) {
    val ocorrencias = dados.ocorrencias.filter { it.preenchida }
    val naoConformes = dados.naoConformes

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("2 · Dados extraídos do PDF", style = MaterialTheme.typography.titleMedium)
            if (template.isNotBlank()) {
                Text(
                    template,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                Chip("${dados.tripulacao.size} tripulante(s)")
                Chip("${ocorrencias.size} ocorrência(s)", ChipTone.Ok)
                if (dados.autos.isNotEmpty()) Chip("${dados.autos.size} auto(s)", ChipTone.Warn)
                if (naoConformes.isNotEmpty()) Chip("${naoConformes.size} item(ns) a assinalar", ChipTone.Warn)
            }

            HorizontalDivider()

            InfoField("Indicativo", dados.indicativo)
            InfoField("Data / turno", listOf(dados.dataOriginal, dados.turno).filter { it.isNotBlank() }.joinToString(" · "))
            InfoField("Divisão / esquadra", listOf(dados.divisao, dados.esquadra).filter { it.isNotBlank() }.joinToString(" · "))
            InfoField("Viatura", listOf(dados.viaturaMarca, dados.viaturaMatricula).filter { it.isNotBlank() }.joinToString(" "))
            dados.kmPercorridos?.let {
                InfoField("Quilómetros", "${dados.kmIniciais} → ${dados.kmFinais}  ($it km)")
            }

            if (dados.tripulacao.isNotEmpty()) {
                SectionTitle("Tripulação")
                dados.tripulacao.forEach { membro ->
                    Text(
                        "${membro.funcao}: ${membro.nome} " +
                            "(${listOf(membro.categoria, membro.matricula).filter { it.isNotBlank() }.joinToString(", ")})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (ocorrencias.isNotEmpty()) {
                SectionTitle("Ocorrências")
                ocorrencias.forEach { ocorrencia ->
                    Text(
                        "${ocorrencia.numero}. ${ocorrencia.descricao.ifBlank { ocorrencia.area }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    val horas = listOf(ocorrencia.horaChegada, ocorrencia.horaSaida)
                        .filter { it.isNotBlank() }.joinToString(" → ")
                    if (horas.isNotBlank() || ocorrencia.area.isNotBlank()) {
                        Text(
                            listOf(ocorrencia.area, horas).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (naoConformes.isNotEmpty()) {
                SectionTitle("Inspeção a assinalar")
                naoConformes.forEach { item ->
                    Text("• ${item.item} — ${item.condicao}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "Podes corrigir estes dados depois de guardar, no detalhe do registo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
