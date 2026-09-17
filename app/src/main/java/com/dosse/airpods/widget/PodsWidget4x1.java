package com.dosse.airpods.widget;

import com.dosse.airpods.R;

/** 가로 4 x 세로 1 위젯. 레이아웃만 다르고 나머지는 {@link PodsWidget} 과 동일. */
public class PodsWidget4x1 extends PodsWidget {
    @Override
    protected int layout() {
        return R.layout.widget_pods_4x1;
    }
}
