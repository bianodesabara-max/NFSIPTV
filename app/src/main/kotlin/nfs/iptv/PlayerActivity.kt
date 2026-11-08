package nfs.iptv

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private var playerView: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var indice by mutableIntStateOf(0)
    private var erro by mutableStateOf<String?>(null)
    private var aviso by mutableStateOf<String?>(null)
    private var mostrarInfo by mutableStateOf(true)
    private var controlesVisiveis by mutableStateOf(false)
    private var modoTela by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)

    private val modos = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Preencher",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Esticar",
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
    )
    private fun nomeModo(m: Int) = modos.firstOrNull { it.first == m }?.second ?: "Preencher"
    private val avisoModo = Runnable { if (aviso?.startsWith("Tela:") == true) aviso = null }

    private fun trocarModoTela() {
        val i = modos.indexOfFirst { it.first == modoTela }
        modoTela = modos[(i + 1) % modos.size].first
        getSharedPreferences("player", Context.MODE_PRIVATE).edit().putInt("modoTela", modoTela).apply()
        aviso = "Tela: ${nomeModo(modoTela)}"
        handler.removeCallbacks(avisoModo)
        handler.postDelayed(avisoModo, 2_000)
    }

    private var tentativas = 0
    /** null = escolhe pelo endereço; true = força HLS (.m3u8); false = força arquivo/fluxo direto. */
    private var forcarHls: Boolean? = null

    private val fila get() = Reproducao.fila
    private val atual get() = fila.getOrNull(indice)
    private val aoVivo get() = atual?.secao == Secao.AO_VIVO

    private val esconderInfo = Runnable { mostrarInfo = false }
    private val reconectar = Runnable { tocar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dados.iniciar(applicationContext)
        if (fila.isEmpty()) {
            finish()
            return
        }
        indice = Reproducao.indice
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Usa também a área do entalhe da câmera (notch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        modoTela = getSharedPreferences("player", Context.MODE_PRIVATE)
            .getInt("modoTela", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(AGENTE)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = tratarErro(error)
            override fun onPlaybackStateChanged(estado: Int) {
                when (estado) {
                    Player.STATE_READY -> { erro = null; aviso = null; tentativas = 0 }
                    Player.STATE_ENDED -> if (!aoVivo && indice < fila.lastIndex) trocar(1)
                    else -> Unit
                }
            }
        })
        player.playWhenReady = true

        setContent { TemaNFS { TelaPlayer() } }
        esconderBarras()
        tocar()
    }

    private fun esconderBarras() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) esconderBarras()
    }

    private fun ehHls(url: String): Boolean {
        val u = url.lowercase()
        return ".m3u8" in u || "/hls/" in u || "type=m3u8" in u || "output=m3u8" in u
    }

    private fun temExtensaoConhecida(url: String) =
        Regex("""\.(m3u8|ts|mp4|mkv|avi|mov|flv|mpd|webm|mp3|aac)(\?|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)

    private fun tocar() {
        val item = atual ?: return
        handler.removeCallbacks(reconectar)
        erro = null
        val mime = when (forcarHls) {
            true -> MimeTypes.APPLICATION_M3U8
            false -> null
            null -> if (ehHls(item.url)) MimeTypes.APPLICATION_M3U8 else null
        }
        val midia = MediaItem.Builder().setUri(item.url).apply { if (mime != null) setMimeType(mime) }.build()
        player.setMediaItem(midia)
        player.prepare()
        player.play()
        mostrarInfoAgora()
    }

    private fun trocar(delta: Int) {
        if (fila.size <= 1) return
        indice = (indice + delta).mod(fila.size)
        Reproducao.indice = indice
        tentativas = 0
        forcarHls = null
        aviso = null
        tocar()
    }

    private fun mostrarInfoAgora() {
        mostrarInfo = true
        handler.removeCallbacks(esconderInfo)
        handler.postDelayed(esconderInfo, 4_000)
    }

    private fun tratarErro(e: PlaybackException) {
        val item = atual ?: return
        // Endereço sem extensão: tenta como HLS antes de desistir
        val formato = e.errorCode in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        if (forcarHls == null && formato && !temExtensaoConhecida(item.url)) {
            forcarHls = !ehHls(item.url)
            tocar()
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (aoVivo && tentativas < 3 && !formato) {
            tentativas++
            aviso = "Reconectando… ($tentativas/3)"
            handler.removeCallbacks(reconectar)
            handler.postDelayed(reconectar, 3_000)
            return
        }
        aviso = null
        erro = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "O servidor recusou a transmissão. O canal pode estar fora do ar ou sua assinatura atingiu o limite de telas."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Sem conexão com o servidor. Confira sua internet."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "Conteúdo não encontrado no servidor."
            in listOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            ) -> "Este aparelho não consegue reproduzir o formato deste conteúdo."
            else -> "Não foi possível reproduzir este conteúdo."
        } + "\n(${e.errorCodeName})"
    }

    /** Controle remoto: cima/baixo e CH+/CH- trocam de canal (ao vivo). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::player.isInitialized) {
            val controlesAbertos = playerView?.isControllerFullyVisible == true
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { trocar(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { trocar(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (aoVivo && !controlesAbertos && erro == null && fila.size > 1) { trocar(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (aoVivo && !controlesAbertos && erro == null && fila.size > 1) { trocar(-1); return true }
                KeyEvent.KEYCODE_INFO -> { mostrarInfoAgora(); return true }
                KeyEvent.KEYCODE_TV_ZOOM_MODE, KeyEvent.KEYCODE_MENU -> { trocarModoTela(); mostrarInfoAgora(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) {
            if (aoVivo && player.playbackState != Player.STATE_IDLE) player.seekToDefaultPosition()
            player.play()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        playerView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------

    @Composable
    private fun TelaPlayer() {
        val item = atual
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = this@PlayerActivity.player
                        useController = true
                        controllerShowTimeoutMs = 4_000
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowSubtitleButton(true)
                        keepScreenOn = true
                        resizeMode = modoTela
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                            controlesVisiveis = v == View.VISIBLE
                            if (controlesVisiveis) mostrarInfoAgora()
                        })
                        playerView = this
                    }
                },
                update = { it.resizeMode = modoTela },
                modifier = Modifier.fillMaxSize(),
            )

            // Nome do canal / filme no topo
            AnimatedVisibility(
                visible = item != null && (mostrarInfo || controlesVisiveis),
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (aoVivo) {
                        Text("${indice + 1}", color = Destaque, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Spacer(Modifier.width(14.dp))
                    }
                    if (item != null && item.logo.isNotBlank()) {
                        Imagem(item.logo, item.nome, Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)), corte = false)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item?.nome ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (aoVivo && fila.size > 1) {
                            Text("Canal ${indice + 1} de ${fila.size}", color = TextoSuave, fontSize = 12.sp)
                        }
                    }
                    TextButton(
                        onClick = { trocarModoTela() },
                        modifier = Modifier.focavel(RoundedCornerShape(50)),
                    ) {
                        Icon(Icons.Filled.AspectRatio, null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(nomeModo(modoTela), color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (aoVivo) {
                        Box(Modifier.background(Erro, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("AO VIVO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Setas para trocar de canal (toque)
            if (aoVivo && fila.size > 1 && controlesVisiveis && erro == null) {
                IconButton(
                    onClick = { trocar(-1) },
                    modifier = Modifier.align(Alignment.CenterStart).padding(16.dp).background(Color(0x66000000), RoundedCornerShape(50)),
                ) { Icon(Icons.Filled.SkipPrevious, "Canal anterior", tint = Color.White) }
                IconButton(
                    onClick = { trocar(1) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp).background(Color(0x66000000), RoundedCornerShape(50)),
                ) { Icon(Icons.Filled.SkipNext, "Próximo canal", tint = Color.White) }
            }

            aviso?.let {
                Text(
                    it, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            erro?.let { mensagem ->
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 520.dp)
                        .background(Color(0xE6151B27), RoundedCornerShape(12.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = Erro, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(mensagem, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { tentativas = 0; forcarHls = null; tocar() },
                            modifier = Modifier.focavel(RoundedCornerShape(50)),
                        ) { Text("Tentar de novo") }
                        if (aoVivo && fila.size > 1) {
                            OutlinedButton(onClick = { trocar(1) }, modifier = Modifier.focavel(RoundedCornerShape(50))) {
                                Text("Próximo canal")
                            }
                        }
                    }
                }
            }
        }
    }
}
