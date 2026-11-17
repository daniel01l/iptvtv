package pl.iptv.tv.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import pl.iptv.tv.data.StremioAccount
import pl.iptv.tv.data.StremioAddon
import pl.iptv.tv.data.StremioApi
import pl.iptv.tv.data.StremioStore

/**
 * Zarządzanie wtyczkami Stremio: lista wtyczek z konta (i dodanych z adresu) z przełącznikiem Włączona / Wyłączona.
 * Tylko włączone wtyczki biorą udział w wyszukiwaniu, katalogach, źródłach i napisach. Stan zapisany w bazie (Room).
 */
class StremioAddonsActivity : BaseActivity() {
    override val pausesLiveTv = true
    override val ps5Hints = true
    lateinit var adapter: StremioAddonAdapter; private set
    private lateinit var list: RecyclerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(40), Ui.dp(20), Ui.dp(40), Ui.dp(12)) }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(StremioUi.text(this, "Wtyczki Stremio", 26f, "accent", bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(StremioUi.button(this, "＋ Dodaj z adresu") { addByUrl() })
        if (StremioAccount.loggedIn) header.addView(StremioUi.button(this, "⟳ Z konta") { refreshFromAccount() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        header.addView(StremioUi.button(this, "✖") { finish() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
        root.addView(header)
        status = StremioUi.text(this, "OK – włącz / wyłącz wtyczkę w wyszukiwaniu i źródłach · przytrzymaj OK – usuń", 14f, "text2").apply { setPadding(Ui.dp(4), Ui.dp(8), 0, Ui.dp(10)) }
        root.addView(status)
        adapter = StremioAddonAdapter(emptyList(), onToggle = { a, on -> toggle(a, on) }, onLong = { a -> removeMenu(a) })
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StremioAddonsActivity); adapter = this@StremioAddonsActivity.adapter; itemAnimator = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setThemedContent(root)
        load(focus = true)
    }

    private fun load(focus: Boolean = false) = lifecycleScope.launch {
        adapter.items = StremioStore.addons(); adapter.notifyDataSetChanged()
        if (adapter.items.isEmpty()) status.text = "Brak wtyczek – dodaj z adresu manifestu albo zaloguj się do konta Stremio."
        if (focus) list.focusPosition(0)
    }

    fun toggle(a: StremioAddon, on: Boolean) = lifecycleScope.launch {
        StremioStore.setEnabled(a.transportUrl, on)
        val i = adapter.items.indexOfFirst { it.first.transportUrl == a.transportUrl }
        adapter.items = StremioStore.addonsCache
        if (i >= 0) adapter.notifyItemChanged(i) else adapter.notifyDataSetChanged()
    }

    private fun addByUrl() = Dialogs.input(this, "Adres wtyczki (manifest.json)", "", "https://…/manifest.json") { url ->
        if (url.isBlank()) return@input
        lifecycleScope.launch {
            runCatching { StremioStore.addCustom(url) }
                .onSuccess { Toast.makeText(this@StremioAddonsActivity, "Dodano: ${it.name}", Toast.LENGTH_SHORT).show(); load() }
                .onFailure { Toast.makeText(this@StremioAddonsActivity, "Nie udało się dodać: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun refreshFromAccount() = lifecycleScope.launch {
        runCatching { StremioStore.replaceFromAccount(StremioApi.addonCollection(StremioAccount.authKey)) }
            .onFailure { Toast.makeText(this@StremioAddonsActivity, "Błąd: ${it.message}", Toast.LENGTH_LONG).show() }
        load()
    }

    private fun removeMenu(a: StremioAddon) = Dialogs.menu(this, a.name, listOf(
        "✖ Usuń z listy" to { lifecycleScope.launch { StremioStore.remove(a.transportUrl); load() }; Unit },
        "ℹ ${a.transportUrl}" to {},
    ))
}
