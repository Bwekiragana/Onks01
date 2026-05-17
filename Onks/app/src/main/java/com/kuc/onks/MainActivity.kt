package com.kuc.onks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kuc.onks.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val REQUIRED_PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnHost.setOnClickListener {
            val name = binding.etName.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                binding.tilName.error = "Enter your name first"
                return@setOnClickListener
            }
            binding.tilName.error = null
            if (!hasPermissions()) { checkAndRequestPermissions(); return@setOnClickListener }
            startActivity(Intent(this, HostActivity::class.java).apply {
                putExtra(EXTRA_NAME, name)
            })
        }

        binding.btnPerformer.setOnClickListener {
            val name = binding.etName.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                binding.tilName.error = "Enter your name first"
                return@setOnClickListener
            }
            binding.tilName.error = null
            if (!hasPermissions()) { checkAndRequestPermissions(); return@setOnClickListener }
            startActivity(Intent(this, PerformerActivity::class.java).apply {
                putExtra(EXTRA_NAME, name)
            })
        }
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, getString(R.string.permission_rationale), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
    }
}
