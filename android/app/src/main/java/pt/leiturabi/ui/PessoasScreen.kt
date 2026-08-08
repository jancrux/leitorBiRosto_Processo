package pt.leiturabi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.leiturabi.AppViewModel
import pt.leiturabi.data.PersonDto

@Composable
fun PessoasScreen(
    viewModel: AppViewModel,
    onVerRegistos: () -> Unit,
    onMensagem: (String) -> Unit,
) {
    val pessoas by viewModel.persons.collectAsStateWithLifecycle()
    var emEdicao by remember { mutableStateOf<PersonDto?>(null) }

    LaunchedEffect(Unit) { viewModel.carregarPessoas() }

    if (pessoas.isEmpty()) {
        EmptyState(
            "Ainda não há rostos reconhecidos.\n\n" +
                "Os rostos aparecem aqui automaticamente à medida que juntas fotos aos registos."
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "${pessoas.size} pessoa(s) — toca para ver os registos, mantém premido para dar nome.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(pessoas.size) { indice ->
                val pessoa = pessoas[indice]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.filtrarPorPessoa(pessoa.id)
                            onVerRegistos()
                        },
                ) {
                    ApiImage(
                        path = pessoa.coverUrl,
                        contentDescription = pessoa.displayName,
                        modifier = Modifier.size(88.dp).clip(CircleShape).clickable(
                            onClick = {
                                viewModel.filtrarPorPessoa(pessoa.id)
                                onVerRegistos()
                            },
                        ),
                    )
                    Text(
                        pessoa.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp).clickable { emEdicao = pessoa },
                    )
                    Text(
                        "${pessoa.recordCount} registo(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { emEdicao = pessoa }) {
                        Text(if (pessoa.named) "Renomear" else "Dar nome",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    emEdicao?.let { pessoa ->
        var nome by remember(pessoa.id) { mutableStateOf(pessoa.name) }
        AlertDialog(
            onDismissRequest = { emEdicao = null },
            title = { Text("Identificar pessoa") },
            text = {
                Column {
                    ApiImage(
                        path = pessoa.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(90.dp).clip(CircleShape),
                    )
                    OutlinedTextField(
                        value = nome,
                        onValueChange = { nome = it },
                        label = { Text("Nome") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Text(
                        "Aparece em ${pessoa.recordCount} registo(s), ${pessoa.faceCount} rosto(s).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val alvo = emEdicao
                    emEdicao = null
                    if (alvo != null) viewModel.renomearPessoa(alvo.id, nome, onMensagem)
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { emEdicao = null }) { Text("Cancelar") } },
        )
    }
}
