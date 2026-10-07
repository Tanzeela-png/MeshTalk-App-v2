package com.example.meshtalk

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class CircularImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return

        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        val scaledBitmap = getCroppedBitmap(bitmap, width)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
    }

    private fun getCroppedBitmap(bmp: Bitmap, radius: Int): Bitmap {
        val sbmp = if (bmp.width != radius || bmp.height != radius) {
            Bitmap.createScaledBitmap(bmp, radius, radius, false)
        } else bmp

        val output = Bitmap.createBitmap(sbmp.width, sbmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val rect = Rect(0, 0, sbmp.width, sbmp.height)

        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(sbmp.width / 2f, sbmp.height / 2f, sbmp.width / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(sbmp, rect, rect, paint)
        return output
    }
}