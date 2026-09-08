package com.fullskat.watch.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.fullskat.watch.MainActivity
import com.fullskat.watch.R

/** Round watch-face widget: a mic that opens a fresh dialog and listens. Static, so it never wakes the CPU. */
class FullSkatComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = build(request.complicationType)

    private fun build(type: ComplicationType): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_mic)
        val description = PlainComplicationText.Builder(getString(R.string.app_name)).build()
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(icon).build(), description)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), description)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(getString(R.string.complication_short)).build(), description)
                    .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                    .setTapAction(tap)
                    .build()
            else -> null
        }
    }
}
