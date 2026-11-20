package de.meshcore.pocketmonitor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import de.meshcore.pocketmonitor.protocol.CompanionPosition
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun CompanionPositionDialog(
    current: CompanionPosition?,
    enabled: Boolean,
    onSave: (CompanionPosition, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var latitude by rememberSaveable { mutableStateOf(current?.let { coordinateText(it.latitudeE6) }.orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(current?.let { coordinateText(it.longitudeE6) }.orEmpty()) }
    var readingGps by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val position = CompanionPosition.parse(latitude, longitude)
    val editable = !saving && !readingGps
    fun fill(value: CompanionPosition) {
        latitude = coordinateText(value.latitudeE6)
        longitude = coordinateText(value.longitudeE6)
        error = null
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation(context)) {
            readingGps = true
        } else {
            error = "Für die GPS-Übernahme ist der genaue Standort erforderlich."
        }
    }
    LaunchedEffect(readingGps) {
        if (!readingGps) return@LaunchedEffect
        try {
            val gps = readPhoneGps(context)
            val value = gps?.let { CompanionPosition.fromDegrees(it.latitude, it.longitude) }
            if (value != null) fill(value) else error = "Kein GPS-Fix verfügbar. Versuch es erneut oder gib die Position manuell ein."
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            error = e.message
        } catch (_: SecurityException) {
            error = "Für die GPS-Übernahme ist der genaue Standort erforderlich."
        } catch (_: IllegalArgumentException) {
            error = "Das Handy-GPS ist nicht verfügbar."
        } finally {
            readingGps = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("GPS-Position") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it; error = null },
                    label = { Text("Breitengrad (−90 bis 90)") },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it; error = null },
                    label = { Text("Längengrad (−180 bis 180)") },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        error = null
                        if (hasPreciseLocation(context)) readingGps = true
                        else permissions.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        ))
                    },
                    enabled = editable,
                ) { Text("Handy-GPS übernehmen") }
                TextButton(onClick = { fill(CompanionPosition.ZERO) }, enabled = editable) {
                    Text("Auf 0.000000 / 0.000000 setzen")
                }
                if (latitude.isNotBlank() && longitude.isNotBlank() && position == null) {
                    Text("Gib gültige Koordinaten als Dezimalzahlen ein.", color = MaterialTheme.colorScheme.error)
                }
                if (readingGps) Text("Warte auf einen GPS-Fix …")
                if (saving) Text("Position wird gespeichert …")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && editable && position != null,
                onClick = {
                    if (saving || position == null) return@TextButton
                    saving = true
                    error = null
                    onSave(position) { result ->
                        saving = false
                        result.fold(
                            onSuccess = { onDismiss() },
                            onFailure = { error = it.message ?: "Position konnte nicht gespeichert werden." },
                        )
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Abbrechen") } },
    )
}

private fun coordinateText(microdegrees: Int): String = String.format(Locale.ROOT, "%.6f", microdegrees / 1e6)

private fun hasPreciseLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission") // Checked before calling the GPS provider, including after the permission prompt.
private suspend fun readPhoneGps(context: Context): Location? {
    if (!hasPreciseLocation(context)) throw SecurityException()
    val manager = checkNotNull(context.getSystemService(LocationManager::class.java)) { "Das Handy-GPS ist nicht verfügbar." }
    check(LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER)) { "Das Handy hat keinen GPS-Anbieter." }
    check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Bitte aktiviere den Standort in den Android-Einstellungen." }
    return withTimeoutOrNull(30_000L) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager, LocationManager.GPS_PROVIDER, signal, ContextCompat.getMainExecutor(context),
            ) { location -> continuation.resume(location) }
        }
    }
}
