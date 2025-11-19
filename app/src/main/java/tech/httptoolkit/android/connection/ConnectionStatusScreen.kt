package tech.httptoolkit.android.connection

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.httptoolkit.android.ProxyConfig
import tech.httptoolkit.android.R
import tech.httptoolkit.android.portfilter.DEFAULT_PORTS
import tech.httptoolkit.android.whereIsCertTrusted
import tech.httptoolkit.android.ui.DmSansFontFamily

@Composable
fun ConnectionStatusScreen(
    proxyConfig: ProxyConfig,
    totalAppCount: Int,
    interceptedAppCount: Int,
    onChangeApps: () -> Unit,
    interceptedPorts: Set<Int>,
    onChangePorts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val certTrustStatus = whereIsCertTrusted(proxyConfig)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // Connected to text
        Text(
            text = if (proxyConfig.ip == "127.0.0.1") {
                stringResource(R.string.connected_tunnel_details)
            } else {
                stringResource(R.string.connected_details, proxyConfig.ip, proxyConfig.port)
            },
            fontSize = 16.sp,
            fontFamily = DmSansFontFamily,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        val successColor = Color(0xFF4CAF7D)
        when (certTrustStatus) {
            "user" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // Pre-Android 7: User trust is sufficient
                    CertificateStatusCard(
                        icon = Icons.Default.Check,
                        iconTint = successColor,
                        heading = stringResource(R.string.pre_v7_connection_status_enabled_heading),
                        details = stringResource(R.string.pre_v7_connection_status_details)
                    )
                } else {
                    // Android 7+: User trust is limited
                    CertificateStatusCard(
                        icon = Icons.Default.Check,
                        iconTint = successColor,
                        heading = stringResource(R.string.user_connection_status_enabled_heading)
                    )

                    CertificateStatusCard(
                        icon = Icons.Default.Warning,
                        iconTint = MaterialTheme.colorScheme.error,
                        heading = stringResource(R.string.system_connection_status_disabled_heading),
                        details = stringResource(R.string.user_connection_status_details)
                    )
                }
            }
            "system" -> {
                // System trust: show BOTH user and system cards
                CertificateStatusCard(
                    icon = Icons.Default.Check,
                    iconTint = successColor,
                    heading = stringResource(R.string.user_connection_status_enabled_heading)
                )

                CertificateStatusCard(
                    icon = Icons.Default.Check,
                    iconTint = successColor,
                    heading = stringResource(R.string.system_connection_status_enabled_heading),
                    details = stringResource(R.string.system_connection_status_details)
                )
            }
            else -> {
                // No certificate trust
                CertificateStatusCard(
                    icon = Icons.Default.Warning,
                    iconTint = MaterialTheme.colorScheme.error,
                    heading = stringResource(R.string.disabled_connection_status_heading),
                    details = stringResource(R.string.none_connection_status_details)
                )
            }
        }

        // App and Port interception buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InterceptionButton(
                icon = R.drawable.ic_apps_24,
                text = getAppStatusText(totalAppCount, interceptedAppCount),
                onClick = onChangeApps,
                modifier = Modifier
                    .weight(1f)
            )

            InterceptionButton(
                icon = R.drawable.ic_network_ports_24,
                text = getPortStatusText(interceptedPorts),
                onClick = onChangePorts,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CertificateStatusCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    heading: String,
    details: String? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint
                )
                Text(
                    text = heading.uppercase(),
                    fontSize = 14.sp,
                    fontFamily = DmSansFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (details != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = details,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontFamily = DmSansFontFamily,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 34.dp, top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun InterceptionButton(
    icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = text,
                fontSize = 14.sp,
                fontFamily = DmSansFontFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun getAppStatusText(totalAppCount: Int, interceptedAppCount: Int): String {
    return when {
        totalAppCount == interceptedAppCount -> stringResource(R.string.all_apps)
        interceptedAppCount > 10 -> stringResource(R.string.selected_apps)
        else -> stringResource(
            R.string.few_apps,
            interceptedAppCount,
            if (interceptedAppCount != 1) "s" else ""
        )
    }
}

@Composable
private fun getPortStatusText(interceptedPorts: Set<Int>): String {
    return when {
        interceptedPorts == DEFAULT_PORTS -> stringResource(R.string.default_ports)
        interceptedPorts.size > 10 -> stringResource(R.string.selected_ports)
        else -> stringResource(
            R.string.few_ports,
            interceptedPorts.size,
            if (interceptedPorts.size != 1) "s" else ""
        )
    }
}
