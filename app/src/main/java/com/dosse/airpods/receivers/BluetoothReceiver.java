package com.dosse.airpods.receivers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public abstract class BluetoothReceiver extends BroadcastReceiver {
    public abstract void onStart();

    public abstract void onStop();

    public abstract void onConnect(BluetoothDevice bluetoothDevice);

    public abstract void onDisconnect(BluetoothDevice bluetoothDevice);

    /** LE 링크의 연결/해제라서 무시한 경우 (진단용) */
    public void onLeLinkIgnored(BluetoothDevice device, String action) {
    }

    /**
     * ACL 브로드캐스트가 LE 링크에 관한 것인가. 에어팟 연결 여부는 클래식 링크로만 판단한다 —
     * 같은 기기의 LE 링크가 잠깐 붙었다 끊겨도 "연결/해제"로 착각하지 않게. (EXTRA_TRANSPORT 는 Android 13+)
     */
    public static boolean isLeLink(Intent intent) {
        return intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
                == BluetoothDevice.TRANSPORT_LE;
    }

    /**
     * When the service is created, we register to get as many bluetooth and airpods related events as possible.
     * ACL_CONNECTED and ACL_DISCONNECTED should have been enough, but you never know with android these days.
     */
    public static IntentFilter buildFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        intentFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
        intentFilter.addAction("android.bluetooth.device.action.BOND_STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.device.action.NAME_CHANGED");
        intentFilter.addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT");
        intentFilter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED");
        intentFilter.addCategory("android.bluetooth.headset.intent.category.companyid.76");
        return intentFilter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        BluetoothDevice bluetoothDevice = intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
        String action = intent.getAction();

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

            // Bluetooth turned off, stop scanner and remove notification.
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                onStop();
            }

            // Bluetooth turned on, start/restart scanner.
            if (state == BluetoothAdapter.STATE_ON) {
                onStart();
            }
        }

        // Airpods filter
        if (bluetoothDevice != null && action != null && !action.isEmpty()) {
            if (action.startsWith("android.bluetooth.device.action.ACL_") && isLeLink(intent)) {
                onLeLinkIgnored(bluetoothDevice, action);
                return;
            }

            // Airpods connected, show notification.
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                onConnect(bluetoothDevice);
            }

            // Airpods disconnected, remove notification but leave the scanner going.
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                    || BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action)) {
                onDisconnect(bluetoothDevice);
            }
        }
    }
}