package com.dosse.airpods.pods.models;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.Pod;

public class PowerbeatsPro extends RegularPods {
    public PowerbeatsPro(Pod leftPod, Pod rightPod, Pod casePod) {
        super(leftPod, rightPod, casePod);
    }

    @Override
    public int getLeftDrawable() {
        return getPod(LEFT).isConnected() ? R.drawable.pod_left : R.drawable.pod_left_disconnected;
    }

    @Override
    public int getRightDrawable() {
        return getPod(RIGHT).isConnected() ? R.drawable.pod_right : R.drawable.pod_right_disconnected;
    }

    @Override
    public int getCaseDrawable() {
        return getPod(CASE).isConnected() ? R.drawable.pod_case : R.drawable.pod_case_disconnected;
    }

    @Override
    public String getModel() {
        return Constants.MODEL_POWERBEATS_PRO;
    }
}