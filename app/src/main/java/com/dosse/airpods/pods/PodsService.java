package com.dosse.airpods.pods;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.core.app.NotificationCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.receivers.BluetoothListener;
import com.dosse.airpods.receivers.BluetoothReceiver;
import com.dosse.airpods.ui.IslandA11yService;
import com.dosse.airpods.ui.IslandOverlay;
import com.dosse.airpods.ui.MainActivity;
import com.dosse.airpods.utils.DiagLog;
import com.dosse.airpods.utils.Logger;
import com.dosse.airpods.utils.PermissionUtils;
import com.dosse.airpods.widget.PodsWidget;

import java.util.Locale;
import java.util.Objects;

import static com.dosse.airpods.utils.SharedPreferencesUtils.isAacpBatteryEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isAacpEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isAutoOnConnect;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isIslandEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isWearAlertEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isWearSoundEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isWearVibrateEnabled;

/**
 * 필요할 때만 몇 초 도는 서비스 (상주하지 않는다).
 * <p>
 * - 에어팟이 연결되면 매니페스트의 AirPodsConnectionReceiver 가 깨운다 → 배터리 값을 읽고
 *   알림/아일랜드를 한 번 띄운 뒤 바로 종료한다.
 * - 그 밖엔 바로가기(갤럭시 루틴) / 앱·위젯을 열 때만 잠깐 돈다. 앱 화면이 앞에 있는 동안엔 비콘을 연속 스캔한다.
 * - 아일랜드는 접근성 서비스(IslandA11yService)가 그린다. 화면이 꺼져 있으면 그쪽에 넘기고 이 서비스는 기다리지 않는다.
 * - 마지막 배터리 값은 위젯/앱에 남겨 둔다.
 */
public class PodsService extends Service {
    public static final String ACTION_SET_ANC = "com.dosse.airpods.SET_ANC";
    public static final String ACTION_SET_CONTROL = "com.dosse.airpods.SET_CONTROL"; // extras: id, value, bool
    public static final String ACTION_RENAME = "com.dosse.airpods.RENAME";          // extra: name
    public static final String ACTION_DEVICE_CONNECTED = "com.dosse.airpods.DEVICE_CONNECTED"; // extra: BluetoothDevice
    public static final String ACTION_REFRESH = "com.dosse.airpods.REFRESH";        // 앱/위젯이 즉석 갱신 요청
    public static final String ACTION_APP_FOREGROUND = "com.dosse.airpods.APP_FG";  // 앱 화면이 앞으로 옴
    public static final String ACTION_APP_BACKGROUND = "com.dosse.airpods.APP_BG";  // 앱 화면이 뒤로 감
    public static final String ACTION_SHOW_ISLAND = "com.dosse.airpods.SHOW_ISLAND"; // 바로가기/루틴: 아일랜드 표시
    public static final String ACTION_SHOW_ALERT = "com.dosse.airpods.SHOW_ALERT";   // 바로가기/루틴: 배터리 알림 표시

    private static final int SHOW_NOTIF = 1;   // 표시 요청 비트: 배터리 알림
    private static final int SHOW_ISLAND = 2;  // 표시 요청 비트: 다이나믹 아일랜드

    private static volatile boolean sRunning = false; // 서비스 인스턴스가 살아 있는가
    private static int sAppForeground = 0;            // 앞에 떠 있는 액티비티 수 (>0 이면 연속 스캔). 메인 스레드 전용

    /** 서비스를 띄울 수 있는 상태인가 — 근처 기기 권한이 없으면 포그라운드 서비스 시작 자체가 예외를 던진다 */
    public static boolean canRun(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PermissionUtils.getBluetoothPermissions(c);
    }

    /** 앱 액티비티가 앞/뒤로 갈 때 호출 → 앞이면 비콘 연속 스캔 */
    public static void setAppForeground(Context c, boolean foreground) {
        sAppForeground = Math.max(0, sAppForeground + (foreground ? 1 : -1));
        if (sRunning)
            nudge(c, foreground ? ACTION_APP_FOREGROUND : ACTION_APP_BACKGROUND);
        else if (foreground)
            wakeIfPodsConnected(c);
        // 뒤로 갈 때 서비스가 없으면 알릴 것도 없다 — 죽은 서비스를 되살리지 않는다
    }

