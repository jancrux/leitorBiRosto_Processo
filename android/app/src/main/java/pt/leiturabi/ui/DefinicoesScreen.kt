package pt.leiturabi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.leiturabi.AppViewModel

@Composable
fun DefinicoesScreen(
    viewModel: AppViewModel,
    onMensagem: (String) -> Unit,
) {
    val definicoes by viewModel.settings.collectAsStateWithLifecycle()
    val estado by viewModel.health.collectAsStateWithLifecycle()

    var url by remember(definicoes.serverUrl) { mutableStateOf(definicoes.serverUrl) }
    var chave by remember(definicoes.apiKey) { mutableStateOf(definicoes.apiKey) }
    var autor by remember(definicoes.author) { mutableStateOf(definicoes.author) }

    LaunchedEffect(Unit) { if (definicoes.isConfigured) viewModel.verificarLigacao() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Servidor", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Endereço do PC onde corre o servidor. Na rede local usa o IP mostrado pelo " +
                        "run_server.bat; fora da rede usa o endereço https do ngrok.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Endereço") },
                    placeholder = { Text("192.168.1.10:8000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = chave,
                    onValueChange = { chave = it },
                    label = { Text("Chave de API") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = autor,
                    onValueChange = { autor = it },
                    label = { Text("O teu nome (fica nos registos criados)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { viewModel.guardarDefinicoes(url, chave, autor, onMensagem) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Guardar e ligar") }
                    OutlinedButton(onClick = { viewModel.verificarLigacao(onMensagem) }) { Text("Testar") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Estado do servidor", style = MaterialTheme.typography.titleMedium)
                val saude = estado
                if (saude == null) {
                    Text(
                        "Sem ligação. Verifica o endereço, a chave e se o servidor está a correr.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    InfoField("Versão", saude.version)
                    InfoField("Registos", saude.records.toString())
                    InfoField("Anexos", saude.attachments.toString())
                    InfoField("Rostos / pessoas", "${saude.faces} / ${saude.persons}")
                    InfoField("Motor facial", "${saude.faceEngine} (${saude.faceModel})")
                    InfoField("Motor de PDF", saude.pdfEngine)
                    InfoField("Autenticação", if (saude.authRequired) "chave obrigatória" else "aberta")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Como ligar", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. No PC, corre setup.bat uma vez e depois run_server.bat.\n" +
                        "2. Copia o IP indicado na janela e a API_KEY do ficheiro .env.\n" +
                        "3. Preenche os campos acima e toca em Guardar e ligar.\n\n" +
                        "Para aceder fora da rede local, corre também run_ngrok.bat no PC e usa " +
                        "aqui o endereço https que o ngrok mostrar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
