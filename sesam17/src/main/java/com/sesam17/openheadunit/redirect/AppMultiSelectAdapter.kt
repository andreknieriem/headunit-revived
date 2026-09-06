package com.sesam17.openheadunit.redirect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sesam17.openheadunit.R

class AppMultiSelectAdapter(
    private val allApps: List<AppInfo>,
    initialSelectedPackages: Set<String>,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<AppMultiSelectAdapter.AppViewHolder>() {

    private val selectedPackages = initialSelectedPackages.toMutableSet()
    private var filteredApps: List<AppInfo> = allApps

    fun getSelectedPackages(): Set<String> = selectedPackages.toSet()

    fun filter(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            val lower = query.lowercase().trim()
            allApps.filter {
                it.appName.lowercase().contains(lower) || it.packageName.lowercase().contains(lower)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        for (app in filteredApps) {
            selectedPackages.add(app.packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }

    fun deselectAll() {
        for (app in filteredApps) {
            selectedPackages.remove(app.packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_multi_select, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.bind(app, selectedPackages.contains(app.packageName))
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvPackage: TextView = itemView.findViewById(R.id.tv_package_name)
        private val cbSelected: CheckBox = itemView.findViewById(R.id.cb_app_selected)

        fun bind(app: AppInfo, isSelected: Boolean) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            if (app.icon != null) {
                ivIcon.setImageDrawable(app.icon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            cbSelected.isChecked = isSelected

            itemView.setOnClickListener {
                if (selectedPackages.contains(app.packageName)) {
                    selectedPackages.remove(app.packageName)
                    cbSelected.isChecked = false
                } else {
                    selectedPackages.add(app.packageName)
                    cbSelected.isChecked = true
                }
                onSelectionChanged(selectedPackages.size)
            }
        }
    }
}
