package nfs.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TelaNavegar(rota: Rota.Navegar, onSerie: (Item) -> Unit, onTrocarLista: () -> Unit) {
    val lista = rota.lista
    val est = rota.estado
    val scope = rememberCoroutineScope()

    fun carregar(forcar: Boolean) {
        scope.launch {
            est.carregando = true
            est.erro = null
            try {
                val c = Repositorio.carregar(lista, forcar)
                est.conteudo = c
                // Abre na primeira seção que tiver conteúdo
                if (c.itens.none { it.secao == est.secao }) {
                    Secao.entries.firstOrNull { s -> c.itens.any { it.secao == s } }?.let { est.secao = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                est.erro = mensagemDeErro(e)
            } finally {
                est.carregando = false
            }
        }
    }

    LaunchedEffect(lista.id) { if (est.conteudo == null && !est.carregando) carregar(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val largo = maxWidth >= 720.dp
        val conteudo = est.conteudo

        Column(Modifier.fillMaxSize()) {
            // ----- Barra do topo -----
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo(18.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(lista.nome, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    conteudo?.info?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextoSuave, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { carregar(true) }, enabled = !est.carregando, modifier = Modifier.focavel(RoundedCornerShape(50))) {
                    Icon(Icons.Filled.Refresh, "Atualizar lista")
                }
                IconButton(onClick = onTrocarLista, modifier = Modifier.focavel(RoundedCornerShape(50))) {
                    Icon(Icons.Filled.SwapHoriz, "Trocar de lista")
                }
            }

            when {
                conteudo == null && est.carregando -> Carregando("Carregando a lista…\nNa primeira vez pode demorar um pouco.")
                conteudo == null && est.erro != null -> MensagemErro(est.erro!!) { carregar(true) }
                conteudo == null -> Carregando("Carregando…")
                else -> Corpo(lista, conteudo, est, largo, onSerie)
            }
        }

        if (est.carregando && conteudo != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Destaque)
        }
        if (est.erro != null && conteudo != null) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { est.erro = null }) { Text("OK") } },
            ) { Text("Não foi possível atualizar: ${est.erro}") }
        }
    }
}

