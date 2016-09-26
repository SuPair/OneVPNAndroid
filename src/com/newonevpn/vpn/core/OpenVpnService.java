package com.newonevpn.vpn.core;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;

import com.dave.onevpnfresh.R;
import com.newonevpn.vpn.LogWindow;
import com.newonevpn.vpn.VpnProfile;
import com.newonevpn.vpn.core.OpenVPN.ByteCountListener;
import com.newonevpn.vpn.core.OpenVPN.ConnectionStatus;
import com.newonevpn.vpn.core.OpenVPN.StateListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Vector;

import static com.newonevpn.vpn.core.OpenVPN.ConnectionStatus.LEVEL_CONNECTED;
import static com.newonevpn.vpn.core.OpenVPN.ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET;
import static com.newonevpn.vpn.core.OpenVPN.ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT;
import static com.newonevpn.vpn.core.OpenVPN.ConnectionStatus.UNKNOWN_LEVEL;

@SuppressLint("NewApi") public class OpenVpnService extends VpnService implements StateListener, Callback, ByteCountListener {
	public static final String START_SERVICE = "com.newonevpn.vpn.START_SERVICE";
	public static final String START_SERVICE_STICKY = "com.newonevpn.vpn.START_SERVICE_STICKY";
	public static final String ALWAYS_SHOW_NOTIFICATION = "com.newonevpn.vpn.NOTIFICATION_ALWAYS_VISIBLE";

    public static final String DISCONNECT_VPN = "com.newonevpn.vpn.DISCONNECT_VPN";
    private static final String PAUSE_VPN = "com.newonevpn.vpn.PAUSE_VPN";
    private static final String RESUME_VPN = "com.newonevpn.vpn.RESUME_VPN";


    public static Thread mProcessThread=null;

	private final Vector<String> mDnslist=new Vector<String>();

	private VpnProfile mProfile;

	private String mDomain=null;

	private final Vector<CIDRIP> mRoutes=new Vector<CIDRIP>();
	private final Vector<String> mRoutesv6=new Vector<String>();

	private CIDRIP mLocalIP=null;

	private int mMtu;
	private String mLocalIPv6=null;
	private DeviceStateReceiver mDeviceStateReceiver;

	private boolean mDisplayBytecount=false;

	private boolean mStarting=false;

	private long mConnecttime;


	private static final int OPENVPN_STATUS = 1;

	private static boolean mNotificationAlwaysVisible =false;

	private final IBinder mBinder = new LocalBinder();
	private boolean mOvpn3 = false;
    
	private static OpenVPNManagement mManagement;

	public class LocalBinder extends Binder {
		public OpenVpnService getService() {
			// Return this instance of LocalService so clients can call public methods
			return OpenVpnService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		String action = intent.getAction();
		if( action !=null && action.equals(START_SERVICE))
			return mBinder;
		else
			return super.onBind(intent);
	}

	@Override
	public void onRevoke() {

		Intent vpnstatus = new Intent();
		vpnstatus.setAction("com.newonevpn.vpn.VPN_STATUS");
		vpnstatus.putExtra("status", "DISCONNECTED");
		vpnstatus.putExtra("detailstatus", "DISCONNECTED");
		sendBroadcast(vpnstatus, permission.ACCESS_NETWORK_STATE);
		mManagement.stopVPN();
		endVpnService();
	}

	// Similar to revoke but do not try to stop process
	public void processDied() {
		endVpnService();
	}

	private void endVpnService() {
		doSendBroadcast("NEXTOVPNTRY", UNKNOWN_LEVEL);
		mProcessThread=null;
		OpenVPN.removeByteCountListener(this);
		unregisterDeviceStateReceiver();
		ProfileManager.setConntectedVpnProfileDisconnected(this);
		if(!mStarting) {
			stopForeground(!mNotificationAlwaysVisible);

			if( !mNotificationAlwaysVisible) {
				stopSelf();
				OpenVPN.removeStateListener(this);
			}
		}
	}

