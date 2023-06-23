package wyvern.matrixpanel;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ConnectionPriority;
import com.welie.blessed.GattStatus;
import com.welie.blessed.HciStatus;
import com.welie.blessed.WriteType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final UUID SERVICE_UUID = UUID.fromString("8a3da2e4-4d2a-48b2-801a-2fbf3ee8a160");
    private static final UUID TX_CHARACTERISTIC_UUID = UUID.fromString("c97aaafc-adf2-4cab-883d-76719b863155");
    private static final UUID CMD_CHARACTERISTIC_UUID = UUID.fromString("cd2de314-70f9-4573-bc71-9c9335e8c963");
    private static final UUID LAT_CHARACTERISTIC_UUID = UUID.fromString("b6120bfa-46b3-45df-93fe-40f4c18ecbb7");
    private static final UUID LON_CHARACTERISTIC_UUID = UUID.fromString("f038d4d6-ddde-4191-b1a3-735bc1af7489");

    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int GIF_REQUEST_CODE = 2;

    private static final byte[] CMD_WRITE_BEGIN = {1};
    private static final byte[] CMD_WRITE_END = {2};

    private static final int BLE_MTU = 517;
    private static final int BUFFER_SIZE = 512;

    private static final String[] PERMISSIONS = {
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    ArrayList<BluetoothPeripheral> scanResultsArray = new ArrayList<BluetoothPeripheral>();
    ArrayAdapter<BluetoothPeripheral> scanResultsAdapter;

    BluetoothCentralManager bluetoothCentralManager;
    BluetoothPeripheral currentPeripheral;

    Button btnScan;
    ProgressBar prgScan;
    ProgressBar prgTransfer;
    TextView txtPrgTransfer;
    int transferCounter = 0;

    Button btnPickFile;
    TextView txtSelectedFile;

    double latitude = 999.999999999;
    double longitude = 999.999999999;
    TextView txtGPS;

    Button btnSend;

    byte[] imageBuffer;

    // Use to indicate if we should begin transfer in the MTU callback
    boolean shouldBeginTransfer = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get all necessary permissions
        this.requestPermissions(PERMISSIONS, PERMISSION_REQUEST_CODE);

        // Setup bluetooth manager
        bluetoothCentralManager = new BluetoothCentralManager(getApplicationContext(), bluetoothCentralManagerCallback,new Handler(Looper.getMainLooper()));

        // Setup Scan Results list
        setupScanResultsAdapter(this, scanResultsArray);
        ListView lstScanResults = this.findViewById(R.id.lstScanResults);
        lstScanResults.setAdapter(scanResultsAdapter);
        lstScanResults.setOnItemClickListener(lstScanResultsOnItemClickListener);

        // File picker
        btnPickFile = this.findViewById(R.id.btnPickFile);
        btnPickFile.setOnClickListener(btnPickFileOnClickListener);
        txtSelectedFile = this.findViewById(R.id.txtSelectedFile);

        // GPS
        txtGPS = this.findViewById(R.id.txtGPS);
        txtGPS.setOnClickListener(txtGPSOnClickListener);
        txtGPS.setTextColor(Color.BLUE);

        // Spinner
        prgScan = this.findViewById(R.id.prgScan);

        // Transfer progressbar
        prgTransfer = this.findViewById(R.id.prgTransfer);
        txtPrgTransfer = this.findViewById(R.id.txtPrgTransfer);

        // Setup scan button
        btnScan = this.findViewById(R.id.btnScan);
        btnScan.setOnClickListener(btnScanOnClickListener);

        // Setup send button
        btnSend = this.findViewById(R.id.btnSend);
        btnSend.setEnabled(false);
        btnSend.setOnClickListener(btnSendOnClickListener);
    }

    // Activity Result Handler
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        // Handle our image request
        if (requestCode == GIF_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (resultData != null) {
                Uri uri = resultData.getData();
                try {
                    // Load file into buffer
                    loadFileIntoBuffer(uri);

                    // Data was correctly loaded
                    if (imageBuffer.length >= 6) {
                        // DO NOT DO THIS IN PRODUCTION!
                        // Show the first 6 bytes to verify proper file load
                        String header = "";
                        for (int i=0;i<6;i++) {
                            header += (char)imageBuffer[i];
                        }
                        Toast.makeText(getApplicationContext(), "Image loaded ["+header+"] (" + imageBuffer.length +")", Toast.LENGTH_LONG).show();
                        // Update the file label
                        txtSelectedFile.setText(uri.getLastPathSegment());
                    }
                } catch (IOException e) {
                    Log.d(TAG, e.getMessage());
                }
            }
        }
    }

    // Attempt to open file and load it into buffer;
    private void loadFileIntoBuffer(Uri uri) throws IOException {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            imageBuffer = new byte[inputStream.available()];
            inputStream.read(imageBuffer, 0, inputStream.available());
        }
        catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    // Update the hyperlink text to show the coordinates
    private void updateGPSText() {
        txtGPS.setText("GPS FIX: " + latitude + "N " + longitude + "E");
    }

    // Add an onclick event to go to the coordinates
    private final View.OnClickListener txtGPSOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            // Open google maps to the coordinates
            Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + latitude + "," + longitude);
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
            }
        }
    };

    // On list item click listener for the scan result list
    private final AdapterView.OnItemClickListener lstScanResultsOnItemClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
            // Disconnect from old peripheral
            if (currentPeripheral != null) {
                currentPeripheral.cancelConnection();
            }

            // Attempt connection to the peripheral
            BluetoothPeripheral peripheral = scanResultsArray.get(position);
            bluetoothCentralManager.connectPeripheral(peripheral, bluetoothPeripheralCallback);
        }
    };

    // Bluetooth Peripheral callback events
    private final BluetoothPeripheralCallback bluetoothPeripheralCallback = new BluetoothPeripheralCallback() {
        BluetoothGattCharacteristic cmd_characteristic;
        BluetoothGattCharacteristic tx_characteristic;

        @Override
        public void onServicesDiscovered(BluetoothPeripheral peripheral) {
            // Get and check the service
            BluetoothGattService service = peripheral.getService(SERVICE_UUID);
            if (service == null) {
                Toast.makeText(getApplicationContext(), "Display service doesnt exist!", Toast.LENGTH_LONG).show();
                peripheral.cancelConnection();
                return;
            }

            // Check if the characteristics exist
            tx_characteristic = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
            if (tx_characteristic == null) {
                Toast.makeText(getApplicationContext(), "Transfer characteristic doesnt exist!", Toast.LENGTH_LONG).show();
                peripheral.cancelConnection();
                return;
            }
            cmd_characteristic = service.getCharacteristic(CMD_CHARACTERISTIC_UUID);
            if (cmd_characteristic == null) {
                Toast.makeText(getApplicationContext(), "Command characteristic doesnt exist!", Toast.LENGTH_LONG).show();
                peripheral.cancelConnection();
                return;
            }
            BluetoothGattCharacteristic lat_characteristic = service.getCharacteristic(LAT_CHARACTERISTIC_UUID);
            if (lat_characteristic == null) {
                Toast.makeText(getApplicationContext(), "LAT characteristic doesnt exist!", Toast.LENGTH_LONG).show();
                peripheral.cancelConnection();
                return;
            }
            BluetoothGattCharacteristic lon_characteristic = service.getCharacteristic(LON_CHARACTERISTIC_UUID);
            if (lat_characteristic == null) {
                Toast.makeText(getApplicationContext(), "LON characteristic doesnt exist!", Toast.LENGTH_LONG).show();
                peripheral.cancelConnection();
                return;
            }

            // Subscribe to the GPS notify events
            peripheral.setNotify(SERVICE_UUID, LAT_CHARACTERISTIC_UUID, true);
            peripheral.setNotify(SERVICE_UUID, LON_CHARACTERISTIC_UUID, true);

            currentPeripheral = peripheral;
            btnSend.setEnabled(true);
        }

        // Handle BLE Notify events
        @Override
        public void onCharacteristicUpdate(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, GattStatus status) {
            if (status == GattStatus.SUCCESS) {
                // Latitude
                if (characteristic.getUuid().equals(LAT_CHARACTERISTIC_UUID)) {
                    latitude = Double.parseDouble(new String(value));
                    updateGPSText();
                    return;
                }

                // Longitude
                if (characteristic.getUuid().equals(LON_CHARACTERISTIC_UUID)) {
                    longitude = Double.parseDouble(new String(value));
                    updateGPSText();
                    return;
                }
            }
        }

        // MTU Changed callback. Received when the MTU request gets a response
        @Override
        public void onMtuChanged(BluetoothPeripheral peripheral, int mtu, GattStatus status) {
            // Check if the MTU is the desired size and if we should begin transfer.
            // We need to check for transfer start otherwise a random MTU update might fire
            // the byte pump.
            if (mtu == BLE_MTU && shouldBeginTransfer) {
                Toast.makeText(getApplicationContext(), "Starting transfer!", Toast.LENGTH_SHORT).show();
                shouldBeginTransfer = false;
                peripheral.writeCharacteristic(cmd_characteristic, CMD_WRITE_BEGIN, WriteType.WITH_RESPONSE);
            }
        }

        // For every write event we receive a response. Process them here
        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, GattStatus status) {
            // Only process successful responses
            if (status == GattStatus.SUCCESS) {
                // Process responses for writes. Update the progressbar and also determine when to stop
                if (characteristic.getUuid().equals(TX_CHARACTERISTIC_UUID)) {
                    transferCounter += value.length;
                    prgTransfer.setProgress(transferCounter);
                    txtPrgTransfer.setText(transferCounter + " / " + imageBuffer.length);
                    // If we're on the last write
                    if (transferCounter >= imageBuffer.length) {
                        // Write a stop command
                        peripheral.writeCharacteristic(cmd_characteristic, CMD_WRITE_END, WriteType.WITH_RESPONSE);
                    }
                    return;
                }

                // Process commands
                if (characteristic.getUuid().equals(CMD_CHARACTERISTIC_UUID)) {
                    // Write end
                    if (Arrays.equals(value, CMD_WRITE_END)) {
                        // Disconnect from peripheral
                        //peripheral.cancelConnection();
                        // Hide the bars
                        prgTransfer.setVisibility(View.INVISIBLE);
                        txtPrgTransfer.setVisibility(View.INVISIBLE);

                        btnSend.setEnabled(true);
                        return;
                    }
                    // Write begin
                    if (Arrays.equals(value, CMD_WRITE_BEGIN)) {
                        doBytePump(peripheral, tx_characteristic);
                    }
                }
            }
        }
    };

    // The transfer pump
    private void doBytePump(BluetoothPeripheral peripheral,BluetoothGattCharacteristic characteristic) {
        boolean endTransmission = false;
        int imageBufferOffset = 0;

        // Pump until we're out of bytes
        while (!endTransmission) {
            int bufsize = BUFFER_SIZE;
            // Shrink buffer size in the end of the buffer
            if (imageBufferOffset + bufsize > imageBuffer.length) {
                bufsize -= imageBufferOffset + bufsize - imageBuffer.length;
                endTransmission = true;
            }

            // Copy bytes to the smaller packet frame
            byte[] subset = new byte[bufsize];
            for (int i = 0; i < bufsize; i++) {
                subset[i] = imageBuffer[imageBufferOffset + i];
            }

            imageBufferOffset += bufsize;

            // Queue the write
            peripheral.writeCharacteristic(characteristic, subset, WriteType.WITH_RESPONSE);
        }
    }

    // Pick File on click
    private final View.OnClickListener btnPickFileOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            // Create a file picker for gif MIME type
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/gif");

            // Show the file picker
            startActivityIfNeeded(intent, GIF_REQUEST_CODE);
        }
    };

    // Simple list 2 adapter
    private void setupScanResultsAdapter(Context context, List list) {
        scanResultsAdapter = new ArrayAdapter<BluetoothPeripheral>(context, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = (TextView) view.findViewById(android.R.id.text1);
                TextView text2 = (TextView) view.findViewById(android.R.id.text2);

                BluetoothPeripheral peripheral = (BluetoothPeripheral) list.get(position);
                text1.setText(peripheral.getName());
                text2.setText(peripheral.getAddress());
                return view;
            }
        };
    }

    // Scan button on click
    private final View.OnClickListener btnScanOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            // Clear list
            scanResultsArray.clear();
            scanResultsAdapter.notifyDataSetChanged();

            // Disable button and enable progressbar
            btnScan.setEnabled(false);
            prgScan.setVisibility(View.VISIBLE);

            // Start wifi scan with service filter
            bluetoothCentralManager.scanForPeripheralsWithServices(new UUID[] {SERVICE_UUID});

            // Stop scan after 3 seconds
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Stop scan
                    bluetoothCentralManager.stopScan();
                    // Force list update
                    scanResultsAdapter.notifyDataSetChanged();
                    // Show button and hide progressbar
                    btnScan.setEnabled(true);
                    prgScan.setVisibility(View.INVISIBLE);
                }
            }, 3000);
        }
    };

    private final View.OnClickListener btnSendOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            btnSend.setEnabled(false);

            // Show and reset the progressbar
            transferCounter = 0;
            prgTransfer.setMax(imageBuffer.length);
            prgTransfer.setProgress(transferCounter);
            prgTransfer.setVisibility(View.VISIBLE);
            txtPrgTransfer.setText(transferCounter + " / " + imageBuffer.length);
            txtPrgTransfer.setVisibility(View.VISIBLE);

            // We should begin the transfer
            shouldBeginTransfer = true;

            // Request high connection priority
            currentPeripheral.requestConnectionPriority(ConnectionPriority.HIGH);
            // Request large MTU. We now must wait for the callback.
            currentPeripheral.requestMtu(BLE_MTU);
        }
    };

    // Callback for bluetooth scan results
    private final BluetoothCentralManagerCallback bluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            // Only add existing items to the list
            for (BluetoothPeripheral item : scanResultsArray) {
                if (item.getAddress() == peripheral.getAddress()) {
                    return;
                }
            }
            scanResultsArray.add(peripheral);
        }

        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            Toast.makeText(getApplicationContext(), "Connected to " + peripheral.getAddress(), Toast.LENGTH_SHORT).show();
            txtGPS.setText("No Fix");
        }

        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, HciStatus status) {
            Toast.makeText(getApplicationContext(), "Connection failed", Toast.LENGTH_LONG).show();
            btnSend.setEnabled(false);
        }

        @Override
        public void onDisconnectedPeripheral(BluetoothPeripheral peripheral, HciStatus status) {
            Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_LONG).show();
            currentPeripheral = null;
            btnSend.setEnabled(false);
            txtGPS.setText("Disconnected");
        }
    };
}