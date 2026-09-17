package com.dosse.airpods.pods;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.pods.models.SinglePods;

import java.util.HashMap;
import java.util.Map;

/**
 * 알림 / 위젯 / 메인 화면이 공유하는 배터리 상태 스냅샷.
 * PodsService가 갱신하고 {@link #last} 에 저장한다. 배터리 값의 출처는 BLE 비콘 또는
 * L2CAP 제어 채널(AACP) — 후자는 내 폰에 페어링된 그 에어팟에서 직접 온다.
 */
public class PodsSnapshot {
    public static final String ACTION = "com.dosse.airpods.SNAPSHOT";
    public static volatile PodsSnapshot last = new PodsSnapshot();

    public final boolean available;   // 표시할 배터리 데이터가 있음
    public final boolean single;      // AirPods Max / Beats 등 단일 유닛
    public String left, right, caseB;                 // "80%" 또는 ""
    public int leftPct, rightPct, casePct;            // 0..100, -1=모름 (배터리 링 채움용)
    public boolean leftChg, rightChg, caseChg;
    public final boolean leftInEar, rightInEar;
    public int leftImg, rightImg, caseImg;            // drawable 리소스 ID (single이면 caseImg만 사용)

    public int rssi = 0; // 최근 비콘 신호 세기 (내 에어팟 찾기용). 0=모름
    public boolean stale = false; // 값은 있지만 마지막 갱신이 오래됨
    public long updatedAt = 0;    // 이 배터리 값을 받은 시각 (epoch ms). 0=모름
    public static volatile long lastBeaconAt = 0; // 마지막 비콘 수신 시각 (신호 신선도 판단)

    // AACP(L2CAP 제어 채널) 상태 - PodsService가 채운다
    public boolean aacpConnected = false;
    public String deviceName = "";
    public Map<Integer, Integer> controls = new HashMap<>();

    public int noiseMode() {
        Integer m = controls.get(AacpManager.ID_LISTENING_MODE);
        return m == null ? 0 : m;
    }

    /** 토글 상태: 1=on, 2=off (LibrePods 규약). null=미지원 */
    public Integer control(int id) {
        return controls.get(id);
    }

    public boolean isOn(int id) {
        Integer v = controls.get(id);
        return v != null && v == 1;
    }

    private static int pct(Pod pod) {
        if (pod == null || !pod.isConnected())
            return -1;
        int s = pod.getStatus();
        return s >= 10 ? 100 : s * 10 + 5;
    }

    private static String fmt(int p) {
        return p < 0 ? "" : p + "%";
    }

    public PodsSnapshot() { // 데이터 없음
        available = false;
        single = false;
        left = right = caseB = "";
        leftPct = rightPct = casePct = -1;
        leftChg = rightChg = caseChg = false;
        leftInEar = rightInEar = false;
        leftImg = R.drawable.pod_left_disconnected;
        rightImg = R.drawable.pod_right_disconnected;
        caseImg = R.drawable.pod_case_disconnected;
    }

    public PodsSnapshot(PodsStatus status) {
        available = true;
        IPods pods = status.getAirpods();
        single = pods.isSingle();

        if (single) {
            SinglePods s = (SinglePods) pods;
            left = right = "";
            caseB = s.getParsedStatus();
            leftPct = rightPct = -1;
            casePct = pct(s.getPod());
            leftChg = rightChg = false;
            caseChg = s.getPod().isCharging();
            leftInEar = rightInEar = false;
            leftImg = R.drawable.pod_left_disconnected;
            rightImg = R.drawable.pod_right_disconnected;
            caseImg = s.getDrawable();
        } else {
            RegularPods r = (RegularPods) pods;
            left = r.getParsedStatus(RegularPods.LEFT);
            right = r.getParsedStatus(RegularPods.RIGHT);
            caseB = r.getParsedStatus(RegularPods.CASE);
            leftPct = pct(r.getPod(RegularPods.LEFT));
            rightPct = pct(r.getPod(RegularPods.RIGHT));
            casePct = pct(r.getPod(RegularPods.CASE));
            leftChg = r.getPod(RegularPods.LEFT).isCharging();
            rightChg = r.getPod(RegularPods.RIGHT).isCharging();
            caseChg = r.getPod(RegularPods.CASE).isCharging();
            leftInEar = r.getPod(RegularPods.LEFT).isInEar();
            rightInEar = r.getPod(RegularPods.RIGHT).isInEar();
            leftImg = r.getLeftDrawable();
            rightImg = r.getRightDrawable();
            caseImg = r.getCaseDrawable();
        }
    }

