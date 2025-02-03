package tech.httptoolkit.android

import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import tech.httptoolkit.android.databinding.PortsListBinding
import java.util.*

val DEFAULT_PORTS = setOf(
    80, // HTTP
    443, // HTTPS
    4443, 8000, 8080, 8443, 8888, 9000 // Common local dev/testing ports
)

const val MIN_PORT = 1
const val MAX_PORT = 65535

class PortListActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var ports: TreeSet<Int> // TreeSet = Mutable + Sorted

    private lateinit var binding: PortsListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = PortsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ports = intent.getIntArrayExtra(IntentExtras.SELECTED_PORTS_EXTRA)!!
            .toCollection(TreeSet())

        binding.portsListRecyclerView.adapter =
            PortListAdapter(
                ports,
                ::deletePort
            )

        binding.portsListInput.filters = arrayOf(MinMaxInputFilter(MIN_PORT, MAX_PORT))

        // Match the UI enabled state to the input field contents:
        binding.portsListAddButton.isEnabled = false
        binding.portsListInput.doAfterTextChanged {
            binding.portsListAddButton.isEnabled = isValidInput(it.toString())
        }

        // Add ports when enter/+ is pressed/clicked:
        binding.portsListAddButton.setOnClickListener { addEnteredPort() }
        binding.portsListInput.setOnEditorActionListener { _, _, _ ->
            addEnteredPort()
            return@setOnEditorActionListener true
        }

        // Show the menu, and listen for clicks:
        binding.portsListMoreMenu.setOnClickListener {
            PopupMenu(ContextThemeWrapper(this, R.style.PopupMenu), binding.portsListMoreMenu).apply {
                this.inflate(R.menu.menu_ports_list)

                this.menu.findItem(R.id.action_reset_ports).isEnabled =
                    ports != DEFAULT_PORTS

                this.setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.action_reset_ports -> {
                            ports.clear()
                            ports.addAll(DEFAULT_PORTS)
                            updateList()
                            true
                        }
                        else -> false
                    }
                }
            }.show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_OK, Intent().putExtra(
                    IntentExtras.SELECTED_PORTS_EXTRA,
                    ports.toIntArray()
                ))
                finish()
            }
        })
    }

    private fun isValidInput(input: String): Boolean =
        input.toIntOrNull() != null &&
        !ports.contains(input.toInt())

    private fun addEnteredPort() {
        if (!isValidInput(binding.portsListInput.text.toString())) return

        ports.add(binding.portsListInput.text.toString().toInt())
        binding.portsListInput.text.clear()
        updateList()
    }

    private fun deletePort(port: Int) {
        ports.remove(port)
        updateList()
    }

    private fun updateList() {
        binding.portsListRecyclerView.adapter?.notifyDataSetChanged()
    }
}