@Composable
private fun Corpo(lista: Lista, conteudo: Conteudo, est: EstadoNavegar, largo: Boolean, onSerie: (Item) -> Unit) {
    val contexto = LocalContext.current
    val porCategoria = remember(conteudo) { conteudo.itens.groupBy { it.categoriaId } }
    val contagemSecao = remember(conteudo) { conteudo.itens.groupingBy { it.secao }.eachCount() }
    val secoes = Secao.entries.filter { (contagemSecao[it] ?: 0) > 0 }.ifEmpty { listOf(Secao.AO_VIVO) }
    val categorias = remember(conteudo, est.secao) { conteudo.categorias.filter { it.secao == est.secao && porCategoria.containsKey(it.id) } }
    val favoritos = Dados.favoritos

    val visiveis: List<Item> = remember(conteudo, est.secao, est.categoria, est.busca, favoritos) {
        val termo = est.busca.trim()
        when {
            termo.isNotEmpty() -> conteudo.itens.filter { it.secao == est.secao && it.nome.contains(termo, ignoreCase = true) }
            est.categoria == CAT_FAVORITOS -> conteudo.itens.filter { it.secao == est.secao && "${lista.id}|${it.chave}" in favoritos }
            est.categoria == null || est.categoria == CAT_TODOS -> conteudo.itens.filter { it.secao == est.secao }
            else -> porCategoria[est.categoria].orEmpty()
        }
    }
    val tituloAtual = when {
        est.busca.isNotBlank() -> "Busca: ${est.busca.trim()}"
        est.categoria == CAT_FAVORITOS -> "Favoritos"
        est.categoria == null || est.categoria == CAT_TODOS -> "Todos"
        else -> categorias.firstOrNull { it.id == est.categoria }?.nome ?: ""
    }

    val abrir: (Int) -> Unit = { i ->
        val item = visiveis[i]
        when {
            item.secao == Secao.SERIES && item.url.isEmpty() -> onSerie(item)
            item.secao == Secao.FILMES -> Reproducao.iniciar(contexto, listOf(item), 0)
            else -> Reproducao.iniciar(contexto, visiveis, i)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ----- Seções e busca -----
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(secoes) { s ->
                FilterChip(
                    selected = est.secao == s,
                    onClick = { if (est.secao != s) { est.secao = s; est.categoria = null } },
                    label = { Text("${s.titulo} (${contagemSecao[s] ?: 0})") },
                    leadingIcon = {
                        Icon(
                            when (s) {
                                Secao.AO_VIVO -> Icons.Filled.LiveTv
                                Secao.FILMES -> Icons.Filled.Movie
                                Secao.SERIES -> Icons.Filled.VideoLibrary
                            }, null, Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.focavel(RoundedCornerShape(8.dp)),
                )
            }
        }
        OutlinedTextField(
            value = est.busca,
            onValueChange = { est.busca = it },
            placeholder = { Text("Buscar em ${est.secao.titulo.lowercase()}") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (est.busca.isNotEmpty()) IconButton(onClick = { est.busca = "" }) { Icon(Icons.Filled.Close, "Limpar") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )

        val listaCategorias: @Composable (Modifier) -> Unit = { m ->
            ListaCategorias(
                categorias = categorias,
                porCategoria = porCategoria,
                totalSecao = contagemSecao[est.secao] ?: 0,
                totalFavoritos = conteudo.itens.count { it.secao == est.secao && "${lista.id}|${it.chave}" in favoritos },
                selecionada = est.categoria ?: if (largo) CAT_TODOS else null,
                onEscolher = { est.categoria = it; est.busca = "" },
                modifier = m,
            )
        }
        val gradeItens: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(
                    "$tituloAtual · ${visiveis.size}",
                    color = TextoSuave, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (visiveis.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (est.categoria == CAT_FAVORITOS) "Nenhum favorito ainda.\nToque na estrela (ou segure o item) para favoritar."
                            else "Nada encontrado.",
                            color = TextoSuave,
                        )
                    }
                } else if (est.secao == Secao.AO_VIVO) {
                    ListaCanais(lista, visiveis, abrir)
                } else {
                    GradeCapas(lista, visiveis, largo, abrir)
                }
            }
        }

        if (largo) {
            Row(Modifier.fillMaxSize()) {
                listaCategorias(Modifier.width(280.dp).fillMaxHeight())
                gradeItens(Modifier.weight(1f).fillMaxHeight())
            }
        } else if (est.categoria == null && est.busca.isBlank()) {
            listaCategorias(Modifier.fillMaxSize())
        } else {
            BackHandler { est.categoria = null; est.busca = "" }
            gradeItens(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ListaCategorias(
    categorias: List<Categoria>,
    porCategoria: Map<String, List<Item>>,
    totalSecao: Int,
    totalFavoritos: Int,
    selecionada: String?,
    onEscolher: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { LinhaCategoria("★  Favoritos", totalFavoritos, selecionada == CAT_FAVORITOS) { onEscolher(CAT_FAVORITOS) } }
        item { LinhaCategoria("Todos", totalSecao, selecionada == CAT_TODOS) { onEscolher(CAT_TODOS) } }
        items(categorias, key = { it.id }) { c ->
            LinhaCategoria(c.nome, porCategoria[c.id]?.size ?: 0, selecionada == c.id) { onEscolher(c.id) }
        }
    }
}

@Composable
private fun LinhaCategoria(nome: String, quantidade: Int, selecionada: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focavel(RoundedCornerShape(8.dp))
            .background(if (selecionada) Destaque.copy(alpha = 0.18f) else Superficie, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            nome, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (selecionada) Destaque else Color.White,
            fontWeight = if (selecionada) FontWeight.Bold else FontWeight.Normal,
        )
        Text("$quantidade", color = TextoSuave, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListaCanais(lista: Lista, canais: List<Item>, abrir: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(canais, key = { i, it -> "$i|${it.chave}" }) { i, canal ->
            val favorito = "${lista.id}|${canal.chave}" in Dados.favoritos
            Row(
                Modifier.fillMaxWidth().focavel(RoundedCornerShape(8.dp))
                    .background(Superficie, RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { abrir(i) }, onLongClick = { Dados.alternarFavorito(lista, canal) })
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = TextoSuave, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                Imagem(
                    canal.logo, canal.nome,
                    Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x14FFFFFF)),
                    corte = false,
                )
                Spacer(Modifier.width(12.dp))
                Text(canal.nome, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { Dados.alternarFavorito(lista, canal) }) {
                    Icon(
                        if (favorito) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (favorito) "Tirar dos favoritos" else "Favoritar",
                        tint = if (favorito) Ouro else TextoSuave,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GradeCapas(lista: Lista, itens: List<Item>, largo: Boolean, abrir: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (largo) 150.dp else 108.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(itens, key = { i, it -> "$i|${it.chave}" }) { i, item ->
            val favorito = "${lista.id}|${item.chave}" in Dados.favoritos
            Column(
                Modifier.focavel(RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { abrir(i) }, onLongClick = { Dados.alternarFavorito(lista, item) })
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                    Imagem(item.logo, item.nome, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), corte = true)
                    if (favorito) {
                        Icon(
                            Icons.Filled.Star, "Favorito", tint = Ouro,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                .background(Color(0x99000000), RoundedCornerShape(50)).padding(3.dp).size(16.dp),
                        )
                    }
                    item.nota.toDoubleOrNull()?.takeIf { it > 0 }?.let { nota ->
                        Text(
                            "★ ${"%.1f".format(nota)}", fontSize = 11.sp, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    item.nome, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// =============================================================================================
// Série: temporadas e episódios
// =============================================================================================

@Composable
fun TelaSerie(lista: Lista, serie: Item) {
    val contexto = LocalContext.current
    var detalhe by remember { mutableStateOf<DetalheSerie?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var tentativa by remember { mutableIntStateOf(0) }
    var temporada by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(tentativa) {
        erro = null
        try {
            detalhe = Repositorio.serie(lista, serie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            erro = mensagemDeErro(e)
        }
    }

    val d = detalhe
    when {
        d == null && erro != null -> MensagemErro(erro!!) { tentativa++ }
        d == null -> Carregando("Carregando episódios…")
        else -> {
            val temporadas = d.episodios.map { it.temporada }.distinct()
            val atual = temporada ?: temporadas.firstOrNull()
            val episodios = d.episodios.filter { it.temporada == atual }
            val favorito = Dados.ehFavorito(lista, serie)

            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row {
                        Imagem(
                            d.capa, serie.nome,
                            Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)), corte = true,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(serie.nome, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            serie.nota.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                                Text("★ ${"%.1f".format(it)}", color = Ouro, fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            if (d.sinopse.isNotBlank()) {
                                Text(d.sinopse, color = TextoSuave, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { Dados.alternarFavorito(lista, serie) },
                                modifier = Modifier.focavel(RoundedCornerShape(50)),
                            ) {
                                Icon(if (favorito) Icons.Filled.Star else Icons.Filled.StarBorder, null, tint = if (favorito) Ouro else Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(if (favorito) "Nos favoritos" else "Favoritar")
                            }
                        }
                    }
                }
                if (d.episodios.isEmpty()) {
                    item { Text("Esta série ainda não tem episódios disponíveis.", color = TextoSuave) }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(temporadas) { t ->
                                FilterChip(
                                    selected = t == atual,
                                    onClick = { temporada = t },
                                    label = { Text("Temporada $t") },
                                    modifier = Modifier.focavel(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                    }
                    itemsIndexed(episodios) { i, ep ->
                        Row(
                            Modifier.fillMaxWidth().focavel(RoundedCornerShape(8.dp))
                                .background(Superficie, RoundedCornerShape(8.dp))
                                .clickable {
                                    val fila = episodios.map {
                                        Item(
                                            nome = "${serie.nome} · T${it.temporada} E${it.numero} · ${it.titulo}",
                                            url = it.url, logo = it.capa.ifEmpty { d.capa }, secao = Secao.SERIES,
                                        )
                                    }
                                    Reproducao.iniciar(contexto, fila, i)
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Imagem(
                                ep.capa.ifEmpty { d.capa }, ep.titulo,
                                Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(4.dp)), corte = true,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Episódio ${ep.numero}", color = Destaque, fontSize = 12.sp)
                                Text(ep.titulo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Filled.PlayArrow, "Assistir", tint = Destaque)
                        }
                    }
                }
            }
        }
    }
}
