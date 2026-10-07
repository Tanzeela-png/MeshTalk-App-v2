package com.example.meshtalk

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onItemLongClick: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val VIEW_TYPE_SENT = 1
    private val VIEW_TYPE_RECEIVED = 2

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == "Me") VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layout = if (viewType == VIEW_TYPE_SENT) {
            R.layout.item_message_me
        } else {
            R.layout.item_message_partner
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ChatViewHolder(view, parent.context)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message, onItemLongClick)

        holder.itemView.setOnLongClickListener {
            if (!message.messageText.startsWith("🚫")) {
                onItemLongClick(message)
            }
            true
        }
    }

    override fun getItemCount(): Int = messages.size

    class ChatViewHolder(itemView: View, private val context: Context) : RecyclerView.ViewHolder(itemView) {
        private val txtMessage: TextView = itemView.findViewById(R.id.tvMessageText)
        private val imgMessage: ImageView = itemView.findViewById(R.id.ivMessageImage)
        private val txtTime: TextView = itemView.findViewById(R.id.tvMessageTime)
        private val imgTick: ImageView? = itemView.findViewById(R.id.ivTickStatus)

        fun bind(message: ChatMessage, onLongClick: (ChatMessage) -> Unit) {

            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val formattedTime = sdf.format(Date(message.timestamp))
            txtTime.text = formattedTime

            if (message.messageText.startsWith("🚫")) {
                txtMessage.visibility = View.VISIBLE
                imgMessage.visibility = View.GONE
                txtMessage.text = message.messageText
                txtMessage.setTextColor(android.graphics.Color.GRAY)
                imgTick?.visibility = View.GONE
            }
            else if (message.messageText.startsWith("IMG_DATA:")) {
                txtMessage.visibility = View.GONE
                imgMessage.visibility = View.VISIBLE

                try {
                    val base64PureString = message.messageText.substringAfter("IMG_DATA:")
                    val decodedBytes = Base64.decode(base64PureString, Base64.NO_WRAP)

                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, options)

                    if (bitmap != null) {
                        imgMessage.setImageBitmap(bitmap)
                        imgMessage.setOnClickListener {
                            showFullScreenImageDialog(bitmap)
                        }
                        imgMessage.setOnLongClickListener {
                            onLongClick(message)
                            true
                        }
                    } else {
                        imgMessage.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    imgMessage.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                handleTickStatus(message)
            }
            else {
                txtMessage.visibility = View.VISIBLE
                imgMessage.visibility = View.GONE

                if (message.senderId == "Me") {
                    txtMessage.setTextColor(android.graphics.Color.WHITE)
                } else {
                    txtMessage.setTextColor(android.graphics.Color.BLACK)
                }
                txtMessage.text = message.messageText
                handleTickStatus(message)
            }
        }

        private fun handleTickStatus(message: ChatMessage) {
            if (message.senderId == "Me" && imgTick != null) {
                imgTick.visibility = View.VISIBLE
                when (message.status) {
                    "READ" -> imgTick.setImageResource(R.drawable.ic_double_tick_blue)
                    "SENT" -> imgTick.setImageResource(R.drawable.ic_double_tick_grey)
                    "PENDING" -> imgTick.setImageResource(R.drawable.ic_clock)
                    else -> imgTick.visibility = View.GONE
                }
            } else {
                imgTick?.visibility = View.GONE
            }
        }

        private fun showFullScreenImageDialog(bitmap: Bitmap) {
            val builder = AlertDialog.Builder(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val mainLayout = android.widget.RelativeLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            val fullImageView = ImageView(context).apply {
                setImageBitmap(bitmap)
                val params = android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                    android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
                }
                layoutParams = params
                adjustViewBounds = true
            }

            val btnSave = Button(context).apply {
                text = "Save to Gallery"
                setBackgroundColor(android.graphics.Color.parseColor("#0088CC"))
                setTextColor(android.graphics.Color.WHITE)
                val params = android.widget.RelativeLayout.LayoutParams(
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                    addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                    setMargins(0, 0, 0, 100)
                }
                layoutParams = params
            }

            mainLayout.addView(fullImageView)
            mainLayout.addView(btnSave)

            builder.setView(mainLayout)
            val alertDialog = builder.create()

            btnSave.setOnClickListener {
                saveBitmapToGallery(bitmap)
                alertDialog.dismiss()
            }

            fullImageView.setOnClickListener {
                alertDialog.dismiss()
            }

            alertDialog.show()
        }

        private fun saveBitmapToGallery(bitmap: Bitmap) {
            val filename = "MeshTalk_${System.currentTimeMillis()}.jpg"
            var fos: OutputStream? = null
            var imageUri: Uri? = null
            val contentResolver = context.contentResolver

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MeshTalk")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) {
                    fos = contentResolver.openOutputStream(imageUri)
                    if (fos != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                        fos.flush()
                        fos.close()
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(imageUri, contentValues, null, null)
                    }

                    Toast.makeText(context, "Image saved to Gallery successfully! 🎉", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}