/*
 * Created by Ubique Innovation AG
 * https://www.ubique.ch
 * Copyright (c) 2020. All rights reserved.
 */
package org.dpppt.android.sdk.internal;

import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;

import org.dpppt.android.sdk.DP3T;
import org.dpppt.android.sdk.R;
import org.dpppt.android.sdk.TracingStatus;
import org.dpppt.android.sdk.internal.crypto.CryptoModule;
import org.dpppt.android.sdk.internal.gatt.BleClient;
import org.dpppt.android.sdk.internal.gatt.BleServer;
import org.dpppt.android.sdk.internal.logger.Logger;

import static org.dpppt.android.sdk.internal.AppConfigManager.DEFAULT_SCAN_DURATION;
import static org.dpppt.android.sdk.internal.AppConfigManager.DEFAULT_SCAN_INTERVAL;

public class TracingService extends Service {

	private static final String TAG = "TracingService";

	public static final String ACTION_START = TracingService.class.getCanonicalName() + ".ACTION_START";
	public static final String ACTION_RESTART_CLIENT = TracingService.class.getCanonicalName() + ".ACTION_RESTART_CLIENT";
	public static final String ACTION_RESTART_SERVER = TracingService.class.getCanonicalName() + ".ACTION_RESTART_SERVER";
	public static final String ACTION_STOP = TracingService.class.getCanonicalName() + ".ACTION_STOP";

	public static final String EXTRA_ADVERTISE = TracingService.class.getCanonicalName() + ".EXTRA_ADVERTISE";
	public static final String EXTRA_RECEIVE = TracingService.class.getCanonicalName() + ".EXTRA_RECEIVE";
	public static final String EXTRA_SCAN_INTERVAL = TracingService.class.getCanonicalName() + ".EXTRA_SCAN_INTERVAL";
	public static final String EXTRA_SCAN_DURATION = TracingService.class.getCanonicalName() + ".EXTRA_SCAN_DURATION";

	private static final String NOTIFICATION_CHANNEL_ID = "dp3t_tracing_service";
	private static final int NOTIFICATION_ID = 1827;

	private Handler handler;
	private PowerManager.WakeLock wl;

	private BleServer bleServer;
	private BleClient bleClient;

