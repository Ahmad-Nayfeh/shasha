package com.ahmadnayfeh.shasha.ui.screens.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * The search box. It uses the TV's own keyboard: focus passes through without opening it, OK opens it
 * (Compose's text field does this for D-pad remotes), and the keyboard's search key submits. The
 * D-pad moves focus out of the box as everywhere else.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateDpAsState(if (focused) 3.dp else 1.dp, label = "searchBorder")
    val shape = RoundedCornerShape(percent = 50)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = ShashaColors.Text, textDirection = TextDirection.Content),
        cursorBrush = SolidColor(ShashaColors.PrimaryBright),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        modifier = modifier.testTag("search_field"),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(if (focused) ShashaColors.SurfaceHigh else ShashaColors.Surface, shape)
                    .border(border, if (focused) ShashaColors.Primary else ShashaColors.Outline, shape)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    ShashaIcons.Search,
                    contentDescription = null,
                    tint = if (focused) ShashaColors.PrimaryBright else ShashaColors.Muted,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted, maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}
