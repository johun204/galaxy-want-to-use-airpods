package com.dosse.airpods.receivers;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import com.dosse.airpods.utils.Logger;

public abstract class BluetoothListener implements BluetoothProfile.ServiceListener {
    public abstract boolean onConnect(BluetoothDevice bluetoothDevice);

    public abstract void onDisconnect();

    @Override
    public void onServiceConnected(int profile, BluetoothProfile bluetoothProfile) {
        if (profile != BluetoothProfile.HEADSET) {
            return;
        }

        // 근처 기기(블루투스) 권한이 없으면 SecurityException — 시스템 콜백이라 잡지 않으면 프로세스가 죽는다
        try {
            for (BluetoothDevice device : bluetoothProfile.getConnectedDevices()) {
                if (onConnect(device)) {
                    break;
                }
            }
        } catch (Throwable t) {
            Logger.error(t);
        }
    }

    @Override
    public void onServiceDisconnected(int profile) {
        if (profile == BluetoothProfile.HEADSET) {
            onDisconnect();
        }
    }
}