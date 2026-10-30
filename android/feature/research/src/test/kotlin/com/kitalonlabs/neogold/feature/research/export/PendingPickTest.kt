package com.kitalonlabs.neogold.feature.research.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BUG-083 item 30: a document the picker created is never left empty on the drive because the screen was gone. */
class PendingPickTest {

    private val discarded = mutableListOf<String>()
    private val chosen = mutableListOf<String>()
    private val pick = PendingPick<String> { discarded += it }

    @Test
    fun tcExp01_aResultForAWaitingScreenGoesToTheScreenAndIsNotDiscarded_BUG_083() {
        pick.expect { chosen += it }
        pick.deliver("content://drive/export.zip")

        assertThat(chosen).containsExactly("content://drive/export.zip")
        assertThat(discarded).isEmpty()
    }

    @Test
    fun tcExp01_aResultThatArrivesAfterTheScreenWasLeftOrLockedIsDiscarded_BUG_083() {
        pick.expect { chosen += it }
        pick.cancel() // the research session locked and the screen left the composition
        pick.deliver("content://drive/export.zip")

        assertThat(chosen).isEmpty()
        assertThat(discarded).containsExactly("content://drive/export.zip")
    }

    @Test
    fun tcExp01_aResultWithNoRequestAtAllIsDiscarded_BUG_083() {
        pick.deliver("content://drive/orphan.zip")

        assertThat(discarded).containsExactly("content://drive/orphan.zip")
    }

    @Test
    fun tcExp01_theWorkerBackingOutCreatesNothingAndDiscardsNothing_BUG_083() {
        pick.expect { chosen += it }
        pick.deliver(null)

        assertThat(chosen).isEmpty()
        assertThat(discarded).isEmpty()
    }

    @Test
    fun tcExp01_oneRequestAnswersOnce_aSecondResultIsDiscarded_BUG_083() {
        pick.expect { chosen += it }
        pick.deliver("first")
        pick.deliver("second")

        assertThat(chosen).containsExactly("first")
        assertThat(discarded).containsExactly("second")
    }

    @Test
    fun tcExp01_aNewerRequestReplacesAnOlderOne_BUG_083() {
        val old = mutableListOf<String>()
        pick.expect { old += it }
        pick.expect { chosen += it }
        pick.deliver("x")

        assertThat(old).isEmpty()
        assertThat(chosen).containsExactly("x")
    }

    @Test
    fun tcExp01_aPlaceTheScreenWillNotUseCanBeRejected_BUG_083() {
        pick.reject("content://drive/late.zip")

        assertThat(discarded).containsExactly("content://drive/late.zip")
    }
}
