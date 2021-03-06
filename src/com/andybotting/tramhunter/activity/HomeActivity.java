/*  
 * Copyright 2012 Andy Botting <andy@andybotting.com>
 *  
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *  
 * This file is distributed in the hope that it will be useful, but  
 * WITHOUT ANY WARRANTY; without even the implied warranty of  
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU  
 * General Public License for more details.  
 *  
 * You should have received a copy of the GNU General Public License  
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.  
 *  
 * This file incorporates work covered by the following copyright and  
 * permission notice:
 * 
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.andybotting.tramhunter.activity;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import com.andybotting.tramhunter.R;
import com.andybotting.tramhunter.TramHunter;
import com.andybotting.tramhunter.objects.Favourite;
import com.andybotting.tramhunter.objects.Tweet;
import com.andybotting.tramhunter.service.YarraTramsTwitter;
import com.andybotting.tramhunter.ui.InfoWindow;
import com.andybotting.tramhunter.ui.UIUtils;
import com.andybotting.tramhunter.util.FavouriteStopUtil;
import com.andybotting.tramhunter.util.GenericUtil;
import com.andybotting.tramhunter.util.PreferenceHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

import android.widget.TextView;

public class HomeActivity extends Activity {

    private static final String TAG = "Home";
    private static final boolean LOGV = Log.isLoggable(TAG, Log.DEBUG);
	
	// Menu items
	private static final int MENU_ABOUT = 0;
	private static final int MENU_SEARCH = 1;
	private static final int MENU_SETTINGS = 2;
	
	private PreferenceHelper mPreferenceHelper;
	private FavouriteStopUtil mFavouriteStopUtil;
	private static String mLastUsedIntentUUID;
	
	private static final int MAX_FETCH_ERRORS = 3;
	private static final int INFO_CHANGE_SECS = 5;
	private static final int TWITTER_UPDATE_MINS = 10;
	
	private List<Tweet> mTweets;
    private View mInfoLoadingView;
    private View mInfoWindowView;
    List<View> mInfoWindows = new ArrayList<View>();
    private int mInfoWindowId = 0;
    private boolean mInfoWindowFirstUpdate = true;
    
    private volatile Thread mRefreshThread;
    private String mErrorMessage;    
	private int mErrorRetry = 0;
	
    // Handler for Info Window update
    Handler UpdateHandler = new Handler() {
    	public void handleMessage(Message msg) {
    		if (!mInfoWindowFirstUpdate)
    			changeInfoWindow();
    		else
    			mInfoWindowFirstUpdate = false;
    	}
	};
	

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	
		mPreferenceHelper = new PreferenceHelper();

        final LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mFavouriteStopUtil = new FavouriteStopUtil(locationManager);
		
		// Show about dialog window on first launch (or just after an upgrade)
		if (mPreferenceHelper.isFirstLaunchThisVersion())
			showAbout();

		goDefaultLaunchActivity();
	}
	
	private void goDefaultLaunchActivity(){
		Bundle extras = getIntent().getExtras();
		boolean isNewIntentUUID = false;
		
		checkStats();
		
		// Check to make sure we have not already used the UUID for a default activity launch
		if(extras != null && extras.containsKey(TramHunter.KEY_PERFORM_DEFAULT_ACTIVITY_LAUNCH)) {
			String currentIntentUUID = extras.getString(TramHunter.KEY_PERFORM_DEFAULT_ACTIVITY_LAUNCH);
			isNewIntentUUID = !currentIntentUUID.equals(mLastUsedIntentUUID); 
			mLastUsedIntentUUID = currentIntentUUID;
		}
		
		if(isNewIntentUUID) {
			String activityName = mPreferenceHelper.defaultLaunchActivity();
			Intent intent = null;
			
			if(activityName.equals("HomeActivity")) {
				intent = null;
			}
			else if (activityName.equals("StopsListActivity")) {
				// Should be renamed to FavStopsListActivity, but causes a problem
				// on upgrade, so we'll just leave it
				startActivity(new Intent(this, FavouriteActivity.class));
			}
			else if (activityName.equals("ClosestStopsListActivity")) {
				Favourite closestFavouriteStop = mFavouriteStopUtil.getClosestFavouriteStop();
				if (closestFavouriteStop != null) {
					// Go to the closest favourite stop
					Bundle bundle = new Bundle();
					
					bundle.putInt("tramTrackerId", closestFavouriteStop.getStop().getTramTrackerID());
					if (closestFavouriteStop.getRoute() != null)
						bundle.putInt("routeId", closestFavouriteStop.getRoute().getId());
					
					intent = new Intent(HomeActivity.this, StopDetailsActivity.class);
					intent.putExtras(bundle);
				}
				else {
					UIUtils.popToast(this, "Unable to determine closest favourite stop!");
				}
			}
			else if(activityName.equals("NearStopsActivity")) {
				startActivity(new Intent(this, NearStopsActivity.class));
			}
			
			if (intent != null)
				startActivityForResult(intent, 1);
		}
		
		setContentView(R.layout.home);
		
		long lastUpdate = mPreferenceHelper.getLastTwitterUpdateTimestamp();
        long timeDiff = UIUtils.dateDiff(lastUpdate);
		
		// Kick off an update
        if (timeDiff > TWITTER_UPDATE_MINS * 60000) {
        	new GetTweets().execute();
        }
        else {
			getInfoWindows();
			changeInfoWindow();
			startRefreshThread();
        }
		
		
		// Search button
		findViewById(R.id.title_btn_search).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	UIUtils.goSearch(HomeActivity.this);
		    }
		});	
		
		// Favourite Stops
		findViewById(R.id.home_btn_starred).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	startActivity(new Intent(HomeActivity.this, FavouriteActivity.class));
		    }
		});
		
		// Browse Stops
		findViewById(R.id.home_btn_browse).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	startActivity(new Intent(HomeActivity.this, RoutesListActivity.class));
		    }
		});
		
		// Nearby Stops
		findViewById(R.id.home_btn_nearby).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	startActivity(new Intent(HomeActivity.this, NearStopsActivity.class));
		    }
		});
		
		// Search
		findViewById(R.id.home_btn_search).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	UIUtils.goSearch(HomeActivity.this);
		    }
		});

		// Network Map
		findViewById(R.id.home_btn_map).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	startActivity(new Intent(HomeActivity.this, NetworkMapActivity.class));
		    }
		});

		// Search Stops
		findViewById(R.id.home_btn_settings).setOnClickListener(new View.OnClickListener() {
		    public void onClick(View v) {
		    	startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
		    }
		});
		
		mInfoLoadingView = findViewById(R.id.info_window_loading);
		
//		// Show or hide the welcome quote
//		View welcomeMessage = findViewById(R.id.welcomeMessage);
//        if (welcomeMessage != null) {
//			if(mPreferenceHelper.isWelcomeQuoteEnabled()) {
//				findViewById(R.id.welcomeMessage).setVisibility(View.VISIBLE);
//				setRandomWelcomeMessage();
//			}
//			else {
//				findViewById(R.id.welcomeMessage).setVisibility(View.GONE);
//			}
//        }
   
	}	
	
	@Override
	protected void onResume() {
		super.onResume();
		//setRandomWelcomeMessage();
	}

//	private String getRandomWelcomeMessage(){
//		Random r = new Random(System.currentTimeMillis());
//		String[] welcomeMessages = getResources().getStringArray(R.array.welcomeMessages);
//		return welcomeMessages[r.nextInt(welcomeMessages.length - 1)];
//	}
//	
//	private void setRandomWelcomeMessage() {
//		TextView welcomeMessageTextView = (TextView) findViewById(R.id.welcomeMessageText);
//		if (welcomeMessageTextView != null) {
//			String welcomeText = "";
//			welcomeText = '"' + getRandomWelcomeMessage()+ '"';
//	        welcomeMessageTextView.setText(welcomeText);
//		}
//    }
	
	public void showAbout() {
		// Get the package name
		String heading = getResources().getText(R.string.app_name) + "\n";

		try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			heading += "v" + pi.versionName + "\n\n";
		} 
		catch (NameNotFoundException e) {
			e.printStackTrace();
		}
		
		// Build alert dialog
		AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
		dialogBuilder.setTitle(heading);
		View aboutView = getLayoutInflater().inflate(R.layout.dialog_about, null);
		dialogBuilder.setView(aboutView);
		dialogBuilder.setPositiveButton("OK",
			new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					mPreferenceHelper.setFirstLaunchThisVersion();
				}
			});
		dialogBuilder.setCancelable(false);
		dialogBuilder.setIcon(R.drawable.icon);
		dialogBuilder.show();
	}
	
	// Add menu items
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		
		menu.add(0, MENU_ABOUT, 0, R.string.menu_item_about)
			.setIcon(android.R.drawable.ic_menu_help);

		menu.add(0, MENU_SETTINGS, 0, R.string.menu_item_settings)
			.setIcon(android.R.drawable.ic_menu_preferences);
		
        menu.add(0, MENU_SEARCH, 0, R.string.menu_item_search)
        	.setIcon(android.R.drawable.ic_search_category_default)
        	.setAlphabeticShortcut(SearchManager.MENU_KEY);

		return true;
	}
	
	// Menu actions
	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
		case MENU_ABOUT:
			showAbout();
			return true;
        case MENU_SEARCH:
            onSearchRequested();
            return true;
		case MENU_SETTINGS:
			startActivity(new Intent(this, SettingsActivity.class));
			return true;
		}
		return false;

	}
	
	
	/**
	 * 
	 */
    public synchronized void startRefreshThread() {
		// Start update status timer, if not already running
		if(mRefreshThread == null) {
			if (LOGV) Log.v(TAG, "Starting refresh thread");
            mRefreshThread = new Thread(new InfoWindowTimer());
            mRefreshThread.setDaemon(true);
    		mRefreshThread.start();
    		mInfoWindowFirstUpdate = true;
    	}
    }

    /**
     * 
     */
    public synchronized void stopRefreshThread(){
    	if(mRefreshThread != null){
    		if (LOGV) Log.v(TAG, "Stopping refresh thread");
    		Thread killThread = mRefreshThread;
    		mRefreshThread = null;
    		killThread.interrupt();
    		mInfoWindowFirstUpdate = true;
    	}
    }


    /**
     *     
     * @author andy
     *
     */
    private class InfoWindowTimer implements Runnable {
        public void run() {
        	while(!Thread.currentThread().isInterrupted()){
        		Message m = new Message();
        		UpdateHandler.sendMessage(m);
        		try {
        			// 5 seconds
        			Thread.sleep(INFO_CHANGE_SECS * 1000);
        		} 
        		catch (InterruptedException e) {
        			Thread.currentThread().interrupt();
        		}
        	}
        }
	}
 
    
    /**
     * 
     * @return
     */
    public View makeErrorInfoWindow() {
		View infoView = getLayoutInflater().inflate(R.layout.info_window, null);
		((TextView) infoView.findViewById(R.id.info_window_title)).setText("Tube Status");
		((TextView) infoView.findViewById(R.id.info_window_subtitle)).setText("Error fetching twitter feed.");
//		((ImageButton) infoView.findViewById(R.id.info_window_icon)).setImageResource(R.drawable.info_window_weekend);
		return infoView;
    }
    
    
    /**
     * 
     * @return
     */
    public View makeUpdateInfoWindow() {
		View infoView = getLayoutInflater().inflate(R.layout.info_window, null);
		((TextView) infoView.findViewById(R.id.info_window_title)).setText("Tube Status");
		((TextView) infoView.findViewById(R.id.info_window_subtitle)).setText("Hit refresh to update Tube Line status.");
//		((ImageButton) infoView.findViewById(R.id.info_window_icon)).setImageResource(R.drawable.info_window_weekend);
		return infoView;
    }
    
    
    /**
     * 
     */
    public void getInfoWindows() {
   	   	
    	InfoWindow infoWindow = new InfoWindow(this);
    	
    	if (LOGV) Log.v(TAG, "infoWindow=" + infoWindow);
    	
    	mInfoWindows = infoWindow.getInfoWindows(mTweets);
    	
    	if (LOGV) Log.v(TAG, "mInfoWindows=" + mInfoWindows);
    }
    
    
    /**
     * Make a status window
     */
    public void setInfoWindow(View infoView) {
		
        if (mInfoLoadingView == null) // Landscape orientation
        	return;
        
        ViewGroup homeRoot = (ViewGroup) findViewById(R.id.home_root);
        
        mInfoWindowView = findViewById(R.id.info_window);
        if (mInfoWindowView != null) {
            homeRoot.removeView(mInfoWindowView);
            mInfoWindowView = null;
        }
        
        mInfoWindowView = infoView;

        homeRoot.addView(infoView, new LayoutParams(
        		LayoutParams.FILL_PARENT,
                (int) getResources().getDimension(R.dimen.info_window_height)));
        
   		mInfoLoadingView.setVisibility(View.GONE);        
   		mInfoWindowView.setVisibility(View.VISIBLE);
    }    
    
    
    /**
     * 
     */
    public void changeInfoWindow() {
    	if (LOGV) Log.v(TAG, "Changing info windows. Size=" + mInfoWindows.size());
    	
    	if (mInfoWindows.size() > 0) {
    		if (mInfoWindowId == mInfoWindows.size()-1)
    			mInfoWindowId = 0;
    		else
    			mInfoWindowId++;
    		setInfoWindow(mInfoWindows.get(mInfoWindowId));
    	}
    }
    
    
    /**
     * Change UI widgets when updating status data
     */
    private void updateRefreshStatus(boolean isRefreshing) {

        if (mInfoWindowView != null) {
        	mInfoWindowView.setVisibility(isRefreshing ? View.GONE : View.VISIBLE);
        	mInfoLoadingView.setVisibility(isRefreshing ? View.VISIBLE : View.GONE);
        }
        else {
        	if (LOGV) Log.v(TAG, "mInfoWindowView is null");
        }
        	
    }
    
	
    /**
     * Async task for updating tube status data
     */
    private class GetTweets extends AsyncTask<Void, Void, Void> {

        protected void onPreExecute() {
        	stopRefreshThread();
        	updateRefreshStatus(true);
        }

        protected Void doInBackground(Void... unused) {
        	
        	mTweets = new ArrayList<Tweet>();
        	YarraTramsTwitter twitter = new YarraTramsTwitter();
        	
        	try {
                if (LOGV) Log.v(TAG, "Updating Tube Status");
                mTweets = twitter.getTweets();
			} catch (Exception e) {
			    mErrorMessage = e.getMessage();
			}
        	return null;
        }

        protected void onPostExecute(Void unused) {
        	// Display a toast with the error
        	if (mErrorMessage != null) {
        		if (mErrorRetry == MAX_FETCH_ERRORS) {
        			if (LOGV) Log.v(TAG, "Maximum errors reached!");
        			setInfoWindow(makeErrorInfoWindow());
        			updateRefreshStatus(false);
        			mErrorMessage = null;
        			mErrorRetry = 0;
        		}
        		else {
        			if (LOGV) Log.v(TAG, "Error number: " + mErrorRetry);
        			new GetTweets().execute();
        		}
        		// Increment error
        		mErrorMessage = null;
        		mErrorRetry++;
        	}
        	else {
        		getInfoWindows();
        		changeInfoWindow();
        		startRefreshThread();
        		updateRefreshStatus(false);
        	}
        }
    }	
	
	
	
	
    /**
     * Check last time stats were sent, and send again if time greater than a week
     */
	private void checkStats() {	
		if (mPreferenceHelper.isSendStatsEnabled()) {
			long statsTimestamp = mPreferenceHelper.getStatsTimestamp();
	        long timeDiff = UIUtils.dateDiff(statsTimestamp);
	        
	        if (LOGV) Log.v(TAG, "Lasts stats date was " + timeDiff + "ms ago" );
			
			// Only once a week
			if (timeDiff > 604800000) {
				new Thread() {
					public void run() {
						uploadStats();
					}
				}.start();
				mPreferenceHelper.setStatsTimestamp();
			}
		}
	}

	private void uploadStats() {
		Log.d("Testing", "Sending app statistics");

		// gather all of the device info
		String app_version = "";
		try {
			try {
				PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
				app_version = pi.versionName;
			} 
			catch (NameNotFoundException e) {
				app_version = "N/A";
			}

			TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			String device_uuid = tm.getDeviceId();
			String device_id = "00000000000000000000000000000000";
			if (device_uuid != null) {
				device_id = GenericUtil.MD5(device_uuid);
			}
			
			String mobile_country_code = tm.getNetworkCountryIso();
			String mobile_network_number = tm.getNetworkOperator();
			int network_type = tm.getNetworkType();
	
			// get the network type string
			String mobile_network_type = "N/A";
			switch (network_type) {
			case 0:
				mobile_network_type = "TYPE_UNKNOWN";
				break;
			case 1:
				mobile_network_type = "GPRS";
				break;
			case 2:
				mobile_network_type = "EDGE";
				break;
			case 3:
				mobile_network_type = "UMTS";
				break;
			case 4:
				mobile_network_type = "CDMA";
				break;
			case 5:
				mobile_network_type = "EVDO_0";
				break;
			case 6:
				mobile_network_type = "EVDO_A";
				break;
			case 7:
				mobile_network_type = "1xRTT";
				break;
			case 8:
				mobile_network_type = "HSDPA";
				break;
			case 9:
				mobile_network_type = "HSUPA";
				break;
			case 10:
				mobile_network_type = "HSPA";
				break;
			}
	
			String device_version = android.os.Build.VERSION.RELEASE;
	
			if (device_version == null) {
				device_version = "N/A";
			}
			
			String device_model = android.os.Build.MODEL;
			
			if (device_model == null) {
				device_model = "N/A";
			}

			String device_language = getResources().getConfiguration().locale.getLanguage();
			String home_function = mPreferenceHelper.defaultLaunchActivity();
			String welcome_message = String.valueOf(mPreferenceHelper.isWelcomeQuoteEnabled());
			
			// post the data
			HttpClient client = new DefaultHttpClient();
			HttpPost post = new HttpPost("http://tramhunter.andybotting.com/stats/app/send");
			post.setHeader("Content-Type", "application/x-www-form-urlencoded");
	
			List<NameValuePair> pairs = new ArrayList<NameValuePair>();
			pairs.add(new BasicNameValuePair("device_id", device_id));
			pairs.add(new BasicNameValuePair("app_version", app_version));
			pairs.add(new BasicNameValuePair("home_function", home_function));
			pairs.add(new BasicNameValuePair("welcome_message", welcome_message));
			pairs.add(new BasicNameValuePair("device_model", device_model));
			pairs.add(new BasicNameValuePair("device_version", device_version));
			pairs.add(new BasicNameValuePair("device_language", device_language));
			pairs.add(new BasicNameValuePair("mobile_country_code", mobile_country_code));
			pairs.add(new BasicNameValuePair("mobile_network_number", mobile_network_number));
			pairs.add(new BasicNameValuePair("mobile_network_type",	mobile_network_type));

			try {
				post.setEntity(new UrlEncodedFormEntity(pairs));
			} 
			catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}

			try {
				HttpResponse response = client.execute(post);
				response.getStatusLine().getStatusCode();
			}
			catch (Exception e) {
				e.printStackTrace();
			}

		} 
		catch (Exception e) {
			e.printStackTrace();
		}

	}
	
    
}
