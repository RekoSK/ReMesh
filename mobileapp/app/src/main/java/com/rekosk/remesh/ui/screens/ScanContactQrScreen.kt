package com.rekosk.remesh.ui.screens

import androidx.compose.runtime.Composable
import com.rekosk.remesh.data.parseContactUri
import com.rekosk.remesh.ui.MeshViewModel
import com.rekosk.remesh.ui.components.QrScannerScreen

/** Points the camera at a `meshcore://contact/add` QR code and adds the contact. */
@Composable
fun ScanContactQrScreen(viewModel: MeshViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    QrScannerScreen(
        title = "Scan QR code",
        onBack = onBack,
        onSuccess = onDone,
        onPayload = { payload, onResult ->
            val shared = parseContactUri(payload)
            if (shared == null) {
                onResult("That QR code is not a MeshCore contact")
            } else {
                viewModel.addContact(shared.name, shared.type, shared.publicKeyHex, onResult = onResult)
            }
        },
    )
}
