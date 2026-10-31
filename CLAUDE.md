# AosPods — 작업 인수인계 (Claude Code용)

> 2026-10-31 기준. 지금까지 진행한 내용, 현재 코드 상태, 남은 문제를 정리한 문서입니다.
> 프로젝트 루트에 `CLAUDE.md`로 두면 Claude Code가 자동으로 읽습니다.

---

## 1. 프로젝트 한 줄 요약

**안드로이드 폰과 에어팟을 페어링하고, 연결된 에어팟의 배터리·충전·착용 상태를 보여주는 앱.**

- 패키지: `pro.aospods.app` · 버전 1.0 (versionCode 1)
- minSdk 26 · targetSdk/compileSdk 34 · Kotlin · View + viewBinding · Material Components
- 앱 이름과 화면은 한국어 전용

---

## 2. 지금까지 진행 단계

| 단계 | 내용 | 결과 |
|---|---|---|
| 4 | **새 미니멀 앱 제작** (현재 작업) | 이 프로젝트 |

### Figma 참고 노드 (파일 키 `1Xt1OKjDlovDLJ7XUhT0WU`)
- `1153-600` 서비스 플로우차트 (STEP 0~6 + 백그라운드 X)
- `1170-670` 메인 설정 화면 와이어프레임
- `1170-855` 시스템 노출 UI — O-01 팝업 · O-02 알림/빠른 설정 타일
- ※ 사용자 메모: 앞으로의 Figma 작업 저장 위치는 **"2027 A.I." 파일** (`vh1QqL0j3qhZA8jDOCujKH`)

---


에어팟은 주변에 **Apple 제조사 데이터(회사 ID `0x004C`)** BLE 광고를 뿌립니다. 그중 **유형 `0x07`(Proximity Pairing, 길이 `0x19`)** 메시지에 배터리·충전·착용 정보가 들어 있습니다.

제조사 데이터를 16진수 문자열 `s`로 바꾼 뒤 **글자 위치**로 읽습니다 (`s[i]` = i번째 글자).

| 위치 | 의미 |
|---|---|
| `s[0..3] = 0719` | 유형 0x07, 길이 25 |
| `s[7] == 'E'` | AirPods Pro 여부 (모델 코드 일부) |
| `s[10]` 니블의 `0x02` 비트 | **좌우 반전 플래그** — 0이면 flip=true |
| `s[12]`, `s[13]` | 왼쪽/오른쪽 배터리 (flip에 따라 서로 바뀜) |
| `s[14]` | 충전 플래그: `0x1`/`0x2` = 각 유닛(flip 따라), `0x4` = 케이스 |
| `s[15]` | 케이스 배터리 |
| `s[10..11]` 바이트를 8비트 2진수로 → 4번째/6번째 글자 | 왼쪽/오른쪽 **착용 여부** (flip 따라) |

**배터리 값 변환**: 0~9 → `n×10+5` %, 10 → 100 %, 그 외(15 등) → 없음(0)
→ 10% 단위 근사치입니다. 구현: `AirPods.kt`의 `AirPodsParser.parse()` (단위 테스트 있음)

> ⚠️ 실기기 확인 결과: 애플 광고는 여러 메시지가 `[유형][길이][내용]`으로 **이어붙어 올 수 있어서**, `findProximity()`가 패킷 전체에서 `0x07` 메시지를 찾아 꺼내도록 고쳤습니다.

---

## 4. 현재 코드 구조

```
app/src/main/java/pro/aospods/app/
├─ MainActivity.kt          메인 설정 화면(M-00), 권한 카드, 에어팟 연결(자체 기기 검색·페어링),
│                           진단 정보, 크래시 로그 표시
├─ ScanService.kt           포그라운드 서비스(connectedDevice): BLE 스캔, 배터리 반영(알림·팝업·타일),
│                           착용 감지 자동 일시정지/재생(오디오 포커스), 진단 카운터
│                           + PodsRepo(앱 전역 상태: pods/running/error/status/진단값)
├─ BtState.kt               연결된 오디오 기기 이름, HFP 전체 배터리, 연결/해제/배터리 방송 수신
├─ AirPods.kt               Pods 데이터 클래스 + AirPodsParser(findProximity/messageTypes/parse)
├─ PodsOverlay.kt           시스템 오버레이 배터리 팝업(O-01), 닫기(X) 버튼
├─ PercentIcon.kt           상태바용 퍼센트 숫자 아이콘(5% 단위) 생성
├─ OnOffTileService.kt      빠른 설정 타일 — 서비스 ON/OFF (O-02)
├─ Prefs.kt                 모든 설정값(SharedPreferences "aospods")
├─ AosApp.kt                크래시 시 스택트레이스를 파일로 저장 → 다음 실행 때 대화상자로 표시
├─ NotifSettingsActivity.kt     N-01 알림 설정(큰 알림/작은 알림)
├─ PercentSettingsActivity.kt   N-02 아이콘 퍼센트 기준 · 연결 끊겨도 표시
└─ PopupSettingsActivity.kt     N-03 팝업 제목·글자 크기·모서리·여백·어둡게·양쪽 착용 시 숨김

app/src/test/  — 단위 테스트 + Robolectric (총 26개, 전부 통과)
  AirPodsParserTest(+ProximityTlvTest) · AutoPauseTest(+PercentIconTest) · NotificationTest
  InteractionTest · SmokeTest · TileTest
```

