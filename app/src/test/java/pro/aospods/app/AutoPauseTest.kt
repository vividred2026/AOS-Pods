package pro.aospods.app

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.aospods.app.ScanService.FocusAction.*

/** 착용 감지: '끼고 있는 개수' 변화 → 일시정지/재생 결정. */
class AutoPauseTest {
    private fun d(prev: Int?, now: Int, p: Boolean = true, r: Boolean = true) =
        ScanService.decideFocus(prev, now, p, r)

    @Test fun firstValueOnlySetsBaseline() = assertEquals(NONE, d(null, 2))
    @Test fun removeOneOfTwoPauses() = assertEquals(PAUSE, d(2, 1))
    @Test fun removeOnlyBudPauses() = assertEquals("한쪽만 끼고 듣다 빼도 일시정지", PAUSE, d(1, 0))
    @Test fun putBackResumes() = assertEquals(RESUME, d(1, 2))
    @Test fun putBackSingleResumes() = assertEquals(RESUME, d(0, 1))
    @Test fun noChangeNothing() = assertEquals(NONE, d(2, 2))
    @Test fun pauseOffSuppresses() = assertEquals(NONE, d(2, 1, p = false))
    @Test fun resumeOffSuppresses() = assertEquals(NONE, d(1, 2, r = false))
}

class PercentIconTest {
    @Test fun roundsDownTo5() {
        assertEquals(85, PercentIcon.step5(87))
        assertEquals(100, PercentIcon.step5(100))
        assertEquals(0, PercentIcon.step5(3))
        assertEquals(55, PercentIcon.step5(55))
    }
}
