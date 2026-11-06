package com.example.cameraapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adapter: PhotoAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        emptyText = findViewById(R.id.emptyText)

        adapter = PhotoAdapter { item ->
            startActivity(Intent(this, ViewerActivity::class.java).setData(item.uri))
        }
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = this@GalleryActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        io.execute {
            val items = PhotoStore.annotated(this)
            runOnUiThread {
                adapter.submit(items)
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}

class PhotoAdapter(private val onClick: (PhotoItem) -> Unit) :
    RecyclerView.Adapter<PhotoAdapter.VH>() {

    private var items: List<PhotoItem> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.photoImage)
    }

    fun submit(newItems: List<PhotoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        Glide.with(holder.img).load(item.uri).centerCrop().into(holder.img)
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
