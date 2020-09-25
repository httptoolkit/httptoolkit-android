package tech.httptoolkit.android

import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.item_app_row.view.*

class ApplicationListAdapter(
    private val data: MutableList<PackageInfo>,
    private val isAppWhitelisted: (PackageInfo) -> Boolean,
    private val onCheckChanged: (PackageInfo, Boolean) -> Unit
) :
    RecyclerView.Adapter<ApplicationListAdapter.AppsViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppsViewHolder {
        return AppsViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
        )
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: AppsViewHolder, position: Int) {
        holder.bind(data[position])
    }

    inner class AppsViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val packageManager by lazy {
            itemView.context.packageManager
        }

        init {
            itemView.row_app_switch.setOnCheckedChangeListener { _, isChecked ->
                onCheckChanged(data[layoutPosition], isChecked)
            }
        }

        fun bind(appInfo: PackageInfo) {
            itemView.row_app_icon_image.setImageDrawable(appInfo.applicationInfo.loadIcon(packageManager))
            itemView.row_app_name.text = appInfo.applicationInfo.loadLabel(packageManager)
            itemView.row_app_package_name.text = appInfo.packageName
            itemView.row_app_switch.isChecked = isAppWhitelisted(appInfo)
        }
    }
}