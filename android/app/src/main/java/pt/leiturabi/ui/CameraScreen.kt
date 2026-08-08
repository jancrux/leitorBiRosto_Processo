package pt.leiturabi.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import pt.leiturabi.util.newCaptureFile
import java.io.File
import java.util.concurrent.Executor

/**
 * Câmara em ecrã inteiro. Cada disparo devolve o ficheiro através de [onCaptured];
 * o utilizador pode tirar várias fotos seguidas e sair quando terminar.
 */
@Composable
fun CameraScreen(
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var temPermissao by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val pedirPermissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        temPermissao = it
    }
    LaunchedEffect(Unit) {
        if (!temPermissao) pedirPermissao.launch(Manifest.permission.CAMERA)
    }

    if (!temPermissao) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("A câmara precisa de autorização para funcionar.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = { pedirPermissao.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 14.dp)) {
                Text("Autorizar câmara")
            }
            Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("Voltar") }
        }
        return
    }

    var frontal by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var contador by remember { mutableIntStateOf(0) }
    var aGravar by remember { mutableStateOf(false) }

    val executor: Executor = remember { ContextCompat.getMainExecutor(context) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(frontal) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (frontal) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }
        }, executor)

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Fechar", tint = Color.White)
            }
            if (contador > 0) {
                Chip("$contador foto(s) nesta sessão", ChipTone.Ok)
            }
            IconButton(onClick = { flash = !flash }) {
                Icon(
                    if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash",
                    tint = Color.White,
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 34.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { frontal = !frontal }) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Trocar câmara", tint = Color.White)
            }

            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(if (aGravar) Color.Gray else Color.White),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    enabled = !aGravar,
                    onClick = {
                        aGravar = true
                        val destino = newCaptureFile(context)
                        val opcoes = ImageCapture.OutputFileOptions.Builder(destino).build()
                        imageCapture.takePicture(
                            opcoes,
                            executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    aGravar = false
                                    contador += 1
                                    onCaptured(destino)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    aGravar = false
                                }
                            },
                        )
                    },
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(Color.White))
                }
            }

            IconButton(onClick = onClose, enabled = contador > 0) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Concluir",
                    tint = if (contador > 0) MaterialTheme.colorScheme.primary else Color.Gray,
                )
            }
        }
    }
}
