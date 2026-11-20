package dev.mooner.lucent.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.mooner.lucent.R
import dev.mooner.lucent.data.ExpressionKind
import dev.mooner.lucent.data.ExpressionSection
import dev.mooner.lucent.data.ExpressionStore
import dev.mooner.lucent.data.Expressions
import dev.mooner.lucent.databinding.ItemEmoteBinding
import dev.mooner.lucent.databinding.ItemEmoteSectionBinding
import dev.mooner.lucent.databinding.ViewEmotePanelBinding
import dev.mooner.lucent.protocol.Expression
import dev.mooner.lucent.ui.common.ExpressionImage

/**
 * 이모지·스티커 판(docs/emotes.md, app-expressions.md 7장). 입력창의 이모지 단추가 키보드 자리에 연다.
 *
 * - 갈래는 둘(이모지, 스티커)이고, 그 아래 분류가 놓인다: "최근"(기기에 둔 20개)과 데몬 목록의 분류들.
 * - 찾기 단추를 누르면 첫 줄이 찾는 칸이 된다. 보이는 이름·이름·태그에서 찾고, 그동안은 키보드가 함께 떠 있어 격자가 낮아진다.
 * - 이모지를 누르면 입력창에 `:name:`이 담기고, 스티커를 누르면 그 한 장이 바로 보내진다. 판은 고른 뒤에도 열려 있다.
 * - 길게 누르면 이름과 설명을 보인다. 그림이 하나도 없으면 그렇다고 말한다.
 */
