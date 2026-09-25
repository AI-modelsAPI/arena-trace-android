package com.ati.arena.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ati.arena.R
import com.ati.arena.session.TurnTracker.Status

/** Turn list: "R3  model  [已切换]  ✓", newest first (rows come from [HudFormat.turnRows]). */
class TurnAdapter : ListAdapter<HudFormat.TurnRow, TurnAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.turn_number)
        val model: TextView = view.findViewById(R.id.turn_model)
        val tag: TextView = view.findViewById(R.id.turn_tag)
        val state: ImageView = view.findViewById(R.id.turn_state)
        val progress: View = view.findViewById(R.id.turn_progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_turn, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        holder.number.text = "R${row.number}"
        holder.model.text = row.label
        holder.model.setTextColor(
            context.getColor(
                when {
                    row.routed -> R.color.warn
                    row.status == Status.RESOLVED -> R.color.on_surface
                    else -> R.color.on_surface_muted
                },
            ),
        )
        holder.tag.visibility = if (row.routed) View.VISIBLE else View.GONE
        val pending = row.status == Status.PENDING
        holder.progress.visibility = if (pending) View.VISIBLE else View.GONE
        holder.state.visibility = if (pending) View.GONE else View.VISIBLE
        if (!pending) {
            val ok = row.status == Status.RESOLVED
            holder.state.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_alert)
            holder.state.imageTintList = ColorStateList.valueOf(context.getColor(if (ok) R.color.ok else R.color.danger))
        }
        holder.itemView.contentDescription =
            "第 ${row.number} 轮，${row.label}" + if (row.routed) "，已切换模型" else ""
    }

    private object Diff : DiffUtil.ItemCallback<HudFormat.TurnRow>() {
        override fun areItemsTheSame(oldItem: HudFormat.TurnRow, newItem: HudFormat.TurnRow): Boolean =
            oldItem.number == newItem.number

        override fun areContentsTheSame(oldItem: HudFormat.TurnRow, newItem: HudFormat.TurnRow): Boolean =
            oldItem == newItem
    }
}