	private void showNotification(String msg, String tickerText, boolean lowpriority, long when, ConnectionStatus status) {
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(ns);
		
	//	Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
		int icon = getIconByConnectionStatus(status);
		
		
		android.app.Notification.Builder nbuilder = new Notification.Builder(this);

		if(mProfile!=null)
			nbuilder.setContentTitle(getString(R.string.notifcation_title,mProfile.mName));
		else
			nbuilder.setContentTitle(getString(R.string.notifcation_title_notconnect));

		nbuilder.setContentText(msg);
		nbuilder.setOnlyAlertOnce(true);
		nbuilder.setOngoing(true);
	//	nbuilder.setContentIntent(getLogPendingIntent());
		nbuilder.setContentInfo(msg);
		nbuilder.setSmallIcon(icon);
		

		if(when !=0)
			nbuilder.setWhen(when);


		// Try to set the priority available since API 16 (Jellybean)
		//jbNotificationExtras(lowpriority, nbuilder);
		if(tickerText!=null && !tickerText.equals(""))
			nbuilder.setTicker(tickerText);

	//	@SuppressWarnings("deprecation")
	//	Notification notification = nbuilder.build();


	//	mNotificationManager.notify(OPENVPN_STATUS, notification);
	//	startForeground(OPENVPN_STATUS, notification);
	}

    private int getIconByConnectionStatus(ConnectionStatus level) {
       switch (level) {
           case LEVEL_CONNECTED:
           case UNKNOWN_LEVEL:
               return R.drawable.ic_stat_vpn;
           case LEVEL_AUTH_FAILED:
           case LEVEL_NONETWORK:
           case LEVEL_NOTCONNECTED:
               return R.drawable.ic_stat_vpn_offline;
           case LEVEL_CONNECTING_NO_SERVER_REPLY_YET:
           case LEVEL_WAITING_FOR_USER_INPUT:
               return R.drawable.ic_stat_vpn_outline;
           case LEVEL_CONNECTING_SERVER_REPLIED:
               return R.drawable.ic_stat_vpn_empty_halo;
           case LEVEL_VPNPAUSED:
               return android.R.drawable.ic_media_pause;
           default:
               return R.drawable.ic_stat_vpn;

       }
    }


    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	private void jbNotificationExtras(boolean lowpriority,
			android.app.Notification.Builder nbuilder) {
		try {
			if(lowpriority) {
				Method setpriority = nbuilder.getClass().getMethod("setPriority", int.class);
				// PRIORITY_MIN == -2
				setpriority.invoke(nbuilder, -2 );

				Method setUsesChronometer = nbuilder.getClass().getMethod("setUsesChronometer", boolean.class);
				setUsesChronometer.invoke(nbuilder,true);

			}

            Intent disconnectVPN = new Intent(this,LogWindow.class);
            disconnectVPN.setAction(DISCONNECT_VPN);
            PendingIntent disconnectPendingIntent = PendingIntent.getActivity(this, 0, disconnectVPN, 0);

            nbuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                   getString(R.string.cancel_connection),disconnectPendingIntent);

            Intent pauseVPN = new Intent(this,OpenVpnService.class);
            if (mDeviceStateReceiver == null || !mDeviceStateReceiver.isUserPaused()) {
                pauseVPN.setAction(PAUSE_VPN);
                PendingIntent pauseVPNPending = PendingIntent.getService(this,0,pauseVPN,0);
                nbuilder.addAction(android.R.drawable.ic_media_pause,
                        getString(R.string.pauseVPN), pauseVPNPending);

            } else {
                pauseVPN.setAction(RESUME_VPN);
                PendingIntent resumeVPNPending = PendingIntent.getService(this,0,pauseVPN,0);
                nbuilder.addAction(android.R.drawable.ic_media_play,
                        getString(R.string.resumevpn), resumeVPNPending);
            }


            //ignore exception
		} catch (NoSuchMethodException nsm) {
            nsm.printStackTrace();
		} catch (IllegalArgumentException e) {
            e.printStackTrace();
		} catch (IllegalAccessException e) {
            e.printStackTrace();
		} catch (InvocationTargetException e) {
            e.printStackTrace();
		}

	}

	PendingIntent getLogPendingIntent() {
		// Let the configure Button show the Log
		Intent intent = new Intent(getBaseContext(),LogWindow.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		PendingIntent startLW = PendingIntent.getActivity(getApplicationContext(), 0, intent, 0);
		intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		return startLW;

	}

	synchronized void registerDeviceStateReceiver(OpenVPNManagement magnagement) {
		// Registers BroadcastReceiver to track network connection changes.
		IntentFilter filter = new IntentFilter();
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_SCREEN_ON);
		mDeviceStateReceiver = new DeviceStateReceiver(magnagement);
		registerReceiver(mDeviceStateReceiver, filter);
		OpenVPN.addByteCountListener(mDeviceStateReceiver);
	}

