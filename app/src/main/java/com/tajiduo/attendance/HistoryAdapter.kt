package com.tajiduo.attendance

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tajiduo.attendance.data.RunRecord
import com.tajiduo.attendance.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 历史记录列表适配器。 */
class HistoryAdapter(
    private val records: List<RunRecord>,
    private val onClick: (RunRecord) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    class Holder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = records.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = records[position]
        val binding = holder.binding
        val context = binding.root.context

        binding.tvTime.text = "${formatTime(record.finishedAt)} · ${if (record.force) "手动" else "定时"}"

        val (label, colorRes) = when {
            record.failedCount > 0 -> "失败" to R.color.state_bad
            record.successCount > 0 -> "成功" to R.color.state_ok
            else -> "跳过" to R.color.state_warn
        }
        binding.tvStatus.text = label
        binding.tvStatus.setTextColor(ContextCompat.getColor(context, colorRes))
        binding.viewDot.backgroundTintList = ContextCompat.getColorStateList(context, colorRes)

        binding.tvSummary.text =
            "成功 ${record.successCount} · 失败 ${record.failedCount} · 跳过 ${record.skippedCount}"

        holder.itemView.setOnClickListener { onClick(record) }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))
}