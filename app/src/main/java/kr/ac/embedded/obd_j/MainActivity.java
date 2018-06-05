package kr.ac.embedded.obd_j;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import kr.ac.embedded.obd_j.adapter.PairedListAdapter;
import kr.ac.embedded.obd_j.dialog.PairedDevicesDialog;
import kr.ac.embedded.obd_j.utility.MyLog;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;

import de.greenrobot.event.EventBus;


public class MainActivity extends AppCompatActivity implements
        PairedDevicesDialog.PairedDeviceDialogListener, SurfaceHolder.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String TAG_DIALOG = "dialog";
    private static final String NO_BLUETOOTH = "장치가 블루투스를 지원하지 않습니다";
    private static final String[] PIDS = {
            "01", "02", "03", "04", "05", "06", "07", "08",
            "09", "0A", "0B", "0C", "0D", "0E", "0F", "10",
            "11", "12", "13", "14", "15", "16", "17", "18",
            "19", "1A", "1B", "1C", "1D", "1E", "1F", "20",
            "49", "4A", "4B"
    };

    // Commands
    private static final String[] INIT_COMMANDS = {"010C", "010D"};

    /*
     * AT Z - OBD2 버전
     * AT SP 0 - OBD 프로토콜 오토
     * 0105 - 엔진 냉매 온도
     * 010C - 엔진 RPM
     * 010D - 속도
     * 010A - 엔진 압력
     * 010F - 흡기 온도
     * 0110 - MAF air flow rate
     * 0111 - Throttle position
     * 011F - Run time since engine starts
     * 0149 - Accelerator pedal position D
     * 014A - Accelerator pedal position E
     * 014B - Accelerator pedal position F
     * 0104 - Engine load
     * */


    private int mCMDPointer = -1;

    // Intent request codes
    private static final int REQUEST_ENABLE_BT = 101;
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 102;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 103;

    // Message types accessed from the BluetoothIOGateway Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names accesses from the BluetoothIOGateway Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast_message";

    // Bluetooth
    private BluetoothIOGateway mIOGateway;
    private static BluetoothAdapter mBluetoothAdapter;
    private DeviceBroadcastReceiver mReceiver;
    private PairedDevicesDialog dialog;
    private List<BluetoothDevice> mDeviceList;

    // Widgets
    private TextView mConnectionStatus;

    // Variable def
    private static StringBuilder mSbCmdResp;
    private static StringBuilder mPartialResponse;
    private String mConnectedDeviceName;
    private final Handler mMsgHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothIOGateway.STATE_CONNECTING:
                            mConnectionStatus.setText(getString(R.string.BT_connecting));
                            mConnectionStatus.setBackgroundColor(Color.YELLOW);
                            break;

                        case BluetoothIOGateway.STATE_CONNECTED:
                            mConnectionStatus.setText(getString(R.string.BT_status_connected_to) + " " + mConnectedDeviceName);
                            mConnectionStatus.setBackgroundColor(Color.GREEN);

                            sendOBD2CMD("AT Z");
                            sendOBD2CMD("AT SP 0");

                            initVideoRecorder();
                            startVideoRecorder();

                            // Send Loop Commands
                            time = new Timer();
                            time.scheduleAtFixedRate(tt, 0, 400);

                            thread = new LoggingThread();
                            thread.start();

                            recordBtn.setVisibility(View.GONE);
                            //uiThreadRun();
                            break;

                        case BluetoothIOGateway.STATE_LISTEN:
                        case BluetoothIOGateway.STATE_NONE:
                            mConnectionStatus.setText(getString(R.string.BT_status_not_connected));
                            mConnectionStatus.setBackgroundColor(Color.RED);
                            break;

                        default:
                            break;
                    }
                    break;

                case MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;

                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    readMessage = readMessage.trim();
                    readMessage = readMessage.toUpperCase();

                    char lastChar = readMessage.charAt(readMessage.length() - 1);
                    if (lastChar == '>') {
                        parseResponse(mPartialResponse.toString() + readMessage);
                        mPartialResponse.setLength(0);
                    } else {
                        mPartialResponse.append(readMessage);
                    }

                    break;

                case MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;

                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    mSbCmdResp.append("W>>");
                    mSbCmdResp.append(writeMessage);
                    mSbCmdResp.append("\n");
                    break;

                case MESSAGE_TOAST:
                    ;
                    break;

                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    break;
            }
        }

    };

    double longitude; //경도
    double latitude; //위도
    double altitude; //고도

    private static String folderName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/OBDLog/OBD_";
    private String fileName = "";

    LoggingThread thread;
    private Camera mCamera = null;
    private MediaRecorder mRecorder = null;

    boolean isRecording = false;

    SurfaceView mSurface = null;
    SurfaceHolder mSurfaceHolder = null;

    TextView logTextView;
    Button recordBtn;
    Timer time;

    String longitudePre, latitudePre, altitudePre, ectPre, eRPMPre, vsPre, fpPre,
            iatPre, mafrPre, tpPre, rtsePre, appdPre, appePre, appfPre, elPre = "-";

    String timeFile;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        // 권한이 없을 때 권한 추가
        addPermission();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mRecorder = new MediaRecorder();

        mSurface = (SurfaceView) findViewById(R.id.surfaceView);
        logTextView = (TextView) findViewById(R.id.log);

        // LocationManager 객체를 얻어온다
        final LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        // GPS 제공자의 정보가 바뀌면 콜백하도록 리스너 등록
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, // 등록할 위치제공자
                    100, // 통지사이의 최소 시간간격 (miliSecond)
                    1, // 통지사이의 최소 변경거리 (m)
                    mLocationListener);
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, // 등록할 위치제공자
                    100, // 통지사이의 최소 시간간격 (miliSecond)
                    1, // 통지사이의 최소 변경거리 (m)
                    mLocationListener);
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        // connect widgets
        mConnectionStatus = (TextView) findViewById(R.id.tvConnectionStatus);

        // make sure user has Bluetooth hardware
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
            MainActivity.this.finish();
        }
        // 화면 꺼짐 방지

        // Init variables
        mSbCmdResp = new StringBuilder();
        mPartialResponse = new StringBuilder();
        mIOGateway = new BluetoothIOGateway(this, mMsgHandler);

        recordBtn = (Button) findViewById(R.id.recordBtn);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if(!isRecording) {
                    if (mBluetoothAdapter == null) {
                        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    }

                    // make sure Bluetooth is enabled
                    if (!mBluetoothAdapter.isEnabled()) {
                        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                    } else {
                        queryPairedDevices();
                        setupMonitor();
                    }
                } else{
                    mRecorder.stop();
                    mRecorder.reset();
                    mRecorder.release();
                    isRecording = false;
                }

