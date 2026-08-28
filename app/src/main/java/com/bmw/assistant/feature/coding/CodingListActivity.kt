package com.bmw.assistant.feature.coding

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bmw.assistant.databinding.ActivityCodingListBinding
import com.bmw.assistant.ui.common.ConnectionBadge

/** Every coding for the chosen module, shown as cards. */
class CodingListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingListBinding
    private val viewModel: CodingListViewModel by viewModels()
    private lateinit var moduleId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodingListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()

        ConnectionBadge.bind(binding.connectionChip, this, this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = CodingAdapter { row ->
            startActivity(
                Intent(this, EditCodingActivity::class.java)
                    .putExtra(EditCodingActivity.EXTRA_CODING_ID, row.coding.id)
            )
        }
        binding.codingList.layoutManager = LinearLayoutManager(this)
        binding.codingList.adapter = adapter

        viewModel.module.observe(this) { m ->
            binding.toolbar.title = m?.name ?: "Codings"
            binding.moduleSubtitle.text = m?.fullName ?: ""
        }
        viewModel.rows.observe(this) { adapter.submitList(it) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load(moduleId)
    }

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
