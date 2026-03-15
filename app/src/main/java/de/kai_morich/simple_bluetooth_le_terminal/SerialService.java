package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
public class SerialService extends Service implements SerialListener {

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        ArrayDeque<byte[]> datas;
        Exception e;

        QueueItem(QueueType type) { this.type=type; if(type==QueueType.Read) init(); }
        QueueItem(QueueType type, Exception e) { this.type=type; this.e=e; }
        QueueItem(QueueType type, ArrayDeque<byte[]> datas) { this.type=type; this.datas=datas; }

        void init() { datas = new ArrayDeque<>(); }
        void add(byte[] data) { datas.add(data); }
    }

    private final Handler mainLooper;
    private final IBinder binder;
    private final ArrayDeque<QueueItem> queue1, queue2;
    private final QueueItem lastRead;
    private final ExecutorService httpExecutor;
    private final StringBuilder incomingLineBuffer;

    private SerialSocket socket;
    private SerialListener listener;
    private boolean connected;

    private static final String MESSAGE_API_URL = "http://192.168.1.65:3000/api/message";

    /**
     * Lifecylce
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new ArrayDeque<>();
        queue2 = new ArrayDeque<>();
        lastRead = new QueueItem(QueueType.Read);
        httpExecutor = Executors.newSingleThreadExecutor();
        incomingLineBuffer = new StringBuilder();
    }

    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        httpExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Api
     */
    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    public void disconnect() {
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if(socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    public void write(byte[] data) throws IOException {
        if(!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    public boolean isConnected() {
        return connected;
    }

    public void enableBackgroundMode() {
        initNotification();
        createNotification();
    }

    public void attach(SerialListener listener) {
        if(Looper.getMainLooper().getThread() != Thread.currentThread())
            throw new IllegalArgumentException("not in main thread");
        initNotification();
        cancelNotification();
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.listener = listener;
        }
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        for(QueueItem item : queue2) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if(connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null;
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public boolean areNotificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return nm.areNotificationsEnabled() && nc != null && nc.getImportance() > NotificationManager.IMPORTANCE_NONE;
    }

    private void createNotification() {
        Intent disconnectIntent = new Intent()
                .setPackage(getPackageName())
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent,  flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to "+socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect", disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    /**
     * SerialListener
     */
    public void onSerialConnect() {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnect();
                        } else {
                            queue1.add(new QueueItem(QueueType.Connect));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Connect));
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnectError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.ConnectError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.ConnectError, e));
                    disconnect();
                }
            }
        }
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) { throw new UnsupportedOperationException(); }

    /**
     * reduce number of UI updates by merging data chunks.
     * Data can arrive at hundred chunks per second, but the UI can only
     * perform a dozen updates if receiveText already contains much text.
     *
     * On new data inform UI thread once (1).
     * While not consumed (2), add more data (3).
     */
    public void onSerialRead(byte[] data) {
        handleIncomingForApi(data);
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    boolean first;
                    synchronized (lastRead) {
                        first = lastRead.datas.isEmpty(); // (1)
                        lastRead.add(data); // (3)
                    }
                    if(first) {
                        mainLooper.post(() -> {
                            ArrayDeque<byte[]> datas;
                            synchronized (lastRead) {
                                datas = lastRead.datas;
                                lastRead.init(); // (2)
                            }
                            if (listener != null) {
                                listener.onSerialRead(datas);
                            } else {
                                queue1.add(new QueueItem(QueueType.Read, datas));
                            }
                        });
                    }
                } else {
                    if(queue2.isEmpty() || queue2.getLast().type != QueueType.Read)
                        queue2.add(new QueueItem(QueueType.Read));
                    queue2.getLast().add(data);
                }
            }
        }
    }

    private void handleIncomingForApi(byte[] data) {
        String chunk = new String(data, StandardCharsets.UTF_8);
        synchronized (incomingLineBuffer) {
            incomingLineBuffer.append(chunk);
            int newlineIndex;
            while ((newlineIndex = incomingLineBuffer.indexOf("\n")) != -1) {
                String message = incomingLineBuffer.substring(0, newlineIndex).replace("\r", "").trim();
                incomingLineBuffer.delete(0, newlineIndex + 1);
                if (!message.isEmpty()) {
                    sendMessageToApi(message);
                }
            }
        }
    }

    private void sendMessageToApi(String message) {
        httpExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                SharedPreferences preferences = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
                String phoneNumber = preferences.getString(MainActivity.PREF_PHONE_NUMBER, "");
                String messageWithPhone = message;
                if (phoneNumber != null && phoneNumber.matches("\\d{10}")) {
                    messageWithPhone = message + " | phone:" + phoneNumber;
                }

                URL url = new URL(MESSAGE_API_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("message", messageWithPhone);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                connection.getResponseCode();
            } catch (Exception ignored) {
                // Keep service resilient in background; transient API/network errors are ignored.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    public void onSerialIoError(Exception e) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialIoError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.IoError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.IoError, e));
                    disconnect();
                }
            }
        }
    }

}
