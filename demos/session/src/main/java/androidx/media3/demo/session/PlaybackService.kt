/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.session

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.*
import androidx.media3.session.MediaSession.ControllerInfo
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {
  private val librarySessionCallback = CustomMediaLibrarySessionCallback()

  private lateinit var player: ExoPlayer
  private lateinit var mediaLibrarySession: MediaLibrarySession
  private lateinit var customCommands: List<CommandButton>

  private var customLayout = ImmutableList.of<CommandButton>()

  companion object {
    private const val SEARCH_QUERY_PREFIX_COMPAT = "androidx://media3-session/playFromSearch"
    private const val SEARCH_QUERY_PREFIX = "androidx://media3-session/setMediaUri"
    private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON =
      "android.media3.session.demo.SHUFFLE_ON"
    private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF =
      "android.media3.session.demo.SHUFFLE_OFF"
  }

  override fun onCreate() {
    super.onCreate()
    customCommands =
      listOf(
        getShuffleCommandButton(
          SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY)
        ),
        getShuffleCommandButton(
          SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY)
        )
      )
    customLayout = ImmutableList.of(customCommands[0])
    initializeSessionAndPlayer()
  }

  override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
    return mediaLibrarySession
  }

  override fun onDestroy() {
    player.release()
    mediaLibrarySession.release()
    super.onDestroy()
  }

  private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {

    override fun onConnect(
      session: MediaSession,
      controller: ControllerInfo
    ): MediaSession.ConnectionResult {
      val connectionResult = super.onConnect(session, controller)
      val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
      customCommands.forEach { commandButton ->
        // Add custom command to available session commands.
        commandButton.sessionCommand?.let { availableSessionCommands.add(it) }
      }
      return MediaSession.ConnectionResult.accept(
        availableSessionCommands.build(),
        connectionResult.availablePlayerCommands
      )
    }

    override fun onPostConnect(session: MediaSession, controller: ControllerInfo) {
      if (!customLayout.isEmpty() && controller.controllerVersion != 0) {
        // Let Media3 controller (for instance the MediaNotificationProvider) know about the custom
        // layout right after it connected.
        ignoreFuture(mediaLibrarySession.setCustomLayout(controller, customLayout))
      }
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle
    ): ListenableFuture<SessionResult> {
      if (CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON == customCommand.customAction) {
        // Enable shuffling.
        player.shuffleModeEnabled = true
        // Change the custom layout to contain the `Disable shuffling` command.
        customLayout = ImmutableList.of(customCommands[1])
        // Send the updated custom layout to controllers.
        session.setCustomLayout(customLayout)
      } else if (CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF == customCommand.customAction) {
        // Disable shuffling.
        player.shuffleModeEnabled = false
        // Change the custom layout to contain the `Enable shuffling` command.
        customLayout = ImmutableList.of(customCommands[0])
        // Send the updated custom layout to controllers.
        session.setCustomLayout(customLayout)
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
      session: MediaLibrarySession,
      browser: ControllerInfo,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
      return Futures.immediateFuture(LibraryResult.ofItem(MediaItemTree.getRootItem(), params))
    }

    override fun onGetItem(
      session: MediaLibrarySession,
      browser: ControllerInfo,
      mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
      val item =
        MediaItemTree.getItem(mediaId)
          ?: return Futures.immediateFuture(
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
          )
      return Futures.immediateFuture(LibraryResult.ofItem(item, /* params= */ null))
    }

    override fun onSubscribe(
      session: MediaLibrarySession,
      browser: ControllerInfo,
      parentId: String,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
      val children =
        MediaItemTree.getChildren(parentId)
          ?: return Futures.immediateFuture(
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
          )
      session.notifyChildrenChanged(browser, parentId, children.size, params)
      return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
      val children =
        MediaItemTree.getChildren(parentId)
          ?: return Futures.immediateFuture(
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
          )

      return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
    }

    override fun onAddMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
      val updatedMediaItems: List<MediaItem> =
        mediaItems.map { mediaItem ->
          if (mediaItem.requestMetadata.searchQuery != null)
            getMediaItemFromSearchQuery(mediaItem.requestMetadata.searchQuery!!)
          else MediaItemTree.getItem(mediaItem.mediaId) ?: mediaItem
        }
      return Futures.immediateFuture(updatedMediaItems)
    }

    private fun getMediaItemFromSearchQuery(query: String): MediaItem {
      // Only accept query with pattern "play [Title]" or "[Title]"
      // Where [Title]: must be exactly matched
      // If no media with exact name found, play a random media instead
      val mediaTitle =
        if (query.startsWith("play ", ignoreCase = true)) {
          query.drop(5)
        } else {
          query
        }

      return MediaItemTree.getItemFromTitle(mediaTitle) ?: MediaItemTree.getRandomItem()
    }
  }

  private fun initializeSessionAndPlayer() {
    player =
      ExoPlayer.Builder(this)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
        .build()
    MediaItemTree.initialize(assets)

    // Testing Issue #153
    player.addListener(playerListener)

    val sessionActivityPendingIntent =
      TaskStackBuilder.create(this).run {
        addNextIntent(Intent(this@PlaybackService, MainActivity::class.java))
        addNextIntent(Intent(this@PlaybackService, PlayerActivity::class.java))

        val immutableFlag = if (Build.VERSION.SDK_INT >= 23) FLAG_IMMUTABLE else 0
        getPendingIntent(0, immutableFlag or FLAG_UPDATE_CURRENT)
      }

    mediaLibrarySession =
      MediaLibrarySession.Builder(this, player, librarySessionCallback)
        .setSessionActivity(sessionActivityPendingIntent)
        .build()
    if (!customLayout.isEmpty()) {
      // Send custom layout to legacy session.
      mediaLibrarySession.setCustomLayout(customLayout)
    }
  }

  private fun getShuffleCommandButton(sessionCommand: SessionCommand): CommandButton {
    val isOn = sessionCommand.customAction == CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON
    return CommandButton.Builder()
      .setDisplayName(
        getString(
          if (isOn) R.string.exo_controls_shuffle_on_description
          else R.string.exo_controls_shuffle_off_description
        )
      )
      .setSessionCommand(sessionCommand)
      .setIconResId(if (isOn) R.drawable.exo_icon_shuffle_off else R.drawable.exo_icon_shuffle_on)
      .build()
  }

  private fun ignoreFuture(customLayout: ListenableFuture<SessionResult>) {
    /* Do nothing. */
  }


  // Testing Issue #153
  private val playerListener: Player.Listener = object : Player.Listener {
    override fun onMetadata(metadata: Metadata) {
      for (i in 0 until metadata.length()) {
        val entry = metadata[i]
        if (entry is IcyInfo) {
          Log.d("Issue #153", "IcyInfo title = ${entry.title}")
        } else if (entry is IcyHeaders) {
          Log.d("Issue #153", "IcyHeaders name = ${entry.name.toString()} genre = ${entry.genre}")
        } else {
          Log.d("Issue #153",  "Other metadata type received. (type = ${entry.javaClass.simpleName})")
        }
      }
      super.onMetadata(metadata)
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
      super.onMediaMetadataChanged(mediaMetadata)
      Log.d("Issue #153", "MediaMetadata title = ${mediaMetadata.title}")
      Log.d("Issue #153", "MediaMetadata station = ${mediaMetadata.station}")
      Log.d("Issue #153", "MediaMetadata genre = ${mediaMetadata.genre}")
    }
  }



}
