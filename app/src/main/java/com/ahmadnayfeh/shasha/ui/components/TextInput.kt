package com.ahmadnayfeh.shasha.ui.components

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * A one-line text box on the TV's own keyboard, for the adult's forms only (a profile's name, a blocked
 * keyword): there is no system text input anywhere in a child profile (SPEC §3). Focus passes through
 * without opening the keyboard; OK opens it, and its action key calls [onDone].
 */
@Composable
fun ShashaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    maxLength: Int = 40,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateDpAsState(if (focused) 3.dp else 1.dp, label = "fieldBorder")
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = ShashaColors.Text, textDirection = TextDirection.ContentOrRtl),
        cursorBrush = SolidColor(accent.bright),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(onAny = {
            keyboard?.hide()
            onDone()
        }),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(if (focused) ShashaColors.SurfaceHigh else ShashaColors.Surface, shape)
                    .border(border, if (focused) accent.main else ShashaColors.Outline, shape)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = if (focused) accent.bright else ShashaColors.Muted, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted, maxLines = 1)
                    inner()
                }
            }
        },
    )
}
