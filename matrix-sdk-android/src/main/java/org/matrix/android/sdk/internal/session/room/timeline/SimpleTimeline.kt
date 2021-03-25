/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.task.SemaphoreCoroutineSequencer
import org.matrix.android.sdk.internal.util.createBackgroundHandler
import org.matrix.android.sdk.internal.util.debug.measureTimeData
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SimpleTimeline internal constructor(val roomId: String,
                                          private val realmConfiguration: RealmConfiguration,
                                          paginationTask: PaginationTask,
                                          getEventTask: GetContextOfEventTask,
                                          fetchTokenAndPaginateTask: FetchTokenAndPaginateTask,
                                          timelineEventMapper: TimelineEventMapper,
                                          timelineInput: TimelineInput,
                                          eventDecryptor: TimelineEventDecryptor) {
    interface Listener {
        fun onStateUpdated()
        fun onEventsUpdated(snapshot: List<TimelineEvent>)
        fun onNewTimelineEvents(eventIds: List<String>)
    }

    companion object {
        val BACKGROUND_HANDLER = createBackgroundHandler("SimpleTimeline_Thread")
    }

    val timelineId = UUID.randomUUID().toString()

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val isStarted = AtomicBoolean(false)
    private val forwardState = AtomicReference(PaginationState())
    private val backwardState = AtomicReference(PaginationState())

    private val backgroundRealm = AtomicReference<Realm>()
    private val timelineDispatcher = BACKGROUND_HANDLER.asCoroutineDispatcher()
    private val timelineScope = CoroutineScope(SupervisorJob() + timelineDispatcher)
    private val sequencer = SemaphoreCoroutineSequencer()

    private val strategyDependencies = LoadTimelineStrategy.Dependencies(
            eventDecryptor = eventDecryptor,
            paginationTask = paginationTask,
            fetchTokenAndPaginateTask = fetchTokenAndPaginateTask,
            timelineInput = timelineInput,
            timelineEventMapper = timelineEventMapper,
            realm = backgroundRealm,
            getContextOfEventTask = getEventTask,
            onEventsUpdated = this::postSnapshot,
            onNewTimelineEvents = this::onNewTimelineEvents
    )
    private var strategy: LoadTimelineStrategy = buildStrategy(LoadTimelineStrategy.Mode.Default)

    val isLive: Boolean
        get() = !getPaginationState(Direction.FORWARDS).hasMoreToLoad

    data class PaginationState(
            val hasMoreToLoad: Boolean = true,
            val loading: Boolean = false,
            val inError: Boolean = false
    )

    enum class Direction {
        FORWARDS,
        BACKWARDS
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        postSnapshot()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun removeAllListeners() {
        listeners.clear()
    }

    fun start() = timelineScope.launch {
        sequencer.post {
            if (isStarted.compareAndSet(false, true)) {
                val realm = Realm.getInstance(realmConfiguration)
                updateState(Direction.FORWARDS) {
                    it.copy(loading = false, hasMoreToLoad = false)
                }
                backgroundRealm.set(realm)
                strategy.onStart()
            }
        }
    }

    fun stop() = timelineScope.launch {
        sequencer.post {
            if (isStarted.compareAndSet(true, false)) {
                strategy.onStop()
                backgroundRealm.get().closeQuietly()
            }
        }
    }

    fun getIndexOfEvent(eventId: String): Int? {
        return strategy.getBuiltEventIndex(eventId)
    }

    fun getPaginationState(direction: Direction): PaginationState {
        return if (direction == Direction.BACKWARDS) {
            backwardState
        } else {
            forwardState
        }.get()
    }

    suspend fun loadMore(count: Long, direction: Direction) = withContext(timelineDispatcher) {
        val baseLogMessage = "loadMore(count: $count, direction: $direction, roomId: $roomId)"
        sequencer.post {
            Timber.v("$baseLogMessage started")
            if (!isStarted.get()) {
                throw IllegalStateException("You should call start before using timeline")
            }
            val currentState = getPaginationState(direction)
            if (!currentState.hasMoreToLoad) {
                Timber.v("$baseLogMessage : nothing more to load")
                return@post
            }
            if (currentState.loading) {
                Timber.v("$baseLogMessage : already loading")
                return@post
            }
            updateState(direction) {
                it.copy(loading = true)
            }
            val loadMoreResult = strategy.loadMore(count, direction)
            val hasMoreToLoad = loadMoreResult != LoadMoreResult.REACHED_END
            updateState(direction) {
                it.copy(loading = false, hasMoreToLoad = hasMoreToLoad)
            }
            postSnapshot()
        }
    }

    suspend fun resetToLive() = openAround(null)

    suspend fun openAround(eventId: String?) = withContext(timelineDispatcher) {
        val baseLogMessage = "openAround(eventId: $eventId)"
        sequencer.post {
            Timber.v("$baseLogMessage started")
            if (!isStarted.get()) {
                throw IllegalStateException("You should call start before using timeline")
            }
            strategy.onStop()
            strategy = if (eventId == null) {
                buildStrategy(LoadTimelineStrategy.Mode.Default)
            } else {
                buildStrategy(LoadTimelineStrategy.Mode.Permalink(eventId))
            }
            updateState(Direction.FORWARDS) {
                it.copy(loading = false, hasMoreToLoad = eventId != null)
            }
            updateState(Direction.BACKWARDS) {
                it.copy(loading = false, hasMoreToLoad = true)
            }
            strategy.onStart()
            postSnapshot()
        }
    }

    private fun postSnapshot() = timelineScope.launch {
        val snapshot = measureTimeData { strategy.buildSnapshot() }
        Timber.v("Building snapshot took ${snapshot.duration} ms")
        withContext(Dispatchers.Main) {
            listeners.forEach {
                tryOrNull { it.onEventsUpdated(snapshot.data) }
            }
        }
    }

    private suspend fun updateState(direction: Direction, update: (PaginationState) -> PaginationState) {
        val stateReference = when (direction) {
            Direction.FORWARDS -> forwardState
            Direction.BACKWARDS -> backwardState
        }
        val currentValue = stateReference.get()
        val newValue = update(currentValue)
        stateReference.set(newValue)
        withContext(Dispatchers.Main) {
            listeners.forEach {
                tryOrNull { it.onStateUpdated() }
            }
        }
    }

    private fun onNewTimelineEvents(eventIds: List<String>) = timelineScope.launch(Dispatchers.Main) {
        listeners.forEach {
            tryOrNull { it.onNewTimelineEvents(eventIds) }
        }
    }

    private fun buildStrategy(mode: LoadTimelineStrategy.Mode): LoadTimelineStrategy {
        return LoadTimelineStrategy(
                roomId = roomId,
                timelineId = timelineId,
                mode = mode,
                dependencies = strategyDependencies
        )
    }

}
