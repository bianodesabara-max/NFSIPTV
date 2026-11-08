package nfs.iptv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val AGENTE = "NFSIPTV/1.0 (Linux; Android)"

@Serializable
enum class TipoLista { XTREAM, M3U_URL, M3U_ARQUIVO }

@Serializable
data class Lista(
    val id: Long,
    val nome: String,
    val tipo: TipoLista,
    val url: String = "",
    val usuario: String = "",
    val senha: String = "",
    val arquivo: String = "",
)

@Serializable
enum class Secao(val titulo: String) { AO_VIVO("Ao vivo"), FILMES("Filmes"), SERIES("Séries") }

@Serializable
data class Categoria(val id: String, val nome: String, val secao: Secao)

@Serializable
data class Item(
    val nome: String,
    val url: String = "",
    val logo: String = "",
    val categoriaId: String = "",
    val secao: Secao = Secao.AO_VIVO,
    val serieId: String = "",
    val nota: String = "",
) {
    /** Identificação única do item dentro da lista (usada nos favoritos). */
    val chave: String get() = url.ifEmpty { "serie:$serieId" }
}

@Serializable
data class Conteudo(
    val itens: List<Item>,
    val categorias: List<Categoria>,
    val info: String = "",
    val atualizadoEm: Long = 0,
)

data class Episodio(val temporada: Int, val numero: Int, val titulo: String, val url: String, val capa: String)

data class DetalheSerie(val capa: String, val sinopse: String, val episodios: List<Episodio>)

// ---------------------------------------------------------------------------------------------
// Armazenamento no aparelho
// ---------------------------------------------------------------------------------------------

object Dados {
    private lateinit var pasta: File
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    var listas by mutableStateOf<List<Lista>>(emptyList())
        private set
    var favoritos by mutableStateOf<Set<String>>(emptySet())
        private set

    fun iniciar(contexto: Context) {
        if (::pasta.isInitialized) return
        pasta = contexto.filesDir
        listas = ler("listas.json") { json.decodeFromString(ListSerializer(Lista.serializer()), it) } ?: emptyList()
        favoritos = ler("favoritos.json") { json.decodeFromString(SetSerializer(String.serializer()), it) } ?: emptySet()
    }

    val pastaDados: File get() = pasta

    private fun <T> ler(nome: String, conversor: (String) -> T): T? {
        val f = File(pasta, nome)
        if (!f.exists()) return null
        return runCatching { conversor(f.readText()) }.getOrNull()
    }

    private fun gravar(nome: String, texto: String) {
        val tmp = File(pasta, "$nome.tmp")
        tmp.writeText(texto)
        val destino = File(pasta, nome)
        if (!tmp.renameTo(destino)) {
            destino.delete()
            tmp.renameTo(destino)
        }
    }

    fun novoId(): Long = maxOf(System.currentTimeMillis(), (listas.maxOfOrNull { it.id } ?: 0L) + 1)

    fun salvarLista(lista: Lista) {
        listas = listas.filter { it.id != lista.id } + lista
        gravar("listas.json", json.encodeToString(ListSerializer(Lista.serializer()), listas))
    }

    fun removerLista(lista: Lista) {
        listas = listas.filter { it.id != lista.id }
        gravar("listas.json", json.encodeToString(ListSerializer(Lista.serializer()), listas))
        File(pasta, "conteudo-${lista.id}.json").delete()
        if (lista.arquivo.isNotEmpty()) File(lista.arquivo).delete()
        favoritos = favoritos.filterNot { it.startsWith("${lista.id}|") }.toSet()
        gravar("favoritos.json", json.encodeToString(SetSerializer(String.serializer()), favoritos))
        if (ultimaLista() == null) File(pasta, "ultima.txt").delete()
    }

    fun ehFavorito(lista: Lista, item: Item) = "${lista.id}|${item.chave}" in favoritos

    fun alternarFavorito(lista: Lista, item: Item) {
        val k = "${lista.id}|${item.chave}"
        favoritos = if (k in favoritos) favoritos - k else favoritos + k
        gravar("favoritos.json", json.encodeToString(SetSerializer(String.serializer()), favoritos))
    }

    fun ultimaLista(): Lista? {
        val id = runCatching { File(pasta, "ultima.txt").readText().trim().toLong() }.getOrNull() ?: return null
        return listas.firstOrNull { it.id == id }
    }

