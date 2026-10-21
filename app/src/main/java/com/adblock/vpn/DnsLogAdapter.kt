package com.adblock.vpn

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class DnsLogAdapter(
    private var logs: List<DnsLogManager.LogEntry>,
    private val onActionClick: (DnsLogManager.LogEntry) -> Unit
) : RecyclerView.Adapter<DnsLogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDomainName: TextView = view.findViewById(R.id.tvDomainName)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
        val btnQuickAction: MaterialButton = view.findViewById(R.id.btnQuickAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dns_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        holder.tvDomainName.text = log.domain
        holder.tvTimestamp.text = log.timestamp

        // Membuat background badge bulat secara dinamis
        val badgeDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f // sudut bulat
        }

        if (log.isBlocked) {
            holder.tvStatusBadge.text = "DIBLOKIR"
            holder.tvStatusBadge.setTextColor(Color.WHITE)
            badgeDrawable.setColor(Color.parseColor("#E53935")) // Merah terang
            holder.tvStatusBadge.background = badgeDrawable
            
            holder.btnQuickAction.text = "Izinkan"
            holder.btnQuickAction.setTextColor(Color.parseColor("#03DAC6"))
        } else {
            holder.tvStatusBadge.text = "DIIZINKAN"
            holder.tvStatusBadge.setTextColor(Color.WHITE)
            badgeDrawable.setColor(Color.parseColor("#4CAF50")) // Hijau terang
            holder.tvStatusBadge.background = badgeDrawable
            
            holder.btnQuickAction.text = "Blokir"
            holder.btnQuickAction.setTextColor(Color.parseColor("#E53935"))
        }

        holder.btnQuickAction.setOnClickListener {
            onActionClick(log)
        }
    }

    override fun getItemCount(): Int = logs.size

    fun updateLogs(newLogs: List<DnsLogManager.LogEntry>) {
        this.logs = newLogs
        notifyDataSetChanged()
    }
}
