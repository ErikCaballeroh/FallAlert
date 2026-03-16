package de.kai_morich.simple_bluetooth_le_terminal;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;

import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    public static final String PREFS_NAME = "fallalert_prefs";
    public static final String PREF_DEVICE_ADDRESS = "selected_device_address";
    public static final String PREF_CHAT_ID = "telegram_chat_id";

    private static final String TAG_HOME = "home";
    private static final String TAG_SETTINGS = "settings";

    private SerialService service;
    private boolean serviceBound;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((SerialService.SerialBinder) binder).getService();
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        Intent serviceIntent = new Intent(this, SerialService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new HomeFragment(), TAG_HOME).commit();
        } else {
            onBackStackChanged();
        }
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public void openSettings() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, new SettingsFragment(), TAG_SETTINGS)
                .addToBackStack(null)
                .commit();
    }

    public void openDevicesConfig() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, new DevicesFragment(), "devices")
                .addToBackStack(null)
                .commit();
    }

    public void openLogsScreen() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String deviceAddress = preferences.getString(PREF_DEVICE_ADDRESS, null);
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            Toast.makeText(this, "Primero selecciona un dispositivo en Configuracion", Toast.LENGTH_LONG).show();
            return;
        }
        Bundle args = new Bundle();
        args.putString("device", deviceAddress);
        args.putBoolean("autoConnect", false);
        TerminalFragment fragment = new TerminalFragment();
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, fragment, "terminal")
                .addToBackStack(null)
                .commit();
    }

    public void startListeningInBackground() {
        if (!serviceBound || service == null) {
            Toast.makeText(this, "Servicio Bluetooth no disponible aun", Toast.LENGTH_SHORT).show();
            return;
        }
        service.enableBackgroundMode();
        if (service.isConnected()) {
            Toast.makeText(this, "Escucha en segundo plano activada", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Servicio activo, pero sin conexion. Conecta desde Configuracion", Toast.LENGTH_LONG).show();
        }
    }

    public void connectSelectedDeviceFromSettings() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String deviceAddress = preferences.getString(PREF_DEVICE_ADDRESS, null);
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            Toast.makeText(this, "Selecciona un dispositivo en Configuracion primero", Toast.LENGTH_LONG).show();
            return;
        }
        if (!serviceBound || service == null) {
            Toast.makeText(this, "Servicio Bluetooth no disponible aun", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Toast.makeText(this, "Activa Bluetooth en ajustes del sistema", Toast.LENGTH_LONG).show();
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            if (!service.isConnected()) {
                SerialSocket socket = new SerialSocket(getApplicationContext(), device);
                service.connect(socket);
            }
            service.enableBackgroundMode();
            Toast.makeText(this, "Conexion iniciada desde Configuracion", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Faltan permisos Bluetooth. Configuralos desde ajustes de la app", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "No se pudo iniciar escucha: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error iniciando servicio: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
