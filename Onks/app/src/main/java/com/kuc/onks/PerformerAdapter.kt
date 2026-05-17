package com.kuc.onks

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kuc.onks.databinding.ItemPerformerBinding
import com.kuc.onks.room.RoomManager

class PerformerAdapter(
    private val onMuteToggle: (String, Boolean) -> Unit,
    private val onVolumeChange: (String, Float) -> Unit
) : ListAdapter<RoomManager.Performer, PerformerAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RoomManager.Performer>() {
            override fun areItemsTheSame(a: RoomManager.Performer, b: RoomManager.Performer) =
                a.id == b.id
            override fun areContentsTheSame(a: RoomManager.Performer, b: RoomManager.Performer) =
                a == b
        }
        private const val GREEN  = 0xFF4CAF50.toInt()
        private const val RED    = 0xFFF44336.toInt()
        private const val GREY   = 0xFF444444.toInt()
    }

    inner class ViewHolder(private val b: ItemPerformerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(p: RoomManager.Performer) {
            b.tvPerformerItemName.text = p.name
            b.tvPerformerItemStatus.text = when {
                p.isTransmitting -> "🎙 LIVE"
                p.isMuted        -> "🔇 Muted"
                !p.isConnected   -> "Disconnected"
                else             -> "Ready"
            }
            b.tvPerformerItemStatus.setTextColor(
                when {
                    p.isTransmitting -> RED
                    !p.isConnected   -> GREY
                    else             -> GREEN
                }
            )
            b.vStatusDot.setBackgroundColor(
                when {
                    p.isTransmitting -> RED
                    p.isConnected    -> GREEN
                    else             -> GREY
                }
            )
            b.btnMute.text = if (p.isMuted) "Unmute" else "Mute"
            b.btnMute.setOnClickListener { onMuteToggle(p.id, !p.isMuted) }

            b.seekVolume.progress = (p.volume * 100).toInt()
            b.seekVolume.setOnSeekBarChangeListener(object :
                android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) onVolumeChange(p.id, v / 100f)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
            })
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPerformerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))
}
