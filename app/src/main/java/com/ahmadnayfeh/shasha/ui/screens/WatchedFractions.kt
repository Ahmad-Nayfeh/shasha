package com.ahmadnayfeh.shasha.ui.screens

import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.ui.model.watchedFraction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Watched fractions (video id → 0..1) of the videos a screen shows, for the bars on their cards. One
 * query for the whole list, re-run only when the list of ids changes (not when focus moves).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun watchedFractions(watch: WatchRepository, videoIds: Flow<List<String>>): Flow<Map<String, Float>> =
    videoIds.distinctUntilChanged()
        .flatMapLatest { ids -> watch.observeProgress(ids) }
        .map { rows -> buildMap { rows.forEach { (id, progress) -> progress.watchedFraction()?.let { put(id, it) } } } }
        .distinctUntilChanged()
