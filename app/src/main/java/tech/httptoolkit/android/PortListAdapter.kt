package tech.httptoolkit.android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.item_port_row.view.*

val PORT_DESCRIPTIONS = mapOf(
    80 to "Standard HTTP port",
    81 to "Alternative HTTP port",
    443 to "Standard HTTPS port",
    8000 to "Popular local development HTTP port",
    8001 to "Popular local development HTTP port",
    8008 to "Alternative HTTP port",
    8080 to "Popular local development HTTP port",
    8090 to "Popular local development HTTP port",
    8433 to "Alternative HTTPS port",
    8888 to "Popular local development HTTP port",
    9000 to "Popular local development HTTP port"
)

class PortListAdapter(
    private val ports: Set<Int>,
    private val onPortDeleted: (Int) -> Unit
) : RecyclerView.Adapter<PortListAdapter.PortViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PortViewHolder {
        return PortViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_port_row, parent, false)
        )
    }

    override fun getItemCount() = ports.size

    override fun onBindViewHolder(holder: PortViewHolder, position: Int) {
        holder.bind(ports.elementAt(position))
    }

    inner class PortViewHolder(v: View) : RecyclerView.ViewHolder(v) {

        init {
            itemView.row_port_delete.setOnClickListener { _ ->
                onPortDeleted(ports.elementAt(layoutPosition))
            }
        }

        fun bind(port: Int) {
            itemView.row_port.text = port.toString()
            itemView.row_port_description.text = PORT_DESCRIPTIONS[port]
                ?: "Unknown port";
        }
    }
}