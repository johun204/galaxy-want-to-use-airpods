package com.dosse.airpods.pods.models;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.Pod;

public class BeatsFlex extends SinglePods {
    public BeatsFlex(Pod singlePod) {
        super(singlePod);
    }

    @Override
    public int getDrawable() {
        return getPod().isConnected() ? R.drawable.pod : R.drawable.pod_disconnected;
    }

    @Override
    public String getModel() {
        return Constants.MODEL_BEATS_FLEX;
    }
}