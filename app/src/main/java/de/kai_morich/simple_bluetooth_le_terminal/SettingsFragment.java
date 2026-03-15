package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Button selectDeviceButton = view.findViewById(R.id.btn_select_device);
        Button connectDeviceButton = view.findViewById(R.id.btn_connect_device);
        Button setPhoneButton = view.findViewById(R.id.btn_set_phone);
        Button openSystemBluetoothButton = view.findViewById(R.id.btn_open_system_bluetooth);
        Button openLogsButton = view.findViewById(R.id.btn_open_logs);

        selectDeviceButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openDevicesConfig();
            }
        });

        connectDeviceButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).connectSelectedDeviceFromSettings();
            }
        });

        setPhoneButton.setOnClickListener(v -> showPhoneDialog());

        openSystemBluetoothButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intent);
        });

        openLogsButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openLogsScreen();
            }
        });
    }

    private void showPhoneDialog() {
        if (getActivity() == null) {
            return;
        }

        SharedPreferences preferences = requireActivity().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String currentPhone = preferences.getString(MainActivity.PREF_PHONE_NUMBER, "");

        EditText input = new EditText(getActivity());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        input.setHint("Ej: 3001234567");
        input.setText(currentPhone);

        new AlertDialog.Builder(getActivity())
                .setTitle("Numero de emergencia")
                .setMessage("Ingresa un numero de 10 digitos")
                .setView(input)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String phone = input.getText().toString().trim();
                    if (!phone.matches("\\d{10}")) {
                        Toast.makeText(getActivity(), "Debe tener exactamente 10 digitos", Toast.LENGTH_LONG).show();
                        return;
                    }
                    preferences.edit().putString(MainActivity.PREF_PHONE_NUMBER, phone).apply();
                    Toast.makeText(getActivity(), "Numero guardado", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
