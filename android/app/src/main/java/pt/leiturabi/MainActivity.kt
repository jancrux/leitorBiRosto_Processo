package pt.leiturabi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import pt.leiturabi.ui.CameraScreen
import pt.leiturabi.ui.CriarRegistoScreen
import pt.leiturabi.ui.DefinicoesScreen
import pt.leiturabi.ui.DetalheScreen
import pt.leiturabi.ui.PesquisarScreen
import pt.leiturabi.ui.PessoasScreen
import pt.leiturabi.ui.theme.LeituraBiTheme

private enum class Destino(val rota: String, val titulo: String, val icone: ImageVector) {
    Pesquisar("pesquisar", "Pesquisar registo", Icons.Default.Search),
    Criar("criar", "Criar registo", Icons.Default.AddCircle),
    Pessoas("pessoas", "Pessoas", Icons.Default.Group),
    Definicoes("definicoes", "Definições", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LeituraBiTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val mostrar: (String) -> Unit = { mensagem ->
        scope.launch { snackbar.showSnackbar(mensagem) }
    }

    val entradaAtual by navController.currentBackStackEntryAsState()
    val rotaAtual = entradaAtual?.destination?.route
    val ecraPrincipal = Destino.entries.any { it.rota == rotaAtual }
    val destinoAtual = Destino.entries.firstOrNull { it.rota == rotaAtual }

    Scaffold(
        topBar = {
            if (ecraPrincipal && destinoAtual != null) {
                TopAppBar(title = { Text(destinoAtual.titulo) })
            }
        },
        bottomBar = {
            if (ecraPrincipal) {
                NavigationBar {
                    Destino.entries.forEach { destino ->
                        val selecionado = entradaAtual?.destination?.hierarchy?.any { it.route == destino.rota } == true
                        NavigationBarItem(
                            selected = selecionado,
                            onClick = {
                                navController.navigate(destino.rota) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destino.icone, contentDescription = destino.titulo) },
                            label = { Text(destino.titulo.substringBefore(" ")) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destino.Pesquisar.rota,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Destino.Pesquisar.rota) {
                PesquisarScreen(
                    viewModel = viewModel,
                    onAbrirRegisto = { id -> navController.navigate("detalhe/$id") },
                )
            }

            composable(Destino.Criar.rota) {
                CriarRegistoScreen(
                    viewModel = viewModel,
                    onAbrirCamara = { navController.navigate("camara") },
                    onMensagem = mostrar,
                )
            }

            composable(Destino.Pessoas.rota) {
                PessoasScreen(
                    viewModel = viewModel,
                    onVerRegistos = {
                        navController.navigate(Destino.Pesquisar.rota) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onMensagem = mostrar,
                )
            }

            composable(Destino.Definicoes.rota) {
                DefinicoesScreen(viewModel = viewModel, onMensagem = mostrar)
            }

            composable("camara") {
                CameraScreen(
                    onCaptured = { ficheiro -> viewModel.juntarFotoCapturada(ficheiro) },
                    onClose = { navController.popBackStack() },
                )
            }

            composable("detalhe/{id}") { entrada ->
                val id = entrada.arguments?.getString("id")?.toIntOrNull() ?: return@composable
                DetalheScreen(
                    viewModel = viewModel,
                    recordId = id,
                    onVoltar = { navController.popBackStack() },
                    onMensagem = mostrar,
                )
            }
        }
    }
}
