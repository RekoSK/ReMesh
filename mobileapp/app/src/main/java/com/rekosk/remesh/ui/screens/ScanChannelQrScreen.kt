package com.rekosk.remesh.ui.screens

import androidx.compose.runtime.Composable
import com.rekosk.remesh.data.parseChannelUri
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.QrScannerScreen

/** Points the camera at a `meshcore://channel/add` QR code and joins the channel. */
@Composable
fun ScanChannelQrScreen(viewModel: MeshViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    QrScannerScreen(
        title = "Scan QR code",
        onBack = onBack,
        onSuccess = onDone,
        onPayload = { payload, onResult ->
            val shared = parseChannelUri(payload)
            if (shared == null) {
                onResult("That QR code is not a MeshCore channel")
            } else {
                viewModel.addChannel(shared.name, shared.secret, onResult)
            }
        },
    )
}