    fun marcarUltima(lista: Lista?) {
        if (lista == null) File(pasta, "ultima.txt").delete() else gravar("ultima.txt", lista.id.toString())
    }

    fun lerCache(lista: Lista): Conteudo? = ler("conteudo-${lista.id}.json") { json.decodeFromString(Conteudo.serializer(), it) }

    fun gravarCache(lista: Lista, conteudo: Conteudo) {
        gravar("conteudo-${lista.id}.json", json.encodeToString(Conteudo.serializer(), conteudo))
    }
}

// ---------------------------------------------------------------------------------------------
// Download e leitura das listas
// ---------------------------------------------------------------------------------------------

/** Traduz erros de conexão para mensagens que dá para entender. */
fun mensagemDeErro(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "Servidor não encontrado. Confira o endereço digitado e a sua internet."
    is java.net.SocketTimeoutException -> "O servidor demorou demais para responder. Confira o endereço (e a porta) ou tente mais tarde."
    is java.net.ConnectException -> "Não foi possível conectar ao servidor. Confira o endereço, a porta e a sua internet."
    is javax.net.ssl.SSLException -> "Erro de segurança na conexão com o servidor. Tente usar http:// em vez de https://."
    is java.net.MalformedURLException -> "Endereço inválido. Confira o que foi digitado."
    is kotlinx.serialization.SerializationException -> "A lista veio num formato que o app não reconheceu."
    is OutOfMemoryError -> "A lista é grande demais para a memória deste aparelho."
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Erro desconhecido (${e.javaClass.simpleName})."
}

object Repositorio {

    /** Carrega o conteúdo da lista: do aparelho (se já baixado) ou do servidor. */
    suspend fun carregar(lista: Lista, forcar: Boolean): Conteudo = withContext(Dispatchers.IO) {
        if (!forcar) Dados.lerCache(lista)?.let { return@withContext it }
        val conteudo = when (lista.tipo) {
            TipoLista.XTREAM -> xtream(lista)
            TipoLista.M3U_URL -> lerM3u(baixarTexto(lista.url))
            TipoLista.M3U_ARQUIVO -> {
                val f = File(lista.arquivo)
                if (!f.exists()) throw IOException("O arquivo da lista não foi encontrado. Adicione a lista novamente.")
                lerM3u(f.readText())
            }
        }
        if (conteudo.itens.isEmpty()) throw IOException("A lista está vazia ou não pôde ser lida.")
        Dados.gravarCache(lista, conteudo)
        conteudo
    }

