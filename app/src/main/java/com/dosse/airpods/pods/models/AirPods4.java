package com.dosse.airpods.pods.models;

import com.dosse.airpods.pods.Pod;

// AirPods 4 (2024). 오픈형이라 기본 AirPods 일러스트(pod / pod_case)를 그대로 쓴다.
public class AirPods4 extends RegularPods {
    public AirPods4(Pod leftPod, Pod rightPod, Pod casePod) {
        super(leftPod, rightPod, casePod);
    }

    @Override
    public String getModel() {
        return Constants.MODEL_AIRPODS_GEN4;
    }
}
