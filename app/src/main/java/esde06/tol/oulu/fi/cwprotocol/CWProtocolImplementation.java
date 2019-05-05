package esde06.tol.oulu.fi.cwprotocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.net.Socket;

import android.util.Log;
import android.os.Handler;
import android.os.ConditionVariable;

import esde06.tol.oulu.fi.EventLogger;


public class CWProtocolImplementation implements CWPControl, CWPMessaging, Runnable {

    private static final String TAG = "ProtocolImplementation";
    private static final String MONITORTAG = "LineUpMessageMonitor";

    public enum CWPState {Disconnected, Connected, LineUp, LineDown}

    private volatile CWPState currentState = CWPState.Disconnected;
    private CWPState nextState = currentState;
    private Boolean lineUpByUser = false;
    private Boolean lineUpByServer = false;

    private CWPConnectionReader reader = null;
    private CWPConnectionWriter writer = null;
    private Handler receiveHandler = new Handler();
    private CWProtocolListener listener;

    private static final int BUFFER_LENGTH = 64;
    private OutputStream nos = null; //Network Output Stream
    private ByteBuffer outBuffer = null;

    private String serverAddress = null;
    private int serverPort = -1;
    private int currentFrequency = CWPControl.DEFAULT_FREQUENCY;

    private int reservedValue = -2147483648;
    private int messageValue = 0;
    private int data32bit = 0;
    private short data16bit = 0;
    private long connectedStamp = 0;
    private long lastLineUpStamp = 0;

    private Semaphore lock = new Semaphore(1);
    private ConditionVariable writerHandle = new ConditionVariable();

    private Timer monitor;
    private TimerTask monitorTask;

    public CWProtocolImplementation(CWProtocolListener listener) {
        this.listener = listener;
    }

    public void addObserver(Observer observer) {
    }

    public void deleteObserver(Observer observer) {
    }

    public void lineUp() throws IOException {
        EventLogger.logEventStarted("LineUp");
        Log.d(TAG, "Line Up signal generated by user.");
        lineUpByUser = true;
        lastLineUpStamp = System.currentTimeMillis();
        int message = (int) (lastLineUpStamp - connectedStamp);
        try {
            lock.acquire();
            data32bit = message;
            currentState = CWPState.LineUp;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.release();
            writerHandle.open();
        }
        startMonitoringLineUpMessage();
        Log.d(TAG, "Line Up message : " + data32bit);
        if (lineUpByServer) {
            return;
        }
        Log.d(TAG, "Sending line Up state change event.");
        listener.onEvent(CWProtocolListener.CWPEvent.ELineUp, 0);
        EventLogger.logEventEnded("LineUp");
    }

    public void lineDown() throws IOException {
        EventLogger.logEventStarted("LineDown");
        Log.d(TAG, "Line Down signal generated by user.");
        lineUpByUser = false;
        short message = (short) (System.currentTimeMillis() - lastLineUpStamp);
        try {
            lock.acquire();
            data16bit = message;
            currentState = CWPState.LineDown;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.release();
            writerHandle.open();
        }
        stopMonitoringLineUpMessage();
        Log.d(TAG, "Line Down message : " + data16bit);
        if (lineUpByServer) {
            return;
        }
        Log.d(TAG, "Sending line Down state change event.");
        listener.onEvent(CWProtocolListener.CWPEvent.ELineDown, 0);
        EventLogger.logEventEnded("LineDown");
    }

    public void connect(String serverAddr, int serverPort, int frequency) {
        Log.d(TAG, "Connect to CWP Server.");
        this.serverAddress = serverAddr;
        this.serverPort = serverPort;
        this.currentFrequency = Math.abs(frequency) * -1;
        reader = new CWPConnectionReader(this);
        reader.startReading();
        Log.d(TAG, "Started Reading incoming messages.");

        writer = new CWPConnectionWriter();
        writer.startSending();
    }

    public void disconnect() throws IOException {
        Log.d(TAG, "Disconnect CWP Server.");
        if (reader != null) {
            reader.stopReading();
            reader = null;
        }
        if (writer != null) {
            writer.stopSending();
            writer = null;
        }

    }

    private void sendFrequency() {
        if (currentFrequency == reservedValue) {
            Log.d(TAG, "Incorrect frequency value... This will not work");
            return;
        }
        try {
            lock.acquire();
            data32bit = this.currentFrequency;
            currentState = CWPState.Connected;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.release();
            writerHandle.open();
        }
        Log.d(TAG, "Frequency change message : " + data32bit);
        Log.d(TAG, "Sending Connected state change event.");
        listener.onEvent(CWProtocolListener.CWPEvent.EConnected, 0);
    }

