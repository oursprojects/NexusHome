package com.capstone.nexushome.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capstone.nexushome.R
import com.capstone.nexushome.data.CommandLog

class CommandLogAdapter : ListAdapter<CommandLog, CommandLogAdapter.LogViewHolder>(DiffCallback) {

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        val tvAction: TextView = itemView.findViewById(R.id.tvAction)
        val tvActionMeta: TextView = itemView.findViewById(R.id.tvActionMeta)
        val ivActionIcon: ImageView = itemView.findViewById(R.id.ivActionIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = getItem(position)
        holder.tvTimestamp.text = log.timestamp
        holder.tvAction.text = log.action
        holder.tvActionMeta.text = holder.itemView.context.getString(resolveMetaText(log))

        holder.ivActionIcon.setImageResource(resolveIcon(log))
        holder.ivActionIcon.imageTintList = null
    }

    fun submitLogs(logs: List<CommandLog>) {
        submitList(logs)
    }

    private fun resolveIcon(log: CommandLog): Int {
        return when {
            log.action.contains("Fan ON", ignoreCase = true) -> R.drawable.fan_on
            log.action.contains("Fan OFF", ignoreCase = true) -> R.drawable.fan_off
            log.action.contains("Light ON", ignoreCase = true) -> R.drawable.light_on
            log.action.contains("Light OFF", ignoreCase = true) -> R.drawable.light_off
            log.action.contains("Auto Mode", ignoreCase = true) -> R.drawable.mode_auto
            log.action.contains("Manual Mode", ignoreCase = true) -> R.drawable.mode_manual
            else -> R.drawable.ic_lucide_clock
        }
    }

    private fun resolveMetaText(log: CommandLog): Int {
        return when {
            log.action.contains("Fan", ignoreCase = true) -> R.string.history_meta_fan
            log.action.contains("Light", ignoreCase = true) -> R.string.history_meta_light
            log.action.contains("Auto Mode", ignoreCase = true) -> R.string.history_meta_auto
            log.action.contains("Manual Mode", ignoreCase = true) -> R.string.history_meta_manual
            else -> R.string.history_meta_default
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<CommandLog>() {
        override fun areItemsTheSame(oldItem: CommandLog, newItem: CommandLog): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CommandLog, newItem: CommandLog): Boolean {
            return oldItem == newItem
        }
    }
}
