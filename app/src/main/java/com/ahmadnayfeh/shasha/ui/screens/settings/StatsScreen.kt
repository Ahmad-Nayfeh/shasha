package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.ChannelUsage
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.ui.components.ChannelAvatar
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaChip
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun StatsRoute() {
    val vm = containerViewModel { container -> StatsViewModel(container.profiles, container.usage, container.policyEngine, zone = { container.clock.zone }) }
    val all by vm.all.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    StatsScreen(all, selected, summary, today = vm.today(), onSelect = vm::select)
}

/**
 * الإحصائيات (SPEC §3): per profile, the minutes of each of the last 7 days, this week against the one
 * before, the most watched channels, and (adult) how often the exit challenge was taken. Plain numbers:
 * no badges, no streaks, no scores.
 */
@Composable
fun StatsScreen(
    profiles: List<Profile>,
    selected: Profile?,
    summary: UsageSummary?,
    today: Long,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val texts = rememberTimeTexts()
    val chip = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { chip.requestFocus() }
    }
    val accent = if (selected?.isChild == true) Accents.Kids else Accents.Teal
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ShashaDimens.ScreenPadding)
            .testTag("stats"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.stats_subtitle), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
            }
            Spacer(Modifier.width(24.dp))
            Row(Modifier.focusRestorer(chip).focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                profiles.forEach { profile ->
                    ShashaChip(
                        profile.name,
                        selected = profile.id == selected?.id,
                        onClick = { onSelect(profile.id) },
                        modifier = (if (profile.id == selected?.id) Modifier.focusRequester(chip) else Modifier).testTag("stats_profile_${profile.id}"),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (selected == null || summary == null || summary.profileId != selected.id) return@Column
        CompositionLocalProvider(LocalAccent provides accent) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatsCard(Modifier.weight(1.45f).height(CardHeight)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(selected.avatar, selected.name, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.stats_last_7_days), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        selected.dailyQuotaMinutes?.let { quota ->
                            QuotaKey(stringResource(R.string.stats_quota_key, texts.durationShort(quota.toLong())))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    WeekChart(summary, today, selected.dailyQuotaMinutes, texts, animate, Modifier.fillMaxWidth().weight(1f))
                }
                Column(Modifier.weight(1f).height(CardHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WeekTotal(summary, selected, texts, Modifier.fillMaxWidth())
                    TopChannels(summary.topChannels, texts, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

private val CardHeight = 372.dp

@Composable
private fun StatsCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun QuotaKey(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 22.dp, height = 2.dp)) {
            drawLine(QuotaLineColor, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = size.height, pathEffect = quotaDash())
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted)
    }
}

/** The daily quota is a reference line, not data: neutral and dashed, drawn over the columns. */
private val QuotaLineColor = ShashaColors.Text.copy(alpha = 0.7f)

private fun DrawScope.quotaDash() = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))

/**
 * The last 7 days as columns, oldest on the right (the reading start) and today on the left. Today is in
 * the accent, the other days in a quiet neutral (3:1 on the card). Values show on today and the busiest
 * day; every other day shows its value when focused (the remote's LEFT and RIGHT walk the week).
 */
@Composable
private fun WeekChart(summary: UsageSummary, today: Long, quotaMinutes: Int?, texts: TimeTexts, animate: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val quiet = ShashaColors.Muted.copy(alpha = 0.65f).compositeOver(ShashaColors.Surface)
    val days = summary.days
    val minutes = days.map { it.minutes }
    val peak = minutes.max()
    val step = tickStep(maxOf(peak, quotaMinutes?.toLong() ?: 0))
    val top = (ceil(maxOf(peak, quotaMinutes?.toLong() ?: 0).toDouble() / step).toLong().coerceAtLeast(1)) * step
    val grow = remember(summary.profileId) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(summary.profileId) {
        if (animate) grow.animateTo(1f, tween(durationMillis = 750, easing = FastOutSlowInEasing))
    }
    val peakIndex = minutes.indexOf(peak).takeIf { peak > 0 }
    val axisLabels = (0..(top / step).toInt()).map { texts.durationShort(it * step) }
    Row(modifier) {
        // The minute ticks, on the reading side, each centred on its gridline.
        AxisLabels(axisLabels, Modifier.width(56.dp).fillMaxHeight().padding(bottom = DayLabelHeight, top = ValueLabelHeight))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize().padding(bottom = DayLabelHeight, top = ValueLabelHeight)) {
                val lines = (top / step).toInt()
                for (i in 0..lines) {
                    val y = size.height * (1f - i.toFloat() / lines)
                    drawLine(ShashaColors.Outline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxSize().focusGroup().testTag("stats_chart")) {
                days.forEachIndexed { index, day ->
                    val isToday = day.epochDay == today
                    DayColumn(
                        label = if (isToday) stringResource(R.string.stats_today) else texts.weekday(LocalDate.ofEpochDay(day.epochDay)),
                        minutes = day.minutes,
                        fraction = day.minutes.toFloat() / top,
                        grow = { (grow.value * (1f + index * 0.08f) - index * 0.08f).coerceIn(0f, 1f) },
                        color = if (isToday) accent.main else quiet,
                        alwaysLabel = isToday || index == peakIndex,
                        value = texts.durationShort(day.minutes),
                        emphasized = isToday,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            if (quotaMinutes != null && quotaMinutes > 0) {
                Canvas(Modifier.fillMaxSize().padding(bottom = DayLabelHeight, top = ValueLabelHeight)) {
                    val y = size.height * (1f - quotaMinutes.toFloat() / top)
                    drawLine(QuotaLineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), pathEffect = quotaDash())
                }
            }
        }
    }
}

/** [labels] are the ticks from 0 up; each is centred on its gridline, towards the columns. */
@Composable
private fun AxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    Layout(
        content = { labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = ShashaColors.Muted, maxLines = 1) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val lines = (placeables.size - 1).coerceAtLeast(1)
            placeables.forEachIndexed { i, label ->
                val y = constraints.maxHeight * (1f - i.toFloat() / lines)
                // The end of the column (left in Arabic) is next to the plot.
                label.placeRelative(constraints.maxWidth - label.width - 8.dp.roundToPx(), (y - label.height / 2f).roundToInt())
            }
        }
    }
}

@Composable
private fun DayColumn(
    label: String,
    minutes: Long,
    fraction: Float,
    grow: () -> Float,
    color: Color,
    alwaysLabel: Boolean,
    value: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    Column(
        modifier
            .semantics { contentDescription = "$label: $value" }
            .focusable(interactionSource = interaction),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize().padding(top = ValueLabelHeight)) {
                val barWidth = BarWidth.toPx().coerceAtMost(size.width * 0.7f)
                val height = size.height * fraction.coerceIn(0f, 1f) * grow()
                if (focused) {
                    // The focused day's band: the remote's "hover".
                    drawRoundRect(accent.main.copy(alpha = 0.10f), Offset((size.width - barWidth * 2f) / 2f, 0f), Size(barWidth * 2f, size.height), CornerRadius(8.dp.toPx()))
                }
                if (height > 0f) {
                    val left = (size.width - barWidth) / 2f
                    val r = 4.dp.toPx().coerceAtMost(height)
                    // Rounded data end, square at the baseline.
                    val bar = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = size.height - height, right = left + barWidth, bottom = size.height,
                                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                            ),
                        )
                    }
                    drawPath(bar, if (focused) accent.main else color)
                }
            }
            if ((alwaysLabel || focused) && minutes > 0) {
                val labelBottom = fraction.coerceIn(0f, 1f) * grow()
                BoxWithConstraints(Modifier.fillMaxSize().padding(top = ValueLabelHeight)) {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (focused || emphasized) ShashaColors.Text else ShashaColors.Muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = maxHeight * labelBottom + 4.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused || emphasized) ShashaColors.Text else ShashaColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(DayLabelHeight).padding(top = 8.dp).fillMaxWidth(),
        )
    }
}

