package com.el1t.openbci_d2xx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Created by El1t on 1/16/15.
 */
public class OpenBCIActivity extends ActionBarActivity
{
	private final static String TAG = "OpenBCI Test Activity";
	final static byte BYTE_START = (byte) 0xA0;
	final static byte BYTE_END = (byte) 0xC0;

	// D2xx settings
	static final int MAX_READ_LENGTH = 512;
	static final int PACKET_LENGTH = 33;
	static D2xxManager sManager = null;
	boolean streaming;
	int mBaudRate = 115200;
	byte mStopBit = D2xxManager.FT_STOP_BITS_1;
	byte mDataBit = D2xxManager.FT_DATA_BITS_8;
	byte mParity = D2xxManager.FT_PARITY_NONE;
	byte mFlowControl = D2xxManager.FT_FLOW_NONE;

	int mDevCount = -1;
	int mDevCurrentIndex = -1;
	int mDevOpenIndex = 0;

	public ReadThread mReadThread;
	boolean readEnabled = false;

	FT_Device mDevice = null;
	boolean mUartConfigured = false;

	byte[] overflowBuffer = new byte[MAX_READ_LENGTH*2];
	int overflowLength = 0;

	TextView mTextView;

	final Handler INIT_HANDLER = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			final byte[] input = (byte[]) msg.obj;
			final char[] decoded = Arrays.copyOf(Charset.forName("US-ASCII").decode(ByteBuffer.wrap(input)).array(), msg.arg1);
			Log.d(TAG, new String(new char[] {decoded[decoded.length - 2], decoded[decoded.length-1]}));
		}
	};

	final Handler PACKET_HANDLER = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			final byte[] input = (byte[]) msg.obj;
			System.arraycopy(input, 0, overflowBuffer, overflowLength, input.length);
			DataPacket[] dataPackets = new DataPacket[(overflowLength + input.length)/PACKET_LENGTH + 1];
			DataPacket temp;
			for (int i = 0; i < overflowLength + input.length; i += PACKET_LENGTH) {
				// Verify integrity
				if (overflowBuffer[i] != (byte) 0xA0 || overflowBuffer[i+32] != (byte) 0xC0) {
					Log.d(TAG, "Invalid header/footer in packet");
					continue;
				}
				temp = new DataPacket(8, 3);
				temp.sampleIndex = (int) overflowBuffer[i + 1];
				for (int j = i + 2; j < i + 26; j+=3) {
					temp.values[(j - i - 2)/3] = interpret24bitAsInt32(overflowBuffer[j], overflowBuffer[j+1], overflowBuffer[j+2]);
				}
				for (int j = i + 26; j < i + PACKET_LENGTH; j+=2) {
					temp.values[(j - i - 26)/2] = interpret16bitAsInt32(overflowBuffer[j], overflowBuffer[j+1]);
				}
				dataPackets[i/PACKET_LENGTH] = temp;
				temp.printToConsole();
			}
			overflowLength = (overflowLength + input.length) % PACKET_LENGTH;
			// Move data to the beginning
			System.arraycopy(overflowBuffer, overflowLength + input.length, overflowBuffer, 0, overflowLength);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_bci);

		try {
			sManager = D2xxManager.getInstance(this);
		} catch (D2xxManager.D2xxException ex) {
			ex.printStackTrace();
		}
		SetupD2xxLibrary();
		mTextView = (TextView) findViewById(R.id.console);

		final IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		filter.setPriority(500);
		registerReceiver(mUsbReceiver, filter);

		// Use material design toolbar
		final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		if(toolbar != null) {
			setSupportActionBar(toolbar);
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
			notifyUSBDeviceAttach();
		}
	}

	private void SetupD2xxLibrary () {
		if(!sManager.setVIDPID(0x0403, 0xada1)) {
			Log.d("ftd2xx-java", "setVIDPID Error");
		}
	}

	/***********USB broadcast receiver*******************************************/
	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if(intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
				Log.d(TAG, "Device detached");
				notifyUSBDeviceDetach();
			}
		}
	};

	// Run read operations on separate thread
	private class ReadThread extends Thread {
//		Handler mHandler;

		// Uses default handler that interprets bytes as a char array
		public ReadThread() {
//			mHandler = new Handler() {
//				@Override
//				public void handleMessage(Message msg) {
//					try {
//						Log.d(TAG, "Output: " + new String((byte[]) msg.obj, "US-ASCII"));
//					} catch (UnsupportedEncodingException e) {
//						Log.e(TAG, "Default read handler", e);
//					}
//				}
//			};
			this.setPriority(Thread.NORM_PRIORITY);
		}

//		public ReadThread(Handler handler) {
//			mHandler = handler;
//			this.setPriority(Thread.NORM_PRIORITY);
//		}

		@Override
		public void run() {
			byte[] readData;
			int dataLength;
			Message msg;

			while(!isInterrupted()) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					break;
				}

				synchronized(mDevice) {
					dataLength = mDevice.getQueueStatus();
					if (dataLength > 0) {
						if (dataLength > MAX_READ_LENGTH) {
							dataLength = MAX_READ_LENGTH;
						}

						readData = new byte[dataLength];
						mDevice.read(readData, dataLength);

						// Object: byte array
						// Arg1: length of array
						if (streaming) {
							msg = PACKET_HANDLER.obtainMessage();
						} else {
							msg = INIT_HANDLER.obtainMessage();
						}
						msg.obj = readData;
						msg.arg1 = dataLength;
						if (streaming) {
							PACKET_HANDLER.sendMessage(msg);
						} else {
							INIT_HANDLER.sendMessage(msg);
						}
					}
				}
			}
			Log.d(TAG, "Reading thread stopped");
		}
	}

	public void notifyUSBDeviceAttach() {
		createDeviceList();
	}

	public void notifyUSBDeviceDetach() {
		disconnect();
	}

	public boolean createDeviceList() {
		int tempDevCount = sManager.createDeviceInfoList(this);

		if (mDevCount != tempDevCount) {
			mDevCount = tempDevCount;
		} else if (tempDevCount < 0) {
			mDevCount = -1;
			mDevCurrentIndex = -1;
			return false;
		}
		return mDevCount > 0;
	}

	public void disconnect() {
		mDevCount = -1;
		mDevCurrentIndex = -1;
		mReadThread.interrupt();
		try {
			Thread.sleep(50);
		}
		catch (InterruptedException e) {
//			e.printStackTrace();
			Log.d(TAG, "Thread interrupted");
		}

		if(mDevice != null) {
			synchronized(mDevice) {
				if(mDevice.isOpen()) {
					mDevice.close();
				}
			}
		}
	}

	public void connect() {
		int tmpProtNumber = mDevOpenIndex + 1;

		if (mDevCurrentIndex != mDevOpenIndex) {
			if (mDevice == null) {
				mDevice = sManager.openByIndex(this, mDevOpenIndex);
			} else {
				synchronized(mDevice) {
					mDevice = sManager.openByIndex(this, mDevOpenIndex);
				}
			}
			mUartConfigured = false;
		} else {
			Toast.makeText(this, "Device port " + tmpProtNumber + " is already opened", Toast.LENGTH_SHORT).show();
			return;
		}

		if (mDevice == null) {
			Toast.makeText(this,"Cannot open port " + tmpProtNumber + ", mDevice is null", Toast.LENGTH_SHORT).show();
		} else if (mDevice.isOpen()) {
			mDevCurrentIndex = mDevOpenIndex;
			Toast.makeText(this, "Opened port " + tmpProtNumber, Toast.LENGTH_SHORT).show();

			if (mReadThread == null || !mReadThread.isInterrupted()) {
				mReadThread = new ReadThread();
				mReadThread.start();
			}
		} else {
			Toast.makeText(this, "Error opening port " + tmpProtNumber, Toast.LENGTH_LONG).show();
		}
	}

	public void enableRead() {
		if (mDevice == null || !mUartConfigured) {
			Log.e(TAG, "Cannot enable read");
			return;
		}
		if(readEnabled) {
			mDevice.stopInTask();
			Log.d(TAG, "Read disabled");
		} else {
			mDevice.purge(D2xxManager.FT_PURGE_TX);
			mDevice.purge(D2xxManager.FT_PURGE_RX);
			mDevice.restartInTask();
			Log.d(TAG, "Read enabled");
		}
	}

	// Write out a character as a new byte[1]
	// TODO: set latency timer?
	public void writeToDevice(char value) {
		if (mDevice != null && mDevice.isOpen()) {
			mDevice.setLatencyTimer((byte) 16);
			mDevice.write(new byte[]{(byte) value}, 1);
			Log.d(TAG, "Sent '" + value + "' to device.");
			mTextView.setText(mTextView.getText() + "\nSent '" + value + "' to device.");
		} else {
			Log.e(TAG, "Write: mDevice not open");
		}
	}

	// Write out a string as a byte array
	public void writeToDevice(String value) {
		if (mDevice != null && mDevice.isOpen()) {
			mDevice.setLatencyTimer((byte) 16);
			mDevice.write(value.getBytes(), value.length());
			Log.d(TAG, "Sent \"" + value + "\" to device.");
		} else {
			Log.e(TAG, "Write: mDevice not open");
		}
	}

	/**
	 * Hot plug for plug in solution
	 * This is workaround before android 4.2. Because BroadcastReceiver can not
	 * receive ACTION_USB_DEVICE_ATTACHED broadcast
	 */
	@Override
	public void onResume() {
		super.onResume();
		mDevCount = 0;
		if (createDeviceList()) {
			connect();
			setConfig(mBaudRate, mDataBit, mStopBit, mParity, mFlowControl);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		writeToDevice(Commands.STOP_STREAM);
		disconnect();
		unregisterReceiver(mUsbReceiver);
	}

	public void setConfig(int baudRate, byte dataBit, byte stopBit, byte parity, byte flowControl) {
		if (!mDevice.isOpen()) {
			Log.e(TAG, "setConfig: device not open");
			return;
		}

		// configure our port
		// reset to UART mode for 232 devices
		mDevice.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET);
		mDevice.setBaudRate(baudRate);
		mBaudRate = baudRate;

		mDevice.setDataCharacteristics(dataBit, stopBit, parity);
		mDataBit = dataBit;
		mStopBit = stopBit;
		mParity = parity;

		mDevice.setFlowControl(flowControl, (byte) 0x0b, (byte) 0x0d);
		mFlowControl = flowControl;

		Log.d(TAG, "Config finished");
		mUartConfigured = true;
		enableRead();
		streaming = false;
		writeToDevice(Commands.SOFT_RESET);
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				streaming = true;
				writeToDevice(Commands.START_STREAM);
			}
		}, 7000);
	}

	private int interpret24bitAsInt32(byte a, byte b, byte c) {
		//little endian
		final int newInt = ((0xFF & a) << 16) | ((0xFF & b) << 8) | 0xFF & c;
		if ((newInt & 0x00800000) > 0) {
			return newInt | 0xFF000000;
		}
		return newInt & 0x00FFFFFF;
	}

	private int interpret16bitAsInt32(byte a, byte b) {
		int newInt = ((0xFF & a) << 8) | (0xFF & b);
		if ((newInt & 0x00008000) > 0) {
			return newInt | 0xFFFF0000;
		}
		return newInt & 0x0000FFFF;
	}
}