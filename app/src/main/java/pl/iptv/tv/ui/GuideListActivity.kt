package pl.iptv.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import pl.iptv.tv.R
import pl.iptv.tv.data.Channel
import pl.iptv.tv.data.Prefs
import pl.iptv.tv.data.Programme
import pl.iptv.tv.data.Repository
import java.util.Calendar

/**
 * Program TV na telefonie w pionie: kanały w przewijanym pasku u góry, program wybranego dnia pod spodem.
 * Na TV i w poziomie używana jest siatka ([GuideActivity]) – przełączenie następuje samo przy obrocie.
 */
class GuideListActivity : BaseActivity() {
    companion object {
        private const val DAY = 86_400_000L
        fun isPortraitPhone(a: android.app.Activity) = a.resources.configuration.screenWidthDp < 600 &&
            a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    private lateinit var chips: RecyclerView
    private lateinit var progs: RecyclerView
    private var group = Repository.ALL
    private var channels: List<Channel> = emptyList()
    private var channel: Channel? = null
    private var dayOffset = 0

    private val progAdapter: ProgrammeAdapter = ProgrammeAdapter(onClick = ::onProgramme, onFocus = ::showDetails,
        onLongClick = { p -> channel?.let { ch -> ProgrammeActions.menu(this, ch, p, ::play, channelItem = true) { progAdapter.notifyDataSetChanged() } } })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // obrót do poziomu: siatka programu
        if (!isPortraitPhone(this)) {
            startActivity(Intent(this, GuideActivity::class.java).putExtras(intent)); finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0); return
        }
        setThemedContent(R.layout.activity_guide_list)
        chips = findViewById(R.id.chips); progs = findViewById(R.id.progs)
        chips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false); chips.adapter = chipAdapter
        chips.itemAnimator = null
        progs.layoutManager = LinearLayoutManager(this); progs.adapter = progAdapter
        val key = savedInstanceState?.getString("ch") ?: intent.getStringExtra(GuideActivity.EXTRA_CHANNEL) ?: Prefs.lastChannel
        dayOffset = savedInstanceState?.getInt("day") ?: 0
        group = savedInstanceState?.getString("group") ?: Repository.ALL
        findViewById<View>(R.id.btnGroup).setOnClickListener { chooseGroup() }
        findViewById<View>(R.id.btnSearch).apply {
            visibility = if (Prefs.epgSearchEnabled) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@GuideListActivity, EpgSearchActivity::class.java)) }
        }
        findViewById<View>(R.id.btnDayPrev).setOnClickListener { setDay(dayOffset - 1) }
        findViewById<View>(R.id.btnDayNext).setOnClickListener { setDay(dayOffset + 1) }
        findViewById<View>(R.id.btnNow).setOnClickListener { setDay(0, scrollNow = true) }
        loadGroup(key)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("ch", channel?.key); out.putInt("day", dayOffset); out.putString("group", group)
    }

    private fun loadGroup(selectKey: String?) {
        channels = Repository.channelsFor(group).ifEmpty { group = Repository.ALL; Repository.channels }
        findViewById<TextView>(R.id.btnGroup).text = "☰ $group"
        chipAdapter.notifyDataSetChanged()
        select(channels.firstOrNull { it.key == selectKey } ?: channels.firstOrNull())
    }

    private fun chooseGroup() {
        val g = Repository.groups()
        Dialogs.choice(this, "Grupa kanałów", g, g.indexOf(group)) { group = g[it]; loadGroup(channel?.key) }
    }

    private fun select(ch: Channel?) {
        val old = channels.indexOf(channel)
        channel = ch
        if (old >= 0) chipAdapter.notifyItemChanged(old)
        val i = channels.indexOf(ch)
        if (i >= 0) { chipAdapter.notifyItemChanged(i); (chips.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Ui.dp(100)) }
        setDay(dayOffset, scrollNow = dayOffset == 0)
    }

    private fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis + offset * DAY

    private fun setDay(offset: Int, scrollNow: Boolean = false) {
        dayOffset = offset.coerceIn(-7, 7)
        val ch = channel
        val from = dayStart(dayOffset)
        findViewById<TextView>(R.id.dayLabel).text = Fmt.day(from)
        findViewById<View>(R.id.btnDayPrev).isEnabled = dayOffset > -7
        findViewById<View>(R.id.btnDayNext).isEnabled = dayOffset < 7
        if (ch == null) { progAdapter.submit(emptyList(), Long.MAX_VALUE); showDetails(null); return }
        val now = System.currentTimeMillis()
        val list = Repository.programmes(ch).filter { it.end > from && it.start < from + DAY }
        val catchupFrom = if (ch.hasCatchup) now - ch.catchupDays * DAY else Long.MAX_VALUE
        progAdapter.submit(list, catchupFrom, ch)
        val cur = list.indexOfFirst { it.isNow(now) }
        if (list.isEmpty()) showDetails(null) else showDetails(list.getOrElse(cur) { list.first() })
        val lm = progs.layoutManager as LinearLayoutManager
        if (scrollNow && cur >= 0) lm.scrollToPositionWithOffset(cur, 0) else lm.scrollToPosition(0)
    }

    private fun showDetails(p: Programme?) {
        val t = findViewById<TextView>(R.id.dTitle); val d = findViewById<TextView>(R.id.dDesc)
        if (p == null) { t.text = channel?.name ?: ""; d.text = "Brak programu TV w tym dniu."; return }
        t.text = "${Fmt.time(p.start)}–${Fmt.time(p.end)}  ${p.fullTitle}"
        d.text = p.desc.ifBlank { "Brak opisu w programie TV." }
    }

    /** Dotknięcie: opis; ponowne dotknięcie tego samego programu (lub OK pilotem) – menu programu. */
    private var lastTapped: Programme? = null
    private fun onProgramme(p: Programme) {
        val ch = channel ?: return
        if (progs.isInTouchMode && lastTapped !== p) { lastTapped = p; showDetails(p); return }
        ProgrammeActions.menu(this, ch, p, ::play, channelItem = true) { progAdapter.notifyDataSetChanged() }
    }

    private fun play(ch: Channel, p: Programme?) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL, ch.key)
            putExtra(PlayerActivity.EXTRA_GROUP, group)
            if (p != null) { putExtra(PlayerActivity.EXTRA_START, p.start); putExtra(PlayerActivity.EXTRA_END, p.end) }
        })
    }

    // ---------------- pasek kanałów ----------------

    private inner class ChipVH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val logo = ImageView(this@GuideListActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; isDuplicateParentStateEnabled = true }
        val name = TextView(this@GuideListActivity).apply { textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        init {
            root.orientation = LinearLayout.VERTICAL; root.gravity = Gravity.CENTER; root.tag = "item"
            root.isFocusable = true; root.isClickable = true
            root.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(4))
            root.layoutParams = RecyclerView.LayoutParams(Ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(6) }
            root.addView(logo, LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(28)))
            root.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(3) })
            Ui.style(root)
        }
    }

    private val chipAdapter = object : RecyclerView.Adapter<ChipVH>() {
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ChipVH(LinearLayout(p.context))
        override fun onBindViewHolder(h: ChipVH, i: Int) {
            val ch = channels[i]
            h.logo.loadPicon(ch); h.name.text = ch.name
            h.root.isActivated = ch.key == channel?.key
            h.root.setOnClickListener { if (ch.key != channel?.key) { lastTapped = null; select(ch) } }
        }
    }
}