    /** 비콘 없이 AACP 배터리만으로 구성 (일반 AirPods 외형). */
    public static PodsSnapshot fromAacp(int lPct, boolean lChg, int rPct, boolean rChg, int cPct, boolean cChg) {
        return new PodsSnapshot(lPct, lChg, rPct, rChg, cPct, cChg);
    }

    private PodsSnapshot(int lPct, boolean lChg, int rPct, boolean rChg, int cPct, boolean cChg) {
        available = true;
        single = false;
        leftInEar = rightInEar = false;
        leftImg = rightImg = caseImg = 0; // overrideBattery 가 채움
        overrideBattery(lPct, lChg, rPct, rChg, cPct, cChg);
    }

    /** 이미 만든 스냅샷의 배터리 값을 AACP 값으로 덮어쓴다 (모델/일러스트/착용 상태는 유지). */
    public void overrideBattery(int lPct, boolean lChg, int rPct, boolean rChg, int cPct, boolean cChg) {
        leftPct = lPct; leftChg = lChg && lPct >= 0; left = fmt(lPct);
        rightPct = rPct; rightChg = rChg && rPct >= 0; right = fmt(rPct);
        casePct = cPct; caseChg = cChg && cPct >= 0; caseB = fmt(cPct);
        leftImg = imgFor(leftImg, lPct >= 0, R.drawable.pod_left, R.drawable.pod_left_disconnected);
        rightImg = imgFor(rightImg, rPct >= 0, R.drawable.pod_right, R.drawable.pod_right_disconnected);
        caseImg = imgFor(caseImg, cPct >= 0, R.drawable.pod_case, R.drawable.pod_case_disconnected);
    }

    // 현재 이미지가 연결/미연결 어느 계열이든 present 에 맞는 계열로 바꿔준다.
    private static int imgFor(int current, boolean present, int fallbackConn, int fallbackDisc) {
        int conn = connRes(current), disc = discRes(current);
        if (conn == 0) { // 알 수 없는 리소스 → 일반 AirPods 로
            conn = fallbackConn;
            disc = fallbackDisc;
        }
        return present ? conn : disc;
    }

    private static int connRes(int r) {
        if (r == R.drawable.pod_left || r == R.drawable.pod_left_disconnected) return R.drawable.pod_left;
        if (r == R.drawable.pod_right || r == R.drawable.pod_right_disconnected) return R.drawable.pod_right;
        if (r == R.drawable.pod_case || r == R.drawable.pod_case_disconnected) return R.drawable.pod_case;
        if (r == R.drawable.podpro_left || r == R.drawable.podpro_left_disconnected) return R.drawable.podpro_left;
        if (r == R.drawable.podpro_right || r == R.drawable.podpro_right_disconnected) return R.drawable.podpro_right;
        if (r == R.drawable.podpro_case || r == R.drawable.podpro_case_disconnected) return R.drawable.podpro_case;
        if (r == R.drawable.podmax || r == R.drawable.podmax_disconnected) return R.drawable.podmax;
        return 0;
    }

    private static int discRes(int r) {
        if (r == R.drawable.pod_left || r == R.drawable.pod_left_disconnected) return R.drawable.pod_left_disconnected;
        if (r == R.drawable.pod_right || r == R.drawable.pod_right_disconnected) return R.drawable.pod_right_disconnected;
        if (r == R.drawable.pod_case || r == R.drawable.pod_case_disconnected) return R.drawable.pod_case_disconnected;
        if (r == R.drawable.podpro_left || r == R.drawable.podpro_left_disconnected) return R.drawable.podpro_left_disconnected;
        if (r == R.drawable.podpro_right || r == R.drawable.podpro_right_disconnected) return R.drawable.podpro_right_disconnected;
        if (r == R.drawable.podpro_case || r == R.drawable.podpro_case_disconnected) return R.drawable.podpro_case_disconnected;
        if (r == R.drawable.podmax || r == R.drawable.podmax_disconnected) return R.drawable.podmax_disconnected;
        return 0;
    }
}