    /** AirPods가 실제로 연결돼 있을 때만 서비스를 깨운다 (연결 안 됐으면 알림도 안 뜨게). 앱이 앞에 있을 때만 호출. */
    @SuppressLint("MissingPermission")
    public static void wakeIfPodsConnected(Context c) {
        Context app = c.getApplicationContext();
        if (sRunning || !canRun(app))
            return;
        try {
            BluetoothAdapter ba = ((BluetoothManager)Objects.requireNonNull(app.getSystemService(BLUETOOTH_SERVICE))).getAdapter();
            if (ba == null || !ba.isEnabled())
                return;
            ba.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    boolean found = false;
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) {
                            if (isPods(d)) {
                                found = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    ba.closeProfileProxy(profile, proxy);
                    // 확인하는 사이 앱이 뒤로 갔으면 띄우지 않는다
                    if (found && sAppForeground > 0)
                        nudge(app, ACTION_APP_FOREGROUND);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.HEADSET);
        } catch (Throwable ignored) {
        }
    }

    private static void nudge(Context c, String action) {
        if (!canRun(c))
            return;
        try {
            c.startService(new Intent(c, PodsService.class).setAction(action));
        } catch (Throwable ignored) {
        }
    }

    private static final String TAG = "AirPods";            // 서비스 알림 채널 id
    private static final int NOTIFICATION_ID = 1;
    private static final int WEAR_NOTIFICATION_ID = 2;

    private static final long SETTLE_MS = 30000;       // 연결 직후 스캔 창
    private static final long REFRESH_MS = 10000;      // 주기적 갱신 스캔 창
    private static final long FAST_HEAD_MS = 15000;    // 창 시작 후 이 시간 동안은 고속 스캔
    private static final long ONDEMAND_MIN_GAP = 45000;// 앱·위젯 열기 즉석 갱신 최소 간격
    private static final long WEAR_SETTLE_MS = 1500;   // 연결 후 알림까지 데이터 안정 대기
    private static final long WATCHDOG_INTERVAL = 60000;
    private static final long DEFER_MS = 600000;       // 화면 꺼짐 상태에서 아일랜드를 미뤄 두는 최대 시간 (10분)
    private static final long IDLE_STOP_MS = 4500;     // 띄울 게 없을 때 값을 읽을 여유를 주고 접는 시간
    private static final long WEAR_TIMEOUT_MS = 600000;// 배터리 알림이 알림함에 남아 있는 시간 (10분)
    private static final long CACHE_OK_MS = 120000;    // 바로가기/루틴이 스캔 없이 써도 되는 캐시 값의 최대 나이
    private static final long AACP_RETRY_MS = 1500;    // 제어 채널 연결 실패 후 재시도 간격
    private static final int AACP_MAX_RETRIES = 2;     // 서비스 수명(=연결 1회)당

    // 소리/진동 조합별 알림 채널 (안드로이드는 채널 생성 후 소리/진동 변경 불가라 4개로 나눔)
    private static final String WEAR_SV = "wear_v3_sv";
    private static final String WEAR_S = "wear_v3_s";
    private static final String WEAR_V = "wear_v3_v";
    private static final String WEAR_SILENT = "wear_v3_none";

    private static final ParcelUuid AIRPODS_UUID_1 = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");
    private static final ParcelUuid AIRPODS_UUID_2 = ParcelUuid.fromString("2a72e02b-7b99-778f-014d-ad0b7221ec74");

    private BluetoothLeScanner mScanner;
    private PodsStatusScanCallback mScanCallback;
    private boolean mScanning = false;
    private long mLastScanEndAt = 0;
    private boolean mDestroyed = false;

    private PodsStatus mLastGoodStatus = null;   // 마지막으로 유효한 배터리를 담은 비콘
    private long mLastGoodAt = 0;
    private int mRssi = 0;
    private boolean mMaybeConnected = false;
    private boolean mGotData = false;            // 이번 서비스 수명에 배터리 값을 받았는가

    private int mShowWhat = 0;                   // 아직 띄우지 못한 표시 요청 (SHOW_* 비트)
    private boolean mAlertPending = false;
    private long mAlertDeadline = 0;             // 이 시각까지만 알림 발화 재시도
    private boolean mShowFreshOnly = false;      // 대기 중인 표시 요청이 캐시 값을 쓰면 안 됨 (새 연결)

    private BluetoothDevice mDevice;
    private AacpManager mAacp;
    private boolean mAacpConnected = false;
    private int mAacpGen = 0;                    // 지난 매니저의 뒤늦은 콜백을 걸러내는 세대 번호
    private int mAacpRetries = 0;
    private String mDeviceName = "";
    // 제어 채널에서 직접 받은 배터리 (내 폰에 페어링된 그 에어팟)
    private int mAbL = -1, mAbR = -1, mAbC = -1;
    private boolean mAbLc = false, mAbRc = false, mAbCc = false;
    private long mAacpBatAt = 0;
    private final java.util.HashMap<Integer, Integer> mControls = new java.util.HashMap<>();

