package pro.aospods.app

/** 착용 변화에 따른 오디오 포커스 동작. */
enum class FocusAction { PAUSE, RESUME, NONE }

/** STEP 3 착용 감지 판단 (안드로이드 의존 없음 — 단위 테스트 대상).
 *  같은 '끼고 있는 개수'가 2번 연속 와야 확정하고, 확정값이 바뀔 때만 동작을 돌려준다. */
class WearDetector {
    private var prevWorn: Int? = null      // 직전에 확정된 끼고 있는 개수(0~2)
    private var pendingWorn: Int? = null   // 오신호 방지용 후보값
    private var pendingCount = 0

    fun feed(wornNow: Int, autoPause: Boolean, autoResume: Boolean): FocusAction {
        if (wornNow != pendingWorn) { pendingWorn = wornNow; pendingCount = 1; return FocusAction.NONE }
        if (++pendingCount < 2) return FocusAction.NONE
        if (wornNow == prevWorn) return FocusAction.NONE
        val action = decide(prevWorn, wornNow, autoPause, autoResume)
        prevWorn = wornNow
        return action
    }

    fun reset() { prevWorn = null; pendingWorn = null; pendingCount = 0 }

    companion object {
        /** 끼고 있는 개수 변화 → 동작. 하나라도 빼면 일시정지, 다시 끼면 재생. 첫 확정값은 기준만 잡음. */
        fun decide(
            prevWorn: Int?, wornNow: Int,
            autoPause: Boolean, autoResume: Boolean,
        ): FocusAction = when {
            prevWorn == null -> FocusAction.NONE
            wornNow < prevWorn && autoPause -> FocusAction.PAUSE
            wornNow > prevWorn && autoResume -> FocusAction.RESUME
            else -> FocusAction.NONE
        }
    }
}
