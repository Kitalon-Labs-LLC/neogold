package com.kitalonlabs.neogold.feature.onboarding.domain

import com.kitalonlabs.neogold.engine.camera.device.DeviceCapabilities
import com.kitalonlabs.neogold.engine.camera.host.CameraSession
import com.kitalonlabs.neogold.engine.camera.qualification.QualificationResult
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.vision.ImageFrame
import com.kitalonlabs.neogold.engine.vision.QualityReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three qualification photos, one at a time, and what happens to each (S07, NG-007). */
class QualificationState(
    private val service: QualificationService,
    private val capabilities: DeviceCapabilities,
    private val cardBatchId: String,
    private val scope: CoroutineScope,
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {
    sealed interface Phase {
        /** Waiting for photo number [taken] + 1. [problem] says why the last photo was not used. */
        data class Photographing(
            val taken: Int,
            val problem: List<QualityReasonCode> = emptyList(),
            val cameraFailed: Boolean = false,
            /** The three photos were judged but the result could not be saved; they are discarded and taken again. */
            val notSaved: Boolean = false
        ) : Phase

        /** A photo is being taken and checked. */
        data object CheckingPhoto : Phase

        /** All three photos are in and the camera's colors are being judged. */
        data object Judging : Phase

        data class Done(val result: QualificationResult) : Phase

        /** The scanned card is not on the phone any more, so there is nothing to compare the photos with. */
        data object CardMissing : Phase
    }

    private val photos = mutableListOf<ImageFrame>()
    private var reference: Map<String, Lab>? = null
    private val _phase = MutableStateFlow<Phase>(Phase.CheckingPhoto)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    init {
        scope.launch {
            reference = service.referenceOf(cardBatchId)
            _phase.value = if (reference == null) Phase.CardMissing else Phase.Photographing(taken = 0)
        }
    }

    /** Drops any photos still held. The screen calls this when it leaves, so card photos do not outlive it. */
    fun release() {
        photos.clear()
    }

    /** Takes the next photo with [session]. Ignored while another photo is being handled. */
    @Suppress("TooGenericExceptionCaught") // any camera failure is shown as "could not be taken"; the user retries
    fun takePhoto(session: CameraSession) {
        val current = _phase.value as? Phase.Photographing ?: return
        if (!_phase.compareAndSet(current, Phase.CheckingPhoto)) return
        scope.launch {
            _phase.value = try {
                handle(session, current.taken)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Phase.Photographing(taken = photos.size, cameraFailed = true)
            }
        }
    }

    private suspend fun handle(session: CameraSession, takenBefore: Int): Phase {
        val frame = session.captureFrame().frame
        val check = withContext(cpu) { service.checkPhoto(frame, checkNotNull(reference)) }
        if (check is PhotoCheck.Retake) return Phase.Photographing(takenBefore, check.reasons)
        photos.add(frame)
        return if (photos.size < PHOTOS_NEEDED) Phase.Photographing(photos.size) else judge()
    }

    /** Judges the three photos and keeps the result. The photos are dropped whether or not it could be saved. */
    @Suppress("TooGenericExceptionCaught") // a storage failure is shown as "not saved"; the user retakes the photos
    private suspend fun judge(): Phase {
        _phase.value = Phase.Judging
        val result = try {
            withContext(cpu) { service.qualify(photos.toList(), capabilities, cardBatchId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        photos.clear()
        return if (result == null) Phase.Photographing(taken = 0, notSaved = true) else Phase.Done(result)
    }

    private companion object {
        const val PHOTOS_NEEDED = 3
    }
}
