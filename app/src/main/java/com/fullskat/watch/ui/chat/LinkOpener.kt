package com.fullskat.watch.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.concurrent.futures.await
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.fullskat.core.net.WebSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens a web link the way Wear OS expects: in the browser of the paired phone (RemoteActivityHelper sends an
 * ACTION_VIEW + CATEGORY_BROWSABLE intent there), else in a browser on the watch if one is installed.
 */
class LinkOpener(private val context: Context) {
    enum class Result {
        /** Sent to the phone: show the "opened on phone" confirmation. */
        PHONE,

        /** A browser on the watch took it. */
        WATCH,

        /** No phone in reach and no browser on the watch. */
        NO_PHONE,

        /** The phone could not be asked (or refused) and the watch has no browser either. */
        FAILED,
    }

    // Created on first use only: nothing touches the Wear remote-interaction services until a link is tapped.
    private val remote by lazy { RemoteActivityHelper(context.applicationContext, Dispatchers.IO.asExecutor()) }

    suspend fun open(url: String): Result {
        if (!WebSources.isWebUrl(url)) return Result.FAILED
        val uri = Uri.parse(url)
        val status = phoneStatus()
        // STATUS_UNKNOWN is what watches report that cannot tell: try the phone anyway, as the docs advise.
        val phoneMayAnswer = status == RemoteActivityHelper.STATUS_AVAILABLE || status == RemoteActivityHelper.STATUS_UNKNOWN
        if (phoneMayAnswer && openOnPhone(uri)) return Result.PHONE
        if (openOnWatch(uri)) return Result.WATCH
        return if (phoneMayAnswer) Result.FAILED else Result.NO_PHONE
    }

    private suspend fun phoneStatus(): Int = try {
        withTimeoutOrNull(STATUS_TIMEOUT_MS) { remote.availabilityStatus.first() } ?: RemoteActivityHelper.STATUS_UNKNOWN
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Phone availability unknown", e)
        RemoteActivityHelper.STATUS_UNKNOWN
    }

    private suspend fun openOnPhone(uri: Uri): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
        withTimeout(SEND_TIMEOUT_MS) { remote.startRemoteActivity(intent).await() }
        true
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "Phone did not answer in time")
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { // RemoteIntentException: no phone, phone app missing, etc.
        Log.w(TAG, "Could not open the link on the phone", e)
        false
    }

    private fun openOnWatch(uri: Uri): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val TAG = "LinkOpener"
        const val STATUS_TIMEOUT_MS = 2_000L
        const val SEND_TIMEOUT_MS = 10_000L
    }
}
