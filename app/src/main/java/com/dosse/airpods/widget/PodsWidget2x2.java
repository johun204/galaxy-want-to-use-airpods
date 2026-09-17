package com.dosse.airpods.widget;

import com.dosse.airpods.R;

/** 2x2 정사각형 위젯. 레이아웃만 다르고 나머지는 {@link PodsWidget} 과 동일. */
public class PodsWidget2x2 extends PodsWidget {
    @Override
    protected int layout() {
        return R.layout.widget_pods_2x2;
    }
}
