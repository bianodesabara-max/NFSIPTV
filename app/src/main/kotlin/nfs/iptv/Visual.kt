package nfs.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

val Fundo = Color(0xFF0B0F17)
val Superficie = Color(0xFF151B27)
val Superficie2 = Color(0xFF1F2737)
val Destaque = Color(0xFF00B4FF)
val TextoSuave = Color(0xFF9AA4B2)
val Ouro = Color(0xFFFFC107)
val Erro = Color(0xFFFF5C5C)

@Composable
fun TemaNFS(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Destaque,
            onPrimary = Color.Black,
            secondary = Destaque,
            background = Fundo,
            onBackground = Color.White,
            surface = Superficie,
            onSurface = Color.White,
            surfaceVariant = Superficie2,
            onSurfaceVariant = TextoSuave,
            surfaceContainerHigh = Superficie2,
            error = Erro,
        ),
        content = conteudo,
    )
}

/** Marca uma borda azul quando o item está selecionado pelo controle remoto. */
fun Modifier.focavel(forma: Shape = RoundedCornerShape(10.dp)): Modifier = composed {
    var focado by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focado = it.isFocused || it.hasFocus }
        .then(if (focado) Modifier.border(2.dp, Destaque, forma) else Modifier)
}

@Composable
fun Logo(tamanho: TextUnit = 22.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.background(Destaque, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("NFS", color = Color.Black, fontWeight = FontWeight.Black, fontSize = tamanho)
        }
        Spacer(Modifier.width(6.dp))
        Text("IPTV", color = Color.White, fontWeight = FontWeight.Bold, fontSize = tamanho)
    }
}

/** Imagem da internet (logo do canal, capa do filme) com a inicial do nome enquanto carrega ou se falhar. */
@Composable
fun Imagem(url: String, nome: String, modifier: Modifier, corte: Boolean) {
    val reserva: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Superficie2), contentAlignment = Alignment.Center) {
            Text(
                nome.trim().take(1).uppercase().ifEmpty { "?" },
                color = TextoSuave, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center,
            )
        }
    }
    Box(modifier) {
        if (url.isBlank()) {
            reserva()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (corte) ContentScale.Crop else ContentScale.Fit,
                loading = { reserva() },
                error = { reserva() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Carregando(texto: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Destaque)
        Spacer(Modifier.height(16.dp))
        Text(texto, color = TextoSuave, textAlign = TextAlign.Center)
    }
}

@Composable
fun MensagemErro(texto: String, onTentar: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Algo deu errado", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(texto, color = TextoSuave, textAlign = TextAlign.Center)
        if (onTentar != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onTentar, modifier = Modifier.focavel(RoundedCornerShape(50))) { Text("Tentar de novo") }
        }
    }
}
