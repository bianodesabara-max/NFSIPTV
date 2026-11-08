package nfs.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color as CorAndroid
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dados.iniciar(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(CorAndroid.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(CorAndroid.TRANSPARENT),
        )
        setContent { TemaNFS { App() } }
    }
}

/** Telas do app, em pilha (o botão Voltar volta para a anterior). */
sealed interface Rota {
    data object Listas : Rota
    class Navegar(val lista: Lista) : Rota {
        val estado = EstadoNavegar()
    }
    class Serie(val lista: Lista, val serie: Item) : Rota
}

/** Guarda o que está aberto na tela da lista, para não perder ao voltar de uma série. */
class EstadoNavegar {
    var conteudo by mutableStateOf<Conteudo?>(null)
    var carregando by mutableStateOf(false)
    var erro by mutableStateOf<String?>(null)
    var secao by mutableStateOf(Secao.AO_VIVO)
    var categoria by mutableStateOf<String?>(null)
    var busca by mutableStateOf("")
}

const val CAT_FAVORITOS = "*favoritos"
const val CAT_TODOS = "*todos"

object Reproducao {
    var fila: List<Item> = emptyList()
    var indice = 0

    fun iniciar(contexto: Context, fila: List<Item>, indice: Int) {
        if (fila.isEmpty()) return
        this.fila = fila
        this.indice = indice.coerceIn(0, fila.lastIndex)
        contexto.startActivity(Intent(contexto, PlayerActivity::class.java))
    }
}

@Composable
fun App() {
    val pilha = remember {
        mutableStateListOf<Rota>(Rota.Listas).apply { Dados.ultimaLista()?.let { add(Rota.Navegar(it)) } }
    }
    BackHandler(enabled = pilha.size > 1) { pilha.removeAt(pilha.lastIndex) }

    Surface(color = Fundo, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (val rota = pilha.last()) {
                Rota.Listas -> TelaListas(onAbrir = { lista ->
                    Dados.marcarUltima(lista)
                    pilha.add(Rota.Navegar(lista))
                })
                is Rota.Navegar -> TelaNavegar(
                    rota = rota,
                    onSerie = { pilha.add(Rota.Serie(rota.lista, it)) },
                    onTrocarLista = {
                        Dados.marcarUltima(null)
                        pilha.clear()
                        pilha.add(Rota.Listas)
                    },
                )
                is Rota.Serie -> TelaSerie(rota.lista, rota.serie)
            }
        }
    }
}

// =============================================================================================
// Minhas listas
// =============================================================================================

