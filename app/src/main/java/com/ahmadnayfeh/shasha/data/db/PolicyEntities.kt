package com.ahmadnayfeh.shasha.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ahmadnayfeh.shasha.policy.UsageKind

/**
 * One viewer profile (SPEC §3). [json] is the whole `com.ahmadnayfeh.shasha.policy.Profile` as
 * PolicyJson writes it, so new profile fields never need a schema change; [sortOrder] is the order
 * of the profile picker.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val json: String,
)

/**
 * Seconds of one [kind] for one profile, local day and channel, added up as they are recorded (one row
 * per key, so the table stays small). [channelId] is "" for time that belongs to no channel
 * (FOREGROUND): SQLite never matches NULLs in a primary key, so NULL would add a row per write.
 */
@Entity(
    tableName = "usage_records",
    primaryKeys = ["profileId", "epochDay", "kind", "channelId"],
    indices = [Index("epochDay")],
)
data class UsageRecordEntity(
    val profileId: String,
    val epochDay: Long,
    val kind: UsageKind,
    val channelId: String,
    /** The newest name seen for [channelId] (channels get renamed). */
    val channelName: String?,
    val seconds: Long,
)

/** The adult left a video early through the exit challenge (SPEC §4: shown in the stats). */
@Entity(tableName = "surrender_events", indices = [Index("profileId", "epochMs")])
data class SurrenderEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val epochMs: Long,
    val videoId: String,
)
