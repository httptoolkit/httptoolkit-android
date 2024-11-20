package tech.httptoolkit.android

import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import tech.httptoolkit.android.databinding.ItemAppRowBinding

class ApplicationListAdapter(
    private val data: MutableList<PackageInfo>,
    private val isAppWhitelisted: (PackageInfo) -> Boolean,
    private val onCheckChanged: (PackageInfo, Boolean) -> Unit
) : RecyclerView.Adapter<ApplicationListAdapter.AppsViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppsViewHolder {
        val binding = ItemAppRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppsViewHolder(binding)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: AppsViewHolder, position: Int) {
        holder.bind(data[position])
    }

    inner class AppsViewHolder(
        private val binding: ItemAppRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val packageManager by lazy {
            itemView.context.packageManager
        }

        init {
            binding.rowAppSwitch.setOnCheckedChangeListener { _, isChecked ->
                onCheckChanged(data[layoutPosition], isChecked)
            }
        }

        fun bind(packageInfo: PackageInfo) {
            val appInfo = packageInfo.applicationInfo!!
            binding.rowAppIconImage.setImageDrawable(appInfo.loadIcon(packageManager))
            binding.rowAppName.text = AppLabelCache.getAppLabel(packageManager, appInfo)
            binding.rowAppPackageName.text = packageInfo.packageName
            binding.rowAppSwitch.isChecked = isAppWhitelisted(packageInfo)
        }
    }
}