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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private static final String TELEGRAM_BOT_URL = "https://api.telegram.org/bot" + BuildConfig.TELEGRAM_BOT_TOKEN + "/sendMessage";
    private static final long TELEGRAM_CHAT_ID = -1003837190435L;
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
                URL url = new URL(TELEGRAM_BOT_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);

                String formattedText =
                        "\uD83D\uDEA8 <b>¡ALERTA DE CAÍDA DETECTADA!</b> \uD83D\uDEA8\n\n" +
                        "\uD83D\uDCF1 Señal recibida: <code>" + message + "</code>\n\n" +
                        "⚠️ Se requiere atención inmediata.\n" +
                        "\uD83D\uDD14 Por favor responde a la brevedad.";

                SharedPreferences preferences = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
                String savedChatId = preferences.getString(MainActivity.PREF_CHAT_ID, "");
                long chatId = TELEGRAM_CHAT_ID;
                if (savedChatId != null && !savedChatId.isEmpty()) {
                    try {
                        chatId = Long.parseLong(savedChatId);
                    } catch (NumberFormatException ignored) {}
                }

                JSONObject payload = new JSONObject();
                payload.put("chat_id", chatId);
                payload.put("text", formattedText);
                payload.put("parse_mode", "HTML");

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int responseCode = connection.getResponseCode();
                String responseBody = readResponseBody(connection, responseCode);
                if (responseCode >= 200 && responseCode < 300) {
                    appendHttpLog("OK " + responseCode + " -> " + truncate(responseBody, 220));
                } else {
                    appendHttpLog("ERROR " + responseCode + " -> " + truncate(responseBody, 220));
                }
            } catch (Exception e) {
                appendHttpLog("EXCEPCION -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void appendHttpLog(String text) {
        byte[] data = ("[HTTP] " + text + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            if (listener != null) {
                mainLooper.post(() -> {
                    if (listener != null) {
                        ArrayDeque<byte[]> datas = new ArrayDeque<>();
                        datas.add(data);
                        listener.onSerialRead(datas);
                    } else {
                        queue1.add(new QueueItem(QueueType.Read));
                        queue1.getLast().add(data);
                    }
                });
            } else {
                if (queue2.isEmpty() || queue2.getLast().type != QueueType.Read) {
                    queue2.add(new QueueItem(QueueType.Read));
                }
                queue2.getLast().add(data);
            }
        }
    }

    private String readResponseBody(HttpURLConnection connection, int responseCode) {
        InputStream stream = null;
        try {
            stream = responseCode >= 200 && responseCode < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) {
                return "<sin body>";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.length() == 0 ? "<body vacio>" : sb.toString();
        } catch (Exception e) {
            return "<no se pudo leer body: " + e.getMessage() + ">";
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
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