@Composable
fun TelaListas(onAbrir: (Lista) -> Unit) {
    var adicionando by remember { mutableStateOf(false) }
    var excluindo by remember { mutableStateOf<Lista?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Logo(26.sp)
        Spacer(Modifier.height(24.dp))
        if (Dados.listas.isEmpty()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.LiveTv, null, tint = Destaque, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text("Bem-vindo ao NFS IPTV", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Adicione a lista do seu provedor para começar. Você pode entrar com o login Xtream Codes " +
                        "(servidor, usuário e senha) ou usar um link ou arquivo M3U.",
                    color = TextoSuave,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { adicionando = true }, modifier = Modifier.focavel(RoundedCornerShape(50))) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Adicionar lista")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Minhas listas", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { adicionando = true }, modifier = Modifier.focavel(RoundedCornerShape(50))) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar")
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Dados.listas, key = { it.id }) { lista ->
                    Row(
                        Modifier.fillMaxWidth().focavel().background(Superficie, RoundedCornerShape(10.dp))
                            .clickable { onAbrir(lista) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (lista.tipo == TipoLista.XTREAM) Icons.Filled.Dns else Icons.Filled.PlaylistPlay,
                            null, tint = Destaque,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lista.nome, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (lista.tipo) {
                                    TipoLista.XTREAM -> "Xtream Codes · ${lista.usuario}"
                                    TipoLista.M3U_URL -> "Link M3U"
                                    TipoLista.M3U_ARQUIVO -> "Arquivo M3U"
                                },
                                color = TextoSuave, fontSize = 13.sp,
                            )
                        }
                        IconButton(onClick = { excluindo = lista }, modifier = Modifier.focavel(RoundedCornerShape(50))) {
                            Icon(Icons.Filled.Delete, "Excluir", tint = TextoSuave)
                        }
                    }
                }
            }
        }
    }

    if (adicionando) {
        DialogoNovaLista(
            onSalvar = { lista ->
                Dados.salvarLista(lista)
                adicionando = false
                onAbrir(lista)
            },
            onCancelar = { adicionando = false },
        )
    }
    excluindo?.let { lista ->
        AlertDialog(
            onDismissRequest = { excluindo = null },
            title = { Text("Excluir lista?") },
            text = { Text("\"${lista.nome}\" e seus favoritos serão removidos deste aparelho.") },
            confirmButton = {
                Button(
                    onClick = { Dados.removerLista(lista); excluindo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Erro),
                ) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { excluindo = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun DialogoNovaLista(onSalvar: (Lista) -> Unit, onCancelar: () -> Unit) {
    val contexto = LocalContext.current
    val scope = rememberCoroutineScope()
    var tipo by remember { mutableStateOf(TipoLista.XTREAM) }
    var nome by remember { mutableStateOf("") }
    var servidor by remember { mutableStateOf("") }
    var usuario by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var arquivo by remember { mutableStateOf<File?>(null) }
    var nomeArquivo by remember { mutableStateOf("") }
    var aviso by remember { mutableStateOf<String?>(null) }

    val seletor = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val destino = File(Dados.pastaDados, "m3u-${System.currentTimeMillis()}.m3u")
                    withContext(Dispatchers.IO) {
                        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
                            destino.outputStream().use { entrada.copyTo(it) }
                        } ?: throw IllegalStateException("não foi possível abrir")
                    }
                    arquivo?.delete()
                    arquivo = destino
                    nomeArquivo = uri.lastPathSegment?.substringAfterLast('/') ?: "lista.m3u"
                    aviso = null
                } catch (e: Exception) {
                    aviso = "Não foi possível ler o arquivo: ${e.message}"
                }
            }
        }
    }

    // Se colarem o link completo do Xtream (get.php?username=...&password=...), preenche os campos.
    fun aproveitarLinkCompleto(texto: String) {
        // Enquanto a pessoa digita ("http:", "http://ex"...) o texto ainda não é um endereço completo:
        // qualquer falha aqui é ignorada para não fechar o app.
        try {
            val t = texto.trim()
            if (!t.contains("username=", ignoreCase = true) || !t.contains("password=", ignoreCase = true)) return
            val u = Uri.parse(t)
            if (!u.isHierarchical) return
            val usr = u.getQueryParameter("username")
            val pwd = u.getQueryParameter("password")
            if (!usr.isNullOrBlank() && !pwd.isNullOrBlank()) {
                usuario = usr
                senha = pwd
            }
        } catch (e: Exception) {
            // ignora
        }
    }

    fun salvar() {
        val id = Dados.novoId()
        val lista = when (tipo) {
            TipoLista.XTREAM -> {
                if (servidor.isBlank() || usuario.isBlank() || senha.isBlank()) {
                    aviso = "Preencha servidor, usuário e senha."; return
                }
                val host = runCatching { URL(Repositorio.normalizarServidor(servidor)).host }.getOrNull()
                if (host.isNullOrBlank()) {
                    aviso = "Endereço do servidor inválido."; return
                }
                Lista(id, nome.trim().ifEmpty { host }, TipoLista.XTREAM, servidor.trim(), usuario.trim(), senha.trim())
            }
            TipoLista.M3U_URL -> {
                val l = link.trim()
                if (!l.startsWith("http://", true) && !l.startsWith("https://", true)) {
                    aviso = "Digite o link completo, começando com http:// ou https://"; return
                }
                Lista(id, nome.trim().ifEmpty { "Lista M3U" }, TipoLista.M3U_URL, url = l)
            }
            TipoLista.M3U_ARQUIVO -> {
                val f = arquivo ?: run { aviso = "Escolha o arquivo da lista."; return }
                Lista(id, nome.trim().ifEmpty { nomeArquivo.substringBeforeLast('.').ifEmpty { "Lista M3U" } }, TipoLista.M3U_ARQUIVO, arquivo = f.absolutePath)
            }
        }
        onSalvar(lista)
    }

    AlertDialog(
        onDismissRequest = { arquivo?.delete(); onCancelar() },
        title = { Text("Adicionar lista") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TipoLista.XTREAM to "Xtream", TipoLista.M3U_URL to "Link M3U", TipoLista.M3U_ARQUIVO to "Arquivo").forEach { (t, rotulo) ->
                        FilterChip(
                            selected = tipo == t,
                            onClick = { tipo = t; aviso = null },
                            label = { Text(rotulo) },
                            modifier = Modifier.focavel(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                OutlinedTextField(
                    value = nome, onValueChange = { nome = it },
                    label = { Text("Nome da lista (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                when (tipo) {
                    TipoLista.XTREAM -> {
                        OutlinedTextField(
                            value = servidor, onValueChange = { servidor = it; aproveitarLinkCompleto(it) },
                            label = { Text("Servidor") }, placeholder = { Text("http://exemplo.com:8080") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = usuario, onValueChange = { usuario = it },
                            label = { Text("Usuário") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = senha, onValueChange = { senha = it },
                            label = { Text("Senha") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                    TipoLista.M3U_URL -> {
                        OutlinedTextField(
                            value = link, onValueChange = { link = it },
                            label = { Text("Link da lista M3U") }, placeholder = { Text("http://...") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    TipoLista.M3U_ARQUIVO -> {
                        OutlinedButton(
                            onClick = { seletor.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().focavel(RoundedCornerShape(50)),
                        ) {
                            Icon(Icons.Filled.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (arquivo == null) "Escolher arquivo .m3u" else nomeArquivo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                aviso?.let { Text(it, color = Erro) }
            }
        },
        confirmButton = {
            Button(onClick = { salvar() }, modifier = Modifier.focavel(RoundedCornerShape(50))) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = { arquivo?.delete(); onCancelar() }) { Text("Cancelar") }
        },
    )
}