	synchronized void unregisterDeviceStateReceiver() {
		if(mDeviceStateReceiver!=null)
			try {
				OpenVPN.removeByteCountListener(mDeviceStateReceiver);
				this.unregisterReceiver(mDeviceStateReceiver);
			} catch (IllegalArgumentException iae) {
				// I don't know why  this happens:
				// java.lang.IllegalArgumentException: Receiver not registered: com.newonevpn.vpn.NetworkSateReceiver@41a61a10
				// Ignore for now ...
				iae.printStackTrace();
			}
		mDeviceStateReceiver=null;
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if(intent != null && intent.getBooleanExtra(ALWAYS_SHOW_NOTIFICATION, false))
			mNotificationAlwaysVisible =true;

		OpenVPN.addStateListener(this);
		OpenVPN.addByteCountListener(this);

        if(intent != null && PAUSE_VPN.equals(intent.getAction()))
        {
            if(mDeviceStateReceiver!=null)
                mDeviceStateReceiver.userPause(true);
            return START_NOT_STICKY;
        }

        if(intent != null && RESUME_VPN.equals(intent.getAction()))
        {
            if(mDeviceStateReceiver!=null)
                mDeviceStateReceiver.userPause(false);
            return START_NOT_STICKY;
        }


        if(intent != null && START_SERVICE.equals(intent.getAction()))
			return START_NOT_STICKY;
		if(intent != null && START_SERVICE_STICKY.equals(intent.getAction())) {
			return START_REDELIVER_INTENT;
		}

        assert(intent!=null);
        if( intent == null )	return 0;

		// Extract information from the intent.
		String prefix = getPackageName();
		String[] argv = intent.getStringArrayExtra(prefix + ".ARGV");
		String nativelibdir = intent.getStringExtra(prefix + ".nativelib");
		String profileUUID = intent.getStringExtra(prefix + ".profileUUID");

		mProfile = ProfileManager.get(profileUUID);
		if( mProfile == null )	return 0;
		
		String startTitle = getString(R.string.start_vpn_title, mProfile.mName);
		String startTicker = getString(R.string.start_vpn_ticker, mProfile.mName);
		showNotification(startTitle, startTicker,
				false,0, LEVEL_CONNECTING_NO_SERVER_REPLY_YET);

		// Set a flag that we are starting a new VPN
		mStarting=true;
		// Stop the previous session by interrupting the thread.
		if(mManagement!=null && mManagement.stopVPN())
			// an old was asked to exit, wait 1s
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
                e.printStackTrace();
			}