    public boolean isConnected() {
        return currentState != CWPState.Disconnected;
    }

    public boolean lineIsUp() {
        return currentState == CWPState.LineUp;
    }

    public boolean serverSetLineUp() {
        return lineUpByServer;
    }

    public void setFrequency(int frequency) {
        Log.d(TAG, "Set frequency to " + frequency);
        if (currentState == CWPState.LineUp) {
            return;
        }
        currentFrequency = Math.abs(frequency) * -1;
        sendFrequency();
    }

    public int frequency() {
        return Math.abs(currentFrequency);
    }

    @Override
    public void run() {
        CWPState previousState = currentState;
        boolean sendStateChange = false;
        if (lineUpByUser) {
            lineUpByServer = nextState == CWPState.LineUp;
            sendStateChange = (nextState == CWPState.LineUp || nextState == CWPState.LineDown);
        } else {
            currentState = nextState;
        }
        lock.release();
        int receivedData = messageValue;

        if (sendStateChange) {
            Log.d(TAG, "Sending server state change event");
            listener.onEvent(CWProtocolListener.CWPEvent.EServerStateChange, receivedData);
            return;
        }

        if (previousState == CWPState.Connected && currentState == CWPState.LineDown) {
            if (receivedData != currentFrequency) {
                Log.d(TAG, "Sending frequency change to the server");
                sendFrequency();
                return;
            } else {
                Log.d(TAG, "Frequency is now changed to " + currentFrequency);
                Log.d(TAG, "Sending Frequency change event.");
                listener.onEvent(CWProtocolListener.CWPEvent.EChangedFrequency, Math.abs(receivedData));
            }
        }
        switch (currentState) {
            case Connected:
                connectedStamp = System.currentTimeMillis();
                Log.d(TAG, "Sending Connected state change event.");
                listener.onEvent(CWProtocolListener.CWPEvent.EConnected, receivedData);
                break;
            case Disconnected:
                Log.d(TAG, "Sending Disconnected state change event.");
                listener.onEvent(CWProtocolListener.CWPEvent.EDisconnected, receivedData);
                break;
            case LineDown:
                lineUpByServer = false;
                Log.d(TAG, "Sending Line Down state change event.");
                listener.onEvent(CWProtocolListener.CWPEvent.ELineDown, receivedData);
                break;
            case LineUp:
                lineUpByServer = true;
                Log.d(TAG, "Sending Line Up state change event.");
                listener.onEvent(CWProtocolListener.CWPEvent.ELineUp, receivedData);

                break;
        }
        EventLogger.logEventEnded("ServerEvent");
    }

