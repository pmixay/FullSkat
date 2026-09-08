package com.fullskat.watch.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.fullskat.watch.FullSkatApp
import com.fullskat.watch.MainActivity
import com.fullskat.watch.R
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The swipe-left tile: one big circle that starts a new dialog and listens, plus the
 * last question so you remember where you left off.
 */
class FullSkatTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                val dao = FullSkatApp.container(this@FullSkatTileService).db.dialogDao()
                val last = runCatching { dao.lastUserMessage() }.getOrNull()
                val today = runCatching { dao.countDialogsSince(startOfToday()) }.getOrDefault(0)
                completer.set(buildTile(last, today))
            }
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder()
                    .setVersion(RESOURCES_VERSION)
                    .addIdToImageMapping(
                        ID_MIC,
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_mic).build()
                            )
                            .build()
                    )
                    .build()
            )
            "resources"
        }

    private fun startOfToday(): Long =
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun subtitleText(lastQuestion: String?, dialogsToday: Int): String = when {
        dialogsToday > 0 -> resources.getQuantityString(R.plurals.tile_dialogs_today, dialogsToday, dialogsToday)
        !lastQuestion.isNullOrBlank() -> getString(R.string.tile_last_prefix, lastQuestion.take(40))
        else -> getString(R.string.tile_hint)
    }

    private fun buildTile(lastQuestion: String?, dialogsToday: Int): TileBuilders.Tile {
        val accent = argb(ACCENT)
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("listen")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_LISTEN,
                                ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val title = LayoutElementBuilders.Text.Builder()
            .setText(getString(R.string.tile_title))
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(14f)).setColor(accent).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
            .build()

        val micDisc = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(84f))
            .setHeight(dp(84f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(accent)
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(42f)).build())
                            .build()
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.tile_hint)).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(ID_MIC)
                    .setWidth(dp(40f))
                    .setHeight(dp(40f))
                    .setColorFilter(LayoutElementBuilders.ColorFilter.Builder().setTint(argb(0xFF1A1200.toInt())).build())
                    .build()
            )
            .build()

        // Halo around the disc, drawn as a larger translucent circle behind it.
        val micButton = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(104f))
            .setHeight(dp(104f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(HALO))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(52f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(micDisc)
            .build()

        val subtitle = LayoutElementBuilders.Text.Builder()
            .setText(subtitleText(lastQuestion, dialogsToday))
            .setMaxLines(2)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(12f)).setColor(argb(SUBTLE)).build())
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(20f)).build()).build())
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(title)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(micButton)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(subtitle)
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build())
            .addContent(column)
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "2"
        private const val ID_MIC = "mic"
        private const val ACCENT = 0xFFFFB35C.toInt()
        private const val HALO = 0x24FFB35C
        private const val SUBTLE = 0xFF8A857C.toInt()

        fun requestUpdate(context: Context) {
            runCatching { getUpdater(context).requestUpdate(FullSkatTileService::class.java) }
        }
    }
}
