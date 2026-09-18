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
import com.dosse.airpods.notification.NotificationBuilder;
import com.dosse.airpods.receivers.BluetoothListener;
import com.dosse.airpods.receivers.BluetoothReceiver;
import com.dosse.airpods.receivers.ScreenReceiver;
import com.dosse.airpods.ui.IslandOverlay;
import com.dosse.airpods.ui.MainActivity;
import com.dosse.airpods.utils.Logger;
import com.dosse.airpods.utils.PermissionUtils;
import com.dosse.airpods.widget.PodsWidget;

import java.util.Locale;
import java.util.Objects;

import static com.dosse.airpods.notification.NotificationBuilder.NOTIFICATION_ID;
import static com.dosse.airpods.notification.NotificationBuilder.TAG;
import static com.dosse.airpods.utils.SharedPreferencesUtils.batteryRefreshSeconds;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isAacpBatteryEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isAacpEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isAutoOnConnect;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isFastScanOnConnect;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isIslandEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isOnDemandEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isPersistentNotificationEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isScanSaverEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isWearAlertEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isWearSoundEnabled;
import static com.dosse.airpods.utils.SharedPreferencesUtils.isWearVibrateEnabled;

/**
 * AirPods가 연결돼 있는 동안에만 살아있는 서비스.
 * <p>
 * 배터리 효율:
 * - 앱 화면이 앞에 떠 있는 동안엔 비콘을 연속 스캔한다.
 * - 앱이 닫히면 듀티 사이클: 연결 직후 30초 창 + 이후 설정 주기(5초~5분)마다 10초 창만.
 *   화면 꺼짐/잠금 시엔 스캔 절전 옵션에 따라 주기 스캔을 건너뛰고, 잠금 해제 시 따라잡는다.
 * - 스캔 창 사이에는 마지막으로 받은 배터리 값을 그대로 보여준다. 매 비콘이 좌/우/케이스를 통째로 교체한다.
 * - AirPods가 끊기면 서비스는 스스로 종료. 다시 연결되면 매니페스트의 AirPodsConnectionReceiver 가 되살린다.
 * - 배터리 알림은 연결당 1회.
 * - 온디맨드 모드(기본): 알림/아일랜드를 한 번 띄운 뒤 서비스를 아예 종료한다. 이후엔
 *   재연결 / 바로가기(갤럭시 루틴) / 앱·위젯을 열 때만 몇 초 동안 다시 돈다.
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

    /** 앱 액티비티가 앞/뒤로 갈 때 호출 → 앞이면 비콘 연속 스캔 */
    public static void setAppForeground(android.content.Context c, boolean foreground) {
        try {
            c.startService(new Intent(c, PodsService.class)
                    .setAction(foreground ? ACTION_APP_FOREGROUND : ACTION_APP_BACKGROUND));
        } catch (Throwable ignored) {
        }
    }

    private static final int WEAR_NOTIFICATION_ID = 2;

    private static final long SETTLE_MS = 30000;       // 연결 직후 스캔 창
    private static final long REFRESH_MS = 10000;      // 주기적 갱신 스캔 창
    private static final long FAST_HEAD_MS = 15000;    // 창 시작 후 이 시간 동안은 고속 스캔
    private static final long ONDEMAND_MIN_GAP = 45000;// 화면 on/앱 열기 즉석 갱신 최소 간격
    private static final long WEAR_SETTLE_MS = 1500;   // 연결 후 알림까지 데이터 안정 대기
    private static final long WATCHDOG_INTERVAL = 60000;

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
    private int mAppForeground = 0;   // 앞에 떠 있는 액티비티 수 (>0 이면 연속 스캔)

    private PodsStatus mLastGoodStatus = null;   // 마지막으로 유효한 배터리를 담은 비콘
    private long mLastGoodAt = 0;
    private int mRssi = 0;
    private boolean mMaybeConnected = false;

    private int mShowWhat = 0;                   // 아직 띄우지 못한 표시 요청 (SHOW_* 비트)
    private boolean mAlertPending = false;
    private long mAlertDeadline = 0;             // 이 시각까지만 알림 발화 재시도

    private BluetoothDevice mDevice;
    private AacpManager mAacp;
    private boolean mAacpConnected = false;
    private String mDeviceName = "";
    // 제어 채널에서 직접 받은 배터리 (내 폰에 페어링된 그 에어팟)
    private int mAbL = -1, mAbR = -1, mAbC = -1;
    private boolean mAbLc = false, mAbRc = false, mAbCc = false;
    private long mAacpBatAt = 0;
    private final java.util.HashMap<Integer, Integer> mControls = new java.util.HashMap<>();

    private NotificationBuilder mBuilder;
    private NotificationManager mNM;
    private Handler mHandler;

    private BroadcastReceiver mBtReceiver;
    private BroadcastReceiver mScreenReceiver;

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
        mBuilder = new NotificationBuilder(this);
        mHandler = new Handler(Looper.getMainLooper());

        startForeground(NOTIFICATION_ID, buildFgNotification());

        // 근처 기기(블루투스) 권한이 없으면 아무 것도 할 수 없다 — 바로 접는다. 안내는 앱 화면에서.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !PermissionUtils.getBluetoothPermissions(this)) {
            stopSelf();
            return;
        }

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
                    onPodsConnected(device);
            }

            @Override
            public void onDisconnect(BluetoothDevice device) {
                if (isPods(device))
                    onPodsGone();
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
                        onPodsConnected(device);
                        return true;
                    }
                    return false;
                }

                @Override
                public void onDisconnect() {
                }
            }, BluetoothProfile.HEADSET);
        }

        // 화면 켜짐/잠금 해제 → 즉석 갱신. 절전 옵션 켜지고 화면 꺼지면 예약 스캔 취소.
        mScreenReceiver = new ScreenReceiver() {
            @Override
            public void onStart() { // 화면 켜짐 (아직 잠금일 수 있음)
                onScreenInteractive();
            }

            @Override
            public void onUnlock() { // 잠금 해제
                onScreenInteractive();
            }

            @Override
            public void onStop() { // 화면 꺼짐
                if (isScanSaverEnabled(PodsService.this))
                    mHandler.removeCallbacks(mReopenWindow);
            }
        };
        try {
            registerReceiver(mScreenReceiver, ScreenReceiver.buildFilter());
        } catch (Throwable t) {
            Logger.error(t);
        }

        // 아무 것도 연결 안 돼 있으면 잠깐 뒤 종료
        mHandler.postDelayed(this::stopIfIdle, 4000);
        mHandler.postDelayed(mWatchdog, WATCHDOG_INTERVAL);
    }

    @Override
    @SuppressLint("MissingPermission")
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_DEVICE_CONNECTED.equals(action)) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (isPods(d))
                onPodsConnected(d);
        } else if (ACTION_REFRESH.equals(action)) {
            requestRefresh();
        } else if (ACTION_SHOW_ISLAND.equals(action)) {
            requestShow(SHOW_ISLAND);
        } else if (ACTION_SHOW_ALERT.equals(action)) {
            requestShow(SHOW_NOTIF);
        } else if (ACTION_APP_FOREGROUND.equals(action)) {
            mAppForeground++;
            if (mMaybeConnected && !mScanning)
                openScanWindow(REFRESH_MS); // 연속 스캔으로 유지됨 (mAppForeground>0)
        } else if (ACTION_APP_BACKGROUND.equals(action)) {
            if (mAppForeground > 0)
                mAppForeground--;
            if (mAppForeground == 0 && mScanning)
                mHandler.postDelayed(mEndWindow, REFRESH_MS); // 잠깐 더 스캔 후 듀티 사이클 복귀
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
        return START_STICKY;
    }

    private void optimistic(int id, int value) {
        mControls.put(id, value);
        mHandler.post(this::publish);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null)
            mHandler.removeCallbacksAndMessages(null);
        stopAacp();
        stopScanner();
        try {
            unregisterReceiver(mBtReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(mScreenReceiver);
        } catch (Throwable ignored) {
        }
        mNM.cancel(NOTIFICATION_ID);
        mNM.cancel(WEAR_NOTIFICATION_ID);
        // 온디맨드 모드에선 연결된 채로 서비스만 내려간다 — 마지막 배터리 값은 위젯/앱에 남겨둔다
        if (!mMaybeConnected)
            PodsSnapshot.last = new PodsSnapshot();
        broadcastSnapshot();
        PodsWidget.updateAll(this);
    }

    // ---------------- 연결 상태 ----------------

    private void onPodsConnected(BluetoothDevice device) {
        boolean wasConnected = mMaybeConnected;
        mMaybeConnected = true;
        mDevice = device;
        startAacp();
        if (!wasConnected) {
            openScanWindow(SETTLE_MS);
            // 연결당 1회: 설정에 따라 알림 / 아일랜드 (앱을 열어 둔 상태에서도 그대로 띄운다)
            int what = isAutoOnConnect(this)
                    ? (isWearAlertEnabled(this) ? SHOW_NOTIF : 0) | (isIslandEnabled(this) ? SHOW_ISLAND : 0)
                    : 0;
            if (what != 0)
                requestShow(what);
            else
                stopLaterIfOnDemand();
        }
    }

    private void onPodsGone() {
        mMaybeConnected = false;
        mLastGoodStatus = null;
        mShowWhat = 0;
        mAlertPending = false;
        stopScanner();
        publish();
        stopSelf();
    }

    private void stopIfIdle() {
        if (mMaybeConnected)
            return;
        if (mShowWhat != 0) // 바로가기/루틴으로 불렀는데 연결된 에어팟이 없음
            android.widget.Toast.makeText(this, R.string.toast_not_connected, android.widget.Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    // ---------------- 스캔 창 (듀티 사이클) ----------------

    // 앱/위젯이 요청한 즉석 갱신. 너무 잦으면 무시.
    private void requestRefresh() {
        if (!mMaybeConnected || mScanning)
            return;
        if (System.currentTimeMillis() - mLastScanEndAt < ONDEMAND_MIN_GAP)
            return;
        openScanWindow(REFRESH_MS);
    }

    // 화면이 켜졌거나 잠금이 풀린 순간.
    private void onScreenInteractive() {
        if (!mMaybeConnected || mScanning)
            return;
        // 절전 옵션 + 아직 잠금 화면 → 무시
        if (isScanSaverEnabled(this) && !isInteractive())
            return;
        long since = System.currentTimeMillis() - mLastScanEndAt;
        // 갱신 주기를 넘겼으면(또는 즉석 갱신 최소 간격 경과) 바로 스캔
        if (since >= batteryRefreshSeconds(this) * 1000L || since >= ONDEMAND_MIN_GAP)
            openScanWindow(REFRESH_MS);
    }

    // 화면이 켜져 있고 잠금도 풀린 상태인가
    private boolean isInteractive() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        boolean screenOn = pm == null || pm.isInteractive();
        boolean locked = km != null && km.isKeyguardLocked();
        return screenOn && !locked;
    }

    private void openScanWindow(long durationMs) {
        if (!mMaybeConnected)
            return;
        mHandler.removeCallbacks(mEndWindow);
        mHandler.removeCallbacks(mReopenWindow);
        // 같은 주기로 제어 채널에도 배터리 재요청 (연결돼 있으면)
        if (mAacp != null && isAacpBatteryEnabled(this))
            mAacp.requestBattery();
        startScanner(isFastScanOnConnect(this));
        mHandler.postDelayed(mEndWindow, durationMs);
        // 고속 구간이 끝나면 일반 속도로 낮춰 다시 스캔
        if (isFastScanOnConnect(this) && durationMs > FAST_HEAD_MS)
            mHandler.postDelayed(() -> {
                if (mScanning)
                    startScanner(false);
            }, FAST_HEAD_MS);
    }

    private final Runnable mReopenWindow = () -> openScanWindow(REFRESH_MS);

    private final Runnable mEndWindow = () -> {
        if (mAppForeground > 0)
            return; // 앱이 열려 있는 동안엔 계속 스캔
        stopScanner();
        mLastScanEndAt = System.currentTimeMillis();
        if (!mMaybeConnected)
            return;
        // 온디맨드 모드는 주기 스캔 자체를 하지 않는다 — 여기서 서비스를 접는다
        // (아직 띄우지 못한 알림/아일랜드가 있으면 그게 끝난 뒤에 접힌다)
        if (isOnDemandEnabled(this)) {
            if (!mAlertPending && mShowWhat == 0)
                stopSelf();
            return;
        }
        // 절전 옵션 + 화면 꺼짐/잠금 → 다음 주기 스캔을 예약하지 않음 (잠금 해제 시 따라잡음)
        if (isScanSaverEnabled(this) && !isInteractive())
            return;
        mHandler.postDelayed(mReopenWindow, batteryRefreshSeconds(this) * 1000L);
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
            PodsSnapshot.lastBeaconAt = mLastGoodAt;
        }
        publish();
    }

    /** 값이 바뀐 경우에만 알림/위젯/메인 화면을 갱신한다. */
    private void publish() {
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
            PodsSnapshot.last = snap;
            broadcastSnapshot();
            PodsWidget.updateAll(this);
            mNM.notify(NOTIFICATION_ID, buildFgNotification());
        }
    }

    private String mLastSig = null;

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
        mShowWhat |= what;
        mAlertDeadline = System.currentTimeMillis() + SETTLE_MS;
        if (!mAlertPending) {
            mAlertPending = true;
            // 이미 값이 있으면 스캔도 대기도 없이 바로 띄운다 (루틴에서 부를 때 즉시 반응 + 스캔 0회)
            mHandler.postDelayed(this::fireShow, hasBattery() ? 0 : WEAR_SETTLE_MS);
        }
    }

    private static boolean hasBattery() {
        PodsSnapshot s = PodsSnapshot.last;
        return s.available && (s.leftPct >= 0 || s.rightPct >= 0 || s.casePct >= 0);
    }

    private void fireShow() {
        mAlertPending = false;
        PodsSnapshot s = PodsSnapshot.last;
        boolean ready = mMaybeConnected && hasBattery();
        if (!ready) {
            // 아직 연결 확인 전이거나 배터리 값을 못 받음 — 마감 시각 전이면 재시도
            if (System.currentTimeMillis() < mAlertDeadline) {
                if (mMaybeConnected && !mScanning)
                    openScanWindow(REFRESH_MS); // 값이 없을 때만 비콘 스캔
                mAlertPending = true;
                mHandler.postDelayed(this::fireShow, 2000);
                return;
            }
            mShowWhat = 0;
            stopLaterIfOnDemand();
            return;
        }
        int what = mShowWhat;
        mShowWhat = 0;
        if ((what & SHOW_NOTIF) != 0)
            showWearAlert(wearBody(s));
        if ((what & SHOW_ISLAND) != 0)
            IslandOverlay.show(this, s);
        stopLaterIfOnDemand();
    }

    /**
     * 온디맨드 모드: 보여줄 걸 다 보여줬으면 서비스를 완전히 종료한다.
     * 이후엔 에어팟 재연결 / 바로가기(루틴) / 앱·위젯 열기 때만 잠깐 다시 뜬다.
     */
    private void stopLaterIfOnDemand() {
        if (!isOnDemandEnabled(this) || mAppForeground > 0)
            return;
        mHandler.postDelayed(() -> {
            if (isOnDemandEnabled(this) && mAppForeground == 0 && !mAlertPending)
                stopSelf();
        }, IslandOverlay.showMs(this) + 1500);
    }

    private String wearBody(PodsSnapshot s) {
        return getString(R.string.pod_left) + " " + dash(s.left)
                + "    " + getString(R.string.pod_right) + " " + dash(s.right)
                + "    " + getString(R.string.pod_case) + " " + dash(s.caseB);
    }

    private void showWearAlert(String body) {
        boolean snd = isWearSoundEnabled(this), vib = isWearVibrateEnabled(this);
        String channel = snd && vib ? WEAR_SV : snd ? WEAR_S : vib ? WEAR_V : WEAR_SILENT;

        Notification n = new NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.wear_alert_title))
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setTimeoutAfter(8000)
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

        String label = getString(R.string.wear_channel);
        createWearChannel(WEAR_SV, label + " (소리+진동)", true, true);
        createWearChannel(WEAR_S, label + " (소리)", true, false);
        createWearChannel(WEAR_V, label + " (진동)", false, true);
        createWearChannel(WEAR_SILENT, label + " (무음)", false, false);

        mNM.deleteNotificationChannel("AirPods_wear");
        mNM.deleteNotificationChannel("wear_alert_v2");
        mNM.deleteNotificationChannel("FOREGROUND_ID");
        mNM.deleteNotificationChannel("background_min");
    }

    private void createWearChannel(String id, CharSequence name, boolean sound, boolean vibrate) {
        NotificationChannel c = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        c.setShowBadge(false);
        if (!sound)
            c.setSound(null, null);
        c.enableVibration(vibrate);
        mNM.createNotificationChannel(c);
    }

    private Notification buildFgNotification() {
        PodsSnapshot s = PodsSnapshot.last;
        if (mMaybeConnected && s.available && isPersistentNotificationEnabled(this))
            return mBuilder.build(s);

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
        mAacp = new AacpManager(new AacpManager.Listener() {
            @Override
            public void onAacpConnected(boolean connected) {
                mAacpConnected = connected;
                if (!connected)
                    mControls.clear();
                mHandler.post(PodsService.this::publish);
            }

            @Override
            public void onControl(int id, int value) {
                mControls.put(id, value);
                mHandler.post(PodsService.this::publish);
            }

            @Override
            public void onBattery(int lp, boolean lc, int rp, boolean rc, int cp, boolean cc) {
                mAbL = lp; mAbLc = lc;
                mAbR = rp; mAbRc = rc;
                mAbC = cp; mAbCc = cc;
                mAacpBatAt = System.currentTimeMillis();
                mHandler.post(() -> {
                    publish();
                    endScanEarlyIfAacp();
                });
            }
        });
        mAacp.start(mDevice);
    }

    /** 제어 채널에서 정확한 배터리를 받았으면 비콘 스캔을 더 돌릴 이유가 없다. */
    private void endScanEarlyIfAacp() {
        if (!mScanning || mAppForeground > 0 || !aacpBatteryUsable())
            return;
        mHandler.removeCallbacks(mEndWindow);
        mHandler.post(mEndWindow);
    }

    private void stopAacp() {
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
                if (n.contains("airpod") || n.contains("beats") || n.contains("pods"))
                    return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
