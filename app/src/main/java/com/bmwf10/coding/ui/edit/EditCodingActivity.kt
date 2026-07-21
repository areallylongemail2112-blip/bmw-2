package com.bmwf10.coding.ui.edit

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.ValueType
import com.bmwf10.coding.databinding.ActivityEditCodingBinding
import com.bmwf10.coding.ecu.ConnectionManager
import com.bmwf10.coding.ui.common.ConnectionBadge
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

/** Screen 3 — edit a single coding value, with confirmation before writing to the ECU. */
class EditCodingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditCodingBinding
    private val viewModel: EditCodingViewModel by viewModels()
    private lateinit var codingId: String
    private var sliderListener: Slider.OnChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditCodingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        codingId = intent.getStringExtra(EXTRA_CODING_ID).orEmpty()
        ConnectionBadge.bind(binding.connectionChip, this, this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        viewModel.ui.observe(this) { model -> if (model != null) bindCoding(model) }
        viewModel.busy.observe(this) { busy ->
            binding.applyButton.isEnabled = !busy
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        }
        viewModel.result.observe(this) { ev ->
            when (val r = ev.getIfNotHandled()) {
                is ApplyResult.Success -> {
                    val extra = r.rawByte?.let { "\n\nWrote coding byte $it to the module." } ?: ""
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Coding applied")
                        .setMessage("New value: ${r.newDisplay}$extra")
                        .setPositiveButton("OK", null)
                        .show()
                }
                is ApplyResult.Error -> snackError(r.message)
                ApplyResult.NeedsConnection -> snackError(
                    "Not connected. Open the Connection screen and connect (or use demo mode) first."
                )
                null -> {}
            }
        }

        viewModel.load(codingId)
    }

    private fun bindCoding(model: EditUiModel) {
        val c = model.coding
        binding.toolbar.title = c.name
        binding.longDescription.text = c.longDescription
        binding.safeInfo.text = buildSafeInfo(c)
        binding.currentValue.text = c.displayValue(model.currentValue)

        // Warning banner for irreversible / flagged codings.
        val warn = c.warning ?: if (c.irreversible)
            "This change is difficult to reverse. Note the current value before applying." else null
        binding.warningBanner.visibility = if (warn != null) View.VISIBLE else View.GONE
        binding.warningText.text = warn ?: ""

        renderInput(c, model.currentValue)

        binding.applyButton.setOnClickListener { confirmAndApply(c) }
    }

    /** Shows only the input control appropriate to the coding's value type. */
    private fun renderInput(c: CodingItem, current: String) {
        binding.booleanSwitch.visibility = View.GONE
        binding.enumSpinner.visibility = View.GONE
        binding.integerGroup.visibility = View.GONE
        binding.hexInputLayout.visibility = View.GONE

        // Avoid stacking slider listeners across rebinds / rotation.
        sliderListener?.let { binding.integerSlider.removeOnChangeListener(it) }
        sliderListener = null

        when (c.valueType) {
            ValueType.BOOLEAN -> {
                binding.booleanSwitch.visibility = View.VISIBLE
                binding.booleanSwitch.isChecked = current.equals("true", true)
            }
            ValueType.ENUM -> {
                binding.enumSpinner.visibility = View.VISIBLE
                val labels = c.options?.map { it.label } ?: emptyList()
                binding.enumSpinner.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item, labels
                )
                val idx = c.options?.indexOfFirst { it.value == current } ?: 0
                if (idx >= 0) binding.enumSpinner.setSelection(idx)
            }
            ValueType.INTEGER -> {
                binding.integerGroup.visibility = View.VISIBLE
                val min = (c.min ?: 0).toFloat()
                val max = (c.max ?: 100).toFloat()
                binding.integerSlider.valueFrom = min
                binding.integerSlider.valueTo = max
                val cur = current.toFloatOrNull()?.coerceIn(min, max) ?: min
                binding.integerSlider.value = cur
                binding.integerValueLabel.text = formatInt(c, cur.toInt())
                val listener = Slider.OnChangeListener { _, value, _ ->
                    binding.integerValueLabel.text = formatInt(c, value.toInt())
                }
                sliderListener = listener
                binding.integerSlider.addOnChangeListener(listener)
            }
            ValueType.HEX -> {
                binding.hexInputLayout.visibility = View.VISIBLE
                binding.hexInput.setText(current.removePrefix("0x"))
            }
        }
    }

    /** Reads the chosen value from whichever control is visible. */
    private fun readInput(c: CodingItem): String = when (c.valueType) {
        ValueType.BOOLEAN -> binding.booleanSwitch.isChecked.toString()
        ValueType.ENUM -> {
            val pos = binding.enumSpinner.selectedItemPosition
            c.options?.getOrNull(pos)?.value ?: c.defaultValue
        }
        ValueType.INTEGER -> binding.integerSlider.value.toInt().toString()
        ValueType.HEX -> "0x" + binding.hexInput.text.toString().trim().removePrefix("0x")
    }

    private fun confirmAndApply(c: CodingItem) {
        val newValue = readInput(c)
        viewModel.validate(newValue)?.let { snackError(it); return }

        if (!ConnectionManager.current.isConnected) {
            snackError("Not connected. Connect on the Connection screen (or use demo mode) first.")
            return
        }

        val oldDisplay = c.displayValue(viewModel.currentValue.value ?: c.defaultValue)
        val newDisplay = c.displayValue(newValue)
        val conn = ConnectionManager.current
        val target = if (conn.isDemo) "the simulated car (demo mode)" else "your car (${conn.label})"

        val message = buildString {
            append("Module: ${c.moduleId.uppercase()}\n")
            append("Coding: ${c.name}\n\n")
            append("Change from “$oldDisplay” to “$newDisplay”.\n\n")
            append("This will be written to $target.")
            if (c.warning != null) append("\n\n⚠ ${c.warning}")
            if (c.irreversible) append("\n\n⚠ This change is hard to undo.")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Apply this coding?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply coding") { _, _ -> viewModel.apply(newValue) }
            .show()
    }

    private fun buildSafeInfo(c: CodingItem): String = when (c.valueType) {
        ValueType.BOOLEAN -> "Safe default: ${if (c.safeDefault.equals("true", true)) "On" else "Off"}"
        ValueType.ENUM -> "Safe default: " +
            (c.options?.firstOrNull { it.value == c.safeDefault }?.label ?: c.safeDefault)
        ValueType.INTEGER -> "Allowed range: ${c.min}–${c.max}${c.unit?.let { " $it" } ?: ""} • " +
            "Safe default: ${formatInt(c, c.safeDefault.toIntOrNull() ?: 0)}"
        ValueType.HEX -> "Hex value • Safe default: 0x${c.safeDefault.removePrefix("0x")}"
    }

    private fun formatInt(c: CodingItem, n: Int): String =
        if (c.unit.isNullOrBlank()) n.toString() else "$n ${c.unit}"

    private fun snackError(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    companion object {
        const val EXTRA_CODING_ID = "coding_id"
    }
}
