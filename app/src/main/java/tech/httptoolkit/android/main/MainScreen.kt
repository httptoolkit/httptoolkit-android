package tech.httptoolkit.android.main

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.httptoolkit.android.ProxyConfig
import tech.httptoolkit.android.R
import tech.httptoolkit.android.connection.ConnectionStatusScreen
import tech.httptoolkit.android.main.ConnectionState.*
import tech.httptoolkit.android.ui.DmSansFontFamily

data class MainScreenState(
    val connectionState: ConnectionState,
    val proxyConfig: ProxyConfig?,
    val hasCamera: Boolean,
    val lastProxy: ProxyConfig?,
    val totalAppCount: Int,
    val interceptedAppCount: Int,
    val interceptedPorts: Set<Int>
)

data class MainScreenActions(
    val onScanQRCode: () -> Unit,
    val onReconnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onRecoverAfterFailure: () -> Unit,
    val onTestInterception: () -> Unit,
    val onOpenDocs: () -> Unit,
    val onChooseApps: () -> Unit,
    val onChoosePorts: () -> Unit
)

@Composable
fun MainScreen(
    screenState: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        LandscapeMainScreen(
            screenState = screenState,
            actions = actions,
            modifier = modifier
        )
    } else {
        PortraitMainScreen(
            screenState = screenState,
            actions = actions,
            modifier = modifier
        )
    }
}

