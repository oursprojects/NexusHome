package com.capstone.nexushome

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.capstone.nexushome.adapter.CommandLogAdapter
import com.capstone.nexushome.data.AppDatabase
import com.capstone.nexushome.data.CommandLog
import com.capstone.nexushome.databinding.ActivityCommandHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommandHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandHistoryBinding
    private lateinit var adapter: CommandLogAdapter
    private val database by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCommandHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupRecyclerView()
        observeHistory()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearHistory.setOnClickListener { showDeleteConfirmation() }
    }

    private fun setupRecyclerView() {
        adapter = CommandLogAdapter()
        binding.rvCommandLog.layoutManager = LinearLayoutManager(this)
        binding.rvCommandLog.adapter = adapter
        binding.rvCommandLog.setHasFixedSize(true)
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                database.commandLogDao().observeAllLogs().collectLatest(::updateList)
            }
        }
    }

    private fun updateList(logs: List<CommandLog>) {
        val hasData = logs.isNotEmpty()
        binding.rvCommandLog.isVisible = hasData
        binding.emptyState.isVisible = !hasData
        binding.btnClearHistory.isEnabled = hasData
        binding.btnClearHistory.alpha = if (hasData) 1f else 0.45f
        binding.tvHistoryCount.text = getString(R.string.history_count_format, logs.size)
        adapter.submitLogs(logs)
    }

    private fun showDeleteConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.clear_history_title))
            .setMessage(getString(R.string.clear_history_msg))
            .setIcon(R.drawable.ic_lucide_trash)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.btn_delete_all)) { _, _ ->
                clearHistory()
            }
            .setBackground(ContextCompat.getDrawable(this, R.drawable.bg_dialog_solid))
            .show()
    }

    private fun clearHistory() {
        binding.btnClearHistory.isEnabled = false
        lifecycleScope.launch {
            val cleared = runCatching {
                withContext(Dispatchers.IO) {
                    database.commandLogDao().clearAll()
                }
                true
            }.getOrElse {
                Toast.makeText(
                    this@CommandHistoryActivity,
                    getString(R.string.msg_clear_history_failed),
                    Toast.LENGTH_LONG
                ).show()
                false
            }

            binding.btnClearHistory.isEnabled = true

            if (cleared) {
                Toast.makeText(
                    this@CommandHistoryActivity,
                    getString(R.string.history_cleared),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
