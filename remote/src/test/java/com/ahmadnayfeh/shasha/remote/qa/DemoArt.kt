package com.ahmadnayfeh.shasha.remote.qa

import java.net.URLEncoder

/** Placeholder thumbnails and avatars for the QA demo, as data: URIs (no network, no real content). */
internal object DemoArt {
    private fun uri(svg: String) =
        "data:image/svg+xml;charset=utf-8," + URLEncoder.encode(svg.trimIndent(), "UTF-8").replace("+", "%20")

    private fun scene(top: String, bottom: String, body: String) = uri(
        """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180">
          <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="$top"/><stop offset="1" stop-color="$bottom"/></linearGradient></defs>
          <rect width="320" height="180" fill="url(#g)"/>
          $body
        </svg>
        """,
    )

    val ocean = scene(
        "#0E4F7A", "#06223A",
        """
        <circle cx="250" cy="42" r="22" fill="#9FE3F5" opacity=".35"/>
        <path d="M0 96 Q40 84 80 96 T160 96 T240 96 T320 96 V180 H0Z" fill="#0B6E99" opacity=".7"/>
        <path d="M0 122 Q40 110 80 122 T160 122 T240 122 T320 122 V180 H0Z" fill="#0A5A80"/>
        <path d="M120 140 q14 -10 28 0 q-14 10 -28 0z M148 140 l10 -7 v14z" fill="#FFB547"/>
        <path d="M200 156 q10 -7 20 0 q-10 7 -20 0z M220 156 l7 -5 v10z" fill="#2EC4B6"/>
        """,
    )
    val space = scene(
        "#1B1140", "#05030F",
        """
        <circle cx="40" cy="30" r="1.5" fill="#fff"/><circle cx="90" cy="70" r="1" fill="#fff"/><circle cx="280" cy="24" r="1.4" fill="#fff"/>
        <circle cx="300" cy="140" r="1" fill="#fff"/><circle cx="20" cy="150" r="1.2" fill="#fff"/><circle cx="150" cy="20" r="1" fill="#fff"/>
        <circle cx="190" cy="100" r="46" fill="#6D4BD8"/><circle cx="175" cy="86" r="46" fill="#8C6DF0" opacity=".55"/>
        <ellipse cx="190" cy="100" rx="78" ry="14" fill="none" stroke="#FFD166" stroke-width="4" opacity=".8" transform="rotate(-14 190 100)"/>
        """,
    )
    val desert = scene(
        "#FFB06B", "#F2703C",
        """
        <circle cx="236" cy="62" r="26" fill="#FFE7A8"/>
        <path d="M0 120 Q80 80 170 118 T320 108 V180 H0Z" fill="#E0823F"/>
        <path d="M0 150 Q100 112 200 150 T320 140 V180 H0Z" fill="#C8642A"/>
        """,
    )
    val forest = scene(
        "#1F6B4A", "#0B2E22",
        """
        <path d="M30 150 l24 -60 l24 60z M70 150 l30 -80 l30 80z M130 150 l22 -52 l22 52z M180 150 l30 -86 l30 86z M236 150 l24 -58 l24 58z" fill="#2E9E6A"/>
        <rect y="148" width="320" height="32" fill="#0B2E22"/>
        """,
    )
    val city = scene(
        "#132544", "#070D1A",
        """
        <circle cx="262" cy="36" r="14" fill="#FFE7A8" opacity=".85"/>
        <rect x="20" y="90" width="36" height="90" fill="#1F3A66"/><rect x="62" y="60" width="30" height="120" fill="#28497F"/>
        <rect x="98" y="100" width="44" height="80" fill="#1F3A66"/><rect x="150" y="40" width="26" height="140" fill="#2F5796"/>
        <rect x="184" y="84" width="40" height="96" fill="#1F3A66"/><rect x="232" y="70" width="30" height="110" fill="#28497F"/>
        <rect x="270" y="104" width="40" height="76" fill="#1F3A66"/>
        <g fill="#FFD166"><rect x="68" y="72" width="5" height="6"/><rect x="80" y="92" width="5" height="6"/><rect x="156" y="56" width="5" height="6"/>
        <rect x="164" y="84" width="5" height="6"/><rect x="190" y="98" width="5" height="6"/><rect x="238" y="84" width="5" height="6"/><rect x="28" y="104" width="5" height="6"/></g>
        """,
    )
    val food = scene(
        "#FFD8A8", "#F4A259",
        """
        <circle cx="160" cy="98" r="62" fill="#fff" opacity=".9"/><circle cx="160" cy="98" r="48" fill="#F6C453"/>
        <circle cx="140" cy="86" r="10" fill="#C0582D"/><circle cx="176" cy="104" r="12" fill="#C0582D"/><circle cx="160" cy="80" r="5" fill="#3E8E41"/>
        <circle cx="146" cy="112" r="5" fill="#3E8E41"/>
        """,
    )
    val code = scene(
        "#10262A", "#081416",
        """
        <g stroke="#2EC4B6" stroke-width="6" stroke-linecap="round" fill="none"><path d="M112 70 L84 98 L112 126"/><path d="M208 70 L236 98 L208 126"/><path d="M176 60 L144 136" stroke="#FFB547"/></g>
        """,
    )
    val kids = scene(
        "#FFE08A", "#FFB547",
        """
        <circle cx="160" cy="92" r="44" fill="#FFD166" stroke="#fff" stroke-width="5"/>
        <circle cx="145" cy="84" r="6" fill="#3A2800"/><circle cx="175" cy="84" r="6" fill="#3A2800"/>
        <path d="M140 104 q20 18 40 0" stroke="#3A2800" stroke-width="5" fill="none" stroke-linecap="round"/>
        <path d="M0 160 Q80 140 160 160 T320 158 V180 H0Z" fill="#2EC4B6"/>
        """,
    )
    val science = scene(
        "#7BDFF2", "#2EC4B6",
        """
        <path d="M140 50 h40 M148 50 v36 l-30 52 a8 8 0 0 0 7 12 h70 a8 8 0 0 0 7 -12 l-30 -52 v-36" stroke="#fff" stroke-width="6" fill="none" stroke-linejoin="round"/>
        <path d="M128 128 h64 l10 16 h-84z" fill="#FF6B6B" opacity=".85"/>
        """,
    )

    fun avatar(from: String, to: String) = uri(
        """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
          <defs><linearGradient id="a" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="$from"/><stop offset="1" stop-color="$to"/></linearGradient></defs>
          <rect width="64" height="64" fill="url(#a)"/><circle cx="32" cy="26" r="11" fill="#fff" opacity=".85"/><path d="M12 58 a20 16 0 0 1 40 0z" fill="#fff" opacity=".85"/>
        </svg>
        """,
    )
}
