package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.screens.settings.Switch
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

private val RowShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(22.dp)

/** A pane's title: the category's icon, name and what it holds; [trailing] (a status) sits at the far side. */
@Composable
internal fun PaneHeader(category: SettingsCategory, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val accent = LocalAccent.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .background(accent.tint(0.14f), CircleShape)
                .border(1.dp, accent.tint(0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(category.icon, contentDescription = null, tint = accent.bright, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(category.title), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(category.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = ShashaColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * A pane's scrolling body. The padding leaves room for the focused row's zoom and glow; the focus scrolls the
 * rows below the fold into view.
 */
@Composable
internal fun PaneColumn(modifier: Modifier = Modifier, spacing: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 40.dp)),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * A row that opens something or changes a setting: [icon] (or [leading]), the title and a line about it, the
 * current [value], and a chevron (or [trailing]). [locked]: the parent PIN is asked first.
 */
@Composable
internal fun PaneRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    body: String? = null,
    value: String? = null,
    locked: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RowShape,
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.02f,
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(24.dp))
            }
            if (leading != null || icon != null) Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (locked) {
                        Spacer(Modifier.width(8.dp))
                        Icon(ShashaIcons.Lock, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(16.dp).testTag("row_locked"))
                    }
                }
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShashaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (trailing != null) {
                trailing()
            } else {
                Icon(ShashaIcons.Chevron, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** An on/off row with a line about it; OK flips it. */
@Composable
internal fun SwitchRow(
    title: String,
    body: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    locked: Boolean = false,
) {
    PaneRow(
        title = title,
        onClick = onToggle,
        modifier = modifier.semantics { stateDescription = if (checked) "on" else "off" },
        icon = icon,
        body = body,
        locked = locked,
    ) { Switch(checked) }
}

/** One of several choices: a round mark, filled for the chosen one. [badge]: a short note beside the title. */
@Composable
internal fun ChoiceRow(
    title: String,
    body: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .semantics { stateDescription = if (selected) "selected" else "not selected" },
        shape = RowShape,
        containerColor = if (selected) accent.tint(0.10f) else ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.02f,
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(if (selected) accent.main else Color.Transparent, CircleShape)
                    .border(2.dp, if (selected) accent.main else ShashaColors.Muted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(ShashaIcons.Check, contentDescription = null, tint = accent.onMain, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, color = if (selected) accent.bright else LocalContentColor.current)
                    if (badge != null) {
                        Spacer(Modifier.width(10.dp))
                        Tag(badge, accent.bright)
                    }
                }
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * In place of a parental pane's content until the parent PIN is given: what the lock is for, and the button
 * that asks for the PIN ([first]: the pane's first focus).
 */
@Composable
internal fun LockedCard(onUnlock: () -> Unit, first: FocusRequester, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Column(
        modifier
            .fillMaxWidth()
            .background(ShashaColors.Surface, CardShape)
            .border(1.dp, ShashaColors.Outline, CardShape)
            .padding(horizontal = 24.dp, vertical = 22.dp)
            .testTag("settings_locked"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(ShashaColors.Secondary.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, ShashaColors.Secondary.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ShashaIcons.Lock, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hub_locked_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.hub_locked_body), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
            }
        }
        Spacer(Modifier.height(18.dp))
        ShashaButton(
            stringResource(R.string.hub_locked_action),
            onUnlock,
            icon = ShashaIcons.Key,
            primary = true,
            modifier = Modifier.focusRequester(first).paneItem("settings_unlock"),
        )
        Spacer(Modifier.height(18.dp))
        InfoLine(ShashaIcons.Info, stringResource(R.string.hub_locked_note), tint = accent.bright.copy(alpha = 0.8f))
    }
}

/** A line of information, not focusable: a small icon and a sentence. */
@Composable
internal fun InfoLine(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = ShashaColors.Muted, textColor: Color = ShashaColors.Muted) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 3.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.weight(1f))
    }
}

/** A small rounded label ([color] text on a tint of it). */
@Composable
internal fun Tag(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/**
 * A block of text that takes the focus only so the remote can move through it (the D-pad scrolls a pane by
 * moving the focus): the focus ring shows where the reader is; OK does nothing.
 */
@Composable
internal fun ReadingCard(
    modifier: Modifier = Modifier,
    tone: Color? = null,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    FocusSurface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        containerColor = tone?.copy(alpha = 0.08f) ?: ShashaColors.Surface,
        focusedContainerColor = tone?.copy(alpha = 0.14f) ?: ShashaColors.SurfaceVariant,
        scale = 1.01f,
    ) {
        val border = tone?.copy(alpha = 0.35f) ?: ShashaColors.Outline
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, border, CardShape)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * The panes' focusable elements by name, so that coming back from a screen one of them opened puts the focus
 * back on it (the screen that opened is gone from the composition by then; its focus state with it).
 * [onFocused] hears which one took the focus.
 */
@Stable
internal class PaneItems {
    private val items = mutableMapOf<String, PaneItemNode>()

    var onFocused: (String) -> Unit = {}

    internal fun attach(node: PaneItemNode) {
        items[node.key] = node
    }

    internal fun detach(node: PaneItemNode) {
        if (items[node.key] === node) items.remove(node.key)
    }

    /** Moves the focus to the element named [key]; false when there is none. */
    fun focus(key: String): Boolean = items[key]?.requestFocus() ?: false
}

internal val LocalPaneItems = staticCompositionLocalOf<PaneItems?> { null }

/** A pane's focusable element named [key] (also its test tag): see [PaneItems]. Place it before the element's own modifiers. */
internal fun Modifier.paneItem(key: String): Modifier = this then PaneItemElement(key) then Modifier.testTag(key)

private data class PaneItemElement(val key: String) : ModifierNodeElement<PaneItemNode>() {
    override fun create() = PaneItemNode(key)

    override fun update(node: PaneItemNode) = node.update(key)

    override fun InspectorInfo.inspectableProperties() {
        name = "paneItem"
        properties["key"] = key
    }
}

internal class PaneItemNode(key: String) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    FocusEventModifierNode,
    FocusRequesterModifierNode {
    var key: String = key
        private set

    private var items: PaneItems? = null

    fun update(key: String) {
        items?.detach(this)
        this.key = key
        items?.attach(this)
    }

    override fun onAttach() = join()

    override fun onObservedReadsChanged() = join()

    override fun onDetach() {
        items?.detach(this)
        items = null
    }

    /** Joins the registry the pane provides, and moves to another one should the pane provide it instead. */
    private fun join() {
        var next: PaneItems? = null
        observeReads { next = currentValueOf(LocalPaneItems) }
        if (next === items) return
        items?.detach(this)
        items = next?.also { it.attach(this) }
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) items?.onFocused?.invoke(key)
    }
}