private val BarWidth = 24.dp
private const val MAX_CHANNELS = 4
private val DayLabelHeight = 30.dp
private val ValueLabelHeight = 22.dp

/** A clean tick step for a scale reaching [maxMinutes]: at most four gridlines above zero. */
internal fun tickStep(maxMinutes: Long): Long =
    listOf(5L, 10L, 15L, 30L, 60L, 120L, 180L, 240L, 360L).firstOrNull { maxMinutes <= it * 4 } ?: 480L

@Composable
private fun WeekTotal(summary: UsageSummary, profile: Profile, texts: TimeTexts, modifier: Modifier = Modifier) {
    val formats = rememberFormats()
    StatsCard(modifier) {
        Text(stringResource(R.string.stats_this_week), style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted)
        Text(
            texts.duration(summary.thisWeekMinutes),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            modifier = Modifier.testTag("stats_week_total"),
        )
        Spacer(Modifier.height(4.dp))
        val change = summary.changePercent
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (change != null && change != 0) {
                Icon(
                    if (change > 0) ShashaIcons.ArrowUp else ShashaIcons.ArrowDown,
                    contentDescription = null,
                    tint = ShashaColors.Text,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when {
                    change == null -> stringResource(R.string.stats_no_previous_week)
                    change == 0 -> stringResource(R.string.stats_same_as_last_week)
                    change > 0 -> stringResource(R.string.stats_more_than_last_week, percent(change))
                    else -> stringResource(R.string.stats_less_than_last_week, percent(change))
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.testTag("stats_change"),
            )
        }
        if (profile.isAdult) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (summary.surrendersLast7Days == 0) {
                    stringResource(R.string.stats_no_surrenders)
                } else {
                    stringResource(R.string.stats_surrenders, formats.count(R.array.count_times, summary.surrendersLast7Days.toLong()))
                },
                style = MaterialTheme.typography.bodySmall,
                color = ShashaColors.Muted,
                modifier = Modifier.testTag("stats_surrenders"),
            )
        }
    }
}

/** "22%" kept left to right inside an Arabic sentence (a directional isolate around it). */
private fun percent(change: Int) = "\u2066${abs(change)}%\u2069"

@Composable
private fun TopChannels(channels: List<ChannelUsage>, texts: TimeTexts, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    StatsCard(modifier) {
        Text(stringResource(R.string.stats_top_channels), style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted)
        Spacer(Modifier.height(8.dp))
        if (channels.isEmpty()) {
            Text(stringResource(R.string.stats_no_channels), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
            return@StatsCard
        }
        val most = channels.first().seconds.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            channels.take(MAX_CHANNELS).forEach { channel ->
                val name = channel.channelName ?: channel.channelId
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(null, name, channel.channelId, 26.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                // A Latin name too starts on the right, clear of the minutes.
                                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.ContentOrRtl),
                                textAlign = TextAlign.Right,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(texts.durationShort(channel.minutes), style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted, maxLines = 1)
                        }
                        Spacer(Modifier.height(4.dp))
                        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                            // Magnitude bars share one baseline: the start of the reading direction (right).
                            val w = size.width * (channel.seconds.toFloat() / most)
                            drawRoundRect(accent.main, Offset(size.width - w, 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}
