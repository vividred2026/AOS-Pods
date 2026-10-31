package pro.aospods.app

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.aospods.app.FocusAction.*

/** 착용 감지: '끼고 있는 개수' 변화 → 일시정지/재생 결정. */
class AutoPauseTest {
    private fun d(prev: Int?, now: Int, p: Boolean = true, r: Boolean = true) =
        WearDetector.decide(prev, now, p, r)

    @Test fun firstValueOnlySetsBaseline() = assertEquals(NONE, d(null, 2))
    @Test fun removeOneOfTwoPauses() = assertEquals(PAUSE, d(2, 1))
    @Test fun removeOnlyBudPauses() = assertEquals("한쪽만 끼고 듣다 빼도 일시정지", PAUSE, d(1, 0))
    @Test fun putBackResumes() = assertEquals(RESUME, d(1, 2))
    @Test fun putBackSingleResumes() = assertEquals(RESUME, d(0, 1))
    @Test fun noChangeNothing() = assertEquals(NONE, d(2, 2))
    @Test fun pauseOffSuppresses() = assertEquals(NONE, d(2, 1, p = false))
    @Test fun resumeOffSuppresses() = assertEquals(NONE, d(1, 2, r = false))
}

/** 오신호 방지: 같은 값이 2번 연속 와야 확정. */
class WearDetectorTest {
    private fun WearDetector.f(n: Int) = feed(n, autoPause = true, autoResume = true)

    @Test fun needsTwoConsecutiveToConfirm() {
        val w = WearDetector()
        assertEquals(NONE, w.f(2)); assertEquals(NONE, w.f(2))   // 기준 확정
        assertEquals("한 번만 오면 무시", NONE, w.f(1))
        assertEquals("두 번째에 일시정지", PAUSE, w.f(1))
        assertEquals("같은 상태 반복은 동작 없음", NONE, w.f(1))
        assertEquals(NONE, w.f(2)); assertEquals(RESUME, w.f(2))
    }

    @Test fun glitchIsIgnored() {
        val w = WearDetector()
        w.f(2); w.f(2)
        assertEquals(NONE, w.f(0))      // 순간 오신호
        assertEquals(NONE, w.f(2))
        assertEquals(NONE, w.f(2))      // 원래 값으로 돌아오면 아무 동작 없음
    }

    @Test fun confirmedEventsActImmediately() {
        val w = WearDetector()
        fun c(n: Int) = w.feed(n, autoPause = true, autoResume = true, confirmed = true)
        assertEquals("첫 확정값은 기준만", NONE, c(2))
        assertEquals("AACP 이벤트는 한 번에 일시정지", PAUSE, c(1))
        assertEquals(PAUSE, c(0))
        assertEquals("다시 끼면 바로 재생", RESUME, c(1))
        assertEquals(NONE, c(1))
    }

    @Test fun resetDropsBaseline() {
        val w = WearDetector()
        w.f(2); w.f(2); w.reset()
        w.f(1); assertEquals("리셋 후 첫 확정값은 기준만", NONE, w.f(1))
    }
}

class PercentIconTest {
    @Test fun roundsDownTo5() {
        assertEquals(85, PercentIcon.step5(87))
        assertEquals(100, PercentIcon.step5(100))
        assertEquals(0, PercentIcon.step5(3))
        assertEquals(55, PercentIcon.step5(55))
    }
}
