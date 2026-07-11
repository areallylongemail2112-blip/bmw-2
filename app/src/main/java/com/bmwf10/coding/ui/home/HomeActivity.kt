package com.bmwf10.coding.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.bmwf10.coding.ui.coding.CodingListActivity
import com.bmwf10.coding.databinding.ActivityHomeBinding
import com.bmwf10.coding.ui.common.ConnectionBadge

/** Screen 1 — module picker shown as a card grid. */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionBadge.bind(binding.connectionChip, this, this)

        val adapter = ModuleAdapter { card ->
            startActivity(
                Intent(this, CodingListActivity::class.java)
                    .putExtra(CodingListActivity.EXTRA_MODULE_ID, card.module.id)
            )
        }
        binding.moduleGrid.layoutManager = GridLayoutManager(this, 2)
        binding.moduleGrid.adapter = adapter

        viewModel.modules.observe(this) { adapter.submitList(it) }
        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }
}