### 메인 화면 구성 (위에서부터)
1. **에어팟 연결** — 상태("연결됨 · AirPods · 배터리 80%") + [연결하기/다시 연결]
2. 서비스 활성화
3. 모델 (에어팟 1·2 / 에어팟 프로 / 파워비츠 프로 / 에어팟 3 / 에어팟 4)
4. **배터리 표시**: 알림으로 표시 · 알림 아이콘에 퍼센트 표시 · 팝업 창으로 표시 (글자를 누르면 각 하위 설정, 스위치는 ON/OFF)
5. **착용 감지**: 일시정지 · 다시 재생
6. 정보: 다크 모드 · 진단 정보 · 개인정보처리방침

### 권한
`BLUETOOTH_SCAN`(neverForLocation) · `BLUETOOTH_CONNECT` · `ACCESS_FINE/COARSE_LOCATION` · `POST_NOTIFICATIONS` · `SYSTEM_ALERT_WINDOW`(팝업) · `FOREGROUND_SERVICE(_CONNECTED_DEVICE)`

---

## 5. 배터리 정보를 받는 두 경로

| 경로 | 언제 | 무엇을 | 코드 |
|---|---|---|---|
| **BLE 광고 (0x07)** | 주로 **케이스 뚜껑을 열 때** | 왼쪽·오른쪽·케이스 따로, 충전, 착용 | `ScanService` 콜백 → `AirPodsParser` |
| **HFP 배터리** | **연결된 상태**(귀에 낀 상태 포함) | **전체 배터리 하나** | `BtState` — `BATTERY_LEVEL_CHANGED` 방송 + `getBatteryLevel()`(숨김 API, 반사 호출) |

- 데모(가짜 85/85/55) 표시는 **완전히 제거**했습니다. 사용자가 가짜 값을 실제로 오인한 적이 있어서, 다시 넣지 마세요.

---

## 6. 에어팟 페어링 (사용자가 밝힌 앱의 주된 목표)

- [연결하기] → 페어링 모드 안내 → **앱 자체 블루투스 기기 검색**(`BluetoothAdapter.startDiscovery`)
- 목록 규칙 (사용자 요구사항):
  - **이름이 확인된 기기만 이름으로 표시** — 이름을 바꾼 에어팟은 바꾼 이름으로 나와야 함
  - **MAC 주소는 절대 노출하지 않음** (안드로이드 기본 CompanionDeviceManager 창은 MAC을 보여줘서 폐기함)
  - 정렬: 🎧 에어팟·비츠 → 🔈 오디오 기기 → 기타, 이미 페어링된 기기는 "(페어링됨)"
  - 검색 종료 시 제목에 "발견 N · 이름 확인 M" 진단 표시, [다시 검색] · [블루투스 설정]
- 선택 시 `createBond()`. 이미 페어링된 기기는 블루투스 설정 화면으로 안내.
- ✅ 실기기(삼성)에서 **페어링 성공 확인**됨 ("연결됨 · AirPods")

---

## 7. 현재 상태와 남은 문제 (우선순위 순)

### 🔴 1. 착용 감지가 동작하지 않음
- 원인: 착용 정보는 **BLE 0x07 광고 안에만** 있는데, 에어팟을 **귀에 끼고 연결·재생 중일 때 0x07이 0건** 수신됨
  (실측: BLE 6182 · 애플 2122 · **07 0**). HFP 배터리에는 착용 정보가 없음.
- 판단 로직 자체는 테스트 완료: "끼고 있는 개수" 기준, 하나라도 빼면 일시정지 / 다시 끼면 재생, 같은 상태 2회 연속일 때만 확정, 포커스를 음악 앱에 뺏기면 상태 초기화.
- **다음 할 일**: 사용자에게 요청한 테스트 결과 확인 — 귀에 낀 채 한쪽을 뺐다 끼울 때 [진단 정보]의 `07` 수가 늘어나는지.
  - 늘어나면 → 해석·판단 쪽 수정으로 해결 가능
  - 계속 0이면 → 루팅 없이 일반 앱으로는 불가(애플 전용 AAP 프로토콜 필요). 스위치에 "이 기기에서는 지원되지 않음" 표시로 정리

