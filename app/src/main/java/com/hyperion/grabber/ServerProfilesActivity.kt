package com.hyperion.grabber

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ServerProfilesActivity : AppCompatActivity() {
    private lateinit var store: ServerProfileStore
    private lateinit var profileList: ListView
    private var profiles: List<ServerProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerProfileStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val description = TextView(this).apply {
            text = getString(R.string.profiles_description)
            setPadding(0, 0, 0, 16)
        }
        profileList = ListView(this)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val save = Button(this).apply {
            text = getString(R.string.profiles_save_current)
            setOnClickListener { showSaveDialog() }
        }
        val close = Button(this).apply {
            text = getString(R.string.profiles_close)
            setOnClickListener { finish() }
        }
        buttons.addView(save, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(close, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(description, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(profileList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        setTitle(getString(R.string.profiles_title))

        profileList.setOnItemClickListener { _, _, position, _ ->
            val profile = profiles.getOrNull(position) ?: return@setOnItemClickListener
            store.activate(profile)
            Toast.makeText(this, R.string.profiles_activated, Toast.LENGTH_SHORT).show()
            finish()
        }
        profileList.setOnItemLongClickListener { _, _, position, _ ->
            val profile = profiles.getOrNull(position) ?: return@setOnItemLongClickListener true
            confirmDelete(profile)
            true
        }
        refresh()
    }

    private fun refresh() {
        profiles = store.ensureCurrentProfile()
        val activeId = store.activeId()
        val labels = if (profiles.isEmpty()) {
            listOf(getString(R.string.profiles_no_profiles))
        } else {
            profiles.map { profile ->
                val active = if (profile.id == activeId) {
                    getString(R.string.profiles_active)
                } else {
                    ""
                }
                "${profile.name}$active\n${profile.host}:${profile.port} · priority ${profile.priority}"
            }
        }
        profileList.adapter = ArrayAdapter(this,
            android.R.layout.simple_list_item_1, labels)
    }

    private fun showSaveDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.profiles_name_hint)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_name_title))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profiles_save) { _, _ ->
                val profile = store.saveCurrent(input.text.toString())
                if (profile == null) {
                    Toast.makeText(this, R.string.profiles_invalid, Toast.LENGTH_LONG).show()
                } else {
                    refresh()
                    Toast.makeText(this, R.string.profiles_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmDelete(profile: ServerProfile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_delete_title))
            .setMessage(profile.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                store.delete(profile)
                refresh()
                Toast.makeText(this, R.string.profiles_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
