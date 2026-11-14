package com.adu.ytqueue

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Invisible activity behind "Share → Add to YT Queue": asks which list, adds, and closes. */
class ShareActivity : AppCompatActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString ?: ""
        val link = YtLinks.parse(text)
        if (link == null) {
            App.toast("No YouTube link found in what was shared")
            finish()
            return
        }
        if (link.videoId == null && link.listId != null && YtLinks.isPrivateList(link.listId)) {
            App.toast("Watch Later and Liked videos are private on YouTube, so they can't be imported.", true)
            finish()
            return
        }
        chooseList(link)
    }

    private fun chooseList(link: YtLink) {
        val names = Store.lists.map { l ->
            val n = l.queue().size
            if (n > 0) "${l.name}  ($n)" else l.name
        }
        val title = if (link.videoId != null) "Add video to…" else "Import playlist into…"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems((names + "New list…").toTypedArray()) { _, which ->
                if (which == names.size) newList(link) else done(Store.lists[which], link)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun newList(link: YtLink) {
        val input = EditText(this).apply { hint = "List name"; setSingleLine() }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("New list")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "New list" }
                done(Store.addList(name), link)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun done(list: VList, link: YtLink) {
        if (handled) return
        handled = true
        if (link.videoId != null) {
            Actions.addVideo(list, link.videoId, Pos.END)
            App.toast("Added to ${list.name}")
        } else if (link.listId != null) {
            Actions.importPlaylist(list, link.listId)
        }
        finish()
    }
}
