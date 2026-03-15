package de.kai_morich.simple_bluetooth_le_terminal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * show list of BLE devices
 */
public class DevicesFragment extends ListFragment {

    private enum ScanState { NONE, LE_SCAN, DISCOVERY, DISCOVERY_FINISHED }
    private ScanState scanState = ScanState.NONE;
    private static final long LE_SCAN_PERIOD = 10000; // similar to bluetoothAdapter.startDiscovery
    private final Handler leScanStopHandler = new Handler();
    private final BluetoothAdapter.LeScanCallback leScanCallback;
    private final Runnable leScanStopCallback;
    private final BroadcastReceiver discoveryBroadcastReceiver;
    private final IntentFilter discoveryIntentFilter;

    private Menu menu;
    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothUtil.Device> listItems = new ArrayList<>();
    private ArrayAdapter<BluetoothUtil.Device> listAdapter;
    ActivityResultLauncher<String[]> requestBluetoothPermissionLauncherForStartScan;
    ActivityResultLauncher<String> requestLocationPermissionLauncherForStartScan;

    public DevicesFragment() {
        leScanCallback = (device, rssi, scanRecord) -> {
            if(device != null && getActivity() != null) {
                getActivity().runOnUiThread(() -> { updateScan(device); });
            }
        };
        discoveryBroadcastReceiver = new BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            @Override
            public void onReceive(Context context, Intent intent) {
                if(BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if(device.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC && getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateScan(device));
                    }
                }
                if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                    scanState = ScanState.DISCOVERY_FINISHED; // don't cancel again
                    stopScan();
                }
            }
        };
        discoveryIntentFilter = new IntentFilter();
        discoveryIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        discoveryIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        leScanStopCallback = this::stopScan; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
        requestBluetoothPermissionLauncherForStartScan = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                granted -> BluetoothUtil.onPermissionsResult(this, granted, this::startScan));
        requestLocationPermissionLauncherForStartScan = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        new Handler(Looper.getMainLooper()).postDelayed(this::startScan, 1); // run after onResume to avoid wrong empty-text
                    } else {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                        builder.setTitle(getText(R.string.location_permission_title));
                        builder.setMessage(getText(R.string.location_permission_denied));
                        builder.setPositiveButton(android.R.string.ok, null);
                        builder.show();
                    }
                });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        if(getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        listAdapter = new ArrayAdapter<BluetoothUtil.Device>(getActivity(), 0, listItems) {
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                BluetoothUtil.Device device = listItems.get(position);
                if (view == null)
                    view = getActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                String deviceName = device.getName();
                if(deviceName == null || deviceName.isEmpty())
                    deviceName = "<unnamed>";
                text1.setText(deviceName);
                text2.setText(device.getDevice().getAddress());
                return view;
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(null);
        View header = getActivity().getLayoutInflater().inflate(R.layout.device_list_header, null, false);
        getListView().addHeaderView(header, null, false);
        setEmptyText("initializing...");
        ((TextView) getListView().getEmptyView()).setTextSize(18);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
        this.menu = menu;
        if (bluetoothAdapter == null) {
            menu.findItem(R.id.bt_settings).setEnabled(false);
            menu.findItem(R.id.ble_scan).setEnabled(false);
        } else if(!bluetoothAdapter.isEnabled()) {
            menu.findItem(R.id.ble_scan).setEnabled(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if(bluetoothAdapter == null) {
            setEmptyText("<bluetooth LE not supported>");
        } else if(!bluetoothAdapter.isEnabled()) {
            setEmptyText("<bluetooth is disabled>");
            if (menu != null) {
                listItems.clear();
                listAdapter.notifyDataSetChanged();
                menu.findItem(R.id.ble_scan).setEnabled(false);
            }
        } else {
            refreshConnectedDevices();
            if (menu != null)
                menu.findItem(R.id.ble_scan).setEnabled(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopScan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        menu = null;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.ble_scan) {
            refreshConnectedDevices();
            return true;
        } else if (id == R.id.ble_scan_stop) {
            stopScan();
            return true;
        } else if (id == R.id.bt_settings) {
            Intent intent = new Intent();
            intent.setAction(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intent);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void startScan() {
        refreshConnectedDevices();
    }

    @SuppressLint("MissingPermission")
    private void refreshConnectedDevices() {
        listItems.clear();

        if (bluetoothAdapter == null) {
            setEmptyText("<bluetooth LE not supported>");
            listAdapter.notifyDataSetChanged();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            setEmptyText("<bluetooth is disabled>");
            listAdapter.notifyDataSetChanged();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                getActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            if(!BluetoothUtil.hasPermissions(this, requestBluetoothPermissionLauncherForStartScan)) {
                setEmptyText("<permission missing>");
                listAdapter.notifyDataSetChanged();
                return;
            }
        }

        populateConnectedDevices();
        populateBondedLeDevices();
        Collections.sort(listItems);
        if (listItems.isEmpty()) {
            setEmptyText("<no BLE devices found, pair/connect in system settings first>");
        } else {
            setEmptyText("");
        }
        listAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private boolean isBonded(BluetoothDevice target) {
        return target != null && target.getBondState() == BluetoothDevice.BOND_BONDED;
    }

    private void legacyScanPathNoLongerUsed() {
        if(scanState != ScanState.NONE)
            return;
        ScanState nextScanState = ScanState.LE_SCAN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if(!BluetoothUtil.hasPermissions(this, requestBluetoothPermissionLauncherForStartScan))
                return;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                scanState = ScanState.NONE;
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(R.string.location_permission_title);
                builder.setMessage(R.string.location_permission_grant);
                builder.setPositiveButton(android.R.string.ok,
                        (dialog, which) -> requestLocationPermissionLauncherForStartScan.launch(Manifest.permission.ACCESS_FINE_LOCATION));
                builder.show();
                return;
            }
            LocationManager locationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
            boolean         locationEnabled = false;
            try {
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch(Exception ignored) {}
            try {
                locationEnabled |= locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch(Exception ignored) {}
            if(!locationEnabled)
                scanState = ScanState.DISCOVERY;
            // Starting with Android 6.0 a bluetooth scan requires ACCESS_COARSE_LOCATION permission, but that's not all!
            // LESCAN also needs enabled 'location services', whereas DISCOVERY works without.
            // Most users think of GPS as 'location service', but it includes more, as we see here.
            // Instead of asking the user to enable something they consider unrelated,
            // we fall back to the older API that scans for bluetooth classic _and_ LE
            // sometimes the older API returns less results or slower
        }
        scanState = nextScanState;
        listItems.clear();
        populateConnectedDevices();
        listAdapter.notifyDataSetChanged();
        setEmptyText("<scanning...>");
        menu.findItem(R.id.ble_scan).setVisible(false);
        menu.findItem(R.id.ble_scan_stop).setVisible(true);
        if(scanState == ScanState.LE_SCAN) {
            leScanStopHandler.postDelayed(leScanStopCallback, LE_SCAN_PERIOD);
            new Thread(() -> bluetoothAdapter.startLeScan(null, leScanCallback), "startLeScan")
                    .start(); // start async to prevent blocking UI, because startLeScan sometimes take some seconds
        } else {
            bluetoothAdapter.startDiscovery();
        }
    }

    @SuppressLint("MissingPermission")
    private void updateScan(BluetoothDevice device) {
        if(scanState == ScanState.NONE)
            return;
        BluetoothUtil.Device device2 = new BluetoothUtil.Device(device); // slow getName() only once
        int pos = Collections.binarySearch(listItems, device2);
        if (pos < 0) {
            listItems.add(-pos - 1, device2);
            listAdapter.notifyDataSetChanged();
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if(scanState == ScanState.NONE)
            return;
        setEmptyText("<no bluetooth devices found>");
        if(menu != null) {
            menu.findItem(R.id.ble_scan).setVisible(true);
            menu.findItem(R.id.ble_scan_stop).setVisible(false);
        }
        switch(scanState) {
            case LE_SCAN:
                leScanStopHandler.removeCallbacks(leScanStopCallback);
                bluetoothAdapter.stopLeScan(leScanCallback);
                break;
            case DISCOVERY:
                bluetoothAdapter.cancelDiscovery();
                break;
            default:
                // already canceled
        }
        scanState = ScanState.NONE;

    }

    @SuppressLint("MissingPermission")
    private void populateConnectedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled())
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                getActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return;

        BluetoothManager bluetoothManager = (BluetoothManager) getActivity().getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null)
            return;

        List<BluetoothDevice> connectedGatt = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for (BluetoothDevice device : connectedGatt) {
            if (device.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC)
                addDeviceIfMissing(device);
        }

        List<BluetoothDevice> connectedGattServer = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER);
        for (BluetoothDevice device : connectedGattServer) {
            if (device.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC)
                addDeviceIfMissing(device);
        }
    }

    @SuppressLint("MissingPermission")
    private void addDeviceIfMissing(BluetoothDevice device) {
        BluetoothUtil.Device wrapped = new BluetoothUtil.Device(device);
        int pos = Collections.binarySearch(listItems, wrapped);
        if (pos < 0)
            listItems.add(-pos - 1, wrapped);
    }

    @SuppressLint("MissingPermission")
    private void populateBondedLeDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled())
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                getActivity().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return;

        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            int type = device.getType();
            if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                addDeviceIfMissing(device);
            }
        }
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        stopScan();
        BluetoothUtil.Device device = listItems.get(position-1);
        if (!isBonded(device.getDevice())) {
            Toast.makeText(getActivity(), "Device is not bonded. Pair it in system Bluetooth settings first.", Toast.LENGTH_LONG).show();
            refreshConnectedDevices();
            return;
        }

        SharedPreferences preferences = requireActivity().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putString(MainActivity.PREF_DEVICE_ADDRESS, device.getDevice().getAddress()).apply();
        Toast.makeText(getActivity(), "Dispositivo guardado para FallAlert", Toast.LENGTH_SHORT).show();
        requireActivity().getSupportFragmentManager().popBackStack();
    }
}
