package com.ahmadnayfeh.shasha.ui.settings.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.remote.QrCodes
import com.ahmadnayfeh.shasha.remote.RemoteState
import com.ahmadnayfeh.shasha.remote.RemoteUrls
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlin.math.floor
import kotlin.math.min

/** Where the phone remote stands, as the screens show it. */
enum class PairingStatus { OFF, STARTING, RUNNING, NO_NETWORK, FAILED }

/**
 * What «التحكم من الجوال» and the first start's last step show: [url] is the pairing link of the QR code
 * (only while running), [address] the short "ip:port", [serviceName] the NSD name.
 */
@Immutable
data class PairingUi(
    val enabled: Boolean,
    val status: PairingStatus,
    val url: String? = null,
    val address: String? = null,
    val serviceName: String? = null,
) {
    companion object {
        fun of(enabled: Boolean, state: RemoteState, token: String?): PairingUi {
            if (!enabled) return PairingUi(enabled = false, status = PairingStatus.OFF)
            return when (state) {
                RemoteState.Stopped, RemoteState.Starting -> PairingUi(true, PairingStatus.STARTING)
                RemoteState.NoNetwork -> PairingUi(true, PairingStatus.NO_NETWORK)
                RemoteState.Failed -> PairingUi(true, PairingStatus.FAILED)
                is RemoteState.Running -> PairingUi(
                    enabled = true,
                    // The QR code needs the token too; it is read before the server starts, so this is a moment at most.
                    status = if (token != null) PairingStatus.RUNNING else PairingStatus.STARTING,
                    url = token?.let { RemoteUrls.pairingUrl(state.address, state.port, it) },
                    address = "${state.address.hostAddress}:${state.port}",
                    serviceName = state.serviceName,
                )
            }
        }
    }
}

/**
 * A QR code drawn cell by cell: black on white, every cell a whole number of pixels (crisp edges, no seams
 * between neighbours), centred, never mirrored (a Canvas ignores the RTL layout). [QrCodes.matrix] brings
 * a 2-cell margin; the white card around it ([PairingQrCard]) makes the quiet zone at least 4 cells.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, description: String? = null) {
    val matrix = remember(text) { QrCodes.matrix(text) }
    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
        drawRect(Color.White)
        val cells = matrix.size
        val cell = floor(min(size.width, size.height) / cells)
        if (cell < 1f) return@Canvas
        val left = floor((size.width - cell * cells) / 2)
        val top = floor((size.height - cell * cells) / 2)
        for (y in 0 until cells) {
            val row = matrix[y]
            var x = 0
            while (x < cells) {
                if (!row[x]) {
                    x++
                    continue
                }
                val start = x
                while (x < cells && row[x]) x++
                drawRect(Color.Black, topLeft = Offset(left + start * cell, top + y * cell), size = Size((x - start) * cell, cell))
            }
        }
    }
}

/**
 * The pairing QR code on its white card while the server runs; otherwise, in the same place and size, what
 * stands in its way (switched off, no home network, starting, failed).
 */
@Composable
fun PairingQrCard(ui: PairingUi, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    val url = ui.url
    if (ui.status == PairingStatus.RUNNING && url != null) {
        Box(
            modifier
                .size(size)
                .background(Color.White, shape)
                .padding(size * 0.06f)
                .testTag("pairing_qr"),
        ) {
            QrCode(url, Modifier.fillMaxSize(), description = stringResource(R.string.remote_qr_description))
        }
        return
    }
    val (icon, title, body) = when (ui.status) {
        PairingStatus.OFF -> Triple(RemoteIcons.QrCode, R.string.remote_off_title, R.string.remote_off_body)
        PairingStatus.NO_NETWORK -> Triple(RemoteIcons.WifiOff, R.string.remote_no_network_title, R.string.remote_no_network_body)
        PairingStatus.FAILED -> Triple(ShashaIcons.Info, R.string.remote_failed_title, R.string.remote_failed_body)
        PairingStatus.STARTING, PairingStatus.RUNNING -> Triple(null, R.string.remote_starting_title, null)
    }
    Placeholder(icon, stringResource(title), body?.let { stringResource(it) }, size, shape, modifier)
}

@Composable
private fun Placeholder(icon: ImageVector?, title: String, body: String?, size: Dp, shape: RoundedCornerShape, modifier: Modifier) {
    Box(
        modifier
            .size(size)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(24.dp)
            .testTag("pairing_placeholder"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon == null) {
                ShashaLoader(size = 56.dp)
            } else {
                Box(Modifier.size(72.dp).background(ShashaColors.SurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
