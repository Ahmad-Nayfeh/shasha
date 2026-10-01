package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import kotlinx.coroutines.flow.first

/**
 * «إرجاع كل الإعدادات إلى وضعها الأصلي», one implementation for the TV's «إعادة الضبط» and the phone's: every adjustable
 * setting ([BehaviorSettings]), the SponsorBlock categories, the quality cap and the server's port. Never the profiles,
 * the PIN itself, the subscriptions, the history or the lists.
 */
class SettingsReset(
    private val behavior: BehaviorSettings?,
    private val sponsorBlock: SponsorBlockSettings,
    private val playback: PlaybackSettings,
    private val remote: RemoteSettings,
) {
    /** Returns true when the server's port changed (the server must move to it). */
    suspend fun resetAll(): Boolean {
        behavior?.resetAll()
        sponsorBlock.reset()
        playback.reset()
        val before = remote.port.first()
        remote.resetPort()
        return remote.port.first() != before
    }
}