    private NotificationManager mNM;
    private Handler mHandler;

    private BroadcastReceiver mBtReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        createChannels();
        mHandler = new Handler(Looper.getMainLooper());

        // 권한이 없거나 백그라운드에서 되살아난 경우 startForeground 가 예외를 던진다 — 죽지 말고 접는다
        try {
            startForeground(NOTIFICATION_ID, buildFgNotification());
        } catch (Throwable t) {
            Logger.error(t);
            mDestroyed = true;
            stopSelf();
            return;
        }

        // 근처 기기(블루투스) 권한이 없으면 아무 것도 할 수 없다 — 바로 접는다. 안내는 앱 화면에서.
        if (!canRun(this)) {
            mDestroyed = true;
            stopSelf();
            return;
        }
        sRunning = true;

        mBtReceiver = new BluetoothReceiver() {
            @Override
            public void onStart() { // BT 켜짐
                if (mMaybeConnected)
                    openScanWindow(REFRESH_MS);
            }

            @Override
            public void onStop() { // BT 꺼짐
                onPodsGone();
            }

            @Override
            public void onConnect(BluetoothDevice device) {
                if (isPods(device))
                    onPodsConnected(device, true);
            }

            @Override
            public void onDisconnect(BluetoothDevice device) {
                // 지금 보고 있는 그 기기가 끊겼을 때만 (다른 에어팟/Beats 해제에 반응하지 않게)
                if (mDevice != null ? mDevice.equals(device) : isPods(device)) {
                    DiagLog.add(PodsService.this, "ACL disconnected");
                    onPodsGone();
                }
            }

            @Override
            public void onLeLinkIgnored(BluetoothDevice device, String action) {
                if (isPods(device))
                    DiagLog.add(PodsService.this, "ignored LE link event: " + action.substring(action.lastIndexOf('.') + 1));
            }
        };
        try {
            registerReceiver(mBtReceiver, BluetoothReceiver.buildFilter());
        } catch (Throwable t) {
            Logger.error(t);
        }

        BluetoothAdapter ba = ((BluetoothManager)Objects.requireNonNull(getSystemService(BLUETOOTH_SERVICE))).getAdapter();

        // 서비스 시작 시점에 이미 AirPods가 연결돼 있는지 확인
        if (ba != null) {
            ba.getProfileProxy(getApplicationContext(), new BluetoothListener() {
                @Override
                public boolean onConnect(BluetoothDevice device) {
                    if (isPods(device)) {
                        onPodsConnected(device, false); // 이미 연결돼 있던 것 — 새 연결이 아니다
                        return true;
                    }
                    return false;
                }

                @Override
                public void onDisconnect() {
                }
            }, BluetoothProfile.HEADSET);
        }

