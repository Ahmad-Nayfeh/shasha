package com.ahmadnayfeh.shasha.remote

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLEncoder

object RemoteUrls {
    /** The link in the TV's QR code: `http://<ip>:<port>/?t=<token>`. */
    fun pairingUrl(address: InetAddress, port: Int, token: String): String {
        val raw = address.hostAddress!!
        val host = if (address is Inet6Address) "[${raw.substringBefore('%')}]" else raw
        return "http://$host:$port/?t=${URLEncoder.encode(token, "UTF-8")}"
    }
}
