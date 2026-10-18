package com.ahmadnayfeh.shasha.ui.screens.search

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ahmadnayfeh.shasha.ui.components.FieldPill
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.TvTextField
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * The search box. It uses the TV's own keyboard, only when asked for: the D-pad's focus lands on the box without opening it
 * (entering Search never opens it), OK opens it ([TvTextField]), and the keyboard's search key submits and closes it. The
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
    TvTextField(
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        onImeAction = onSearch,
        modifier = modifier.testTag("search_field"),
    ) { focused, inner ->
        FieldPill(
            focused,
            hint,
            value.isEmpty(),
            ShashaIcons.Search,
            iconSize = 26.dp,
            ring = ShashaColors.Primary,
            iconTint = ShashaColors.PrimaryBright,
            inner = inner,
        )
    }
}
