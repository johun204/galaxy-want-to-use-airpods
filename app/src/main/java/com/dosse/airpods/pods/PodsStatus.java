package com.dosse.airpods.pods;

import com.dosse.airpods.pods.models.AirPods1;
import com.dosse.airpods.pods.models.AirPods2;
import com.dosse.airpods.pods.models.AirPods3;
import com.dosse.airpods.pods.models.AirPods4;
import com.dosse.airpods.pods.models.AirPodsMax;
import com.dosse.airpods.pods.models.AirPodsPro;
import com.dosse.airpods.pods.models.AirPodsPro2;
import com.dosse.airpods.pods.models.AirPodsPro3;
import com.dosse.airpods.pods.models.BeatsFlex;
import com.dosse.airpods.pods.models.BeatsSolo3;
import com.dosse.airpods.pods.models.BeatsStudio3;
import com.dosse.airpods.pods.models.BeatsX;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.pods.models.Powerbeats3;
import com.dosse.airpods.pods.models.PowerbeatsPro;
import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.utils.Logger;

/**
 * Decoding the beacon:
 * This was done through reverse engineering. Hopefully it's correct.
 * - The beacon coming from a pair of AirPods/Beats contains a manufacturer specific data field n°76 of 27 bytes
 * - We convert this data to a hexadecimal string
 * - The 12th and 13th characters in the string represent the charge of the left and right pods.
 * Under unknown circumstances[1], they are right and left instead (see isFlipped). Values between 0 and 10 are battery 0-100%; Value 15 means it's disconnected
 * - The 15th character in the string represents the charge of the case. Values between 0 and 10 are battery 0-100%; Value 15 means it's disconnected
 * - The 14th character in the string represents the "in charge" status.
 * Bit 0 (LSB) is the left pod; Bit 1 is the right pod; Bit 2 is the case. Bit 3 might be case open/closed but I'm not sure and it's not used
 * - The 11th character in the string represents the in-ear detection status. Bit 1 is the left pod; Bit 3 is the right pod.
 * - The 7th character in the string represents the model
 * <p>
 * Notes:
 * 1) - isFlipped set by bit 1 of 10th character in the string; seems to be related to in-ear detection;
 */
public class PodsStatus {
    public static final PodsStatus DISCONNECTED = new PodsStatus();

    private IPods mPods;
    private final long mTimestamp = System.currentTimeMillis();

    private PodsStatus() {
    }

    public PodsStatus(String status) {
        if (status == null)
            return;

        boolean flip = isFlipped(status);

        int leftStatus = Integer.parseInt("" + status.charAt(flip ? 12 : 13), 16); // Left airpod (0-10 batt; 15=disconnected)
        int rightStatus = Integer.parseInt("" + status.charAt(flip ? 13 : 12), 16); // Right airpod (0-10 batt; 15=disconnected)
        int caseStatus = Integer.parseInt("" + status.charAt(15), 16); // Case (0-10 batt; 15=disconnected)
        int singleStatus = Integer.parseInt("" + status.charAt(13), 16); // Single (0-10 batt; 15=disconnected)

        int chargeStatus = Integer.parseInt("" + status.charAt(14), 16); // Charge status (bit 0=left; bit 1=right; bit 2=case)

        boolean chargeL = (chargeStatus & (flip ? 0b00000010 : 0b00000001)) != 0;
        boolean chargeR = (chargeStatus & (flip ? 0b00000001 : 0b00000010)) != 0;
        boolean chargeCase = (chargeStatus & 0b00000100) != 0;
        boolean chargeSingle = (chargeStatus & 0b00000001) != 0;

        int inEarStatus = Integer.parseInt("" + status.charAt(11), 16); // InEar status (bit 1=left; bit 3=right)

        boolean inEarL = (inEarStatus & (flip ? 0b00001000 : 0b00000010)) != 0;
        boolean inEarR = (inEarStatus & (flip ? 0b00000010 : 0b00001000)) != 0;

        Pod leftPod = new Pod(leftStatus, chargeL, inEarL);
        Pod rightPod = new Pod(rightStatus, chargeR, inEarR);
        Pod casePod = new Pod(caseStatus, chargeCase, false);
        Pod singlePod = new Pod(singleStatus, chargeSingle, false);

        // idFull = 모델 ID (Apple product id의 리틀엔디안 hex). 예: 0x2013 -> "1320"
        // 예전엔 idSingle(한 글자)로도 매칭했는데, AirPods 4(0x2019 -> "1920")가
        // Beats Studio 3의 '9' 매칭에 걸려 오인식됐다. 이제 idFull 전체로만 판별한다.
        String idFull = status.substring(6, 10);
        Logger.debug("Model id: " + idFull); // 디버그 빌드에서 기기별 ID 확인용 (adb logcat -s AirPods)

        switch (idFull) {
            case "0220": mPods = new AirPods1(leftPod, rightPod, casePod); break;       // AirPods 1세대
            case "0F20": mPods = new AirPods2(leftPod, rightPod, casePod); break;       // AirPods 2세대
            case "1320": mPods = new AirPods3(leftPod, rightPod, casePod); break;       // AirPods 3세대
            case "1920":                                                                // AirPods 4 (0x2019)
            case "1A20":                                                                // AirPods 4 ANC (0x201A)
            case "1B20": mPods = new AirPods4(leftPod, rightPod, casePod); break;       // AirPods 4 ANC (0x201B)
            case "0E20": mPods = new AirPodsPro(leftPod, rightPod, casePod); break;     // AirPods Pro
            case "1420":
            case "2420": mPods = new AirPodsPro2(leftPod, rightPod, casePod); break;    // AirPods Pro 2
            case "2720": mPods = new AirPodsPro3(leftPod, rightPod, casePod); break;    // AirPods Pro 3
            case "0A20": mPods = new AirPodsMax(singlePod); break;                      // AirPods Max (0x200A)
            case "0B20": mPods = new PowerbeatsPro(leftPod, rightPod, casePod); break;  // Powerbeats Pro (0x200B)
            case "0520": mPods = new BeatsX(singlePod); break;                          // Beats X
            case "1020": mPods = new BeatsFlex(singlePod); break;                       // Beats Flex
            case "0620": mPods = new BeatsSolo3(singlePod); break;                      // Beats Solo 3
            case "0720":
            case "0920": mPods = new BeatsStudio3(singlePod); break;                    // Beats Studio 3 (0x2007/0x2009)
            case "0320": mPods = new Powerbeats3(singlePod); break;                     // Powerbeats 3
            default:     mPods = new RegularPods(leftPod, rightPod, casePod); break;    // 미상 -> 일반 AirPods 취급
        }
    }

    public static boolean isFlipped(String str) {
        return (Integer.parseInt("" + str.charAt(10), 16) & 0x02) == 0;
    }

    public IPods getAirpods() {
        return mPods;
    }

    public boolean isAllDisconnected() {
        if (this == DISCONNECTED) {
            return true;
        }

        return mPods.isDisconnected();
    }

    public long getTimestamp() {
        return mTimestamp;
    }
}