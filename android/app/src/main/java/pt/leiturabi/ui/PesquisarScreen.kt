package pt.leiturabi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import pt.leiturabi.data.RecordSummaryDto

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PesquisarScreen(
    viewModel: AppViewModel,
    onAbrirRegisto: (Int) -> Unit,
) {
    val estado by viewModel.search.collectAsStateWithLifecycle()
    val definicoes by viewModel.settings.collectAsStateWithLifecycle()
    var filtrosVisiveis by remember { mutableStateOf(false) }

    val escolherRosto = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::pesquisarPorRosto) }

    if (!definicoes.isConfigured) {
        EmptyState("Configura primeiro o endereço do servidor nas Definições.")
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            OutlinedTextField(
                value = estado.filtros.q,
                onValueChange = { texto -> viewModel.atualizarFiltros { it.copy(q = texto) } },
                label = { Text("Pesquisar em tudo") },
                placeholder = { Text("agente, morada, NUIPC, texto do PDF…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { escolherRosto.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.Face, null, Modifier.size(18.dp))
                    Text("Por rosto", Modifier.padding(start = 6.dp))
                }
                IconButton(onClick = { filtrosVisiveis = !filtrosVisiveis }) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = "Filtros",
                        tint = if (estado.filtros.ativos > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (estado.filtros.ativos > 0) {
                    Chip("${estado.filtros.ativos} filtro(s)", ChipTone.Info)
                    TextButton(onClick = { viewModel.limparFiltros() }) { Text("Limpar") }
                }
            }

            AnimatedVisibility(visible = filtrosVisiveis) {
                Column(Modifier.padding(top = 6.dp)) {
                    CampoFiltro("Indicativo", estado.filtros.indicativo) { valor ->
                        viewModel.atualizarFiltros { it.copy(indicativo = valor) }
                    }
                    CampoFiltro("Matrícula da viatura", estado.filtros.matricula) { valor ->
                        viewModel.atualizarFiltros { it.copy(matricula = valor) }
                    }
                    CampoFiltro("Agente (nome ou nº)", estado.filtros.agente) { valor ->
                        viewModel.atualizarFiltros { it.copy(agente = valor) }
                    }
                    CampoFiltro("Esquadra", estado.filtros.esquadra) { valor ->
                        viewModel.atualizarFiltros { it.copy(esquadra = valor) }
                    }
                    CampoFiltro("NPP / NUIPC", estado.filtros.nuipc) { valor ->
                        viewModel.atualizarFiltros { it.copy(nuipc = valor) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            CampoFiltro("De (AAAA-MM-DD)", estado.filtros.dataDe) { valor ->
                                viewModel.atualizarFiltros { it.copy(dataDe = valor) }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            CampoFiltro("Até (AAAA-MM-DD)", estado.filtros.dataAte) { valor ->
                                viewModel.atualizarFiltros { it.copy(dataAte = valor) }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        FiltroBooleano("Só com fotos", estado.filtros.comFotos) { ativo ->
                            viewModel.atualizarFiltros { it.copy(comFotos = ativo) }
                        }
                        FiltroBooleano("Só com não-conformes", estado.filtros.comNaoConformes) { ativo ->
                            viewModel.atualizarFiltros { it.copy(comNaoConformes = ativo) }
                        }
                    }
                }
            }
        }

        Text(
            text = when {
                estado.aCarregar && estado.resultados.isEmpty() -> "A pesquisar…"
                estado.modoRosto -> "${estado.resultados.size} registo(s) com rosto semelhante"
                else -> "${estado.resultados.size} de ${estado.total} registo(s)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        estado.erro?.let { erro ->
            Text(
                erro,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (estado.resultados.isEmpty() && !estado.aCarregar) {
            EmptyState("Sem registos para estes critérios.")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(estado.resultados.size) { indice ->
                RegistoCartao(estado.resultados[indice]) { onAbrirRegisto(estado.resultados[indice].id) }
            }
            if (estado.podeCarregarMais) {
                item {
                    OutlinedButton(
                        onClick = { viewModel.pesquisar(reiniciar = false) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    ) { Text(if (estado.aCarregar) "A carregar…" else "Carregar mais") }
                }
            }
            item { Box(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun CampoFiltro(rotulo: String, valor: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = onChange,
        label = { Text(rotulo) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun FiltroBooleano(rotulo: String, ativo: Boolean, onChange: (Boolean) -> Unit) {
    val tone = if (ativo) ChipTone.Info else ChipTone.Neutral
    Chip(
        text = (if (ativo) "✓ " else "") + rotulo,
        tone = tone,
        modifier = Modifier.clickable { onChange(!ativo) }.padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegistoCartao(registo: RecordSummaryDto, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (registo.coverUrl != null) {
                ApiImage(
                    path = registo.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("sem\nimagem", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    registo.title.ifBlank { "(sem título)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitulo = listOf(
                    registo.esquadra,
                    registo.tripulacao.joinToString(", ").ifBlank { "sem tripulação" },
                ).filter { it.isNotBlank() }.joinToString(" · ")
                Text(
                    subtitulo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    val dataTexto = registo.dataOriginal.ifBlank { registo.data.orEmpty() }
                    if (dataTexto.isNotBlank()) Chip(dataTexto)
                    if (registo.turno.isNotBlank()) Chip(registo.turno, ChipTone.Neutral)
                    if (registo.viaturaMatricula.isNotBlank()) Chip(registo.viaturaMatricula)
                    registo.kmPercorridos?.let { Chip("$it km", ChipTone.Neutral) }
                    if (registo.occurrenceCount > 0) Chip("${registo.occurrenceCount} ocorr.", ChipTone.Ok)
                    if (registo.offenceCount > 0) Chip("${registo.offenceCount} auto(s)", ChipTone.Warn)
                    if (registo.naoConformes > 0) Chip("${registo.naoConformes} NC", ChipTone.Warn)
                    if (registo.pdfCount > 0) Chip("PDF", ChipTone.Neutral)
                    if (registo.photoCount > 0) Chip("${registo.photoCount} foto(s)", ChipTone.Neutral)
                    if (registo.faceCount > 0) Chip("${registo.faceCount} rosto(s)", ChipTone.Neutral)
                    registo.score?.let { Chip("semelhança ${(it * 100).toInt()}%", ChipTone.Ok) }
                }
            }
        }
    }
}
