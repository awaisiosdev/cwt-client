package esde06.tol.oulu.fi.cwprotocol;

import java.io.IOException;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Date;
import android.util.Log;
import android.os.Handler;


public class CWProtocolImplementation implements CWPControl, CWPMessaging, Runnable {

    public enum CWPState {Disconnected, Connected, LineUp, LineDown};
    private volatile CWPState currentState = CWPState.Disconnected;
    private CWPState nextState = currentState;
    private int currentFrequency = CWPControl.DEFAULT_FREQUENCY;
    private CWPConnectionReader reader;
    private Handler receiveHandler = new Handler();
    private int messageValue;
    private static final String TAG = "ProtocolImplementation";
    private CWProtocolListener listener;
    private Boolean lineUpByUser = false;
    private Boolean lineUpByServer = false;

    public CWProtocolImplementation(CWProtocolListener listener){
          this.listener = listener;
    }

    public void addObserver(Observer observer) {
    }

    public void deleteObserver(Observer observer) {
    }

    public void lineUp() throws IOException {
        Log.d(TAG, "Line Up signal generated by user.");
        currentState = CWPState.LineUp;
        lineUpByUser = true;
        if (lineUpByServer){
           return;
        }
        Log.d(TAG, "Sending line Up state change event.");
        listener.onEvent(CWProtocolListener.CWPEvent.ELineUp, 0);
    }

    public void lineDown() throws IOException {
        Log.d(TAG, "Line Down signal generated by user.");
        currentState = CWPState.LineDown;
        lineUpByUser = false;
        if (lineUpByServer){
            return;
        }
        Log.d(TAG, "Sending line Down state change event.");
        listener.onEvent(CWProtocolListener.CWPEvent.ELineDown, 0);
    }

    public void connect(String serverAddr, int serverPort, int frequency) throws IOException {
        Log.d(TAG, "Connect to CWP Server.");
        reader = new CWPConnectionReader(this);
        reader.startReading();
        Log.d(TAG, "Started Reading incoming messages.");
        currentState = CWPState.Connected;
    }

    public void disconnect() throws IOException {
        Log.d(TAG, "Disconnect CWP Server.");
        if (reader != null) {
            try {
                reader.stopReading();
                reader.join();
            } catch (InterruptedException e) {

            }
            reader = null;
        }
        currentState = CWPState.Disconnected;
    }

    public CWPState getCurrentState() {
        return currentState;
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

    public void setFrequency(int frequency) throws IOException {
        Log.d(TAG, "Set frequency to " + frequency);
        this.currentFrequency = frequency;
    }

    public int frequency() {
        return currentFrequency;
    }

    @Override
    public void run() {
        currentState = nextState;
        switch(nextState){
            case Connected:
                listener.onEvent(CWProtocolListener.CWPEvent.EConnected, 0);
                Log.d(TAG, "Sending Connected state change event.");
                break;
            case Disconnected:
                listener.onEvent(CWProtocolListener.CWPEvent.EDisconnected, 0);
                Log.d(TAG, "Sending Disconnected state change event.");
                break;
            case LineDown:
                lineUpByServer = false;
                if (lineUpByUser) {
                    listener.onEvent(CWProtocolListener.CWPEvent.EServerStateChange, 0);
                    Log.d(TAG, "Sending server state change event");
                } else {
                    listener.onEvent(CWProtocolListener.CWPEvent.ELineDown, 0);
                    Log.d(TAG, "Sending Line Down state change event.");
                }
                break;
            case LineUp:
                lineUpByServer = true;
                if (lineUpByUser) {
                    listener.onEvent(CWProtocolListener.CWPEvent.EServerStateChange, 0);
                    Log.d(TAG, "Sending server state change event");
                } else {
                    listener.onEvent(CWProtocolListener.CWPEvent.ELineUp, 0);
                    Log.d(TAG, "Sending Line Up state change event.");
                }
                break;

        }
    }

    private class CWPConnectionReader extends Thread {
        private volatile boolean running = false;
        private Runnable myProcessor;
        private static final String TAG = "CWPReader";

        // Used before networking for timing cw signals
        private Timer readerTimer;
        private TimerTask readerTask;


        CWPConnectionReader(Runnable processor) {
            myProcessor = processor;
        }

        void startReading() {
            Log.d(TAG, "Reading Started");
            running = true;
            start();
        }

        void stopReading() throws InterruptedException {
            Log.d(TAG, "Reading Stopped");
            readerTimer.cancel();
            running = false;
            readerTimer = null;
            readerTask = null;
            changeProtocolState(CWPState.Disconnected, 0);
        }

        private void doInitialize() throws InterruptedException {
           readerTimer = new Timer();
           readerTask = new TimerTask() {
               @Override
               public void run() {
                   try {
                       if (currentState == CWPState.LineUp) {
                           changeProtocolState(CWPState.LineDown, 0);
                       } else if (currentState == CWPState.LineDown) {
                           changeProtocolState(CWPState.LineUp, 0);
                       }
                   } catch (InterruptedException e) {

                   }
               }
           };
           // Set the delay and period to 6 seconds.
           readerTimer.scheduleAtFixedRate(readerTask, 6000, 6000);
        }


        @Override
        public void run() {
            try {
                changeProtocolState(CWPState.Connected, 0);
                doInitialize();
                changeProtocolState(CWPState.LineDown, 0);
                while(running) {
                    
                }
            } catch (InterruptedException e) {

            }


        }

        private void changeProtocolState(CWPState state, int param) throws InterruptedException {
            Log.d(TAG, "Change protocol state to " + state);
            nextState = state;
            messageValue = param;
            receiveHandler.post(myProcessor);
        }
    }

}
