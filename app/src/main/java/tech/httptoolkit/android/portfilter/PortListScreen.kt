package tech.httptoolkit.android.portfilter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tech.httptoolkit.android.R
import tech.httptoolkit.android.ui.AppConstants

@Composable
fun PortListScreen(
    initialPorts: Set<Int>,
    onPortsChanged: (Set<Int>) -> Unit,
    defaultPorts: Set<Int>,
    modifier: Modifier = Modifier
) {
    var ports by remember { mutableStateOf(initialPorts) }
    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    // Notify parent of changes
    LaunchedEffect(ports) {
        onPortsChanged(ports)
    }

    val canResetToDefaults = ports != defaultPorts
    val sortedPorts = remember(ports) { ports.sorted() }

    val isValidInput = remember(inputText, ports) {
        inputText.toIntOrNull()?.let { port ->
            port in MIN_PORT..MAX_PORT && !ports.contains(port)
        } ?: false
    }

    fun addPort() {
        val port = inputText.toIntOrNull()
        if (port != null && isValidInput) {
            ports = ports + port
            inputText = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .padding(horizontal = AppConstants.spacingLarge)
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Explanation text
        Text(
            text = stringResource(R.string.port_config_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = AppConstants.spacingSmall)
        )

        // Input card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppConstants.spacingSmall)
                .zIndex(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = AppConstants.elevationDefault),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppConstants.spacingLarge),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text(stringResource(R.string.add_port_prompt)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { addPort() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = AppConstants.spacingSmall),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                IconButton(
                    onClick = { addPort() },
                    enabled = isValidInput
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_add_port)
                    )
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_options)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_reset_to_defaults)) },
                            onClick = {
                                showMenu = false
                                ports = defaultPorts
                            },
                            enabled = canResetToDefaults
                        )
                    }
                }
            }
        }

        // Port list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = AppConstants.spacingSmall)
        ) {
            items(
                items = sortedPorts,
                key = { it }
            ) { port ->
                PortItem(
                    port = port,
                    description = PORT_DESCRIPTIONS[port] ?: "Unknown port",
                    onDelete = {
                        ports = ports - port
                    }
                )
            }
        }
    }
}

@Composable
fun PortItem(
    port: Int,
    description: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppConstants.spacingTiny, horizontal = AppConstants.spacingSmall),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppConstants.spacingLarge),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = port.toString(),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_port, port)
                )
            }
        }
    }
}