//                initVideoRecorder();
//                startVideoRecorder();

            }
        });
    }

    void initVideoRecorder() {
        mCamera = Camera.open();
        Camera.Parameters p = mCamera.getParameters();
        p.setPreviewFpsRange(30000,30000);
//        if(p.isAutoExposureLockSupported())
//            p.setAutoExposureLock(true);
        mCamera.setParameters(p);
//        mCamera.cancelAutoFocus();
//        mCamera.unlock();
//        mCamera.setDisplayOrientation(90);
        mSurfaceHolder = mSurface.getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    void startVideoRecorder() {

        if (isRecording) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;

            mCamera.lock();
            isRecording = false;
        } else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String now = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                    timeFile = new SimpleDateFormat("HH_mm_ss").format(new Date());
                    String mFolderName = folderName + now;
                    File file = new File(mFolderName);
                    if (!file.exists())
                        file.mkdir();
                    try {
                        mRecorder = new MediaRecorder();
                        mCamera.unlock();
                        mRecorder.setCamera(mCamera);
                        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                        mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                        mRecorder.setOutputFile(mFolderName + "/" + timeFile + ".mp4");
                        mRecorder.setVideoFrameRate(30);
                        mRecorder.setVideoEncodingBitRate(17300000);
                        mRecorder.setVideoSize(1280, 720);
                        mRecorder.setOrientationHint(0);
                        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                        mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

                        mRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
                        mRecorder.setMaxFileSize(0);
                        mRecorder.prepare();
                        mRecorder.start();
                        isRecording = true;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }


    /*
        public void uiThreadRun() {
            while (true) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String textValue = "";
                        try {
                            textValue = longitudePre + ", " + latitudePre + ", " + altitudePre + ", " + ectPre + eRPMPre + vsPre + fpPre +
                                    iatPre + mafrPre + tpPre + rtsePre + appdPre + appePre + appfPre + elPre;
                            // 동작 구현
                            logTextView.setText(textValue);
                            Thread.sleep(1000);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        }
    */
    private class LoggingThread extends Thread {

        private static final String TAG = "LoggingThread";

        public LoggingThread() {
            //초기화 작업
            String now = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            folderName = folderName + now;
        }

        public void run() {

            while (true) {
//                longitudePre = getPreferences("longitude");
//                latitudePre = getPreferences("latitude");
//                altitudePre = getPreferences("altitude");
//                ectPre = getPreferences("ect");
//                eRPMPre = getPreferences("eRPM");
//                vsPre = getPreferences("vs");
//                fpPre = getPreferences("fp");
//                iatPre = getPreferences("iat");
//                mafrPre = getPreferences("mafr");
//                tpPre = getPreferences("tp");
//                rtsePre = getPreferences("rtse");
//                appdPre = getPreferences("appd");
//                appePre = getPreferences("appe");
//                appfPre = getPreferences("appf");
//                elPre = getPreferences("el");

                try {
                    // 동작 구현
                    String now = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                    String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                    fileName = timeFile + ".txt";
                    WriteTextFile(folderName, fileName, "\r\n" + now + "_" + time + " , " +
                            longitude + ", " + latitude + ", " + altitude + ", " + eRPMPre + vsPre);
                    Thread.sleep(500);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        //텍스트내용을 경로의 텍스트 파일에 쓰기
        public void WriteTextFile(String foldername, String filename, String contents) {

            try {
                File dir = new File(foldername);
                //디렉토리 폴더가 없으면 생성함
                if (!dir.exists()) {
                    dir.mkdir();
                    //contents = "시간, 위도, 경도, 고도, 엔진 냉매 온도, 엔진 RPM, 속도" +
                    //        ", 연료 압력, 흡기 온도, MAF 공기 유량, 쓰로틀 위치, 엔진 시동 후 시간, 악셀 페달 위치 D, 악셀 페달 위치 E," +
                    //        "악셀 페달 위치 F, 엔진 로드 값";
                }
                //파일 output stream 생성
                FileWriter fos = new FileWriter(foldername + "/" + filename, true);
                //파일쓰기
                BufferedWriter writer = new BufferedWriter(fos);
                writer.write(contents);
                writer.flush();

                writer.close();
                fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


    }

    private final void addPermission() {
        // 권한 물어서 권한안되어있으면 권한 셋팅해주기
        if( ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_COARSE_LOCATION
            , Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, 1);
        }
    }

    private final LocationListener mLocationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            //여기서 위치값이 갱신되면 이벤트가 발생한다.
            //값은 Location 형태로 리턴되며 좌표 출력 방법은 다음과 같다.

            Log.d("test", "onLocationChanged, location:" + location);
            longitude = location.getLongitude(); //경도
            latitude = location.getLatitude();   //위도
            altitude = location.getAltitude();  //고도

//            savePreferences("longitude", longitude + "");
//            savePreferences("latitude", latitude + "");
//            savePreferences("altitude", altitude + "");
        }

        public void onProviderDisabled(String provider) {
            // Disabled시
            Log.d("test", "onProviderDisabled, provider:" + provider);
        }

        public void onProviderEnabled(String provider) {
            // Enabled시
            Log.d("test", "onProviderEnabled, provider:" + provider);
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
            // 변경시
            Log.d("test", "onStatusChanged, provider:" + provider + ", status:" + status + " ,Bundle:" + extras);
        }
    };

    TimerTask tt = new TimerTask() {
        @Override
        public void run() {
            sendInitCommands();
        }
    };

    // 값 불러오기
    public String getPreferences(String key) {
        SharedPreferences pref = getSharedPreferences("pref", MODE_PRIVATE);
        return pref.getString(key, "-,");
    }

    // 값 저장하기
    public void savePreferences(String key, String data) {
        SharedPreferences pref = getSharedPreferences("pref", MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putString(key, data);
        editor.apply();
    }

    // 값(Key Data) 삭제하기
    public void removePreferences(String data) {
        SharedPreferences pref = getSharedPreferences("pref", MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.remove(data);
        editor.apply();
    }

    // 값(ALL Data) 삭제하기
    public void removeAllPreferences() {
        SharedPreferences pref = getSharedPreferences("pref", MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.clear();
        editor.apply();
    }

    @Override
    protected void onStart() {
        super.onStart();

    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    @Override
    protected void onPause() {
        super.onPause();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(time != null)
            time.cancel();

        if(thread != null) {
            thread.interrupt();
            thread.stop();
        }

        // Un register receiver
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }

        // Stop scanning if is in progress
        cancelScanning();

        // Stop mIOGateway
        if (mIOGateway != null) {
            mIOGateway.stop();
        }

        // Clear StringBuilder
        if (mSbCmdResp.length() > 0) {
            mSbCmdResp.setLength(0);
        }

        removeAllPreferences();

        if (isRecording) {
            mRecorder.stop();
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }

        // Unregister EventBus
        EventBus.getDefault().unregister(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan:
                queryPairedDevices();
                setupMonitor();
                return true;

            case R.id.menu_send_cmd:
//                mCMDPointer = -1;
//                sendDefaultCommands();
                return true;

            case R.id.menu_clr_scr:
                mSbCmdResp.setLength(0);
                return true;

            case R.id.menu_clear_code:
                sendOBD2CMD("04");
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ENABLE_BT:
                if (resultCode == RESULT_CANCELED) {
                    return;
                }
                if (resultCode == RESULT_OK) {
                    queryPairedDevices();
                    setupMonitor();
                }
                break;

            default:
                // nothing at the moment
        }
    }

    private void setupMonitor() {
        // Start mIOGateway
        if (mIOGateway == null) {
            mIOGateway = new BluetoothIOGateway(this, mMsgHandler);
        }

        // Only if the state is STATE_NONE, do we know that we haven't started already
        if (mIOGateway.getState() == BluetoothIOGateway.STATE_NONE) {
            // Start the Bluetooth chat services
            mIOGateway.start();
        }

        // clear string builder if contains data
        if (mSbCmdResp.length() > 0) {
            mSbCmdResp.setLength(0);
        }

    }

    private void queryPairedDevices() {
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        // If there are paired devices
        if (pairedDevices.size() > 0) {
            PairedDevicesDialog dialog = new PairedDevicesDialog();
            dialog.setAdapter(new PairedListAdapter(this, pairedDevices), false);
            showChooserDialog(dialog);
        } else {
            scanAroundDevices();
        }
    }

    private void showChooserDialog(DialogFragment dialogFragment) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        dialogFragment.show(ft, "dialog");
    }

    private void scanAroundDevices() {

        if (mReceiver == null) {
            // Register the BroadcastReceiver
            mReceiver = new DeviceBroadcastReceiver();
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(mReceiver, filter);
        }

        // Start scanning
        mBluetoothAdapter.startDiscovery();
    }

    private void cancelScanning() {
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }
    }

    /**
     * Callback method for once a new device detected.
     *
     * @param device BluetoothDevice
     */
    public void onEvent(BluetoothDevice device) {
        if (mDeviceList == null) {
            mDeviceList = new ArrayList<>(10);
        }

        mDeviceList.add(device);

        // create dialog
        final Fragment fragment = this.getSupportFragmentManager().findFragmentByTag(TAG_DIALOG);
        if (fragment != null && fragment instanceof PairedDevicesDialog) {
            PairedListAdapter adapter = dialog.getAdapter();
            adapter.notifyDataSetChanged();
        } else {
            dialog = new PairedDevicesDialog();
            dialog.setAdapter(new PairedListAdapter(this, new HashSet<>(mDeviceList)), true);
            showChooserDialog(dialog);
        }
    }

    @Override
    public void onDeviceSelected(BluetoothDevice device) {
        cancelScanning();

        // Attempt to connect to the device
        mIOGateway.connect(device, true);
    }

    @Override
    public void onSearchAroundDevicesRequested() {
        scanAroundDevices();
    }

    @Override
    public void onCancelScanningRequested() {
        cancelScanning();
    }


    private void sendOBD2CMD(String sendMsg) {
        if (mIOGateway.getState() != BluetoothIOGateway.STATE_CONNECTED) {
            return;
        }

        String strCMD = sendMsg;
        strCMD += '\r';

        byte[] byteCMD = strCMD.getBytes();
        mIOGateway.write(byteCMD);
    }

    private void sendInitCommands() {
        if (mCMDPointer >= INIT_COMMANDS.length) {
            mCMDPointer = -1;
            return;
        }

        // reset pointer
        if (mCMDPointer < 0) {
            mCMDPointer = 0;
        }

        sendOBD2CMD(INIT_COMMANDS[mCMDPointer]);
    }

    private void parseResponse(String buffer) {
        // Log.e("msgLog: ",buffer);
        switch (mCMDPointer) {
//            case 0: // CMD: AT Z, no parse needed
//                break;
//            case 1: // CMD: AT SP 0, no parse needed
//                mSbCmdResp.append("R>>");
//                mSbCmdResp.append(buffer);
//                mSbCmdResp.append("\n");
//                break;

            case 0: // CMD: 0105, Engine coolant temperature
                int ect = showEngineCoolantTemperature(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (엔진 냉매 온도 : ");
                mSbCmdResp.append(ect);
                mSbCmdResp.append((char) 0x00B0);
                mSbCmdResp.append("˚C)");
                mSbCmdResp.append("\n");
//                savePreferences("ect", ect + ",");
                ectPre = ect + ", ";
                break;

            case 1: // CMD: 010C, EngineRPM
                int eRPM = showEngineRPM(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (엔진 RPM: ");
                mSbCmdResp.append(eRPM);
                mSbCmdResp.append(")");
                mSbCmdResp.append("\n");
//                savePreferences("eRPM", eRPM + ",");
                eRPMPre = eRPM + ", ";
                break;

            case 2: // CMD: 010D, Vehicle Speed
                int vs = showVehicleSpeed(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (차량 속도: ");
                mSbCmdResp.append(vs);
                mSbCmdResp.append("Km/h)");
                mSbCmdResp.append("\n");
//                savePreferences("vs", vs + ",");
                vsPre = vs + ", ";
                break;

            case 3: // CMD: 010A, Fuel Pressure
                int fp = showFuelPressure(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (연료 압력: ");
                mSbCmdResp.append(fp);
                mSbCmdResp.append("kPa)");
                mSbCmdResp.append("\n");
//                savePreferences("fp", fp + ",");
                fpPre = fp + ", ";
                break;

            case 4: // CMD: 010F, Intake air temperature
                int iat = showIntakeAirTemperature(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (흡기 온도: ");
                mSbCmdResp.append(iat);
                mSbCmdResp.append("˚C)");
                mSbCmdResp.append("\n");
//                savePreferences("iat", iat + ",");
                iatPre = iat + ", ";
                break;

            case 5: // CMD: 0110, MAR air flow rate
                int mafr = showMAFAirFlowRate(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (MAF 공기 유량 : ");
                mSbCmdResp.append(mafr);
                mSbCmdResp.append("g/s)");
                mSbCmdResp.append("\n");
//                savePreferences("mafr", mafr + ",");
                mafrPre = mafr + ", ";
                break;

            case 6: // CMD: 0111, Throttle position
                int tp = showThrottlePosition(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (스로틀 위치: ");
                mSbCmdResp.append(tp);
                mSbCmdResp.append("%)");
                mSbCmdResp.append("\n");
//                savePreferences("tp", tp + ",");
                tpPre = tp + ", ";
                break;

            case 7: // CMD: 011F, Run time since engine start
                int rtse = showRunTimeSinceEngineStart(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (엔진 시동 후 운전 시간: ");
                mSbCmdResp.append(rtse);
                mSbCmdResp.append("s)");
                mSbCmdResp.append("\n");
//                savePreferences("rtse", rtse + ",");
                rtsePre = rtse + ", ";
                break;

            case 8: // CMD: 0149, Accelerator pedal position D
                int appd = showAcceleratorPedalPositionD(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (가속 페달 위치 D: ");
                mSbCmdResp.append(appd);
                mSbCmdResp.append("%)");
                mSbCmdResp.append("\n");
//                savePreferences("appd", appd + ",");
                appdPre = appd + ", ";
                break;

            case 9: // CMD: 014A, Accelerator pedal position E
                int appe = showAcceleratorPedalPositionE(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (가속 페달 위치 E: ");
                mSbCmdResp.append(appe);
                mSbCmdResp.append("%)");
                mSbCmdResp.append("\n");
//                savePreferences("appe", appe + ",");
                appePre = appe + ", ";
                break;

            case 10: // CMD: 014B, Accelerator pedal position F
                int appf = showAcceleratorPedalPositionF(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (가속 페달 위치 F: ");
                mSbCmdResp.append(appf);
                mSbCmdResp.append("%)");
                mSbCmdResp.append("\n");
//                savePreferences("appf", appf + ",");
                appfPre = appf + ", ";
                break;

            case 11: // CMD: 0104, Calculated Engine load value
                int el = showEngineLoad(buffer);
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append(" (엔진 로드 : ");
                mSbCmdResp.append(el);
                mSbCmdResp.append("%)");
                mSbCmdResp.append("\n");
//                savePreferences("el", el + ",");
                elPre = el + ", ";
                break;

            default:
                mSbCmdResp.append("R>>");
                mSbCmdResp.append(buffer);
                mSbCmdResp.append("\n");
        }

        if (mCMDPointer >= 0) {
            mCMDPointer++;
            sendInitCommands();
        }
    }

    private String cleanResponse(String text) {
        text = text.trim();
        text = text.replace("\t", "");
        text = text.replace(" ", "");
        text = text.replace(">", "");

        return text;
    }

    private int showEngineCoolantTemperature(String buffer) {
        String buf = buffer;
        buf = cleanResponse(buf);
        if (buf.contains("4105")) {
            try {
                buf = buf.substring(buf.indexOf("4105"));

                String temp = buf.substring(4, 6);
                int A = Integer.valueOf(temp, 16);
                A -= 40;

                return A;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showEngineRPM(String buffer) {
        String buf = buffer;
//        Log.e("rpm buffer : ", buf);
        buf = cleanResponse(buf);
//        Log.e("rpm clean buffer : ", buf);
        if (buf.contains("410C")) {
            try {
                buf = buf.substring(buf.indexOf("410C"));

                String MSB = buf.substring(4, 6);
                String LSB = buf.substring(6, 8);
                int A = Integer.valueOf(MSB, 16);
                int B = Integer.valueOf(LSB, 16);

                return ((A * 256) + B) / 4;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showVehicleSpeed(String buffer) {
        String buf = buffer;
//        Log.e("buffer data : ", buf);
        buf = cleanResponse(buf);
//        Log.e("buffer clean :", buf);
//        Log.e("buf.containers : ", buf.contains("410D")+"");
        if (buf.contains("410D")) {
//            Log.e("buf.containers : ", "in");
            try {
                buf = buf.substring(buf.indexOf("410D"));

                String temp = buf.substring(4, 6);
//                Log.e("temp value : ", temp);
                return Integer.valueOf(temp, 16);
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                Log.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showFuelPressure(String buffer) {
        String buf = buffer;
//        Log.e("fuel buffer : ", buf);
        buf = cleanResponse(buf);
//        Log.e("fuel clean buffer : ", buf);
        if (buf.contains("410A")) {
            try {
                buf = buf.substring(buf.indexOf("410A"));

                String temp = buf.substring(4, 6);

                return Integer.valueOf(temp, 16) * 3;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showIntakeAirTemperature(String buffer) {
        String buf = buffer;
        buf = cleanResponse(buf);

        if (buf.contains("410F")) {
            try {
                buf = buf.substring(buf.indexOf("410F"));

                String temp = buf.substring(4, 6);

                return Integer.valueOf(temp, 16) - 40;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showMAFAirFlowRate(String buffer) {
        String buf = buffer;
//        Log.e("MAF buffer : ", buf);
        buf = cleanResponse(buf);
//        Log.e("MAF clean buffer : ", buf);
        if (buf.contains("4110")) {
            try {
                buf = buf.substring(buf.indexOf("4110"));

                String MSB = buf.substring(4, 6);
                String LSB = buf.substring(6, 8);
                int A = Integer.valueOf(MSB, 16);
                int B = Integer.valueOf(LSB, 16);

                return ((A * 256) + B) / 100;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showThrottlePosition(String buffer) {
        String buf = buffer;
        buf = cleanResponse(buf);

        if (buf.contains("4111")) {
            try {
                buf = buf.substring(buf.indexOf("4111"));

                String temp = buf.substring(4, 6);

                return (Integer.valueOf(temp, 16) * 100) / 255;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showRunTimeSinceEngineStart(String buffer) {
        String buf = buffer;
        buf = cleanResponse(buf);

        if (buf.contains("411F")) {
            try {
                buf = buf.substring(buf.indexOf("411F"));

                String MSB = buf.substring(4, 6);
                String LSB = buf.substring(6, 8);
                int A = Integer.valueOf(MSB, 16);
                int B = Integer.valueOf(LSB, 16);

                return (A * 256) + B;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showAcceleratorPedalPositionD(String buffer) {
        String buf = buffer;
//        Log.e("AccD buffer : ", buf);
        buf = cleanResponse(buf);
//        Log.e("AccD clean buffer : ", buf);

        if (buf.contains("4149")) {
            try {
                buf = buf.substring(buf.indexOf("4149"));

                String temp = buf.substring(4, 6);

                return (Integer.valueOf(temp, 16) * 100) / 255;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showAcceleratorPedalPositionE(String buffer) {
        String buf = buffer;
//        Log.e("AccE clean buffer : ", buf);
        buf = cleanResponse(buf);
//        Log.e("AccE clean buffer : ", buf);

        if (buf.contains("414A")) {
            try {
                buf = buf.substring(buf.indexOf("414A"));

                String temp = buf.substring(4, 6);

                return (Integer.valueOf(temp, 16) * 100) / 255;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showAcceleratorPedalPositionF(String buffer) {
        String buf = buffer;
//        Log.e("AccF clean buffer : ", buf);

        buf = cleanResponse(buf);
//        Log.e("AccF clean buffer : ", buf);

        if (buf.contains("414B")) {
            try {
                buf = buf.substring(buf.indexOf("414B"));

                String temp = buf.substring(4, 6);

                return (Integer.valueOf(temp, 16) * 100) / 255;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }

    private int showEngineLoad(String buffer) {
        String buf = buffer;
        buf = cleanResponse(buf);

        if (buf.contains("4104")) {
            try {
                buf = buf.substring(buf.indexOf("4104"));

                String temp = buf.substring(4, 6);

                return (Integer.valueOf(temp, 16) * 100) / 255;
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                MyLog.e(TAG, e.getMessage());
            }
        }

        return -1;
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {

        if (mCamera == null) {
            try {
                mCamera.setPreviewDisplay(mSurfaceHolder);
                mCamera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mCamera.stopPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }
}