    fun baixarTexto(endereco: String): String {
        var atual = endereco.trim()
        repeat(6) {
            val c = URL(atual).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 90_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", AGENTE)
                c.setRequestProperty("Accept", "*/*")
                val codigo = c.responseCode
                if (codigo in 300..399) {
                    val destino = c.getHeaderField("Location") ?: throw IOException("Redirecionamento inválido")
                    atual = URL(URL(atual), destino).toString()
                    return@repeat
                }
                if (codigo == 401 || codigo == 403) throw IOException("Acesso negado pelo servidor (código $codigo). Confira usuário, senha ou assinatura.")
                if (codigo == 404) throw IOException("Endereço não encontrado no servidor (código 404).")
                if (codigo !in 200..299) throw IOException("O servidor respondeu com erro $codigo.")
                return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Redirecionamentos demais")
    }

    // ----------------------------- M3U -----------------------------

    private val regexAtributo = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    fun lerM3u(texto: String): Conteudo {
        val itens = ArrayList<Item>()
        var info: String? = null
        var grupoExtra: String? = null
        texto.lineSequence().forEach { bruta ->
            val linha = bruta.trim()
            when {
                linha.startsWith("#EXTINF", ignoreCase = true) -> { info = linha; grupoExtra = null }
                linha.startsWith("#EXTGRP:", ignoreCase = true) -> grupoExtra = linha.substringAfter(':').trim()
                linha.isEmpty() || linha.startsWith("#") -> Unit
                else -> {
                    val inf = info
                    val atributos = inf?.let { a -> regexAtributo.findAll(a).associate { it.groupValues[1].lowercase() to it.groupValues[2] } }
                        ?: emptyMap()
                    val nome = inf?.let { nomeDoExtinf(it) }?.ifBlank { null }
                        ?: atributos["tvg-name"]?.ifBlank { null }
                        ?: linha.substringAfterLast('/')
                    val grupo = atributos["group-title"]?.trim()?.ifBlank { null } ?: grupoExtra ?: "Sem categoria"
                    val secao = secaoPelaUrl(linha)
                    itens += Item(
                        nome = nome.trim(),
                        url = linha,
                        logo = atributos["tvg-logo"].orEmpty(),
                        categoriaId = "${secao.name}:$grupo",
                        secao = secao,
                    )
                    info = null
                }
            }
        }
        val categorias = itens.asSequence().map { Categoria(it.categoriaId, it.categoriaId.substringAfter(':'), it.secao) }
            .distinctBy { it.id }.toList()
        return Conteudo(itens, categorias, "${itens.size} itens", System.currentTimeMillis())
    }

    /** Nome do canal: o texto depois da primeira vírgula que não está entre aspas. */
    private fun nomeDoExtinf(linha: String): String {
        var aspas = false
        for (i in linha.indices) {
            val c = linha[i]
            if (c == '"') aspas = !aspas
            if (c == ',' && !aspas) return linha.substring(i + 1)
        }
        return ""
    }

    private fun secaoPelaUrl(url: String): Secao {
        val u = url.lowercase()
        return when {
            "/movie/" in u -> Secao.FILMES
            "/series/" in u -> Secao.SERIES
            else -> Secao.AO_VIVO
        }
    }

    // ----------------------------- Xtream Codes -----------------------------

    fun normalizarServidor(s: String): String {
        var t = s.trim().trimEnd('/')
        if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) t = "http://$t"
        // Aceita também quando a pessoa cola o endereço completo (player_api.php, get.php...)
        val u = URL(t)
        val porta = if (u.port != -1) ":${u.port}" else ""
        return "${u.protocol}://${u.host}$porta"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(lista: Lista, acao: String, extra: String = ""): String {
        val base = normalizarServidor(lista.url)
        var u = "$base/player_api.php?username=${enc(lista.usuario)}&password=${enc(lista.senha)}"
        if (acao.isNotEmpty()) u += "&action=$acao"
        return u + extra
    }

    private fun JsonElement?.texto(): String = (this as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

    private fun lerArray(texto: String): List<JsonObject> =
        (runCatching { Dados.json.parseToJsonElement(texto) }.getOrNull() as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** Confere login e devolve (extensão do ao vivo, texto de informação da conta). */
    private fun conferirConta(lista: Lista): Pair<String, String> {
        val resposta = baixarTexto(api(lista, ""))
        val raiz = runCatching { Dados.json.parseToJsonElement(resposta) }.getOrNull() as? JsonObject
            ?: throw IOException("O servidor não respondeu como um servidor Xtream. Confira o endereço.")
        val usuario = raiz["user_info"] as? JsonObject
            ?: throw IOException("Usuário ou senha incorretos.")
        if (usuario["auth"].texto() != "1") throw IOException("Usuário ou senha incorretos.")
        when (usuario["status"].texto().lowercase()) {
            "expired" -> throw IOException("Sua assinatura está vencida.")
            "banned" -> throw IOException("Esta conta foi bloqueada pelo provedor.")
            "disabled" -> throw IOException("Esta conta está desativada.")
        }
        val formatos = (usuario["allowed_output_formats"] as? JsonArray)?.map { it.texto() } ?: emptyList()
        val ext = if ("m3u8" in formatos || formatos.isEmpty()) "m3u8" else "ts"

        val partes = mutableListOf<String>()
        usuario["exp_date"].texto().toLongOrNull()?.let {
            partes += "Válida até " + SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(it * 1000))
        }
        usuario["max_connections"].texto().takeIf { it.isNotEmpty() }?.let { partes += "$it tela(s)" }
        return ext to partes.joinToString(" · ")
    }

    private suspend fun xtream(lista: Lista): Conteudo = coroutineScope {
        val (extAoVivo, info) = conferirConta(lista)
        val base = normalizarServidor(lista.url)
        val u = lista.usuario
        val p = lista.senha

        fun baixar(acao: String) = async(Dispatchers.IO) { runCatching { lerArray(baixarTexto(api(lista, acao))) }.getOrDefault(emptyList()) }
        val catVivo = baixar("get_live_categories")
        val vivo = baixar("get_live_streams")
        val catFilmes = baixar("get_vod_categories")
        val filmes = baixar("get_vod_streams")
        val catSeries = baixar("get_series_categories")
        val series = baixar("get_series")

        val categorias = ArrayList<Categoria>()
        val itens = ArrayList<Item>()

        fun cats(lista: List<JsonObject>, secao: Secao) {
            lista.forEach { c ->
                val id = c["category_id"].texto()
                categorias += Categoria("${secao.name}:$id", c["category_name"].texto().ifEmpty { "Sem nome" }, secao)
            }
        }
        cats(catVivo.await(), Secao.AO_VIVO)
        cats(catFilmes.await(), Secao.FILMES)
        cats(catSeries.await(), Secao.SERIES)

        vivo.await().forEach { s ->
            val id = s["stream_id"].texto()
            if (id.isEmpty()) return@forEach
            itens += Item(
                nome = s["name"].texto(),
                url = "$base/live/$u/$p/$id.$extAoVivo",
                logo = s["stream_icon"].texto(),
                categoriaId = "AO_VIVO:${s["category_id"].texto()}",
                secao = Secao.AO_VIVO,
            )
        }
        filmes.await().forEach { s ->
            val id = s["stream_id"].texto()
            if (id.isEmpty()) return@forEach
            val ext = s["container_extension"].texto().ifEmpty { "mp4" }
            itens += Item(
                nome = s["name"].texto(),
                url = "$base/movie/$u/$p/$id.$ext",
                logo = s["stream_icon"].texto(),
                categoriaId = "FILMES:${s["category_id"].texto()}",
                secao = Secao.FILMES,
                nota = s["rating"].texto(),
            )
        }
        series.await().forEach { s ->
            val id = s["series_id"].texto()
            if (id.isEmpty()) return@forEach
            itens += Item(
                nome = s["name"].texto(),
                logo = s["cover"].texto(),
                categoriaId = "SERIES:${s["category_id"].texto()}",
                secao = Secao.SERIES,
                serieId = id,
                nota = s["rating"].texto(),
            )
        }

        // Categorias usadas pelos itens mas que o servidor não listou
        val conhecidas = categorias.map { it.id }.toHashSet()
        itens.asSequence().map { it.categoriaId }.distinct().filter { it !in conhecidas }.forEach { id ->
            categorias += Categoria(id, "Outros", Secao.valueOf(id.substringBefore(':')))
        }

        Conteudo(itens, categorias, info, System.currentTimeMillis())
    }

    /** Temporadas e episódios de uma série (Xtream). */
    suspend fun serie(lista: Lista, serie: Item): DetalheSerie = withContext(Dispatchers.IO) {
        val texto = baixarTexto(api(lista, "get_series_info", "&series_id=${serie.serieId}"))
        val raiz = runCatching { Dados.json.parseToJsonElement(texto) }.getOrNull() as? JsonObject
            ?: throw IOException("Não foi possível carregar a série.")
        val info = raiz["info"] as? JsonObject
        val base = normalizarServidor(lista.url)

        val brutos: List<Pair<String, JsonObject>> = when (val eps = raiz["episodes"]) {
            is JsonObject -> eps.entries.flatMap { (temp, arr) -> (arr as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { temp to it } } }
            is JsonArray -> eps.flatMap { el ->
                when (el) {
                    is JsonArray -> el.mapNotNull { e -> (e as? JsonObject)?.let { "" to it } }
                    is JsonObject -> listOf("" to el)
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
        val episodios = brutos.mapNotNull { (temp, e) ->
            val id = e["id"].texto()
            if (id.isEmpty()) return@mapNotNull null
            val ext = e["container_extension"].texto().ifEmpty { "mp4" }
            val temporada = e["season"].texto().toIntOrNull() ?: temp.toIntOrNull() ?: 1
            val numero = e["episode_num"].texto().toIntOrNull() ?: 0
            val capa = ((e["info"] as? JsonObject)?.get("movie_image")).texto()
            Episodio(
                temporada = temporada,
                numero = numero,
                titulo = e["title"].texto().ifEmpty { "Episódio $numero" },
                url = "$base/series/${lista.usuario}/${lista.senha}/$id.$ext",
                capa = capa,
            )
        }.sortedWith(compareBy({ it.temporada }, { it.numero }))

        DetalheSerie(
            capa = info?.get("cover").texto().ifEmpty { serie.logo },
            sinopse = info?.get("plot").texto(),
            episodios = episodios,
        )
    }
}