	private final BroadcastReceiver bluetoothStateChangeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
				int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
				if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_ON) {
					invalidateForegroundNotification();
				}
			}
		}
	};

	private boolean startAdvertising;
	private boolean startReceiveing;
	private long scanInterval;
	private long scanDuration;

	public TracingService() { }

	@Override
	public void onCreate() {
		super.onCreate();

		IntentFilter bluetoothFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
		registerReceiver(bluetoothStateChangeReceiver, bluetoothFilter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || intent.getAction() == null) {
			stopSelf();
			return START_NOT_STICKY;
		}

		if (wl == null) {
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
					getPackageName() + ":TracingServiceWakeLock");
			wl.acquire();
		}

		Logger.i(TAG, "onStartCommand() with " + intent.getAction());

		scanInterval = intent.getLongExtra(EXTRA_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
		scanDuration = intent.getLongExtra(EXTRA_SCAN_DURATION, DEFAULT_SCAN_DURATION);

		startAdvertising = intent.getBooleanExtra(EXTRA_ADVERTISE, true);
		startReceiveing = intent.getBooleanExtra(EXTRA_RECEIVE, true);

		if (ACTION_START.equals(intent.getAction())) {
			startForeground(NOTIFICATION_ID, createForegroundNotification());
			start();
		} else if (ACTION_RESTART_CLIENT.equals(intent.getAction())) {
			startForeground(NOTIFICATION_ID, createForegroundNotification());
			ensureStarted();
			restartClient();
		} else if (ACTION_RESTART_SERVER.equals(intent.getAction())) {
			startForeground(NOTIFICATION_ID, createForegroundNotification());
			ensureStarted();
			restartServer();
		} else if (ACTION_STOP.equals(intent.getAction())) {
			stopForegroundService();
		}

		return START_REDELIVER_INTENT;
	}

	private Notification createForegroundNotification() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel();
		}

		Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		PendingIntent contentIntent = null;
		if (launchIntent != null) {
			contentIntent = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		}

		TracingStatus status = DP3T.getStatus(this);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
				.setOngoing(true)
				.setSmallIcon(R.drawable.ic_handshakes)
				.setContentIntent(contentIntent);

		if (status.getErrors().size() > 0) {
			String errorText = getNotificationErrorText(status.getErrors());
			builder.setContentTitle(getString(R.string.dp3t_sdk_service_notification_title))
					.setContentText(errorText)
					.setStyle(new NotificationCompat.BigTextStyle().bigText(errorText))
					.setPriority(NotificationCompat.PRIORITY_DEFAULT);
		} else {
			String text = getString(R.string.dp3t_sdk_service_notification_text);
			builder.setContentTitle(getString(R.string.dp3t_sdk_service_notification_title))
					.setContentText(text)
					.setStyle(new NotificationCompat.BigTextStyle().bigText(text))
					.setPriority(NotificationCompat.PRIORITY_LOW)
					.build();
		}

		return builder.build();
	}

	private String getNotificationErrorText(ArrayList<TracingStatus.ErrorState> errors) {
		StringBuilder sb = new StringBuilder(getString(R.string.dp3t_sdk_service_notification_errors)).append("\n");
		String sep = "";
		for (TracingStatus.ErrorState error : errors) {
			sb.append(sep).append(getString(error.getErrorString()));
			sep = ", ";
		}
		return sb.toString();
	}

	@RequiresApi(api = Build.VERSION_CODES.O)
	private void createNotificationChannel() {
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		String channelName = getString(R.string.dp3t_sdk_service_notification_channel);
		NotificationChannel channel =
				new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
		notificationManager.createNotificationChannel(channel);
	}

	private void invalidateForegroundNotification() {
		Notification notification = createForegroundNotification();
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(NOTIFICATION_ID, notification);
	}

	private void start() {
		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
		handler = new Handler();

		invalidateForegroundNotification();
		restartClient();
		restartServer();
	}

	private void ensureStarted() {
		if (handler == null) {
			handler = new Handler();
		}
		invalidateForegroundNotification();
	}

	private void restartClient() {
		try {
			startClient();
		} catch (Throwable t) {
			t.printStackTrace();
			Logger.e(TAG, t);
		}

		handler.postDelayed(() -> {
			stopScanning();
			scheduleNextClientRestart(this, scanInterval);
		}, scanDuration);
	}

	private void restartServer() {
		startServer();
		scheduleNextServerRestart(this);
	}

	public static void scheduleNextClientRestart(Context context, long scanInterval) {
		long now = System.currentTimeMillis();
		long delay = scanInterval - (now % scanInterval);
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		Intent intent = new Intent(context, TracingServiceBroadcastReceiver.class);
		intent.setAction(ACTION_RESTART_CLIENT);
		PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + delay, pendingIntent);
		} else {
			// setExact has exact behaviour below Android Marshmellow
			alarmManager.setExact(AlarmManager.RTC_WAKEUP, now + delay, pendingIntent);
		}
	}

	public static void scheduleNextServerRestart(Context context) {
		long nextEpochStart = CryptoModule.getInstance(context).getCurrentEpochStart() + CryptoModule.MILLISECONDS_PER_EPOCH;
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		Intent intent = new Intent(context, TracingServiceBroadcastReceiver.class);
		intent.setAction(ACTION_RESTART_SERVER);
		PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextEpochStart, pendingIntent);
		} else {
			// setExact has exact behaviour below Android Marshmellow
			alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextEpochStart, pendingIntent);
		}
	}

	private void stopForegroundService() {
		stopClient();
		stopServer();
		stopForeground(true);
		wl.release();
		stopSelf();
	}

	@Override
	public void onDestroy() {
		Logger.i(TAG, "onDestroy()");

		unregisterReceiver(bluetoothStateChangeReceiver);

		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void startServer() {
		stopServer();
		if (startAdvertising) {
			bleServer = new BleServer(this);
			bleServer.start();
			bleServer.startAdvertising();
			Logger.d(TAG, "startAdvertising");
		}
	}

	private void stopServer() {
		if (bleServer != null) {
			bleServer.stop();
			bleServer = null;
		}
	}

	private void startClient() {
		stopClient();
		if (startReceiveing) {
			bleClient = new BleClient(this);
			bleClient.setMinTimeToReconnectToSameDevice(scanInterval);
			bleClient.start();
			Logger.d(TAG, "startScanning");
		}
	}

	private void stopScanning() {
		if (bleClient != null) {
			bleClient.stopScan();
		}
	}

	private void stopClient() {
		if (bleClient != null) {
			bleClient.stop();
			bleClient = null;
		}
	}

}
