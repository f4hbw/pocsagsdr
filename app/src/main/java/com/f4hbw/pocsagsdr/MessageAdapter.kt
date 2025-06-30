package com.f4hbw.pocsagsdr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter(private val messages: List<PocsagMessage>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timestampText: TextView = view.findViewById(R.id.timestampText)
        val addressText: TextView = view.findViewById(R.id.addressText)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val typeText: TextView = view.findViewById(R.id.typeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        holder.timestampText.text = message.timestamp
        holder.addressText.text = message.address
        holder.messageText.text = message.message
        holder.typeText.text = message.type

        // Couleur alternée pour les messages
        val backgroundColor = if (position % 2 == 0) {
            0xFF2C2C2C.toInt()
        } else {
            0xFF1E1E1E.toInt()
        }
        holder.itemView.setBackgroundColor(backgroundColor)
    }

    override fun getItemCount() = messages.size
}