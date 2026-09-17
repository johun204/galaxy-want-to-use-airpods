package com.dosse.airpods.receivers;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.utils.Logger;

/**
 * AirPods가 연결되는 순간 서비스를 되살린다.
 * 서비스는 연결이 끊기면 스스로 종료하므로, 평소엔 아무 것도 돌지 않는다.
 * ACL_CONNECTED 는 매니페스트 수신이 허용된 브로드캐스트이고, BLUETOOTH_CONNECT 권한이
 * 걸린 이 브로드캐스트를 받으면 백그라운드에서도 포그라운드 서비스를 시작할 수 있다.
 */
public class AirPodsConnectionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction()))
            return;

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (!PodsService.isPods(device))
            return;

        try {
            // 기기를 그대로 넘겨서 서비스가 async 프록시 콜백을 기다리지 않고
            // 즉시 스캔(부스트 모드)을 시작하게 한다 → 헤즈업 알림 지연 방지
            Intent start = new Intent(context, PodsService.class)
                    .setAction(PodsService.ACTION_DEVICE_CONNECTED)
                    .putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            context.startForegroundService(start);
        } catch (Throwable t) {
            Logger.error(t);
        }
    }
}
