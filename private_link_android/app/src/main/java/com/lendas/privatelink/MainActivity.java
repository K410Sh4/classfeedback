package com.lendas.privatelink;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final UUID SERVICE_UUID = UUID.fromString("621dd260-3266-4eba-afd4-3c2141905dc1");
    private static final UUID CONTROL_UUID = UUID.fromString("ffe335b5-7234-4023-ba02-2248ed84cd1b");
    private static final UUID RESPONSE_UUID = UUID.fromString("65adba79-fcc7-41ef-a2e5-8faee3247b40");
    private static final UUID OTA_UUID = UUID.fromString("119cde0a-c330-4ee8-89f2-98daa95897ad");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int PAIRING_PASSKEY = 496110;
    private static final int MIN_RSSI = -78;
    private static final int REQUEST_BLE_PERMISSIONS = 1001;
    private static final int REQUEST_FIRMWARE = 1002;
    private static final int OTA_CHUNK = 180;\n    private static final int MAX_WRITE_START_RETRIES = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> devices = new LinkedHashMap<>();
    private final ArrayDeque<WriteTask> writeQueue = new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic controlChar;
    private BluetoothGattCharacteristic responseChar;
    private BluetoothGattCharacteristic otaChar;
    private BluetoothDevice pendingDevice;

    private boolean scanning = false;
    private boolean connected = false;
    private boolean authenticated = false;
    private boolean writing = false;
    private boolean otaActive = false;
    private boolean waitingOtaReady = false;
    private boolean waitingOtaFinal = false;

    private byte[] privateKey;
    private byte[] pendingFirmware;
    private String pendingFirmwareName;
    private int otaOffset = 0;

    private LinearLayout deviceList;
    private TextView stateText;
    private TextView telemetryText;
    private TextView logText;
    private ProgressBar otaProgress;
    private Button scanButton;
    private Button pingButton;
    private Button statusButton;
    private Button rebootButton;
    private Button otaButton;
    private EditText keyField;
    private LinearLayout keyPanel;
    private LinearLayout mainPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        buildUi();
        loadPrivateKey();

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondReceiver, filter);

        if (adapter == null) {
            setState("Bluetooth não disponível neste aparelho.");
            scanButton.setEnabled(false);
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        disconnectGatt();
        try {
            unregisterReceiver(bondReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF101417);

        TextView title = text("LENDAS S3 PrivateLink", 24, true);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);

        TextView subtitle = text("ESP32-S3 N16R8 • BLE privado • OTA", 14, false);
        subtitle.setTextColor(0xFF9FB2BA);
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(32));
        scroll.addView(content);

        keyPanel = card();
        TextView keyTitle = text("Configuração privada", 18, true);
        keyTitle.setTextColor(0xFFFFFFFF);
        keyPanel.addView(keyTitle);

        TextView keyHelp = text(
                "Cole a chave HMAC de 64 caracteres do PROJECT_PRIVATE_KEYS.txt. " +
                "Ela fica criptografada no armazenamento local do Android.", 13, false);
        keyHelp.setTextColor(0xFFB8C6CC);
        keyHelp.setPadding(0, dp(6), 0, dp(8));
        keyPanel.addView(keyHelp);

        keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setHint("64 caracteres hexadecimais");
        keyField.setTextColor(0xFFFFFFFF);
        keyField.setHintTextColor(0xFF71838B);
        keyPanel.addView(keyField, new LinearLayout.LayoutParams(-1, dp(52)));

        Button saveKey = button("SALVAR CHAVE");
        saveKey.setOnClickListener(v -> savePrivateKey());
        keyPanel.addView(saveKey);

        TextView passkey = text("Passkey BLE: " + PAIRING_PASSKEY, 13, false);
        passkey.setTextColor(0xFF9FB2BA);
        passkey.setPadding(0, dp(8), 0, 0);
        keyPanel.addView(passkey);

        content.addView(keyPanel);

        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        mainPanel.setVisibility(View.GONE);

        LinearLayout stateCard = card();
        stateText = text("Aguardando ESP32-S3", 18, true);
        stateText.setTextColor(0xFFFFFFFF);
        stateCard.addView(stateText);

        TextView security = text(
                "LE Secure Connections + Bond + HMAC-SHA256", 13, false);
        security.setTextColor(0xFF8FD7C7);
        stateCard.addView(security);
        mainPanel.addView(stateCard);

        scanButton = button("BUSCAR MEU ESP32-S3");
        scanButton.setOnClickListener(v -> {
            if (scanning) stopScan();
            else startScan();
        });
        mainPanel.addView(scanButton);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        mainPanel.addView(deviceList);

        LinearLayout telemetryCard = card();
        TextView telemetryTitle = text("Telemetria", 18, true);
        telemetryTitle.setTextColor(0xFFFFFFFF);
        telemetryCard.addView(telemetryTitle);

        telemetryText = text("Firmware: -\nUptime: -\nHeap: -\nBateria: -", 14, false);
        telemetryText.setTextColor(0xFFD3DEE2);
        telemetryText.setPadding(0, dp(8), 0, dp(8));
        telemetryCard.addView(telemetryText);

        LinearLayout commands = new LinearLayout(this);
        commands.setOrientation(LinearLayout.HORIZONTAL);

        pingButton = button("PING");
        statusButton = button("STATUS");
        rebootButton = button("REINICIAR");

        pingButton.setOnClickListener(v -> sendCommand("PING"));
        statusButton.setOnClickListener(v -> sendCommand("STATUS"));
        rebootButton.setOnClickListener(v -> sendCommand("REBOOT"));

        commands.addView(pingButton, weightParams());
        commands.addView(statusButton, weightParams());
        commands.addView(rebootButton, weightParams());
        telemetryCard.addView(commands);

        mainPanel.addView(telemetryCard);

        LinearLayout otaCard = card();
        TextView otaTitle = text("Atualização OTA", 18, true);
        otaTitle.setTextColor(0xFFFFFFFF);
        otaCard.addView(otaTitle);

        TextView otaHelp = text(
                "Selecione o .bin da aplicação. O S3 valida SHA-256 + HMAC " +
                "antes de ativar o novo firmware.", 13, false);
        otaHelp.setTextColor(0xFFB8C6CC);
        otaHelp.setPadding(0, dp(6), 0, dp(10));
        otaCard.addView(otaHelp);

        otaProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        otaProgress.setMax(1000);
        otaCard.addView(otaProgress, new LinearLayout.LayoutParams(-1, dp(18)));

        otaButton = button("SELECIONAR FIRMWARE .BIN");
        otaButton.setOnClickListener(v -> chooseFirmware());
        otaCard.addView(otaButton);
        mainPanel.addView(otaCard);

        LinearLayout logCard = card();
        TextView logTitle = text("Console", 18, true);
        logTitle.setTextColor(0xFFFFFFFF);
        logCard.addView(logTitle);

        logText = text("Pronto.", 11, false);
        logText.setTextColor(0xFFB7C7CE);
        logText.setTextIsSelectable(true);
        logCard.addView(logText, new LinearLayout.LayoutParams(-1, dp(220)));
        mainPanel.addView(logCard);

        Button changeKey = button("TROCAR CHAVE PRIVADA");
        changeKey.setOnClickListener(v -> clearPrivateKey());
        mainPanel.addView(changeKey);

        content.addView(mainPanel);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        setAuthenticatedControls(false);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundColor(0xFF182126);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setState(String value) {
        if (stateText != null) stateText.setText(value);
    }

    private void log(String value) {
        runOnUiThread(() -> {
            String old = logText == null ? "" : logText.getText().toString();
            String line = "[" + new java.text.SimpleDateFormat(
                    "HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + "] " + value;
            String next = line + "\n" + old;
            if (next.length() > 14000) next = next.substring(0, 14000);
            if (logText != null) logText.setText(next);
        });
    }

    private void toast(String value) {
        runOnUiThread(() ->
                Toast.makeText(this, value, Toast.LENGTH_LONG).show());
    }

    private void loadPrivateKey() {
        try {
            String encoded = getPreferences(MODE_PRIVATE).getString("hmac_blob", null);
            if (encoded != null) {
                privateKey = decryptStoredKey(encoded);
            }
        } catch (Exception e) {
            log("Falha ao carregar chave local: " + e.getMessage());
        }

        boolean has = privateKey != null && privateKey.length == 32;
        keyPanel.setVisibility(has ? View.GONE : View.VISIBLE);
        mainPanel.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private void savePrivateKey() {
        String value = keyField.getText().toString().trim();

        if (!value.matches("(?i)[0-9a-f]{64}")) {
            toast("A chave precisa ter 64 caracteres hexadecimais.");
            return;
        }

        try {
            byte[] key = hexToBytes(value);
            String blob = encryptStoredKey(key);
            getPreferences(MODE_PRIVATE).edit().putString("hmac_blob", blob).apply();
            privateKey = key;
            keyField.setText("");
            keyPanel.setVisibility(View.GONE);
            mainPanel.setVisibility(View.VISIBLE);
            toast("Chave salva no Android Keystore.");
        } catch (Exception e) {
            toast("Falha ao salvar chave: " + e.getMessage());
        }
    }

    private void clearPrivateKey() {
        disconnectGatt();
        getPreferences(MODE_PRIVATE).edit().remove("hmac_blob").apply();
        privateKey = null;
        keyPanel.setVisibility(View.VISIBLE);
        mainPanel.setVisibility(View.GONE);
    }

    private SecretKey getStorageKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        if (!ks.containsAlias("lendas_private_link_store")) {
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(
                    "lendas_private_link_store",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }

        return ((KeyStore.SecretKeyEntry) ks.getEntry(
                "lendas_private_link_store", null)).getSecretKey();
    }

    private String encryptStoredKey(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getStorageKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);

        byte[] all = new byte[1 + iv.length + encrypted.length];
        all[0] = (byte) iv.length;
        System.arraycopy(iv, 0, all, 1, iv.length);
        System.arraycopy(encrypted, 0, all, 1 + iv.length, encrypted.length);

        return android.util.Base64.encodeToString(all, android.util.Base64.NO_WRAP);
    }

    private byte[] decryptStoredKey(String encoded) throws Exception {
        byte[] all = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
        int ivLen = all[0] & 0xFF;
        byte[] iv = Arrays.copyOfRange(all, 1, 1 + ivLen);
        byte[] encrypted = Arrays.copyOfRange(all, 1 + ivLen, all.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getStorageKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT < 31) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQUEST_BLE_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_BLE_PERMISSIONS);
        }
    }

    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            toast("Ative o Bluetooth.");
            return;
        }

        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("Scanner BLE indisponível.");
            return;
        }

        devices.clear();
        deviceList.removeAllViews();

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        scanButton.setText("PARAR BUSCA");
        setState("Buscando seu PrivateLink...");
        scanner.startScan(Arrays.asList(filter), settings, scanCallback);

        mainHandler.postDelayed(() -> {
            if (scanning) stopScan();
        }, 12000);
    }

    private void stopScan() {
        if (scanner != null && scanning && hasBlePermissions()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }

        scanning = false;
        if (scanButton != null) scanButton.setText("BUSCAR MEU ESP32-S3");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            if (result.getRssi() < MIN_RSSI) return;

            String address = result.getDevice().getAddress();
            boolean isNew = !devices.containsKey(address);
            devices.put(address, result);

            if (isNew) runOnUiThread(MainActivity.this::rebuildDeviceList);
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("Scan BLE falhou: " + errorCode);
            runOnUiThread(() -> {
                scanning = false;
                scanButton.setText("BUSCAR MEU ESP32-S3");
            });
        }
    };

    private void rebuildDeviceList() {
        deviceList.removeAllViews();

        for (ScanResult result : devices.values()) {
            BluetoothDevice device = result.getDevice();

            LinearLayout row = card();

            TextView info = text(
                    "ESP32-S3 PrivateLink\n" +
                    device.getAddress() + " • RSSI " + result.getRssi() + " dBm",
                    13, false);
            info.setTextColor(0xFFD2DEE2);
            row.addView(info);

            Button connect = button("CONECTAR");
            connect.setOnClickListener(v -> beginConnection(device));
            row.addView(connect);
            deviceList.addView(row);
        }
    }

    private void beginConnection(BluetoothDevice device) {
        stopScan();

        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }

        pendingDevice = device;

        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            setState("Pareando... confirme o código " + PAIRING_PASSKEY);
            toast("Quando o Android pedir, use o código " + PAIRING_PASSKEY);
            boolean started = device.createBond();

            if (!started) {
                log("Android não iniciou o bonding automaticamente.");
                connectGatt(device);
            }
            return;
        }

        connectGatt(device);
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);

            if (device == null || pendingDevice == null ||
                    !device.getAddress().equals(pendingDevice.getAddress())) {
                return;
            }

            if (state == BluetoothDevice.BOND_BONDED) {
                log("Pareamento concluído.");
                connectGatt(device);
            } else if (state == BluetoothDevice.BOND_NONE) {
                setState("Pareamento cancelado.");
                log("Pareamento não concluído.");
            }
        }
    };

    private void connectGatt(BluetoothDevice device) {
        if (!hasBlePermissions()) return;

        disconnectGatt();

        setState("Conectando ao ESP32-S3...");
        log("Conectando em " + device.getAddress());

        if (Build.VERSION.SDK_INT >= 23) {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private void disconnectGatt() {
        connected = false;
        authenticated = false;
        setAuthenticatedControls(false);
        writeQueue.clear();
        writing = false;

        if (gatt != null && hasBlePermissions()) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
        }

        gatt = null;
        controlChar = null;
        responseChar = null;
        otaChar = null;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                runOnUiThread(() -> setState("BLE conectado • preparando canal seguro"));
                log("BLE conectado.");

                if (hasBlePermissions()) {
                    bluetoothGatt.requestConnectionPriority(
                            BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    bluetoothGatt.requestMtu(247);

                    mainHandler.postDelayed(() -> {
                        if (connected && gatt == bluetoothGatt) {
                            bluetoothGatt.discoverServices();
                        }
                    }, 450);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                authenticated = false;
                otaActive = false;
                runOnUiThread(() -> {
                    setState("Desconectado");
                    setAuthenticatedControls(false);
                });
                log("BLE desconectado. status=" + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
            log("MTU: " + mtu);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Falha ao descobrir serviços: " + status);
                return;
            }

            BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
            if (service == null) {
                log("Serviço PrivateLink não encontrado.");
                return;
            }

            controlChar = service.getCharacteristic(CONTROL_UUID);
            responseChar = service.getCharacteristic(RESPONSE_UUID);
            otaChar = service.getCharacteristic(OTA_UUID);

            if (controlChar == null || responseChar == null || otaChar == null) {
                log("Characteristics incompletas.");
                return;
            }

            if (!hasBlePermissions()) return;

            bluetoothGatt.setCharacteristicNotification(responseChar, true);
            BluetoothGattDescriptor cccd = responseChar.getDescriptor(CCCD_UUID);

            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    bluetoothGatt.writeDescriptor(
                            cccd,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    bluetoothGatt.writeDescriptor(cccd);
                }
            } else {
                mainHandler.postDelayed(MainActivity.this::beginAuthentication, 350);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status) {
            if (descriptor.getUuid().equals(CCCD_UUID)) {
                log("Notifications ativas. status=" + status);
                mainHandler.postDelayed(MainActivity.this::beginAuthentication, 1200);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            if (characteristic.getUuid().equals(RESPONSE_UUID)) {
                handleNotification(value);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33 &&
                    characteristic.getUuid().equals(RESPONSE_UUID)) {
                handleNotification(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            WriteTask completed = null;

            synchronized (writeQueue) {
                if (!writeQueue.isEmpty()) {
                    completed = writeQueue.pollFirst();
                }
                writing = false;
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write BLE falhou. status=" + status);
                if (otaActive) failOta("Falha de escrita BLE");
            } else if (completed != null && completed.onSuccess != null) {
                mainHandler.post(completed.onSuccess);
            }

            pumpWrites();
        }
    };

    private int authGeneration = 0;

    private void beginAuthentication() {
        if (!connected || controlChar == null || privateKey == null) return;

        authenticated = false;
        final int generation = ++authGeneration;

        runOnUiThread(() -> setState("Autenticando aplicativo..."));
        log("Iniciando HELLO seguro...");
        enqueueText(controlChar, "HELLO", null);

        mainHandler.postDelayed(() -> {
            if (generation != authGeneration || authenticated || !connected) return;

            log("Timeout aguardando HELLO/AUTH. Reiniciando handshake uma vez...");
            synchronized (writeQueue) {
                writeQueue.clear();
                writing = false;
            }
            enqueueText(controlChar, "HELLO", null);
        }, 8000);
    }

    private void handleNotification(byte[] bytes) {
        if (bytes == null) return;
        String message = new String(bytes, StandardCharsets.UTF_8);
        log("S3 → " + message);

        if (message.startsWith("HELLO|")) {
            try {
                String nonceHex = message.substring("HELLO|".length()).trim();
                byte[] nonce = hexToBytes(nonceHex);
                String macHex = hmacHex(privateKey, nonce);
                enqueueText(controlChar, "AUTH|" + macHex, null);
            } catch (Exception e) {
                log("HELLO inválido: " + e.getMessage());
            }
            return;
        }

        if (message.startsWith("AUTH_OK|")) {
            authenticated = true;
            authGeneration++;
            runOnUiThread(() -> {
                setState("Canal privado autenticado");
                setAuthenticatedControls(true);
            });
            enqueueText(controlChar, "STATUS", null);
            return;
        }

        if (message.startsWith("AUTH_FAIL|")) {
            authenticated = false;
            runOnUiThread(() -> setState("Chave privada rejeitada"));
            return;
        }

        if (message.startsWith("TEL|")) {
            updateTelemetry(message);
            return;
        }

        if (message.startsWith("OTA_READY|") && waitingOtaReady) {
            waitingOtaReady = false;
            otaActive = true;
            otaOffset = 0;
            mainHandler.post(this::sendNextOtaChunk);
            return;
        }

        if (message.startsWith("OTA_PROGRESS|")) {
            String[] p = message.split("\\|");
            if (p.length >= 3) {
                try {
                    long done = Long.parseLong(p[1]);
                    long total = Long.parseLong(p[2]);
                    int progress = total <= 0 ? 0 : (int) Math.min(1000, done * 1000L / total);
                    runOnUiThread(() -> otaProgress.setProgress(progress));
                } catch (Exception ignored) {}
            }
            return;
        }

        if (message.startsWith("OTA_OK|")) {
            waitingOtaFinal = false;
            otaActive = false;
            runOnUiThread(() -> {
                otaProgress.setProgress(1000);
                otaButton.setEnabled(true);
                toast("Firmware validado. O ESP32-S3 vai reiniciar.");
            });
            return;
        }

        if (message.startsWith("OTA_ERROR|")) {
            failOta(message);
        }
    }

    private void updateTelemetry(String message) {
        String firmware = "-";
        String uptime = "-";
        String heap = "-";
        String battery = "-";

        for (String field : message.split("\\|")) {
            int idx = field.indexOf('=');
            if (idx <= 0) continue;

            String key = field.substring(0, idx);
            String value = field.substring(idx + 1);

            if ("fw".equals(key)) firmware = value;
            else if ("uptime_ms".equals(key)) uptime = value;
            else if ("heap".equals(key)) heap = value;
            else if ("battery_v".equals(key)) battery = value;
        }

        String finalFirmware = firmware;
        String finalUptime = uptime;
        String finalHeap = heap;
        String finalBattery = battery;

        runOnUiThread(() -> telemetryText.setText(
                "Firmware: " + finalFirmware +
                "\nUptime: " + finalUptime + " ms" +
                "\nHeap: " + finalHeap +
                "\nBateria: " + finalBattery));
    }

    private void setAuthenticatedControls(boolean enabled) {
        if (pingButton != null) pingButton.setEnabled(enabled);
        if (statusButton != null) statusButton.setEnabled(enabled);
        if (rebootButton != null) rebootButton.setEnabled(enabled);
        if (otaButton != null) otaButton.setEnabled(enabled && !otaActive);
    }

    private void sendCommand(String command) {
        if (!authenticated || controlChar == null) return;
        enqueueText(controlChar, command, null);
    }

    private void chooseFirmware() {
        if (!authenticated || otaActive) return;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/octet-stream", "application/x-binary", "*/*"});
        startActivityForResult(intent, REQUEST_FIRMWARE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_FIRMWARE ||
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        new Thread(() -> {
            try {
                byte[] image = readAll(uri);

                if (image.length == 0) {
                    throw new IllegalArgumentException("Arquivo vazio.");
                }

                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                String shaHex = bytesToHex(sha.digest(image));
                String hmacHex = hmacHex(privateKey, image);

                pendingFirmware = image;
                pendingFirmwareName = "firmware.bin";

                String begin = "OTA_BEGIN|" + image.length + "|" +
                        shaHex + "|" + hmacHex + "|" + pendingFirmwareName;

                waitingOtaReady = true;

                runOnUiThread(() -> {
                    otaProgress.setProgress(0);
                    otaButton.setEnabled(false);
                    setState("Preparando atualização OTA...");
                });

                enqueueText(controlChar, begin, null);
            } catch (Exception e) {
                failOta("Não foi possível abrir firmware: " + e.getMessage());
            }
        }).start();
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Arquivo indisponível.");

            byte[] buffer = new byte[16384];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            return out.toByteArray();
        }
    }

    private void sendNextOtaChunk() {
        if (!otaActive || pendingFirmware == null || otaChar == null) return;

        if (otaOffset >= pendingFirmware.length) {
            waitingOtaFinal = true;
            enqueueText(controlChar, "OTA_END", null);
            runOnUiThread(() -> setState("Validando firmware no ESP32-S3..."));
            return;
        }

        int end = Math.min(pendingFirmware.length, otaOffset + OTA_CHUNK);
        byte[] chunk = Arrays.copyOfRange(pendingFirmware, otaOffset, end);
        int sentEnd = end;

        enqueueWrite(otaChar, chunk, () -> {
            otaOffset = sentEnd;
            int progress = (int) Math.min(
                    1000L, ((long) otaOffset * 1000L) / pendingFirmware.length);
            otaProgress.setProgress(progress);
            sendNextOtaChunk();
        });
    }

    private void failOta(String reason) {
        otaActive = false;
        waitingOtaReady = false;
        waitingOtaFinal = false;

        runOnUiThread(() -> {
            otaButton.setEnabled(authenticated);
            setState(authenticated ? "Canal privado autenticado" : "OTA interrompida");
            toast("OTA falhou: " + reason);
        });

        log("OTA falhou: " + reason);
    }

    private void enqueueText(BluetoothGattCharacteristic characteristic,
                             String value,
                             Runnable onSuccess) {
        enqueueWrite(characteristic, value.getBytes(StandardCharsets.UTF_8), onSuccess);
    }

    private void enqueueWrite(BluetoothGattCharacteristic characteristic,
                              byte[] value,
                              Runnable onSuccess) {
        synchronized (writeQueue) {
            writeQueue.addLast(new WriteTask(characteristic, value, onSuccess));
        }
        pumpWrites();
    }

    private void pumpWrites() {
        BluetoothGatt localGatt = gatt;
        if (localGatt == null || !connected || !hasBlePermissions()) return;

        WriteTask task;

        synchronized (writeQueue) {
            if (writing || writeQueue.isEmpty()) return;
            task = writeQueue.peekFirst();
            writing = true;
        }

        boolean started;
        int startStatus = 0;

        if (Build.VERSION.SDK_INT >= 33) {
            startStatus = localGatt.writeCharacteristic(
                    task.characteristic,
                    task.payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            started = startStatus == 0;
        } else {
            task.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            task.characteristic.setValue(task.payload);
            started = localGatt.writeCharacteristic(task.characteristic);
            startStatus = started ? 0 : -1;
        }

        if (!started) {
            task.startAttempts++;

            synchronized (writeQueue) {
                writing = false;
            }

            log("Write BLE ocupado/recusado. código=" + startStatus +
                    " tentativa=" + task.startAttempts + "/" + MAX_WRITE_START_RETRIES);

            if (task.startAttempts >= MAX_WRITE_START_RETRIES) {
                synchronized (writeQueue) {
                    writeQueue.pollFirst();
                }

                if (!authenticated && !otaActive) {
                    final int finalStartStatus = startStatus;
                    runOnUiThread(() ->
                            setState("Falha BLE antes do HELLO • código " + finalStartStatus));
                }

                if (otaActive) {
                    failOta("BLE recusou escrita. código=" + startStatus);
                }

                return;
            }

            long retryDelay = Math.min(1800L, 180L * task.startAttempts);
            mainHandler.postDelayed(this::pumpWrites, retryDelay);
        }
    }

    private static String hmacHex(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return bytesToHex(mac.doFinal(data));
    }

    private static byte[] hexToBytes(String input) {
        if (input == null || (input.length() % 2) != 0) {
            throw new IllegalArgumentException("Hex inválido.");
        }

        byte[] out = new byte[input.length() / 2];

        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(input.charAt(i * 2), 16);
            int lo = Character.digit(input.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Hex inválido.");
            out[i] = (byte) ((hi << 4) | lo);
        }

        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }

    private static class WriteTask {
        final BluetoothGattCharacteristic characteristic;
        final byte[] payload;
        final Runnable onSuccess;
        int startAttempts = 0;

        WriteTask(BluetoothGattCharacteristic characteristic,
                  byte[] payload,
                  Runnable onSuccess) {
            this.characteristic = characteristic;
            this.payload = payload;
            this.onSuccess = onSuccess;
        }
    }
}