@Composable
private fun PortraitMainScreen(
    screenState: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp

    // Determine layout mode based on screen height
    val smallScreen = screenHeightDp < 680
    val guidelinePercent = when {
        screenHeightDp >= 680 -> 0.16f
        screenHeightDp >= 380 -> 0.08f
        else -> 0.18f
    }
    val statusGuidelinePercent = if (!smallScreen) 0.32f else guidelinePercent + 0.08f
    val postStatusSpacer = 50.dp

    Box(modifier = modifier.fillMaxSize()) {
        if (smallScreen) { // On small screens, we move the logo to the background
            Image(
                painter = painterResource(id = R.drawable.ic_transparent_icon),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),
                alpha = 0.1f
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Reserve space for the logo and status text positioned absolutely
                Spacer(modifier = Modifier.height((screenHeightDp * statusGuidelinePercent).dp + postStatusSpacer))

                // Scrollable detail container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (screenState.connectionState) {
                            DISCONNECTED -> {
                                if (screenState.hasCamera) {
                                    DetailText(
                                        text = stringResource(R.string.disconnected_details),
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                } else {
                                    DetailText(
                                        text = stringResource(R.string.disconnected_no_camera_details),
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                }
                            }

                            CONNECTED -> {
                                if (screenState.proxyConfig != null) {
                                    Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp)) {
                                        ConnectionStatusScreen(
                                            proxyConfig = screenState.proxyConfig,
                                            totalAppCount = screenState.totalAppCount,
                                            interceptedAppCount = screenState.interceptedAppCount,
                                            onChangeApps = actions.onChooseApps,
                                            interceptedPorts = screenState.interceptedPorts,
                                            onChangePorts = actions.onChoosePorts
                                        )
                                    }
                                }
                            }

                            FAILED -> {
                                DetailText(
                                    text = stringResource(R.string.failed_details),
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }

                            CONNECTING, DISCONNECTING -> {
                                // No details shown during these states
                            }
                        }
                    }
                }

                // Button container - only visible when not transitioning
                if (screenState.connectionState != CONNECTING && screenState.connectionState != DISCONNECTING) {
                    ButtonCard(
                        isLandscape = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (screenState.connectionState) {
                            DISCONNECTED -> {
                                if (screenState.hasCamera) {
                                    PrimaryButton(
                                        text = stringResource(R.string.scan_button),
                                        onClick = actions.onScanQRCode
                                    )
                                }
                                if (screenState.lastProxy != null) {
                                    SecondaryButton(
                                        text = stringResource(R.string.reconnect_button),
                                        onClick = actions.onReconnect
                                    )
                                }
                            }

                            CONNECTED -> {
                                PrimaryButton(
                                    text = stringResource(R.string.disconnect_button),
                                    onClick = actions.onDisconnect
                                )
                                SecondaryButton(
                                    text = stringResource(R.string.test_button),
                                    onClick = actions.onTestInterception
                                )
                            }

                            FAILED -> {
                                PrimaryButton(
                                    text = stringResource(R.string.try_again_button),
                                    onClick = actions.onRecoverAfterFailure
                                )
                            }

                            else -> {}
                        }

                        // Docs button always shown
                        SecondaryButton(
                            text = stringResource(R.string.docs_button),
                            onClick = actions.onOpenDocs
                        )
                    }
                }
            }

            if (!smallScreen) {
                Image(
                    painter = painterResource(id = R.drawable.ic_transparent_icon),
                    contentDescription = "The HTTP Toolkit Logo",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (screenHeightDp * guidelinePercent).dp)
                )
            }

            Text(
                text = stringResource(
                    when (screenState.connectionState) {
                        DISCONNECTED -> R.string.disconnected_status
                        CONNECTING -> R.string.connecting_status
                        CONNECTED -> R.string.connected_status
                        DISCONNECTING -> R.string.disconnecting_status
                        FAILED -> R.string.failed_status
                    }
                ),
                fontSize = 40.sp,
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.05).sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (screenHeightDp * statusGuidelinePercent).dp)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun LandscapeMainScreen(
    screenState: MainScreenState,
    actions: MainScreenActions,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize()
    ) {
        // Left side: Status and details
        Column(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status text
            Text(
                text = stringResource(
                    when (screenState.connectionState) {
                        DISCONNECTED -> R.string.disconnected_status
                        CONNECTING -> R.string.connecting_status
                        CONNECTED -> R.string.connected_status
                        DISCONNECTING -> R.string.disconnecting_status
                        FAILED -> R.string.failed_status
                    }
                ),
                fontSize = 40.sp,
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.05).sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Detail container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (screenState.connectionState) {
                    DISCONNECTED -> {
                        if (screenState.hasCamera) {
                            DetailText(
                                text = stringResource(R.string.disconnected_details),
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        } else {
                            DetailText(
                                text = stringResource(R.string.disconnected_no_camera_details),
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                    }

                    CONNECTED -> {
                        if (screenState.proxyConfig != null) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                                ConnectionStatusScreen(
                                    proxyConfig = screenState.proxyConfig,
                                    totalAppCount = screenState.totalAppCount,
                                    interceptedAppCount = screenState.interceptedAppCount,
                                    onChangeApps = actions.onChooseApps,
                                    interceptedPorts = screenState.interceptedPorts,
                                    onChangePorts = actions.onChoosePorts
                                )
                            }
                        }
                    }

                    FAILED -> {
                        DetailText(
                            text = stringResource(R.string.failed_details),
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }

                    CONNECTING, DISCONNECTING -> {
                        // No details shown during these states
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.End + WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_transparent_icon),
                contentDescription = "The HTTP Toolkit Logo",
                modifier = Modifier
                    .weight(1f, fill = false)
                    .wrapContentSize()
                    .padding(bottom = 16.dp)
            )

            if (screenState.connectionState != CONNECTING && screenState.connectionState != DISCONNECTING) {
                ButtonCard(
                    isLandscape = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (screenState.connectionState) {
                        DISCONNECTED -> {
                            if (screenState.hasCamera) {
                                PrimaryButton(
                                    text = stringResource(R.string.scan_button),
                                    onClick = actions.onScanQRCode
                                )
                            }
                            if (screenState.lastProxy != null) {
                                SecondaryButton(
                                    text = stringResource(R.string.reconnect_button),
                                    onClick = actions.onReconnect
                                )
                            }
                        }

                        CONNECTED -> {
                            PrimaryButton(
                                text = stringResource(R.string.disconnect_button),
                                onClick = actions.onDisconnect
                            )
                            SecondaryButton(
                                text = stringResource(R.string.test_button),
                                onClick = actions.onTestInterception
                            )
                        }

                        FAILED -> {
                            PrimaryButton(
                                text = stringResource(R.string.try_again_button),
                                onClick = actions.onRecoverAfterFailure
                            )
                        }

                        else -> {}
                    }

                    // Docs button always shown
                    SecondaryButton(
                        text = stringResource(R.string.docs_button),
                        onClick = actions.onOpenDocs
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailText(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontSize = 20.sp,
        fontFamily = DmSansFontFamily,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp)
    )
}

@Composable
private fun ButtonCard(
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape: Shape
    val contentPadding: PaddingValues

    if (isLandscape) {
        // Landscape: rounded corners all around, with margins
        shape = MaterialTheme.shapes.small
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
    } else {
        // Portrait: rounded top corners only, extends to screen edges
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = 0.dp,
            bottomStart = 0.dp
        )
        // Content padding includes bottom inset padding, card extends to edge
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = 8.dp
        )
    }

    Card(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!isLandscape) {
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    } else {
                        Modifier
                    }
                )
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontFamily = DmSansFontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground
        ),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(width = 1.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontFamily = DmSansFontFamily,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp
        )
    }
}
