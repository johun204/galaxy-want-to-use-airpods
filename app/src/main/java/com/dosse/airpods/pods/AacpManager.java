package com.dosse.airpods.pods;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;

import androidx.annotation.RequiresApi;

import com.dosse.airpods.utils.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

/**
 * Apple Accessory Communication Protocol (AACP) over the L2CAP control channel (PSM 0x1001).
 * <p>
 * 리버스 엔지니어링 프로토콜(LibrePods 기반, GPLv3). 노이즈 컨트롤·귀 감지·대화 감지 등
 * 각종 컨트롤 명령·이름 변경, 그리고 배터리(opcode 0x04)를 읽는다.
 * 이 채널은 내 폰에 페어링된 그 에어팟에만 붙으므로 배터리 값이 옆사람 것과 섞이지 않는다.
 * <p>
 * 주의: L2CAP 0x1001 연결은 기기/ROM 에 따라 실패할 수 있다. 실패하면 컨트롤 UI 가 안 뜰 뿐,
 * 배터리 표시 등 나머지는 정상 동작한다.
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class AacpManager {
    private static final int PSM = 0x1001; // 4097
    private static final ParcelUuid APPLE_UUID = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    // 노이즈 컨트롤 모드 (LISTENING_MODE 값)
    public static final int NOISE_OFF = 1, NOISE_ANC = 2, NOISE_TRANSPARENCY = 3, NOISE_ADAPTIVE = 4;

    // 컨트롤 명령 식별자 (LibrePods ControlCommandIdentifiers)
    public static final int ID_LISTENING_MODE = 0x0D;
    public static final int ID_EAR_DETECTION = 0x0A;
    public static final int ID_LISTENING_MODE_CONFIGS = 0x1A;   // 길게 눌러 전환할 모드 비트마스크
    public static final int ID_ONE_BUD_ANC = 0x1B;
    public static final int ID_VOICE_TRIGGER = 0x12;            // "Hey Siri"
    public static final int ID_AUTO_ANC_STRENGTH = 0x2E;        // 적응형 오디오 세기 (0-100)
    public static final int ID_ADAPTIVE_VOLUME = 0x26;          // 개인화된 볼륨
    public static final int ID_CONVERSATION_DETECT = 0x28;      // 대화 감지
    public static final int ID_ALLOW_OFF_OPTION = 0x34;         // 노이즈 컨트롤에 "끄기" 포함
    public static final int ID_IN_CASE_TONE = 0x31;             // 케이스에 넣을 때 소리
    public static final int ID_AUTOMATIC_CONNECTION = 0x20;
    public static final int ID_SLEEP_DETECTION = 0x35;
    public static final int ID_DYNAMIC_END_OF_CHARGE = 0x3B;    // 최적화된 배터리 충전
    public static final int ID_VOLUME_SWIPE = 0x25;             // 볼륨 스와이프 (Pro 2)
    public static final int ID_DOUBLE_CLICK_INTERVAL = 0x17;    // 누르는 속도 (0=기본 1=느리게 2=가장느리게)
    public static final int ID_CLICK_HOLD_INTERVAL = 0x18;      // 길게 누르기 인식 시간

    private static final byte[] HEADER = {0x04, 0x00, 0x04, 0x00};
    private static final byte OP_CONTROL = 0x09;
    private static final byte OP_EAR_DETECTION = 0x06;
    private static final byte OP_BATTERY = 0x04;
    private static final byte OP_RENAME = 0x1A;

    // 배터리 컴포넌트 / 상태 (LibrePods)
    private static final int COMP_LEFT = 4, COMP_RIGHT = 2, COMP_CASE = 8;
    private static final int BATT_CHARGING = 1, BATT_DISCONNECTED = 4, BATT_OPTIMIZED = 5;

    public interface Listener {
        void onAacpConnected(boolean connected);

        /** id = ControlCommandIdentifier, value = 첫 바이트 (토글은 1=on 2=off). */
        void onControl(int id, int value);

        /**
         * 제어 채널에서 직접 받은 배터리. pct = 0..100 (-1=미인식). chg = 충전 중.
         * 이 채널은 내 폰에 페어링된 바로 그 에어팟에만 붙는다 → 옆사람 것과 절대 안 섞임.
         */
        void onBattery(int leftPct, boolean leftChg, int rightPct, boolean rightChg, int casePct, boolean caseChg);
    }

    private final Listener mListener;
    private volatile BluetoothSocket mSocket;
    private volatile OutputStream mOut;
    private Thread mReader;
    private volatile boolean mRunning = false;

    public AacpManager(Listener listener) {
        mListener = listener;
    }

    public synchronized void start(BluetoothDevice device) {
        if (mRunning)
            return;
        mRunning = true;
        mReader = new Thread(() -> run(device), "aacp-reader");
        mReader.start();
    }

    public synchronized void stop() {
        mRunning = false;
        closeSocket();
        if (mReader != null) {
            mReader.interrupt();
            mReader = null;
        }
    }

    // ---------------- 명령 ----------------

    public void setNoiseMode(int mode) {
        if (mode < NOISE_OFF || mode > NOISE_ADAPTIVE)
            return;
        setControlByte(ID_LISTENING_MODE, mode);
    }

    public void setControlBool(int id, boolean on) {
        setControlByte(id, on ? 0x01 : 0x02);
    }

    public void setControlByte(int id, int value) {
        // [09 00 id d0 d1 d2 d3] (7바이트) 앞에 헤더 → 11바이트
        writeRaw(concat(HEADER, new byte[]{OP_CONTROL, 0x00, (byte) id, (byte) value, 0x00, 0x00, 0x00}));
    }

    /** 에어팟에게 현재 상태(배터리 포함)를 다시 보내달라고 요청. 아무 때나 보내도 됨. */
    public void requestBattery() {
        writeRaw(concat(HEADER, new byte[]{0x0F, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    public void rename(String name) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] pkt = new byte[5 + n.length];
        pkt[0] = OP_RENAME;
        pkt[1] = 0x00;
        pkt[2] = 0x01;
        pkt[3] = (byte) n.length;
        pkt[4] = 0x00;
        System.arraycopy(n, 0, pkt, 5, n.length);
        writeRaw(concat(HEADER, pkt));
    }

    // ---------------- 내부 ----------------

    private void run(BluetoothDevice device) {
        try {
            BluetoothSocket socket = openSocket(device);
            socket.connect();
            mSocket = socket;
            mOut = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            for (int i = 0; i < 2; i++) {
                writeRaw(new byte[]{0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}); // handshake
                writeRaw(concat(HEADER, new byte[]{0x4D, 0x00, (byte) 0xD7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})); // feature flags
                writeRaw(concat(HEADER, new byte[]{0x0F, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF})); // request notifications (기기가 현재 설정 전부 덤프)
                sleep(200);
            }

            notifyConnected(true);
            Logger.debug("AACP connected");

            byte[] buf = new byte[1024];
            while (mRunning) {
                int n = in.read(buf);
                if (n < 0)
                    break;
                parse(buf, n);
            }
        } catch (Throwable t) {
            Logger.debug("AACP failed: " + t);
        } finally {
            notifyConnected(false);
            closeSocket();
            mRunning = false;
        }
    }

    @SuppressWarnings("MissingPermission")
    private BluetoothSocket openSocket(BluetoothDevice device) throws Exception {
        try {
            return device.createInsecureL2capChannel(PSM);
        } catch (Throwable t) {
            Logger.debug("createInsecureL2capChannel failed, trying reflection: " + t);
        }
        Object[][] specs = {
                {device, 3, -1, true, true, PSM, APPLE_UUID},
                {device, 3, true, true, PSM, APPLE_UUID},
                {3, -1, true, true, device, PSM, APPLE_UUID},
        };
        for (Object[] args : specs) {
            try {
                Class<?>[] types = new Class<?>[args.length];
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a instanceof Integer) types[i] = int.class;
                    else if (a instanceof Boolean) types[i] = boolean.class;
                    else if (a instanceof BluetoothDevice) types[i] = BluetoothDevice.class;
                    else types[i] = ParcelUuid.class;
                }
                Constructor<BluetoothSocket> c = BluetoothSocket.class.getDeclaredConstructor(types);
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (Throwable ignored) {
            }
        }
        throw new IllegalStateException("no usable BluetoothSocket L2CAP constructor");
    }

    // read() 청크 하나 = 패킷 하나 (L2CAP CoC 는 대체로 경계 보존). 헤더로 스캔.
    private void parse(byte[] b, int len) {
        int off = 0;
        while (len - off >= 4 && b[off] == 0x04 && b[off + 1] == 0x00 && b[off + 2] == 0x04 && b[off + 3] == 0x00)
            off += 4;
        if (off == 0 || len - off < 3)
            return;
        int op = b[off] & 0xFF;
        if (op == (OP_CONTROL & 0xFF) && len - off >= 4) {
            int id = b[off + 2] & 0xFF;
            int value = b[off + 3] & 0xFF;
            emit(id, value);
        } else if (op == (OP_EAR_DETECTION & 0xFF)) {
            // 착용 상태 통보 → 귀 감지 기능이 켜져 있다는 뜻으로만 사용
            emit(ID_EAR_DETECTION, 0x01);
        } else if (op == (OP_BATTERY & 0xFF)) {
            parseBattery(b, off, len);
        }
    }

    // 배터리 패킷: [04 00 04 00][04 00][count][component 5바이트 × count]
    // off 는 opcode(0x04) 위치. component: [0]=종류 [2]=레벨(0..100) [3]=상태(1=충전 4=미연결 5=최적화충전)
    private void parseBattery(byte[] b, int off, int len) {
        int count = (off + 2 < len) ? (b[off + 2] & 0xFF) : 0;
        int lp = -1, rp = -1, cp = -1;
        boolean lc = false, rc = false, cc = false;
        for (int i = 0; i < count && i < 6; i++) {
            int base = off + 3 + i * 5;
            if (base + 3 >= len)
                break;
            int comp = b[base] & 0xFF;
            int level = b[base + 2] & 0xFF;
            int status = b[base + 3] & 0xFF;
            int pct = (status == BATT_DISCONNECTED || level > 100) ? -1 : level;
            boolean chg = (status == BATT_CHARGING || status == BATT_OPTIMIZED);
            if (comp == COMP_LEFT) { lp = pct; lc = chg; }
            else if (comp == COMP_RIGHT) { rp = pct; rc = chg; }
            else if (comp == COMP_CASE) { cp = pct; cc = chg; }
        }
        try {
            mListener.onBattery(lp, lc, rp, rc, cp, cc);
        } catch (Throwable ignored) {
        }
    }

    private void emit(int id, int value) {
        try {
            mListener.onControl(id, value);
        } catch (Throwable ignored) {
        }
    }

    private void writeRaw(byte[] packet) {
        OutputStream out = mOut;
        if (out == null)
            return;
        try {
            synchronized (this) {
                out.write(packet);
                out.flush();
            }
        } catch (Throwable t) {
            Logger.debug("AACP write failed: " + t);
        }
    }

    private void notifyConnected(boolean c) {
        try {
            mListener.onAacpConnected(c);
        } catch (Throwable ignored) {
        }
    }

    private void closeSocket() {
        BluetoothSocket s = mSocket;
        mSocket = null;
        mOut = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
