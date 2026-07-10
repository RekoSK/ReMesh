package com.rekosk.remesh.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreviewUseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * A camera viewfinder that hands every decoded QR payload to [onPayload], plus a
 * gallery picker for a code that arrived as a picture rather than on a screen.
 *
 * [onPayload] reports back null when it accepted the payload, or a message to show.
 * Scanning is latched while a payload is in flight: the analyser fires many times a
 * second, and acting on the same code twice is never what anyone wants.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    title: String,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    onPayload: (payload: String, onResult: (String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var handled by remember { mutableStateOf(false) }

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasCamera = it }

    LaunchedEffect(Unit) {
        if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun accept(payload: String) {
        if (handled) return
        handled = true
        onPayload(payload) { error ->
            if (error == null) {
                onSuccess()
            } else {
                handled = false
                scope.launch { snackbar.showSnackbar(error) }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = decodeQrFromImage(context, uri)
        if (payload == null) {
            scope.launch { snackbar.showSnackbar("No QR code found in that image") }
        } else {
            accept(payload)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(title) },
                actions = {
                    IconButton(onClick = { pickImage.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Filled.Image, contentDescription = "Pick an image")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (hasCamera) {
                CameraQrPreview(onQrDecoded = ::accept)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "ReMesh needs the camera to scan a QR code. " +
                            "You can pick a picture of one instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow camera")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(onQrDecoded: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // The analyser outlives a recomposition; keep it pointing at the current lambda.
    val currentCallback by rememberUpdatedState(onQrDecoded)

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            val mainExecutor = ContextCompat.getMainExecutor(viewContext)

            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = CameraPreviewUseCase.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(analysisExecutor) { image ->
                            val text = decodeQr(image)
                            // The callback touches Compose state, so hop to the main thread.
                            if (text != null) mainExecutor.execute { currentCallback(text) }
                        }
                    }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, mainExecutor)

            previewView
        },
    )
}

private val qrHints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

/**
 * Reads the camera's luminance plane, which is all a QR reader wants.
 *
 * The rows are copied out one at a time because a camera's `rowStride` is usually
 * wider than the image: handing zxing the padded buffer as if it were tightly
 * packed would skew every row.
 */
private fun decodeQr(image: ImageProxy): String? {
    try {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        val rowStride = plane.rowStride

        val luminance = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val available = buffer.remaining()
            if (available <= 0) return null
            val toRead = minOf(rowStride, available)
            buffer.get(row, 0, toRead)
            row.copyInto(luminance, y * width, 0, minOf(width, toRead))
        }

        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return runCatching {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), qrHints).text
        }.getOrNull() // Most frames simply contain no QR code.
    } finally {
        image.close()
    }
}

/** Reads a QR code out of a still image the user picked from their gallery. */
private fun decodeQrFromImage(context: Context, uri: Uri): String? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        // getPixels() cannot read a hardware bitmap.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val luminance = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance))).text
}.getOrNull()