		if (mProcessThread!=null) {
			mProcessThread.interrupt();
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
                e.printStackTrace();
			}
		}
		// An old running VPN should now be exited
		mStarting=false;


        // Open the Management Interface
        if (!mOvpn3) {

            // start a Thread that handles incoming messages of the managment socket
            OpenVpnManagementThread ovpnManagementThread = new OpenVpnManagementThread(mProfile, this);
            if (ovpnManagementThread.openManagementInterface(this)) {

                Thread mSocketManagerThread = new Thread(ovpnManagementThread, "OpenVPNManagementThread");
                mSocketManagerThread.start();
             //   DashBoard.bMuteClickButton = false;
                mManagement = ovpnManagementThread;
                OpenVPN.logInfo("started Socket Thread");
            }
        }

        // Start a new session by creating a new thread.
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);        

		mOvpn3 = prefs.getBoolean("ovpn3", false);
		mOvpn3 = false;

		Runnable processThread;
		if(mOvpn3) {

			OpenVPNManagement mOpenVPN3 = instantiateOpenVPN3Core();
			processThread = (Runnable) mOpenVPN3;
			mManagement = mOpenVPN3;


        } else {
            HashMap<String, String> env = new HashMap<String, String>();
            processThread = new OpenVPNThread(this, argv, env, nativelibdir);
        }

		mProcessThread = new Thread(processThread, "OpenVPNProcessThread");
		mProcessThread.start();
	//	DashBoard.bMuteClickButton = false;

		if(mDeviceStateReceiver!=null)
			unregisterDeviceStateReceiver();
		
		registerDeviceStateReceiver(mManagement);


		ProfileManager.setConnectedVpnProfile(this, mProfile);

		return START_NOT_STICKY;
	}

	private OpenVPNManagement instantiateOpenVPN3Core() {
		return null;
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onDestroy() {
		if (mProcessThread != null) {
			mProcessThread.interrupt();
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
                e.printStackTrace();
			}
		}
		if (mDeviceStateReceiver!= null) {
			this.unregisterReceiver(mDeviceStateReceiver);
		}
		// Just in case unregister for state
		OpenVPN.removeStateListener(this);

	}



	public ParcelFileDescriptor openTun() {
		Builder builder = new Builder();
		
		if(mLocalIP==null && mLocalIPv6==null) {
			OpenVPN.logMessage(0, "", getString(R.string.opentun_no_ipaddr));
			return null;
		}

		if(mLocalIP!=null) {
			builder.addAddress(mLocalIP.mIp, mLocalIP.len);
		}

		if(mLocalIPv6!=null) {
			String[] ipv6parts = mLocalIPv6.split("/");
			builder.addAddress(ipv6parts[0],Integer.parseInt(ipv6parts[1]));
		}
		

		for (String dns : mDnslist ) {
			try {
				builder.addDnsServer(dns);
			} catch (IllegalArgumentException iae) {
				OpenVPN.logError(R.string.dns_add_error, dns,iae.getLocalizedMessage());
			}
		}


		builder.setMtu(mMtu);
		

		for (CIDRIP route:mRoutes) {
			try {
				builder.addRoute(route.mIp, route.len);
			} catch (IllegalArgumentException ia) {
				OpenVPN.logMessage(0, "", getString(R.string.route_rejected) + route + " " + ia.getLocalizedMessage());
			}
		}

		for(String v6route:mRoutesv6) {
			try {
				String[] v6parts = v6route.split("/");
				builder.addRoute(v6parts[0],Integer.parseInt(v6parts[1]));
			} catch (IllegalArgumentException ia) {
				OpenVPN.logMessage(0, "", getString(R.string.route_rejected) + v6route + " " + ia.getLocalizedMessage());
			}
		}

		if(mDomain!=null)
			builder.addSearchDomain(mDomain);
		
		OpenVPN.logInfo(R.string.last_openvpn_tun_config);
		OpenVPN.logInfo(R.string.local_ip_info,mLocalIP.mIp,mLocalIP.len,mLocalIPv6, mMtu);
		OpenVPN.logInfo(R.string.dns_server_info, joinString(mDnslist), mDomain);
		OpenVPN.logInfo(R.string.routes_info, joinString(mRoutes));
		OpenVPN.logInfo(R.string.routes_info6, joinString(mRoutesv6));

		
		String session = mProfile.mName;//"Template";//NetSee.currentServerName;//mProfile.mName;
		/*if(mLocalIP!=null && mLocalIPv6!=null)
			session = getString(R.string.session_ipv6string,session, mLocalIP, mLocalIPv6);
		else if (mLocalIP !=null)
			session= getString(R.string.session_ipv4string,session, mLocalIP);*/

		builder.setSession(session);

		// No DNS Server, log a warning 
		if(mDnslist.size()==0)
			OpenVPN.logInfo(R.string.warn_no_dns);

		// Reset information
		mDnslist.clear();
		mRoutes.clear();
		mRoutesv6.clear();
		mLocalIP=null;
		mLocalIPv6=null;
		mDomain=null;

		//builder.setConfigureIntent(getLogPendingIntent());
		
		try {
            return builder.establish();
		} catch (Exception e) {
			OpenVPN.logMessage(0, "", getString(R.string.tun_open_error));
			OpenVPN.logMessage(0, "", getString(R.string.error) + e.getLocalizedMessage());
			OpenVPN.logMessage(0, "", getString(R.string.tun_error_helpful));
			return null;
		}

	}


	// Ugly, but java has no such method
	private <T> String joinString(Vector<T> vec) {
		String ret = "";
		if(vec.size() > 0){ 
			ret = vec.get(0).toString();
			for(int i=1;i < vec.size();i++) {
				ret = ret + ", " + vec.get(i).toString();
			}
		}
		return ret;
	}






	public void addDNS(String dns) {
		mDnslist.add(dns);		
	}


	public void setDomain(String domain) {
		if(mDomain==null) {
			mDomain=domain;
		}
	}

	public void addRoute(String dest, String mask) {
		CIDRIP route = new CIDRIP(dest, mask);		
		if(route.len == 32 && !mask.equals("255.255.255.255")) {
			OpenVPN.logMessage(0, "", getString(R.string.route_not_cidr,dest,mask));
		}

		if(route.normalise())
			OpenVPN.logMessage(0, "", getString(R.string.route_not_netip,dest,route.len,route.mIp));

		mRoutes.add(route);
	}

	public void addRoutev6(String extra) {
		mRoutesv6.add(extra);		
	}

	public void setMtu(int mtu) {
		mMtu=mtu;
	}

    public void setLocalIP(CIDRIP cdrip)
	{
		mLocalIP=cdrip;
	}


	public void setLocalIP(String local, String netmask,int mtu, String mode) {
		mLocalIP = new CIDRIP(local, netmask);
		mMtu = mtu;

		if(mLocalIP.len == 32 && !netmask.equals("255.255.255.255")) {
			// get the netmask as IP
			long netint = CIDRIP.getInt(netmask);
			if(Math.abs(netint - mLocalIP.getInt()) ==1) {
				if("net30".equals(mode))
					mLocalIP.len=30;
				else
					mLocalIP.len=31;
			} else {
				OpenVPN.logMessage(0, "", getString(R.string.ip_not_cidr, local,netmask,mode));
			}
		}
	}

	public void setLocalIPv6(String ipv6addr) {
		mLocalIPv6 = ipv6addr;
	}

	@Override
	public void updateState(String state,String logmessage, int resid, ConnectionStatus level) {
		// If the process is not running, ignore any state, 
		// Notification should be invisible in this state
		if( level == ConnectionStatus.LEVEL_CONNECTED && OpenVpnService.getManagement() != null )
			OpenVpnService.getManagement().setConnected(true);
		if( level == ConnectionStatus.LEVEL_NOTCONNECTED && OpenVpnService.getManagement() != null )
		{
			OpenVpnService.getManagement().setConnected(false);
		}
		
		
		if(mProcessThread==null && !mNotificationAlwaysVisible)
			return;
		doSendBroadcast(state, level);
		// Display byte count only after being connected

		{
			if (level == LEVEL_WAITING_FOR_USER_INPUT) {
				// The user is presented a dialog of some kind, no need to inform the user 
				// with a notifcation
				return;
			} else if(level == LEVEL_CONNECTED) {
				mDisplayBytecount = true;
				mConnecttime = System.currentTimeMillis();
			} else {
				mDisplayBytecount = false;
			}

			// Other notifications are shown,
			// This also mean we are no longer connected, ignore bytecount messages until next
			// CONNECTED
			String ticker = getString(resid);
			showNotification(getString(resid) +" " + logmessage,ticker,false,0, level);

		}
	}

	private void doSendBroadcast(String state, ConnectionStatus level) {
	//	DashBoard.bMuteClickButton = true;
		
		Intent vpnstatus = new Intent();
		vpnstatus.setAction("com.newonevpn.vpn.VPN_STATUS");
		vpnstatus.putExtra("status", level.toString());
		vpnstatus.putExtra("detailstatus", state);
		sendBroadcast(vpnstatus, permission.ACCESS_NETWORK_STATE);
	}

	@Override
	public void updateByteCount(long in, long out, long diffin, long diffout) {
		if(mDisplayBytecount) {
			String netstat = String.format(getString(R.string.statusline_bytecount),
					humanReadableByteCount(in, false),
					humanReadableByteCount(diffin/ OpenVPNManagement.mBytecountInterval, true),
					humanReadableByteCount(out, false),
					humanReadableByteCount(diffout/ OpenVPNManagement.mBytecountInterval, true));

			boolean lowpriority = !mNotificationAlwaysVisible;
			showNotification(netstat,null,lowpriority,mConnecttime, LEVEL_CONNECTED);
		}

	}

	// From: http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
	public static String humanReadableByteCount(long bytes, boolean mbit) {
		if(mbit)
			bytes = bytes *8;
		int unit = mbit ? 1000 : 1024;
		if (bytes < unit)
			return bytes + (mbit ? " bit" : " B");

		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (mbit ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (mbit ? "" : "");
		if(mbit)
			return String.format(Locale.getDefault(),"%.1f %sbit", bytes / Math.pow(unit, exp), pre);
		else 
			return String.format(Locale.getDefault(),"%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	@Override
	public boolean handleMessage(Message msg) {
		Runnable r = msg.getCallback();
		if(r!=null){
			r.run();
			return true;
		} else {
			return false;
		}
	}

	public static OpenVPNManagement getManagement() {
		return mManagement;
	}
}
