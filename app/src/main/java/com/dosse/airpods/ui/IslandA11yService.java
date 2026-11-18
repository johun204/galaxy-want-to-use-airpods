package com.dosse.airpods.ui;

import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.pods.PodsSnapshot;
import com.dosse.airpods.utils.DiagLog;

/**
 * 다이나믹 아일랜드를 접근성 오버레이 층에 띄우기 위한 접근성 서비스.
 * <p>
 * '다른 앱 위에 표시' 창은 시스템이 잠금 화면 아래에 숨기고 헤즈업 알림 배너보다도 아래에 그린다.
 * 접근성 오버레이는 잠금 화면·알림 창보다 위 층이라 잠금 상태에서도 바로 보이고 배너에 가려지지 않는다.
 * <p>
 * 접근성 이벤트는 하나도 구독하지 않는다(설정 xml 에 이벤트 종류 없음) — 화면 내용을 읽지 않고,
 * 이벤트로 깨어나지도 않는다. 오버레이 창을 붙일 자격만 쓴다.
 * <p>
 * 배터리: 시스템이 이 서비스를 계속 살려 두므로, 화면이 꺼져 있어 못 띄운 아일랜드는 여기서 기다린다.
 * 그 덕에 PodsService(포그라운드 서비스 + 에어팟 제어 채널)는 기다리지 않고 바로 종료한다.
 * 기다리는 동안엔 주기적으로 깨어나지 않고 화면 켜짐/연결 해제 브로드캐스트만 받는다.
 */
public class IslandA11yService extends AccessibilityService {
    private static volatile IslandA11yService sInstance;

    private BroadcastReceiver mWaiter;   // 화면이 켜지길 기다리는 아일랜드가 있을 때만 등록
    private long mWaitUntil = 0;

    /**
     * 화면이 꺼져 있어 못 띄운 아일랜드를 넘겨받아, 화면이 켜지는 순간 마지막 배터리 값으로 띄운다.
     * 메인 스레드에서 호출. @return 넘겨받았으면 true (접근성 서비스가 꺼져 있으면 false)
     */
    public static boolean deferIsland(long until) {
        IslandA11yService s = sInstance;
        if (s == null)
            return false;
        s.waitForScreen(until);
        return true;
    }

    private void waitForScreen(long until) {
        mWaitUntil = until;
        if (mWaiter != null)
            return;
        mWaiter = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(i.getAction())) {
                    if (PodsService.isPods(i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))) {
                        DiagLog.add(c, "island cancelled: AirPods disconnected");
                        stopWaiting();
                    }
                    return;
                }
                // 화면 켜짐: 잠금 화면 위에도 보이므로 바로 띄운다
                if (System.currentTimeMillis() > mWaitUntil) {
                    DiagLog.add(c, "island dropped: screen stayed off for 10 min");
                } else {
                    boolean ok = IslandOverlay.show(IslandA11yService.this, PodsSnapshot.last);
                    DiagLog.add(c, ok ? "island shown on screen on (accessibility)"
                            : "island failed: " + IslandOverlay.lastFailure);
                }
                stopWaiting();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ContextCompat.registerReceiver(this, mWaiter, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void stopWaiting() {
        if (mWaiter == null)
            return;
        try {
            unregisterReceiver(mWaiter);
        } catch (Throwable ignored) {
        }
        mWaiter = null;
    }

    /** 켜져 있으면 접근성 오버레이를 붙일 수 있는 컨텍스트, 아니면 null */
    public static IslandA11yService instance() {
        return sInstance;
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopWaiting();
        IslandOverlay.hide();
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
