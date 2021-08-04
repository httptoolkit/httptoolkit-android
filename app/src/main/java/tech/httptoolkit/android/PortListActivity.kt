package tech.httptoolkit.android

import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import kotlinx.android.synthetic.main.ports_list.*
import kotlinx.coroutines.*

val DEFAULT_PORTS = setOf(
    80, // HTTP
    443, // HTTPS
    8000, 8001, 8080, 8888, 9000 // Common local dev ports
)

const val MIN_PORT = 1
const val MAX_PORT = 65535

// Used to both to send and return the current list of selected ports
const val SELECTED_PORTS_EXTRA = "tech.httptoolkit.android.SELECTED_PORTS_EXTRA"

class PortListActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var ports: MutableSet<Int>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ports_list)

        ports = intent.getIntArrayExtra(SELECTED_PORTS_EXTRA)!!.toMutableSet()

        ports_list_recyclerView.adapter =
            PortListAdapter(
                ports,
                ::deletePort
            )

        ports_list_input.filters = arrayOf(MinMaxInputFilter(MIN_PORT, MAX_PORT))

        // Match the UI enabled state to the input field contents:
        ports_list_add_button.isEnabled = false
        ports_list_input.doAfterTextChanged {
            ports_list_add_button.isEnabled = isValidInput(it.toString())
        }

        // Add ports when enter/+ is pressed/clicked:
        ports_list_add_button.setOnClickListener { addEnteredPort() }
        ports_list_input.setOnEditorActionListener { _, _, _ ->
            addEnteredPort()
            return@setOnEditorActionListener true
        }

        // Show the menu, and listen for clicks:
        ports_list_more_menu.setOnClickListener {
            PopupMenu(this, ports_list_more_menu).apply {
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
    }

    private fun isValidInput(input: String): Boolean =
        input.toIntOrNull() != null &&
        !ports.contains(input.toInt())

    private fun addEnteredPort() {
        if (!isValidInput(ports_list_input.text.toString())) return

        ports.add(ports_list_input.text.toString().toInt())
        ports_list_input.text.clear()
        updateList()
    }

    private fun deletePort(port: Int) {
        ports.remove(port)
        updateList()
    }

    private fun updateList() {
        ports_list_recyclerView.adapter?.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        setResult(RESULT_OK, Intent().putExtra(
            SELECTED_PORTS_EXTRA,
            ports.toIntArray()
        ))
        finish()
    }
}