    private void handleLongLineUpMessage() {
        Log.d(MONITORTAG, "Sending LineDown Signal");
        try {
            lock.acquire();
            data16bit = (short) (System.currentTimeMillis() - lastLineUpStamp);
            ;
            writerHandle.open();
            lock.release();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.d(MONITORTAG, "Sending LineUp Signal");
        lastLineUpStamp = System.currentTimeMillis();
        try {
            lock.acquire();
            data32bit = (int) (lastLineUpStamp - connectedStamp);
            writerHandle.open();
            lock.release();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startMonitoringLineUpMessage() {
        monitor = new Timer();
        monitorTask = new TimerTask() {
            @Override
            public void run() {
                Log.d(MONITORTAG, "Monitoring LineUp Message....");
                if (!(lineUpByUser && currentState == CWPState.LineUp)) {
                    return;
                }
                Log.d(MONITORTAG, "Found Lineup signal sending by user.");
                if ((System.currentTimeMillis() - lastLineUpStamp) < 30000) {
                    return;
                }
                Log.d(MONITORTAG, "LineUp signal is up for more than 30 seconds, handling it now..");
                handleLongLineUpMessage();
            }
        };
        monitor.scheduleAtFixedRate(monitorTask, 0, 16000);
    }

    private void stopMonitoringLineUpMessage() {
        monitor.cancel();  // cancel the timer
        monitor = null;    // remove the reference to monitor.
        monitorTask = null; // remove the reference to monitorTask.
    }

    private class CWPConnectionReader extends Thread {

        private static final String TAG = "CWPReader";

        private volatile boolean running = false;
        private Runnable myProcessor;
        private Socket cwpSocket = null;
        private InputStream nis = null; //Network Input Stream
        private int bytesToRead = 4;
        private int bytesRead = 0;

        CWPConnectionReader(Runnable processor) {
            myProcessor = processor;
        }

        void startReading() {
            Log.d(TAG, "Reading Started");
            running = true;
            start();
        }

        void stopReading() throws IOException {
            Log.d(TAG, "Reading Stopped");
            running = false;
            changeProtocolState(CWPState.Disconnected, 0);
            if (cwpSocket != null) {
                cwpSocket.close();
                cwpSocket = null;
            }
            if (nis != null) {
                nis.close();
                nis = null;
            }
            if (nos != null) {
                nos.close();
                nos = null;
            }
        }

        private void doInitialize() throws IOException {
            InetSocketAddress address = new InetSocketAddress(serverAddress, serverPort);
            cwpSocket = new Socket();
            cwpSocket.connect(address);
            nis = cwpSocket.getInputStream();
            nos = cwpSocket.getOutputStream();
            changeProtocolState(CWPState.Connected, 0);
        }

        private int readLoop(byte[] bytes) throws IOException {
            int readNow = nis.read(bytes, bytesRead, bytesToRead - bytesRead);
            if (readNow == -1) {
                throw new IOException("Read -1 from server");
            }
            return readNow;
        }

        // Start new read cycle
        private void startNewReadCycle(int bytesToRead) {
            this.bytesToRead = bytesToRead;
            this.bytesRead = 0;
            Log.d(TAG, "Starting new read cycle for bytes: " + bytesToRead);
        }

        @Override
        public void run() {
            try {
                doInitialize();
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_LENGTH);
                while (running) {
                    bytesRead = bytesRead + readLoop(buffer.array());
                    Log.d(TAG, "Bytes Read: " + bytesRead + " , Bytes To Read: " + this.bytesToRead);
                    if (bytesRead != this.bytesToRead) {
                        continue;
                    }
                    Log.d(TAG, "Bytes read cycle completed.");
                    buffer.position(0);

                    if (this.bytesRead == 2) {
                        short value = buffer.getShort();
                        Log.d(TAG, "Received value: " + value);
                        Log.d(TAG, "Received Line Down Signal");
                        changeProtocolState(CWPState.LineDown, value);
                        startNewReadCycle(4);
                    }

                    if (this.bytesRead == 4) {
                        int value = buffer.getInt();
                        Log.d(TAG, "Received value: " + value);
                        if (value > 0) {
                            Log.d(TAG, "Received Line Up Signal");
                            changeProtocolState(CWPState.LineUp, value);
                            startNewReadCycle(2);
                        } else if (value < 0) {
                            if (value != reservedValue) {
                                Log.d(TAG, "Received Frequency Confirmation signal");
                                changeProtocolState(CWPState.LineDown, value);
                            } else {
                                Log.d(TAG, "Ignoring reserved value :" + value);
                            }
                            startNewReadCycle(4);
                        }
                    }

                    buffer.clear();
                }
            } catch (IOException e) {
                changeProtocolState(CWPState.Disconnected, 0);
            }
        }

        private void changeProtocolState(CWPState state, int param) {
            EventLogger.logEventStarted("ServerEvent");
            Log.d(TAG, "Change protocol state to " + state);
            try {
                lock.acquire();
                nextState = state;
                messageValue = param;
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                if (nextState != CWPState.Connected) {
                    lock.release();
                }
                receiveHandler.post(myProcessor);
            }

        }
    }

    private class CWPConnectionWriter extends Thread {
        private static final String TAG = "CWPWriter";
        private volatile boolean running = false;

        private void startSending() {
            Log.d(TAG, "Sending started");
            running = true;
            start();
        }

        private void stopSending() {
            Log.d(TAG, "Sending stopped");
            running = false;
            writerHandle.open();
        }

        private void sendMessage(int msg) throws IOException {
            outBuffer = ByteBuffer.allocate(4);
            outBuffer.order(ByteOrder.BIG_ENDIAN);
            outBuffer.putInt(msg);
            outBuffer.position(0);
            byte[] data = outBuffer.array();
            nos.write(data);
            nos.flush();
        }

        private void sendMessage(short msg) throws IOException {
            outBuffer = ByteBuffer.allocate(2);
            outBuffer.order(ByteOrder.BIG_ENDIAN);
            outBuffer.putShort(msg);
            outBuffer.position(0);
            byte[] data = outBuffer.array();
            nos.write(data);
            nos.flush();
        }

        @Override
        public void run() {
            while (running) {
                writerHandle.block();     // block thread execution when there is no data to send.

                if (data32bit > 0 || data32bit < 0) {
                    try {
                        lock.acquire();
                        sendMessage(data32bit);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        data32bit = 0;
                        lock.release();
                    }
                } else if (data16bit > 0) {
                    try {
                        lock.acquire();
                        sendMessage(data16bit);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        data16bit = 0;
                        lock.release();
                    }
                }
                writerHandle.close(); // close so thread does not keep running until protocol implementation opens it again.
            }
        }
    }
}
