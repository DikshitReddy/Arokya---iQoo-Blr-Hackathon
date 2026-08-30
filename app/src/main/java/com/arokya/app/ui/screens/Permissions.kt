package com.arokya.app.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.arokya.app.ui.theme.Ar

/**
 * Real runtime permissions. Each "Allow" opens the actual Android system
 * dialog. Granted rows turn into a teal tick.
 *
 * Note: Android only shows a permission dialog twice. After a second denial
 * the system stops asking, so we fall back to opening app Settings.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    // Activity recognition only exists as a runtime permission on Android 10+
    val activityPerm =
        if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null

    fun granted(perm: String?): Boolean {
        if (perm == null) return true
        return ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
    }

    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var camOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var actOk by remember { mutableStateOf(granted(activityPerm)) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micOk = it }
    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { camOk = it }
    val actLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { actOk = it }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ar.Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Text(
            "Three things to allow",
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            color = Ar.Navy
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "You can change any of these later in Settings.",
            fontSize = 14.sp,
            color = Ar.Slate
        )
        Spacer(Modifier.height(24.dp))

        PermissionRow(
            icon = Icons.Filled.Mic,
            title = "Microphone",
            reason = "so you can talk to your coach",
            granted = micOk,
            onAllow = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        )
        Spacer(Modifier.height(12.dp))
        PermissionRow(
            icon = Icons.Filled.CameraAlt,
            title = "Camera",
            reason = "so it can recognise meals and your pantry",
            granted = camOk,
            onAllow = { camLauncher.launch(Manifest.permission.CAMERA) }
        )
        Spacer(Modifier.height(12.dp))
        PermissionRow(
            icon = Icons.Filled.MonitorHeart,
            title = "Activity & health data",
            reason = "so recommendations reflect your day",
            granted = actOk,
            onAllow = {
                if (activityPerm != null) actLauncher.launch(activityPerm) else actOk = true
            }
        )

        Spacer(Modifier.height(28.dp))

        // If the user denied twice, Android stops showing dialogs — offer Settings.
        if (!(micOk && camOk && actOk)) {
            Text(
                "Not seeing a prompt? Open app settings",
                fontSize = 12.5.sp,
                color = Ar.Teal,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { openAppSettings() }
                    .padding(8.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        ArPrimaryButton("Continue", onClick = onDone)

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Ar.Muted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Your data stays on this device.", fontSize = 12.sp, color = Ar.Muted)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    reason: String,
    granted: Boolean,
    onAllow: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ar.CardBg, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(Ar.BlueChipBg, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Ar.Blue, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
            Spacer(Modifier.height(2.dp))
            Text(reason, fontSize = 12.5.sp, color = Ar.Muted, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Granted",
                    tint = Ar.Teal,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Allowed", fontSize = 12.5.sp, color = Ar.Teal, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onAllow,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ar.Teal),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("Allow", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}