### 🟠 2. 왼쪽 배터리가 안 나오는 경우가 있었음
- 케이스를 연 상태에서 오른쪽·케이스만 표시된 적 있음. 원문 패킷 확인 필요 → [진단 정보] → [복사] 내용으로 판정.

### 🟡 3. 진단용 코드가 아직 켜져 있음 (정식 빌드 전 정리 필요)
- 진단 카운터·`lastRawHex`·[진단 정보] 화면 — 유지할지 숨길지 결정.

### 🟢 4. 플로우차트 대비 미구현 (의도적 또는 보류)
- STEP 1 **부팅·블루투스 ON 시 서비스 자동 시작 리시버** — 미구현 (`RECEIVE_BOOT_COMPLETED` 추가 여부 사용자 결정 대기)
- STEP 4 탭 제스처(어시스턴트·단축 통화) — 사용자 요청으로 **제거**
- STEP 6 평가 다이얼로그 — 비핵심이라 제외
- O-02 알림의 L/R/K 칩 디자인(커스텀 RemoteViews) — 미적용 (팝업만 반영)

---

## 8. 해결한 주요 버그 기록 (재발 방지용)

| 증상 | 원인 | 수정 |
|---|---|---|
| 모든 스위치가 안 눌림 | `RowSwitch` 스타일에 `android:clickable="false"` | true로 변경 + 탭 회귀 테스트 |
| 팝업 닫기 불가 | 닫기 버튼 없음 | X 버튼 + `dismissed` 플래그(다음 hide() 때 초기화) |
| 실폰에서 가짜 85/85/55 | 6초 내 못 찾으면 데모 표시 | 데모 완전 제거 |
| 앱이 죽음 | 표시 단계 예외 | 스캔 콜백·deliver를 try/catch로 격리 + 크래시 로그 저장/표시 |
| 에어팟 0x07 0건 | 맨 앞 바이트가 07일 때만 해석 | 패킷 전체에서 0x07 메시지 탐색 |
| 팝업 오른쪽 잘림 | 여백을 `lp.x`로 줘서 창이 밀림 | 창 너비 = 화면폭-2×여백, 가운데 정렬 |
| 퍼센트 아이콘이 안 바뀜 | 상태바 아이콘이 고정 그림 | `PercentIcon`으로 숫자 비트맵 아이콘 |
| "연결 끊겨도 표시" 무효 | 데모 제거 후 미사용 | 끊김 시 마지막 값 유지 |
| 일시정지가 한쪽 착용 시 안 됨 / 한 번 뒤 영구 정지 | 양쪽 착용 기준 · 포커스 상실 미처리 | 착용 개수 기준 · `AUDIOFOCUS_LOSS` 시 초기화 |

---

## 9. 빌드 · 테스트

```bash
# 테스트 + 린트 + 디버그 APK
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 결과물: app/build/outputs/apk/debug/app-debug.apk (디버그 서명)
```

- 원래 작업 환경엔 **gradle wrapper가 없어** 시스템 Gradle(8.14.3)로 빌드했습니다. 로컬에서 쓰려면 `gradle wrapper --gradle-version 8.9` 한 번 실행하거나 Android Studio로 여세요.
- 소스 zip에는 `local.properties`를 뺐습니다. Android Studio로 열면 SDK 경로가 자동으로 잡힙니다.
- AGP 8.5.2 · Kotlin Android 플러그인 · JDK 17
- 실기기 설치: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

### 실기기 확인 팁
- 앱 아래쪽 **[진단 정보]** → 연결 기기, HFP 배터리, BLE/애플/07 수신 수, 마지막 0x07 원문과 글자별 해석값 → [복사]
- 크래시가 나면 다음 실행 때 "이전 비정상 종료 로그" 대화상자 → [복사]
- logcat: `adb logcat | grep -i aospods`

---

## 10. 작업 원칙 (지금까지 사용자와 합의된 것)

- 답변·UI 문구는 **한국어**
- **안 되는 기능은 빼고**, 되는 기능만 미니멀하게
- **가짜/데모 값은 실제처럼 보이게 표시하지 않기**
- 기기 목록에 **MAC 주소 노출 금지**, 이름으로 표시
- Figma 와이어프레임(위 노드)을 디자인 기준으로 삼기
