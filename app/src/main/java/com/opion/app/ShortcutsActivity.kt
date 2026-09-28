package com.opion.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.opion.app.databinding.ActivityShortcutsBinding
import com.opion.app.databinding.ItemShortcutBinding
import com.opion.app.shortcuts.PrivacyShortcuts

class ShortcutsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityShortcutsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val targets = PrivacyShortcuts.targets(this)

        for (target in targets) {
            val row = ItemShortcutBinding.inflate(layoutInflater, binding.shortcutContainer, false)
            row.root.text = target.title
            row.root.setOnClickListener {
                val ok = PrivacyShortcuts.open(this, target.intents)
                if (!ok) {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.shortcuts_open_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            binding.shortcutContainer.addView(row.root)
        }
    }
}