class ExpressionPanel(
    private val binding: ViewEmotePanelBinding,
    private val window: android.view.Window,
    private val store: ExpressionStore,
    onPick: (ExpressionKind, Expression) -> Unit,
) {
    private val grid = Grid(store) { expression -> onPick(kind, expression) }
    private var kind = ExpressionKind.EMOJI
    /** 고른 분류. null이면 아직 고르지 않았다(처음 열 때 정한다). */
    private var sectionId: String? = null
    private var keyboardShown = false

    val isShown: Boolean get() = binding.root.isVisible

    /** 찾는 중인가. 이때는 키보드가 올라와도 판을 닫지 않는다. */
    var isSearching = false
        private set

    init {
        binding.grid.adapter = grid
        binding.tabEmoji.setOnClickListener { select(ExpressionKind.EMOJI) }
        binding.tabStickers.setOnClickListener { select(ExpressionKind.STICKER) }
        binding.searchToggle.setOnClickListener { setSearching(!isSearching) }
        binding.search.doAfterTextChanged { if (isSearching) refresh() }
    }

    fun show() {
        binding.root.isVisible = true
        // 칸 수는 판의 폭이 정해져야 셀 수 있다. 배치 도중에 목록을 채우면 다시 배치하라는 요청이 버려지므로 다음 차례로 미룬다.
        binding.grid.doOnLayout { binding.grid.post { if (isShown) refresh() } }
    }

    fun hide() {
        // 판이 물러날 때는 키보드를 건드리지 않는다(글 칸으로 옮겨 간 것일 수 있다).
        setSearching(false, touchKeyboard = false)
        binding.root.isVisible = false
    }

    /** 키보드가 뜨고 내릴 때. 찾는 동안 키보드가 떠 있으면 격자를 낮춘다. */
    fun onKeyboard(shown: Boolean) {
        keyboardShown = shown
        val height = binding.root.resources.getDimensionPixelSize(
            if (shown && isSearching) R.dimen.emote_grid_height_compact else R.dimen.emote_grid_height,
        )
        if (binding.gridFrame.layoutParams.height != height) binding.gridFrame.updateLayoutParams { this.height = height }
    }

    private fun select(kind: ExpressionKind) {
        if (this.kind != kind) sectionId = null
        this.kind = kind
        refresh()
    }

    private fun setSearching(on: Boolean, touchKeyboard: Boolean = true) {
        if (isSearching == on) return
        isSearching = on
        binding.tabs.isVisible = !on
        binding.search.isVisible = on
        binding.searchToggle.setImageResource(if (on) R.drawable.ic_close else R.drawable.ic_search)
        binding.searchToggle.contentDescription = binding.root.context.getString(if (on) R.string.emote_search_close else R.string.emote_search)
        val ime = WindowCompat.getInsetsController(window, binding.search)
        if (on) {
            binding.search.setText("")
            binding.search.requestFocus()
            ime.show(WindowInsetsCompat.Type.ime())
        } else {
            binding.search.clearFocus()
            if (touchKeyboard) ime.hide(WindowInsetsCompat.Type.ime())
            onKeyboard(keyboardShown)
        }
        if (isShown) refresh()
    }

    /** 목록이나 그림이 바뀌었거나, 갈래·분류·찾을 말이 바뀌었을 때 다시 그린다. */
    fun refresh() {
        if (!isShown || binding.grid.width == 0) return
        val res = binding.root.resources
        val now = store.current.value
        val sticker = kind == ExpressionKind.STICKER
        binding.tabEmoji.isSelected = !sticker
        binding.tabStickers.isSelected = sticker
        binding.search.setHint(if (sticker) R.string.emote_search_hint_sticker else R.string.emote_search_hint_emoji)

        val query = binding.search.text.toString().trim()
        val items: List<Expression>
        if (isSearching && query.isNotEmpty()) {
            binding.sectionScroll.isVisible = false
            items = now.search(kind, query)
        } else {
            val sections = sections(now)
            val chosen = sections.firstOrNull { it.id == sectionId } ?: sections.firstOrNull()
            sectionId = chosen?.id
            showSections(sections, chosen)
            items = chosen?.items.orEmpty()
        }

        val cell = res.getDimensionPixelSize(if (sticker) R.dimen.sticker_cell else R.dimen.emote_cell)
        val columns = (binding.grid.width / cell).coerceAtLeast(1)
        if ((binding.grid.layoutManager as? GridLayoutManager)?.spanCount != columns) {
            binding.grid.layoutManager = GridLayoutManager(binding.root.context, columns)
        }
        grid.show(items, sticker, cell, res.getDimensionPixelSize(if (sticker) R.dimen.sticker_cell_image else R.dimen.emote_cell_image))

        binding.emoteEmpty.isVisible = items.isEmpty()
        binding.emoteEmpty.setText(
            when {
                isSearching && query.isNotEmpty() -> R.string.emote_search_none
                sticker -> R.string.emote_empty_sticker
                else -> R.string.emote_empty_emoji
            },
        )
        binding.searchToggle.isVisible = isSearching || now.of(kind).isNotEmpty()
    }

    /** "최근"을 맨 앞에, 그 뒤에 목록의 분류를 둔다. 분류 없는 묶음은 혼자면 "전체", 다른 분류가 있으면 "기타"다. */
    private fun sections(now: Expressions): List<ExpressionSection> {
        val ctx = binding.root.context
        val fromCatalog = now.sections(kind).let { list ->
            list.map { if (it.id.isEmpty()) it.copy(label = ctx.getString(if (list.size == 1) R.string.emote_all else R.string.emote_other)) else it }
        }
        val recent = store.recent(kind)
        return if (recent.isEmpty()) fromCatalog
        else listOf(ExpressionSection(RECENT, ctx.getString(R.string.emote_recent), null, recent)) + fromCatalog
    }

    private fun showSections(sections: List<ExpressionSection>, chosen: ExpressionSection?) {
        // 고를 것이 하나뿐이면 줄 자체를 두지 않는다.
        binding.sectionScroll.isVisible = sections.size > 1
        binding.sections.removeAllViews()
        if (sections.size <= 1) return
        val inflater = LayoutInflater.from(binding.root.context)
        for (section in sections) {
            val chip = ItemEmoteSectionBinding.inflate(inflater, binding.sections, false)
            chip.label.text = section.label
            val icon = section.icon?.let(store::file)
            chip.icon.isVisible = icon != null
            if (icon != null) ExpressionImage.load(chip.icon, icon) { chip.icon.isVisible = false }
            chip.root.isSelected = section.id == chosen?.id
            chip.label.isSelected = chip.root.isSelected
            chip.root.contentDescription = section.label
            chip.root.setOnClickListener {
                sectionId = section.id
                refresh()
            }
            binding.sections.addView(chip.root)
        }
    }

    private class Grid(private val store: ExpressionStore, private val onPick: (Expression) -> Unit) : RecyclerView.Adapter<Grid.Cell>() {
        private var items: List<Expression> = emptyList()
        private var sticker = false
        private var cell = 0
        private var image = 0

        fun show(items: List<Expression>, sticker: Boolean, cell: Int, image: Int) {
            this.items = items
            this.sticker = sticker
            this.cell = cell
            this.image = image
            @Suppress("NotifyDataSetChanged") // 갈래나 분류를 바꾸면 격자 전체가 바뀐다.
            notifyDataSetChanged()
        }

        class Cell(val b: ItemEmoteBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Cell(ItemEmoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Cell, position: Int) {
            val expression = items[position]
            val b = holder.b
            val ctx = b.root.context
            b.root.updateLayoutParams { height = cell }
            b.image.updateLayoutParams {
                width = image
                height = image
            }
            // 그림이 아직 없거나 디코더가 받지 못하면 이름으로 보인다.
            val file = store.file(expression)
            val title = Expressions.title(expression)
            b.name.text = title
            b.name.isVisible = file == null
            b.image.isVisible = file != null
            if (file != null) {
                ExpressionImage.load(b.image, file) {
                    b.image.isVisible = false
                    b.name.isVisible = true
                }
            } else {
                ExpressionImage.clear(b.image)
            }
            val spoken = ctx.getString(if (sticker) R.string.sticker_spoken else R.string.emoji_spoken, title)
            b.root.contentDescription = if (expression.description.isBlank()) spoken else "$spoken, ${expression.description}"
            b.root.setOnClickListener { onPick(expression) }
            b.root.setOnLongClickListener {
                val shown = if (sticker) expression.name else ":${expression.name}:"
                val about = listOf(expression.label, expression.description).filter { it.isNotBlank() }.joinToString(" · ")
                Toast.makeText(ctx, if (about.isEmpty()) shown else "$shown\n$about", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private companion object {
        /** "최근" 묶음의 ID. 목록의 분류 ID(영문 소문자·숫자·밑줄)와 겹치지 않는다. */
        const val RECENT = "#recent"
    }
}
