package com.github.kr328.clash.design.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.DeviceProfile

class DeviceProfileAdapter(
    private var profiles: List<DeviceProfile>,
    private val onItemClick: (DeviceProfile) -> Unit,
    private val onEditClick: (DeviceProfile) -> Unit,
    private val onDeleteClick: (DeviceProfile) -> Unit
) : RecyclerView.Adapter<DeviceProfileAdapter.ViewHolder>() {

    // ID профиля, у которого сейчас открыты кнопки редактирования
    private var expandedProfileId: Int? = null

    fun updateData(newProfiles: List<DeviceProfile>) {
        this.profiles = newProfiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]

        holder.tvName.text = profile.name

        // Дефолтные профили нельзя редактировать/удалять
        val isExpanded = profile.id == expandedProfileId
        holder.actionContainer.visibility = if (isExpanded && !profile.isDefault) View.VISIBLE else View.GONE

        // Обычный клик (выбор)
        holder.rootLayout.setOnClickListener {
            if (expandedProfileId != null) {
                expandedProfileId = null // Прячем кнопки, если были открыты у другого
                notifyDataSetChanged()
            } else {
                onItemClick(profile)
            }
        }

        // Долгое нажатие (показать/скрыть кнопки)
        holder.rootLayout.setOnLongClickListener {
            if (!profile.isDefault) {
                expandedProfileId = if (expandedProfileId == profile.id) null else profile.id
                notifyDataSetChanged()
            }
            true
        }

        holder.btnEdit.setOnClickListener {
            expandedProfileId = null
            onEditClick(profile)
        }

        holder.btnDelete.setOnClickListener {
            expandedProfileId = null
            onDeleteClick(profile)
        }
    }

    override fun getItemCount(): Int = profiles.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootLayout: LinearLayout = view.findViewById(R.id.root_layout)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val actionContainer: LinearLayout = view.findViewById(R.id.action_container)
        val btnEdit: ImageView = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageView = view.findViewById(R.id.btn_delete)
    }
}