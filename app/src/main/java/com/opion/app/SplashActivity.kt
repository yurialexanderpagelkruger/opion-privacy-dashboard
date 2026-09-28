package com.opion.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.opion.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.splashContent.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
        lifecycleScope.launch {
            delay(480L)
            if (isFinishing || isDestroyed) return@launch
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}
