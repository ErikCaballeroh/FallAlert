package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
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
        Button setChatIdButton = view.findViewById(R.id.btn_set_chat_id);
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

        setChatIdButton.setOnClickListener(v -> showChatIdDialog());

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

    private void showChatIdDialog() {
        if (getActivity() == null) {
            return;
        }

        SharedPreferences preferences = requireActivity().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String currentChatId = preferences.getString(MainActivity.PREF_CHAT_ID, "");

        EditText input = new EditText(getActivity());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setHint("Ej: -1001234567890");
        input.setText(currentChatId);

        new AlertDialog.Builder(getActivity())
                .setTitle("Chat ID de Telegram")
                .setMessage("Ingresa el Chat ID del grupo o canal donde el bot enviara alertas.")
                .setView(input)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String chatId = input.getText().toString().trim();
                    try {
                        Long.parseLong(chatId);
                    } catch (NumberFormatException e) {
                        Toast.makeText(getActivity(), "Chat ID invalido", Toast.LENGTH_LONG).show();
                        return;
                    }
                    preferences.edit().putString(MainActivity.PREF_CHAT_ID, chatId).apply();
                    Toast.makeText(getActivity(), "Chat ID guardado", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

}