        // 아무 것도 연결 안 돼 있으면 잠깐 뒤 종료
        mHandler.postDelayed(this::stopIfIdle, 4000);
        mHandler.postDelayed(mWatchdog, WATCHDOG_INTERVAL);
    }

    @Override
    @SuppressLint("MissingPermission")
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (mDestroyed) // onCreate 에서 이미 접기로 함
            return START_NOT_STICKY;

        if (ACTION_DEVICE_CONNECTED.equals(action)) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (isPods(d))
                onPodsConnected(d, true);
        } else if (ACTION_REFRESH.equals(action)) {
            requestRefresh();
        } else if (ACTION_SHOW_ISLAND.equals(action)) {
            requestShow(SHOW_ISLAND);
        } else if (ACTION_SHOW_ALERT.equals(action)) {
            requestShow(SHOW_NOTIF);
        } else if (ACTION_APP_FOREGROUND.equals(action)) {
            if (mMaybeConnected && !mScanning)
                openScanWindow(REFRESH_MS); // 연속 스캔으로 유지됨 (sAppForeground>0)
        } else if (ACTION_APP_BACKGROUND.equals(action)) {
            if (sAppForeground == 0) {
                if (mScanning)
                    mHandler.postDelayed(mEndWindow, REFRESH_MS); // 잠깐 더 스캔한 뒤 접는다
                else
                    stopLater(IDLE_STOP_MS);
            }
        } else if (action != null && mAacp != null) {
            switch (action) {
                case ACTION_SET_ANC: {
                    int mode = intent.getIntExtra("mode", 0);
                    mAacp.setNoiseMode(mode);
                    optimistic(AacpManager.ID_LISTENING_MODE, mode);
                    break;
                }
                case ACTION_SET_CONTROL: {
                    int id = intent.getIntExtra("id", 0);
                    int value = intent.getIntExtra("value", 0);
                    if (intent.getBooleanExtra("bool", true)) {
                        mAacp.setControlBool(id, value == 1);
                        optimistic(id, value == 1 ? 1 : 2);
                    } else {
                        mAacp.setControlByte(id, value);
                        optimistic(id, value);
                    }
                    break;
                }
                case ACTION_RENAME: {
                    String name = intent.getStringExtra("name");
                    if (name != null && !name.trim().isEmpty()) {
                        mAacp.rename(name.trim());
                        mDeviceName = name.trim();
                        mHandler.post(this::publish);
                    }
                    break;
                }
            }
        }
        // 스스로 접는 서비스다 — 프로세스가 죽은 뒤 백그라운드에서 되살아나면 포그라운드 시작이 거부된다
        return START_NOT_STICKY;
    }

    private void optimistic(int id, int value) {
        mControls.put(id, value);
        mHandler.post(this::publish);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        boolean ran = sRunning;
        sRunning = false;
        mDestroyed = true; // 이후 도착하는 AACP 콜백/예약 작업이 알림·스냅샷을 다시 건드리지 않게
        if (mHandler != null)
            mHandler.removeCallbacksAndMessages(null);
        stopAacp();
        stopScanner();
        try {
            unregisterReceiver(mBtReceiver);
        } catch (Throwable ignored) {
        }
        mNM.cancel(NOTIFICATION_ID);
        // 배터리 알림(WEAR_NOTIFICATION_ID)은 지우지 않는다 — 서비스가 내려가도 알림함에 남아야 한다
        if (!ran) // 권한 없음 등으로 시작도 못 한 경우 — 스냅샷은 건드리지 않는다
            return;
        // 연결된 채로 서비스만 내려가는 게 보통이다 — 마지막 배터리 값은 위젯/앱에 남겨둔다
        if (!mMaybeConnected)
            setLast(new PodsSnapshot());
        broadcastSnapshot();
        PodsWidget.updateAll(this);
    }

    // ---------------- 연결 상태 ----------------

    /**
     * @param fresh 방금 연결됐다는 이벤트로 알게 된 경우 true. 서비스가 새로 뜨면서 "이미 연결돼 있음"을
     *              확인한 것(앱·위젯 열기, 바로가기)은 false — 연결 시 자동 표시를 다시 띄우지 않는다.
     */
    private void onPodsConnected(BluetoothDevice device, boolean fresh) {
        boolean wasConnected = mMaybeConnected;
        mMaybeConnected = true;
        mDevice = device;
        startAacp();
        if (!wasConnected) {
            DiagLog.add(this, fresh ? "AirPods connected" : "service up, AirPods already connected");
            openScanWindow(SETTLE_MS, true); // 연결 직후엔 항상 고속 — 표시가 늦지 않게
            // 연결당 1회: 설정에 따라 알림 / 아일랜드 (앱을 열어 둔 상태에서도 그대로 띄운다)
            int what = fresh && isAutoOnConnect(this)
                    ? (isWearAlertEnabled(this) ? SHOW_NOTIF : 0) | (isIslandEnabled(this) ? SHOW_ISLAND : 0)
                    : 0;
            if (what != 0)
                requestShow(what, true); // 지난 연결 때의 값이 아니라 이번에 읽은 값으로
            else
                stopLater(IDLE_STOP_MS);
        }
    }

    private void onPodsGone() {
        if (mMaybeConnected)
            DiagLog.add(this, "AirPods disconnected");
        mMaybeConnected = false;
        mLastGoodStatus = null;
        mShowWhat = 0;
        mShowFreshOnly = false;
        mAlertPending = false;
        stopScanner();
        publish();
        stopSelf();
    }

    private void stopIfIdle() {
        if (mMaybeConnected)
            return;
        if (mShowWhat != 0) // 바로가기/루틴으로 불렀는데 연결된 에어팟이 없음
            DiagLog.add(this, "shortcut: AirPods not connected");
        if (mShowWhat != 0)
            android.widget.Toast.makeText(this, R.string.toast_not_connected, android.widget.Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    // ---------------- 스캔 창 ----------------

    // 앱/위젯이 요청한 즉석 갱신. 너무 잦으면 무시.
    private void requestRefresh() {
        if (!mMaybeConnected || mScanning)
            return;
        if (System.currentTimeMillis() - mLastScanEndAt < ONDEMAND_MIN_GAP)
            return;
        openScanWindow(REFRESH_MS);
    }

    private void openScanWindow(long durationMs) {
        openScanWindow(durationMs, true);
    }

    /** @param forceFast 처음 이 시간 동안 고속 스캔 (값이 빨리 필요할 때) */
    private void openScanWindow(long durationMs, boolean forceFast) {
        if (!mMaybeConnected)
            return;
        mHandler.removeCallbacks(mEndWindow);
        // 같은 주기로 제어 채널에도 배터리 재요청 (연결돼 있으면)
        if (mAacp != null && isAacpBatteryEnabled(this))
            mAacp.requestBattery();
        startScanner(forceFast);
        mHandler.postDelayed(mEndWindow, durationMs);
        // 고속 구간이 끝나면 일반 속도로 낮춰 다시 스캔
        if (forceFast && durationMs > FAST_HEAD_MS)
            mHandler.postDelayed(() -> {
                if (mScanning)
                    startScanner(false);
            }, FAST_HEAD_MS);
    }

    private final Runnable mFireShow = this::fireShow;

    private final Runnable mEndWindow = () -> {
        if (sAppForeground > 0)
            return; // 앱이 열려 있는 동안엔 계속 스캔
        stopScanner();
        mLastScanEndAt = System.currentTimeMillis();
        // 주기 스캔은 없다 — 여기서 서비스를 접는다 (아직 띄우지 못한 알림/아일랜드가 있으면 그게 끝난 뒤에)
        if (mMaybeConnected)
            stopLater(0);
    };

    @SuppressLint("MissingPermission")
    private void startScanner(boolean fast) {
        try {
            BluetoothAdapter ba = ((BluetoothManager)Objects.requireNonNull(getSystemService(BLUETOOTH_SERVICE))).getAdapter();
            if (ba == null || !ba.isEnabled())
                return;
            BluetoothLeScanner scanner = ba.getBluetoothLeScanner();
            if (scanner == null)
                return;

            if (mScanning)
                stopScanner();
            mScanner = scanner;

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(fast ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_BALANCED)
                    .setReportDelay(1)
                    .build();

            mScanCallback = new PodsStatusScanCallback() {
                @Override
                public void onStatus(PodsStatus status, int rssi) {
                    mRssi = rssi;
                    setStatus(status);
                }
            };
            mScanner.startScan(PodsStatusScanCallback.getScanFilters(), settings, mScanCallback);
            mScanning = true;
            Logger.debug("scan window start, fast=" + fast);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScanner() {
        try {
            if (mScanner != null && mScanCallback != null) {
                mScanner.stopScan(mScanCallback);
                Logger.debug("scan window stop");
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
        mScanCallback = null;
        mScanning = false;
    }

    // ---------------- 상태 반영 ----------------

    private void setStatus(PodsStatus status) {
        if (status != PodsStatus.DISCONNECTED && !status.isAllDisconnected()) {
            mLastGoodStatus = status;
            mLastGoodAt = System.currentTimeMillis();
            if (!mGotData)
                DiagLog.add(this, "battery beacon received (rssi " + mRssi + ")");
            mGotData = true;
            PodsSnapshot.lastBeaconAt = mLastGoodAt;
        }
        publish();
    }

    /** 값이 바뀐 경우에만 알림/위젯/메인 화면을 갱신한다. */
    private void publish() {
        if (mDestroyed)
            return;
        boolean haveBeacon = mMaybeConnected && mLastGoodStatus != null;
        boolean useAacp = aacpBatteryUsable();

        PodsSnapshot snap;
        if (haveBeacon) {
            snap = new PodsSnapshot(mLastGoodStatus);
            // 비콘에서 모델/일러스트/착용상태는 쓰되, 배터리 값은 내 에어팟에서 직접 온 AACP 값으로 덮어씀
            if (useAacp && !snap.single)
                snap.overrideBattery(mAbL, mAbLc, mAbR, mAbRc, mAbC, mAbCc);
        } else if (useAacp) {
            // 비콘이 없어도 제어 채널만으로 배터리 표시
            snap = PodsSnapshot.fromAacp(mAbL, mAbLc, mAbR, mAbRc, mAbC, mAbCc);
        } else {
            snap = new PodsSnapshot();
        }

        long at = useAacp ? mAacpBatAt : (haveBeacon ? mLastGoodAt : 0);
        snap.aacpConnected = mAacpConnected;
        snap.deviceName = mDeviceName;
        snap.controls = new java.util.HashMap<>(mControls);
        snap.rssi = mRssi;
        snap.updatedAt = at;
        snap.stale = at > 0 && System.currentTimeMillis() - at > 90_000;

        String sig = sig(snap);
        if (!sig.equals(mLastSig)) {
            mLastSig = sig;
            setLast(snap);
            broadcastSnapshot();
            PodsWidget.updateAll(this);
        }
    }

    private String mLastSig = null;

    // 프로세스가 죽어도 위젯/앱에 마지막 값이 남도록 저장까지 한다
    private void setLast(PodsSnapshot snap) {
        PodsSnapshot.last = snap;
        snap.save(this);
    }

    private static String sig(PodsSnapshot s) {
        // rssi 는 sig 제외 (지터로 매 비콘마다 갱신 방지). 찾기 화면은 정적 필드를 폴링.
        // updatedAt 은 분 단위로만 반영 → "HH:mm" 표시가 바뀔 때만 위젯/알림 재생성.
        StringBuilder a = new StringBuilder("|").append(s.aacpConnected).append(s.deviceName)
                .append(s.stale).append(s.updatedAt / 60000).append('|');
        java.util.List<Integer> keys = new java.util.ArrayList<>(s.controls.keySet());
        java.util.Collections.sort(keys);
        for (int k : keys)
            a.append(k).append('=').append(s.controls.get(k)).append(',');
        if (!s.available)
            return "off" + a;
        return (s.single ? "1" : "0") + s.left + "|" + s.right + "|" + s.caseB + "|"
                + s.leftChg + s.rightChg + s.caseChg + s.leftInEar + s.rightInEar + "|"
                + s.leftImg + s.rightImg + s.caseImg + a;
    }

    private void broadcastSnapshot() {
        sendBroadcast(new Intent(PodsSnapshot.ACTION).setPackage(getPackageName()));
    }

    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!mMaybeConnected) {
                stopSelf();
                return;
            }
            mHandler.postDelayed(this, WATCHDOG_INTERVAL);
        }
    };

    // ---------------- 배터리 표시 (알림 / 아일랜드) ----------------

    /** 알림·아일랜드를 띄워 달라는 요청. 배터리 값이 들어오는 대로 발화한다. */
    private void requestShow(int what) {
        requestShow(what, false);
    }

    /** @param freshOnly 캐시된 값을 쓰지 않고 이번 서비스 수명에 읽은 값이 올 때까지 기다린다 */
    private void requestShow(int what, boolean freshOnly) {
        mShowWhat |= what;
        mShowFreshOnly |= freshOnly;
        DiagLog.add(this, "show requested:" + ((what & SHOW_ISLAND) != 0 ? " island" : "")
                + ((what & SHOW_NOTIF) != 0 ? " notification" : "") + (freshOnly ? " (fresh value)" : ""));
        mAlertDeadline = System.currentTimeMillis() + SETTLE_MS;
        if (!mAlertPending) {
            mAlertPending = true;
            // 이미 값이 있으면 스캔도 대기도 없이 바로 띄운다 (루틴에서 부를 때 즉시 반응 + 스캔 0회)
            mHandler.postDelayed(mFireShow, hasBattery() ? 0 : WEAR_SETTLE_MS);
        }
    }

    private boolean hasBattery() {
        PodsSnapshot s = PodsSnapshot.last;
        if (!s.available || (s.leftPct < 0 && s.rightPct < 0 && s.casePct < 0))
            return false;
        if (mGotData)
            return true;
        // 서비스가 내려가 있던 동안의 값 — 새 연결엔 쓰지 않고, 바로가기/루틴도 최근 것만 쓴다
        return !mShowFreshOnly && System.currentTimeMillis() - s.updatedAt < CACHE_OK_MS;
    }

    private void fireShow() {
        mAlertPending = false;
        PodsSnapshot s = PodsSnapshot.last;
        boolean ready = mMaybeConnected && hasBattery();
        if (!ready) {
            // 아직 연결 확인 전이거나 배터리 값을 못 받음 — 마감 시각 전이면 재시도
            if (System.currentTimeMillis() < mAlertDeadline) {
                DiagLog.add(this, mMaybeConnected ? "waiting for battery value" : "waiting for connection");
                if (mMaybeConnected && !mScanning)
                    openScanWindow(REFRESH_MS, true); // 값이 없을 때만, 대신 고속으로
                mAlertPending = true;
                mHandler.postDelayed(mFireShow, 2000);
                return;
            }
            DiagLog.add(this, "gave up: " + (mMaybeConnected ? "no battery value in 30s" : "not connected"));
            mShowWhat = 0;
            mShowFreshOnly = false;
            stopLater(0);
            return;
        }
        // 아일랜드를 먼저 띄운다. 접근성 오버레이는 잠금 화면 위에도 보이니 화면만 켜져 있으면 바로 띄우고,
        // 화면이 꺼져 있으면 접근성 서비스에 넘긴다 (화면이 켜지는 순간 그쪽이 띄운다 — 이 서비스는 기다리지 않는다).
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        boolean screenOn = pm == null || pm.isInteractive();
        boolean islandShown = false;
        if ((mShowWhat & SHOW_ISLAND) != 0) {
            if (!IslandA11yService.isRunning()) {
                DiagLog.add(this, "island skipped: accessibility off");
            } else if (screenOn) {
                islandShown = IslandOverlay.show(this, s);
                DiagLog.add(this, islandShown ? "island shown (" + (aacpBatteryUsable() ? "control channel" : "beacon") + " value)"
                        : "island failed: " + IslandOverlay.lastFailure);
            } else if (IslandA11yService.deferIsland(System.currentTimeMillis() + DEFER_MS)) {
                DiagLog.add(this, "island handed to accessibility: waits for screen on");
            }
        }
        // 알림은 알림함에 남으니 화면이 꺼져 있어도 지금 띄운다.
        // 아일랜드를 띄웠다면 같은 내용의 헤즈업 배너는 중복이라 배너 없이 넣는다.
        if ((mShowWhat & SHOW_NOTIF) != 0) {
            showWearAlert(wearBody(s), islandShown);
            DiagLog.add(this, "notification posted" + (islandShown ? " (no banner)" : ""));
        }
        mShowWhat = 0;
        mShowFreshOnly = false;
        // 아일랜드 창은 접근성 서비스 쪽에 붙어 있어 이 서비스가 떠 있을 필요가 없다 — 바로 접는다
        stopLater(0);
    }

    /**
     * 보여줄 걸 다 보여줬으면 서비스를 완전히 종료한다.
     * 이후엔 에어팟 재연결 / 바로가기(루틴) / 앱·위젯 열기 때만 잠깐 다시 뜬다.
     */
    private void stopLater(long delayMs) {
        if (sAppForeground > 0)
            return;
        mHandler.removeCallbacks(mStopIfDone);
        mHandler.postDelayed(mStopIfDone, delayMs);
    }

    private final Runnable mStopIfDone = () -> {
        if (sAppForeground == 0 && !mAlertPending && mShowWhat == 0) {
            DiagLog.add(this, "service stopped");
            stopSelf();
        }
    };

    private String wearBody(PodsSnapshot s) {
        return getString(R.string.pod_left) + " " + dash(s.left)
                + "    " + getString(R.string.pod_right) + " " + dash(s.right)
                + "    " + getString(R.string.pod_case) + " " + dash(s.caseB);
    }

    /**
     * @param quiet 아일랜드를 이미 띄웠으면 true — 헤즈업 배너가 아일랜드를 가리지 않도록
     *              소리/진동은 그대로 두고 화면 상단 배너만 띄우지 않는다.
     */
    private void showWearAlert(String body, boolean quiet) {
        String channel = wearChannel(quiet);

        Notification n = new NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.wear_alert_title))
                .setContentText(body)
                .setPriority(quiet ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setTimeoutAfter(WEAR_TIMEOUT_MS)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
        mNM.notify(WEAR_NOTIFICATION_ID, n);
    }

    // ---------------- 상태 알림 (딱 하나) ----------------

    private void createChannels() {
        NotificationChannel status = new NotificationChannel(TAG, TAG, NotificationManager.IMPORTANCE_LOW);
        status.setSound(null, null);
        status.enableVibration(false);
        status.enableLights(false);
        status.setShowBadge(false);
        status.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        mNM.createNotificationChannel(status);

        // 착용 알림 채널은 쓰는 순간에 만든다 (헤즈업 O/X 두 벌)
        for (String old : new String[]{WEAR_SV, WEAR_S, WEAR_V, WEAR_SILENT})
            mNM.deleteNotificationChannel(old);
        mNM.deleteNotificationChannel("AirPods_wear");
        mNM.deleteNotificationChannel("wear_alert_v2");
        mNM.deleteNotificationChannel("FOREGROUND_ID");
        mNM.deleteNotificationChannel("background_min");
    }

    /** 소리/진동 조합 + 헤즈업 여부에 맞는 채널을 (없으면 만들어서) 돌려준다. */
    private String wearChannel(boolean quiet) {
        boolean snd = isWearSoundEnabled(this), vib = isWearVibrateEnabled(this);
        String suffix = snd && vib ? "sv" : snd ? "s" : vib ? "v" : "none";
        String id = (quiet ? "wear_v4q_" : "wear_v4_") + suffix;
        String label = getString(R.string.wear_channel) + " ("
                + getString(snd && vib ? R.string.wear_ch_sound_vib : snd ? R.string.wear_ch_sound
                        : vib ? R.string.wear_ch_vib : R.string.wear_ch_silent) + ")"
                + (quiet ? " (" + getString(R.string.wear_ch_no_banner) + ")" : "");

        NotificationChannel c = new NotificationChannel(id, label,
                quiet ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_HIGH);
        c.setShowBadge(false);
        if (!snd)
            c.setSound(null, null);
        c.enableVibration(vib);
        mNM.createNotificationChannel(c); // 이미 있으면 무시된다
        return id;
    }

    // 몇 초만 떠 있는 포그라운드 서비스 알림 — 배터리 값은 배터리 알림/아일랜드가 보여준다
    private Notification buildFgNotification() {
        return new NotificationCompat.Builder(this, TAG)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.bg_noti_title))
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    private static String dash(String s) {
        return (s == null || s.isEmpty()) ? "—" : s;
    }

    // ---------------- AACP ----------------

    @SuppressLint("MissingPermission")
    private void startAacp() {
        if (mAacp != null || mDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isAacpEnabled(this))
            return;
        try {
            String alias = mDevice.getAlias();
            mDeviceName = alias != null ? alias : (mDevice.getName() != null ? mDevice.getName() : "AirPods");
        } catch (Throwable t) {
            mDeviceName = "AirPods";
        }
        // 콜백은 리더 스레드에서 온다 — 상태는 전부 메인 스레드에서만 건드린다
        final int gen = ++mAacpGen;
        mAacp = new AacpManager(new AacpManager.Listener() {
            @Override
            public void onAacpConnected(boolean connected) {
                mHandler.post(() -> {
                    if (mDestroyed || gen != mAacpGen)
                        return;
                    mAacpConnected = connected;
                    DiagLog.add(PodsService.this, connected ? "control channel connected"
                            : "control channel down: " + (mAacp != null ? mAacp.lastError : ""));
                    if (!connected) {
                        mControls.clear();
                        retryAacp();
                    }
                    publish();
                });
            }

            @Override
            public void onControl(int id, int value) {
                mHandler.post(() -> {
                    if (mDestroyed || gen != mAacpGen)
                        return;
                    mControls.put(id, value);
                    publish();
                });
            }

            @Override
            public void onBattery(int lp, boolean lc, int rp, boolean rc, int cp, boolean cc) {
                mHandler.post(() -> {
                    if (mDestroyed || gen != mAacpGen)
                        return;
                    mAbL = lp; mAbLc = lc;
                    mAbR = rp; mAbRc = rc;
                    mAbC = cp; mAbCc = cc;
                    if (mAacpBatAt == 0)
                        DiagLog.add(PodsService.this, "battery from control channel");
                    mAacpBatAt = System.currentTimeMillis();
                    // 제어 채널 배터리를 쓰지 않는 설정이면 '값을 받았다'로 치지 않는다 (표시엔 비콘 값만 쓰므로)
                    if (isAacpBatteryEnabled(PodsService.this) && (lp >= 0 || rp >= 0 || cp >= 0))
                        mGotData = true;
                    publish();
                    endScanEarlyIfAacp();
                });
            }
        });
        mAacp.start(mDevice);
    }

    // 제어 채널이 실패했거나 끊겼다 — 연결 직후엔 L2CAP 이 아직 안 열릴 수 있어 몇 번 다시 시도한다
    private void retryAacp() {
        if (mAacp != null) {
            mAacp.stop();
            mAacp = null;
        }
        if (!mMaybeConnected || mAacpRetries >= AACP_MAX_RETRIES)
            return;
        mAacpRetries++;
        mHandler.postDelayed(this::startAacp, AACP_RETRY_MS);
    }

    /** 제어 채널에서 정확한 배터리를 받았으면 비콘 스캔을 더 돌릴 이유가 없다. */
    private void endScanEarlyIfAacp() {
        if (!mScanning || sAppForeground > 0 || !aacpBatteryUsable())
            return;
        mHandler.removeCallbacks(mEndWindow);
        mHandler.post(mEndWindow);
    }

    private void stopAacp() {
        mAacpGen++;
        if (mAacp != null) {
            mAacp.stop();
            mAacp = null;
        }
        mAacpConnected = false;
        mControls.clear();
        mAbL = mAbR = mAbC = -1;
        mAacpBatAt = 0;
    }

    // AACP 배터리를 배터리 소스로 쓸 수 있는가
    private boolean aacpBatteryUsable() {
        return isAacpEnabled(this) && isAacpBatteryEnabled(this) && mAacpConnected
                && (mAbL >= 0 || mAbR >= 0 || mAbC >= 0);
    }

    // ---------------- AirPods 판별 ----------------

    @SuppressLint("MissingPermission")
    public static boolean isPods(BluetoothDevice device) {
        if (device == null)
            return false;
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids)
                    if (AIRPODS_UUID_1.equals(u) || AIRPODS_UUID_2.equals(u))
                        return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = device.getName();
            if (name != null) {
                String n = name.toLowerCase(Locale.ROOT);
                if (n.contains("airpod") || n.contains("beats"))
                    return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
