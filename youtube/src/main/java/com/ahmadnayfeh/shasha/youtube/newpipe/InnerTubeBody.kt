package com.ahmadnayfeh.shasha.youtube.newpipe

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The body of a request to YouTube's InnerTube API as NewPipeExtractor builds its own (the WEB client: [language], [country]
 * and the [clientVersion] the extractor reads from YouTube), with the call's own fields added by [fields]. The extractor
 * builds that context with its own JSON library, which only its runtime sees; the requests this module makes itself (a channel
 * section's order, the Shorts of a search) use this one.
 */
internal object InnerTubeBody {

    fun build(language: String, country: String, clientVersion: String, fields: JsonObjectBuilder.() -> Unit): ByteArray = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("hl", language)
                put("gl", country)
                put("clientName", "WEB")
                put("clientVersion", clientVersion)
                put("originalUrl", "https://www.youtube.com")
                put("platform", "DESKTOP")
                put("utcOffsetMinutes", 0)
            }
            putJsonObject("request") {
                put("internalExperimentFlags", buildJsonArray { })
                put("useSsl", true)
            }
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
        fields()
    }.toString().toByteArray(Charsets.UTF_8)
}
