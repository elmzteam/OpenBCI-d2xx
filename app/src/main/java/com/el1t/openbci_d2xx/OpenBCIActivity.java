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
import android.widget.Toast;

import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;

import java.io.UnsupportedEncodingException;
import java.nio.CharBuffer;

/**
 * Created by El1t on 1/16/15.
 */
public class OpenBCIActivity extends ActionBarActivity
{
	public static D2xxManager sManager = null;
	static final int readLength = 512;

	/*local variables*/
	int mBaudRate = 115200; /*baud rate*/
	byte mStopBit = D2xxManager.FT_STOP_BITS_1; /*1:1stop bits, 2:2 stop bits*/
	byte mDataBit = D2xxManager.FT_DATA_BITS_8; /*8:8bit, 7: 7bit*/
	byte mParity = D2xxManager.FT_PARITY_NONE;  /* 0: none, 1: odd, 2: even, 3: mark, 4: space*/
	byte mFlowControl = D2xxManager.FT_FLOW_NONE; /*0:none, 1: flow control(CTS,RTS)*/

	int mDevCount = -1;
	int mDevCurrentIndex = -1;
	int mDevOpenIndex = 0;

	public ReadThread mReadThread;
	boolean readEnabled = false;

	FT_Device mDevice = null;
	boolean mUartConfigured = false;

	private final static String TAG = "OpenBCI Test Activity";

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

		final IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		filter.setPriority(500);
		this.registerReceiver(mUsbReceiver, filter);

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
		Handler mHandler;

		public ReadThread() {
			mHandler = new Handler() {
				@Override
				public void handleMessage(Message msg) {
					if(msg.arg1 > 0) {
//						char[] input = (char[]) msg.obj;
//						readText.append(String.copyValueOf(readDataToText, 0, msg.arg1));
						Log.d(TAG, "Output: " + new String((char[]) msg.obj));
					}
				}
			};
			this.setPriority(Thread.NORM_PRIORITY);
		}

		public ReadThread(Handler handler) {
			mHandler = handler;
			this.setPriority(Thread.NORM_PRIORITY);
		}

		@Override
		public void run() {
			final byte[] readData = new byte[readLength];
			CharBuffer charBuffer;
			int iavailable;

			while(!isInterrupted()) {
				try {
					Thread.sleep(4);
				} catch (InterruptedException e) {
					break;
				}

				synchronized(mDevice) {
					iavailable = mDevice.getQueueStatus();
					if (iavailable > 0) {
						if (iavailable > readLength) {
							iavailable = readLength;
						}

						mDevice.read(readData, iavailable);
						try {
							Log.d(TAG, new String(readData, "US-ASCII"));
						} catch (UnsupportedEncodingException e) {
							Log.e(TAG, "Encoding error", e);
						}
						//charBuffer  = Charset.forName("US-ASCII").decode(ByteBuffer.wrap(readData));
						//Message msg = mHandler.obtainMessage();
						//msg.obj = Arrays.copyOfRange(charBuffer.array(), charBuffer.position(), charBuffer.limit());
//						//msg.obj = readData[0];
						//msg.arg1 = iavailable;
						//mHandler.sendMessage(msg);
					}
				}
			}
		}
	}

	public void notifyUSBDeviceAttach() {
		createDeviceList();
	}

	public void notifyUSBDeviceDetach() {
		disconnect();
	}

	public void createDeviceList() {
		int tempDevCount = sManager.createDeviceInfoList(this);

		if (mDevCount != tempDevCount) {
			mDevCount = tempDevCount;
		} else if (tempDevCount < 0) {
			mDevCount = -1;
			mDevCurrentIndex = -1;
		}
	}

	public void disconnect() {
		mDevCount = -1;
		mDevCurrentIndex = -1;
		mReadThread.interrupt();
		try {
			Thread.sleep(50);
		}
		catch (InterruptedException e) {
			e.printStackTrace();
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
			mDevice.restartInTask();
			Log.d(TAG, "Read enabled");
		}
	}

	// Write out a character as a new byte[1]
	// TODO: set latency timer?
	public void writeToDevice(char value) {
		if (mDevice != null && mDevice.isOpen()) {
			mDevice.setLatencyTimer((byte) 16);
			mDevice.write(new byte[] { (byte) value }, 1);
			Log.d(TAG, "Sent '" + value + "' to device.");
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
		createDeviceList();
		if (mDevCount > 0) {
			connect();
			setConfig(mBaudRate, mDataBit, mStopBit, mParity, mFlowControl);
		}
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

		Toast.makeText(this, "Config done", Toast.LENGTH_SHORT).show();
		mUartConfigured = true;
		enableRead();
		writeToDevice('v');
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				writeToDevice('b');
			}
		}, 1000);
	}
}