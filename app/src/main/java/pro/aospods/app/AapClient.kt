package pro.aospods.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid

/** AACP 채널(L2CAP PSM 0x1001)을 여는 실험용 클라이언트.
 *  공개 API로는 클래식 L2CAP 소켓을 만들 수 없어 BluetoothSocket 숨김 생성자를 반사로 호출한다.
 *  안드로이드 버전·제조사에 따라 막히거나 연결이 거부될 수 있음 — 결과는 log로 알림. 모든 작업은 호출한 스레드에서 실행. */
class AapClient(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onMessage: (Aap.Msg) -> Unit,
) {
    /** 한 번이라도 연결에 성공했는지. */
    @Volatile var everConnected = false
        private set
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    /** 소켓 생성 → 연결 → 초기 메시지 → 수신 반복. 연결이 끊기거나 close() 될 때까지 블록. */
    @SuppressLint("MissingPermission")
    fun run() {
        val s = try { createSocket() } catch (e: Throwable) {
            log("① 소켓 생성 실패: ${errText(e)}"); return
        }
        socket = s
        log("① 소켓 생성 성공")

        // connect()는 오래 막힐 수 있어 5초 뒤 강제로 닫는다
        val watchdog = Thread {
            try { Thread.sleep(CONNECT_TIMEOUT_MS) } catch (_: InterruptedException) { return@Thread }
            if (!s.isConnected) { log("② 연결 시간 초과(5초)"); close() }
        }.apply { isDaemon = true; start() }
        try {
            s.connect()
        } catch (e: Throwable) {
            watchdog.interrupt()
            if (!closed) log("② 연결 실패: ${errText(e)}")
            close(); return
        }
        watchdog.interrupt()
        everConnected = true
        log("② 연결 성공 — 초기 메시지 전송")

        try {
            for (p in listOf(Aap.HANDSHAKE, Aap.FEATURE_FLAGS, Aap.REQUEST_NOTIFICATIONS)) {
                send(p); Thread.sleep(200)
            }
            onConnected()
            log("③ 전송 완료 — 에어팟 응답 대기 (한쪽을 뺐다 끼워 보세요)")
            // 정보(0x1D)·0x17 등은 수백 바이트~1KB를 넘을 수 있어 넉넉히(잘리면 해석 불가)
            val buf = ByteArray(8192)
            // 해석하지 않는 메시지는 같은 내용이면 한 번만 기록(0x0E·0x55처럼 몇 초마다 반복되는 것이 로그를 덮지 않게)
            val seenRaw = HashSet<Int>()
            val input = s.inputStream
            var count = 0
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) { log("연결이 끊겼어요 (받은 메시지 ${count}건)"); break }
                if (n == 0) continue
                count++
                val p = buf.copyOf(n)
                val op = Aap.opcode(p)
                val msg = Aap.parse(p)
                val parsed = msg != null && msg !is Aap.Msg.Other
                if (op != null && op in Aap.PRIVATE_OPS) {
                    // 주소·일련번호가 들어 있는 메시지: 원문은 남기지 않고 해석 결과만
                    when {
                        parsed -> log("← " + Aap.describe(msg!!))
                        op == Aap.OP_INFO -> log("← 기기 정보 수신했지만 해석 실패 (${n}B)")
                        seenRaw.add(p.contentHashCode()) ->
                            log("← 메시지 0x%02X (주소·일련번호 포함이라 원문 생략 · 이후 같은 내용은 생략)".format(op))
                    }
                    if (msg != null) onMessage(msg)
                } else if (msg != null && msg !is Aap.Msg.Other) {
                    log("← ${Aap.describe(msg)}\n   ${hex(p)}")
                    onMessage(msg)
                } else if (seenRaw.add(p.contentHashCode())) {
                    log("← ${hex(p.copyOf(minOf(n, 48)))}" + if (n > 48) " …(${n}B)" else "")
                }
            }
        } catch (e: Throwable) {
            if (!closed) log("수신 오류: ${errText(e)}")
        } finally { close() }
    }

    /** 에어팟에 메시지 전송. 연결돼 있지 않거나 실패하면 false. */
    fun send(p: ByteArray): Boolean {
        val s = socket ?: return false
        if (closed || !s.isConnected) return false
        return try {
            synchronized(this) { s.outputStream.write(p); s.outputStream.flush() }; true
        } catch (e: Exception) { log("전송 실패: ${errText(e)}"); false }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
    }

    /** 안드로이드 버전별 BluetoothSocket 숨김 생성자 시그니처를 차례로 시도. */
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun createSocket(): BluetoothSocket {
        // 안드로이드 17+는 숨김 API 예외 없이는 생성자가 보이지 않음.
        // 플레이 갈래는 우회 라이브러리 없이 일반 반사만 시도 → 허용된 폰에서만 열리고, 막힌 폰은 아래 실패 로그로 끝남
        if (!HiddenApi.allowBluetooth()) {
            log("숨김 API 예외: ${HiddenApi.result}")
            if (!BuildConfig.AACP) log("플레이 버전은 숨김 API 우회를 쓰지 않아 이 폰에서는 열리지 않을 수 있어요 → 전체 버전(깃허브 릴리스)을 설치하면 돼요")
        }
        val type = 3   // BluetoothSocket.TYPE_L2CAP
        val uuid = ParcelUuid.fromString(Aap.UUID)
        val i = Integer.TYPE
        val z = java.lang.Boolean.TYPE
        val A = BluetoothAdapter::class.java
        val D = BluetoothDevice::class.java
        val U = ParcelUuid::class.java
        // (매개변수 형식, 값) — 최신 → 예전 순
        val candidates = listOf(
            arrayOf(A, D, i, z, z, i, U) to arrayOf<Any>(adapter, device, type, true, true, Aap.PSM, uuid),
            arrayOf(D, i, z, z, i, U) to arrayOf<Any>(device, type, true, true, Aap.PSM, uuid),
            arrayOf(D, i, i, z, z, i, U) to arrayOf<Any>(device, type, -1, true, true, Aap.PSM, uuid),
            arrayOf(i, i, z, z, D, i, U) to arrayOf<Any>(type, -1, true, true, device, Aap.PSM, uuid),
            arrayOf(i, z, z, D, i, U) to arrayOf<Any>(type, true, true, device, Aap.PSM, uuid),
        )
        val errors = mutableListOf<String>()
        for ((types, args) in candidates) {
            try {
                val c = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                c.isAccessible = true
                return c.newInstance(*args) as BluetoothSocket
            } catch (e: Throwable) {
                errors += (e.cause ?: e).javaClass.simpleName
            }
        }
        val available = BluetoothSocket::class.java.declaredConstructors.joinToString(" / ") { c ->
            c.parameterTypes.joinToString(",") { it.simpleName }
        }
        throw IllegalStateException(
            "맞는 생성자 없음 [${errors.joinToString()}] 숨김 API: ${HiddenApi.result} · 사용 가능: ${available.ifEmpty { "(안 보임)" }}"
        )
    }

    private fun errText(e: Throwable): String {
        val root = e.cause ?: e
        return "${root.javaClass.simpleName}: ${root.message ?: ""}".trim()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000L
        fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
    }
}
