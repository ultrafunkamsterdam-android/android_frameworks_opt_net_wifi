/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.HOTSPOT_FAILED;
import static android.net.wifi.WifiManager.HOTSPOT_STARTED;
import static android.net.wifi.WifiManager.HOTSPOT_STOPPED;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_LOCAL_ONLY;
import static android.net.wifi.WifiManager.IFACE_IP_MODE_TETHERED;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_GENERAL;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_NO_CHANNEL;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_FEATURE_INFRA_5G;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY;
import static android.provider.Settings.Secure.LOCATION_MODE_OFF;

import static com.android.server.wifi.LocalOnlyHotspotRequestInfo.HOTSPOT_NO_ERROR;
import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.SoftApCallback;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;

import com.android.internal.os.PowerProfile;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.WifiServiceImpl.LocalOnlyRequestorCallback;
import com.android.server.wifi.hotspot2.PasspointProvisioningTestUtil;
import com.android.server.wifi.util.WifiAsyncChannel;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link WifiServiceImpl}.
 *
 * Note: this is intended to build up over time and will not immediately cover the entire file.
 */
@SmallTest
public class WifiServiceImplTest {

    private static final String TAG = "WifiServiceImplTest";
    private static final String SCAN_PACKAGE_NAME = "scanPackage";
    private static final int DEFAULT_VERBOSE_LOGGING = 0;
    private static final String ANDROID_SYSTEM_PACKAGE = "android";
    private static final String TEST_PACKAGE_NAME = "TestPackage";
    private static final String SYSUI_PACKAGE_NAME = "com.android.systemui";
    private static final int TEST_PID = 6789;
    private static final int TEST_PID2 = 9876;
    private static final int TEST_UID = 1200000;
    private static final int OTHER_TEST_UID = 1300000;
    private static final int TEST_USER_HANDLE = 13;
    private static final int TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER = 17;
    private static final int TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER = 234;
    private static final String WIFI_IFACE_NAME = "wlan0";
    private static final String TEST_COUNTRY_CODE = "US";

    private AsyncChannel mAsyncChannel;
    private WifiServiceImpl mWifiServiceImpl;
    private TestLooper mLooper;
    private PowerManager mPowerManager;
    private Handler mHandler;
    private Handler mHandlerSpyForCmiRunWithScissors;
    private Messenger mAppMessenger;
    private int mPid;
    private int mPid2 = Process.myPid();
    private OsuProvider mOsuProvider;
    private SoftApCallback mStateMachineSoftApCallback;
    private ApplicationInfo mApplicationInfo;

    final ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);
    final ArgumentCaptor<IntentFilter> mIntentFilterCaptor =
            ArgumentCaptor.forClass(IntentFilter.class);

    final ArgumentCaptor<Message> mMessageCaptor = ArgumentCaptor.forClass(Message.class);
    final ArgumentCaptor<SoftApModeConfiguration> mSoftApModeConfigCaptor =
            ArgumentCaptor.forClass(SoftApModeConfiguration.class);
    final ArgumentCaptor<Handler> mHandlerCaptor = ArgumentCaptor.forClass(Handler.class);

    @Mock Context mContext;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiCountryCode mWifiCountryCode;
    @Mock Clock mClock;
    @Mock WifiController mWifiController;
    @Mock WifiTrafficPoller mWifiTrafficPoller;
    @Mock ClientModeImpl mClientModeImpl;
    @Mock ActiveModeWarden mActiveModeWarden;
    @Mock HandlerThread mHandlerThread;
    @Mock Resources mResources;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock WifiLockManager mLockManager;
    @Mock WifiMulticastLockManager mWifiMulticastLockManager;
    @Mock WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock WifiBackupRestore mWifiBackupRestore;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock WifiPermissionsWrapper mWifiPermissionsWrapper;
    @Mock WifiSettingsStore mSettingsStore;
    @Mock ContentResolver mContentResolver;
    @Mock PackageManager mPackageManager;
    @Mock UserManager mUserManager;
    @Mock WifiApConfigStore mWifiApConfigStore;
    @Mock WifiConfiguration mApConfig;
    @Mock ActivityManager mActivityManager;
    @Mock AppOpsManager mAppOpsManager;
    @Mock IBinder mAppBinder;
    @Mock IBinder mAnotherAppBinder;
    @Mock LocalOnlyHotspotRequestInfo mRequestInfo;
    @Mock LocalOnlyHotspotRequestInfo mRequestInfo2;
    @Mock IProvisioningCallback mProvisioningCallback;
    @Mock ISoftApCallback mClientSoftApCallback;
    @Mock ISoftApCallback mAnotherSoftApCallback;
    @Mock PowerProfile mPowerProfile;
    @Mock WifiTrafficPoller mWifiTrafficPolller;
    @Mock ScanRequestProxy mScanRequestProxy;
    @Mock ITrafficStateCallback mTrafficStateCallback;
    @Mock INetworkRequestMatchCallback mNetworkRequestMatchCallback;
    @Mock WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;

    @Spy FakeWifiLog mLog;

    private class WifiAsyncChannelTester {
        private static final String TAG = "WifiAsyncChannelTester";
        public static final int CHANNEL_STATE_FAILURE = -1;
        public static final int CHANNEL_STATE_DISCONNECTED = 0;
        public static final int CHANNEL_STATE_HALF_CONNECTED = 1;
        public static final int CHANNEL_STATE_FULLY_CONNECTED = 2;

        private int mState = CHANNEL_STATE_DISCONNECTED;
        private WifiAsyncChannel mChannel;
        private WifiLog mAsyncTestLog;

        WifiAsyncChannelTester(WifiInjector wifiInjector) {
            mAsyncTestLog = wifiInjector.makeLog(TAG);
        }

        public int getChannelState() {
            return mState;
        }

        public void connect(final Looper looper, final Messenger messenger,
                final Handler incomingMessageHandler) {
            assertEquals("AsyncChannel must be in disconnected state",
                    CHANNEL_STATE_DISCONNECTED, mState);
            mChannel = new WifiAsyncChannel(TAG);
            mChannel.setWifiLog(mLog);
            Handler handler = new Handler(mLooper.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                                mChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                                mState = CHANNEL_STATE_HALF_CONNECTED;
                            } else {
                                mState = CHANNEL_STATE_FAILURE;
                            }
                            break;
                        case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                            mState = CHANNEL_STATE_FULLY_CONNECTED;
                            break;
                        case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                            mState = CHANNEL_STATE_DISCONNECTED;
                            break;
                        default:
                            incomingMessageHandler.handleMessage(msg);
                            break;
                    }
                }
            };
            mChannel.connect(null, handler, messenger);
        }

        private Message sendMessageSynchronously(Message request) {
            return mChannel.sendMessageSynchronously(request);
        }

        private void sendMessage(Message request) {
            mChannel.sendMessage(request);
        }
    }

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = spy(new Handler(mLooper.getLooper()));
        mAppMessenger = new Messenger(mHandler);
        mAsyncChannel = spy(new AsyncChannel());
        mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        WifiInjector.sWifiInjector = mWifiInjector;
        when(mRequestInfo.getPid()).thenReturn(mPid);
        when(mRequestInfo2.getPid()).thenReturn(mPid2);
        when(mWifiInjector.getUserManager()).thenReturn(mUserManager);
        when(mWifiInjector.getWifiCountryCode()).thenReturn(mWifiCountryCode);
        when(mWifiInjector.getWifiController()).thenReturn(mWifiController);
        when(mWifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(mWifiInjector.getClientModeImpl()).thenReturn(mClientModeImpl);
        when(mClientModeImpl.syncInitialize(any())).thenReturn(true);
        when(mClientModeImpl.getHandler()).thenReturn(new Handler());
        when(mWifiInjector.getActiveModeWarden()).thenReturn(mActiveModeWarden);
        when(mWifiInjector.getWifiServiceHandlerThread()).thenReturn(mHandlerThread);
        when(mWifiInjector.getPowerProfile()).thenReturn(mPowerProfile);
        when(mHandlerThread.getLooper()).thenReturn(mLooper.getLooper());
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getApplicationInfo(any(), anyInt())).thenReturn(mApplicationInfo);
        when(mWifiInjector.getWifiApConfigStore()).thenReturn(mWifiApConfigStore);
        doNothing().when(mFrameworkFacade).registerContentObserver(eq(mContext), any(),
                anyBoolean(), any());
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mActivityManager);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        IPowerManager powerManagerService = mock(IPowerManager.class);
        mPowerManager = new PowerManager(mContext, powerManagerService, new Handler());
        when(mContext.getSystemServiceName(PowerManager.class)).thenReturn(Context.POWER_SERVICE);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        WifiAsyncChannel wifiAsyncChannel = new WifiAsyncChannel("WifiServiceImplTest");
        wifiAsyncChannel.setWifiLog(mLog);
        when(mFrameworkFacade.makeWifiAsyncChannel(anyString())).thenReturn(wifiAsyncChannel);
        when(mWifiInjector.getFrameworkFacade()).thenReturn(mFrameworkFacade);
        when(mWifiInjector.getWifiLockManager()).thenReturn(mLockManager);
        when(mWifiInjector.getWifiMulticastLockManager()).thenReturn(mWifiMulticastLockManager);
        when(mWifiInjector.getWifiLastResortWatchdog()).thenReturn(mWifiLastResortWatchdog);
        when(mWifiInjector.getWifiBackupRestore()).thenReturn(mWifiBackupRestore);
        when(mWifiInjector.makeLog(anyString())).thenReturn(mLog);
        when(mWifiInjector.getWifiTrafficPoller()).thenReturn(mWifiTrafficPoller);
        when(mWifiInjector.getWifiPermissionsUtil()).thenReturn(mWifiPermissionsUtil);
        when(mWifiInjector.getWifiPermissionsWrapper()).thenReturn(mWifiPermissionsWrapper);
        when(mWifiInjector.getWifiSettingsStore()).thenReturn(mSettingsStore);
        when(mWifiInjector.getClock()).thenReturn(mClock);
        when(mWifiInjector.getScanRequestProxy()).thenReturn(mScanRequestProxy);
        when(mWifiInjector.getWifiNetworkSuggestionsManager())
                .thenReturn(mWifiNetworkSuggestionsManager);
        when(mClientModeImpl.syncStartSubscriptionProvisioning(anyInt(),
                any(OsuProvider.class), any(IProvisioningCallback.class), any())).thenReturn(true);
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)).thenReturn(true);
        // Create an OSU provider that can be provisioned via an open OSU AP
        mOsuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETUP_WIZARD),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_STACK),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mScanRequestProxy.startScan(anyInt(), anyString())).thenReturn(true);

        ArgumentCaptor<SoftApCallback> softApCallbackCaptor =
                ArgumentCaptor.forClass(SoftApCallback.class);
        mWifiServiceImpl = new WifiServiceImpl(mContext, mWifiInjector, mAsyncChannel);
        verify(mActiveModeWarden).registerSoftApCallback(softApCallbackCaptor.capture());
        mStateMachineSoftApCallback = softApCallbackCaptor.getValue();
        mWifiServiceImpl.setWifiHandlerLogForTest(mLog);
    }

    private WifiAsyncChannelTester verifyAsyncChannelHalfConnected() throws RemoteException {
        WifiAsyncChannelTester channelTester = new WifiAsyncChannelTester(mWifiInjector);
        Handler handler = mock(Handler.class);
        TestLooper looper = new TestLooper();
        channelTester.connect(looper.getLooper(),
                mWifiServiceImpl.getWifiServiceMessenger(TEST_PACKAGE_NAME), handler);
        mLooper.dispatchAll();
        assertEquals("AsyncChannel must be half connected",
                WifiAsyncChannelTester.CHANNEL_STATE_HALF_CONNECTED,
                channelTester.getChannelState());
        return channelTester;
    }

    /**
     * Verifies that any operations on WifiServiceImpl without setting up the ClientModeImpl
     * channel would fail.
     */
    @Test
    public void testRemoveNetworkUnknown() {
        assertFalse(mWifiServiceImpl.removeNetwork(-1, TEST_PACKAGE_NAME));
        verify(mClientModeImpl, never()).syncRemoveNetwork(any(), anyInt());
    }

    /**
     * Tests whether we're able to set up an async channel connection with WifiServiceImpl.
     * This is the path used by some WifiManager public API calls.
     */
    @Test
    public void testAsyncChannelHalfConnected() throws RemoteException {
        verifyAsyncChannelHalfConnected();
    }

    /**
     * Ensure WifiMetrics.dump() is the only dump called when 'dumpsys wifi WifiMetricsProto' is
     * called. This is required to support simple metrics collection via dumpsys
     */
    @Test
    public void testWifiMetricsDump() {
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()),
                new String[]{mWifiMetrics.PROTO_DUMP_ARG});
        verify(mWifiMetrics)
                .dump(any(FileDescriptor.class), any(PrintWriter.class), any(String[].class));
        verify(mClientModeImpl, never())
                .dump(any(FileDescriptor.class), any(PrintWriter.class), any(String[].class));
    }

    /**
     * Ensure WifiServiceImpl.dump() doesn't throw an NPE when executed with null args
     */
    @Test
    public void testDumpNullArgs() {
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), null);
    }

    /**
     * Verify that wifi can be enabled by a caller with NETWORK_SETTINGS permission.
     */
    @Test
    public void testSetWifiEnabledSuccess() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mWifiController).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that the CMD_TOGGLE_WIFI message won't be sent if wifi is already on.
     */
    @Test
    public void testSetWifiEnabledNoToggle() throws Exception {
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(false);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mWifiController, never()).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify a SecurityException is thrown if a caller does not have the CHANGE_WIFI_STATE
     * permission to toggle wifi.
     */
    @Test
    public void testSetWifiEnableWithoutChangeWifiStatePermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        try {
            mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true);
            fail();
        } catch (SecurityException e) {
        }
    }

    /**
     * Verify setWifiEnabled returns failure if a caller does not have the NETWORK_SETTINGS
     * permission to toggle wifi.
     */
    @Test
    public void testSetWifiEnableWithoutNetworkSettingsPermission() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
    }

    /**
     * Verify that wifi can be disabled by a caller with NETWORK_SETTINGS permission.
     */
    @Test
    public void testSetWifiDisabledSuccess() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mWifiController).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that CMD_TOGGLE_WIFI message won't be sent if wifi is already off.
     */
    @Test
    public void testSetWifiDisabledNoToggle() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mWifiController, never()).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify a SecurityException is thrown if a caller does not have the CHANGE_WIFI_STATE
     * permission to toggle wifi.
     */
    @Test
    public void testSetWifiDisabledWithoutChangeWifiStatePermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false);
            fail();
        } catch (SecurityException e) { }
    }

    /**
     * Ensure unpermitted callers cannot write the SoftApConfiguration.
     *
     * @throws SecurityException
     */
    @Test
    public void testSetWifiApConfigurationNotSavedWithoutPermission() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(false);
        WifiConfiguration apConfig = new WifiConfiguration();
        try {
            mWifiServiceImpl.setWifiApConfiguration(apConfig, TEST_PACKAGE_NAME);
            fail("Expected SecurityException");
        } catch (SecurityException e) { }
    }

    /**
     * Ensure softap config is written when the caller has the correct permission.
     */
    @Test
    public void testSetWifiApConfigurationSuccess() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        WifiConfiguration apConfig = createValidSoftApConfiguration();

        assertTrue(mWifiServiceImpl.setWifiApConfiguration(apConfig, TEST_PACKAGE_NAME));
        mLooper.dispatchAll();
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mWifiApConfigStore).setApConfiguration(eq(apConfig));
    }

    /**
     * Ensure that a null config does not overwrite the saved ap config.
     */
    @Test
    public void testSetWifiApConfigurationNullConfigNotSaved() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        assertFalse(mWifiServiceImpl.setWifiApConfiguration(null, TEST_PACKAGE_NAME));
        verify(mWifiApConfigStore, never()).setApConfiguration(isNull(WifiConfiguration.class));
    }

    /**
     * Ensure that an invalid config does not overwrite the saved ap config.
     */
    @Test
    public void testSetWifiApConfigurationWithInvalidConfigNotSaved() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        assertFalse(mWifiServiceImpl.setWifiApConfiguration(new WifiConfiguration(),
                                                            TEST_PACKAGE_NAME));
        verify(mWifiApConfigStore, never()).setApConfiguration(any());
    }

    /**
     * Ensure unpermitted callers are not able to retrieve the softap config.
     *
     * @throws SecurityException
     */
    @Test
    public void testGetWifiApConfigurationNotReturnedWithoutPermission() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(false);
        try {
            mWifiServiceImpl.getWifiApConfiguration();
            fail("Expected a SecurityException");
        } catch (SecurityException e) {
        }
    }

    /**
     * Ensure permitted callers are able to retrieve the softap config.
     */
    @Test
    public void testGetWifiApConfigurationSuccess() {
        setupClientModeImplHandlerForRunWithScissors();

        mWifiServiceImpl = new WifiServiceImpl(mContext, mWifiInjector, mAsyncChannel);
        mWifiServiceImpl.setWifiHandlerLogForTest(mLog);

        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);

        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        WifiConfiguration apConfig = new WifiConfiguration();
        when(mWifiApConfigStore.getApConfiguration()).thenReturn(apConfig);
        assertEquals(apConfig, mWifiServiceImpl.getWifiApConfiguration());
    }

    /**
     * Ensure we return the proper variable for the softap state after getting an AP state change
     * broadcast.
     */
    @Test
    public void testGetWifiApEnabled() {
        // set up WifiServiceImpl with a live thread for testing
        HandlerThread serviceHandlerThread = createAndStartHandlerThreadForRunWithScissors();
        when(mWifiInjector.getWifiServiceHandlerThread()).thenReturn(serviceHandlerThread);
        mWifiServiceImpl = new WifiServiceImpl(mContext, mWifiInjector, mAsyncChannel);
        mWifiServiceImpl.setWifiHandlerLogForTest(mLog);

        // ap should be disabled when wifi hasn't been started
        assertEquals(WifiManager.WIFI_AP_STATE_DISABLED, mWifiServiceImpl.getWifiApEnabledState());

        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        mLooper.dispatchAll();

        // ap should be disabled initially
        assertEquals(WifiManager.WIFI_AP_STATE_DISABLED, mWifiServiceImpl.getWifiApEnabledState());

        // send an ap state change to verify WifiServiceImpl is updated
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_DISABLED, SAP_START_FAILURE_GENERAL,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        assertEquals(WifiManager.WIFI_AP_STATE_FAILED, mWifiServiceImpl.getWifiApEnabledState());
    }

    /**
     * Ensure we do not allow unpermitted callers to get the wifi ap state.
     */
    @Test
    public void testGetWifiApEnabledPermissionDenied() {
        // we should not be able to get the state
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.ACCESS_WIFI_STATE),
                                                eq("WifiService"));

        try {
            mWifiServiceImpl.getWifiApEnabledState();
            fail("expected SecurityException");
        } catch (SecurityException expected) { }
    }

    /**
     * Make sure we do not start wifi if System services have to be restarted to decrypt the device.
     */
    @Test
    public void testWifiControllerDoesNotStartWhenDeviceTriggerResetMainAtBoot() {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mWifiController, never()).start();
    }

    /**
     * Make sure we do start WifiController (wifi disabled) if the device is already decrypted.
     */
    @Test
    public void testWifiControllerStartsWhenDeviceIsDecryptedAtBootWithWifiDisabled() {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mWifiController).start();
        verify(mWifiController, never()).sendMessage(CMD_WIFI_TOGGLED);
    }

    /**
     * Make sure we do start WifiController (wifi enabled) if the device is already decrypted.
     */
    @Test
    public void testWifiFullyStartsWhenDeviceIsDecryptedAtBootWithWifiEnabled() {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.handleWifiToggled(true)).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        when(mClientModeImpl.syncGetWifiState()).thenReturn(WIFI_STATE_DISABLED);
        when(mContext.getPackageName()).thenReturn(ANDROID_SYSTEM_PACKAGE);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mWifiController).start();
        verify(mWifiController).sendMessage(CMD_WIFI_TOGGLED);
    }

    /**
     * Verify caller with proper permission can call startSoftAp.
     */
    @Test
    public void testStartSoftApWithPermissionsAndNullConfig() {
        boolean result = mWifiServiceImpl.startSoftAp(null);
        assertTrue(result);
        verify(mWifiController)
                .sendMessage(eq(CMD_SET_AP), eq(1), eq(0), mSoftApModeConfigCaptor.capture());
        assertNull(mSoftApModeConfigCaptor.getValue().getWifiConfiguration());
    }

    /**
     * Verify caller with proper permissions but an invalid config does not start softap.
     */
    @Test
    public void testStartSoftApWithPermissionsAndInvalidConfig() {
        boolean result = mWifiServiceImpl.startSoftAp(mApConfig);
        assertFalse(result);
        verifyZeroInteractions(mWifiController);
    }

    /**
     * Verify caller with proper permission and valid config does start softap.
     */
    @Test
    public void testStartSoftApWithPermissionsAndValidConfig() {
        WifiConfiguration config = createValidSoftApConfiguration();
        boolean result = mWifiServiceImpl.startSoftAp(config);
        assertTrue(result);
        verify(mWifiController)
                .sendMessage(eq(CMD_SET_AP), eq(1), eq(0), mSoftApModeConfigCaptor.capture());
        assertEquals(config, mSoftApModeConfigCaptor.getValue().getWifiConfiguration());
    }

    /**
     * Verify a SecurityException is thrown when a caller without the correct permission attempts to
     * start softap.
     */
    @Test(expected = SecurityException.class)
    public void testStartSoftApWithoutPermissionThrowsException() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_STACK),
                                                eq("WifiService"));
        mWifiServiceImpl.startSoftAp(null);
    }

    /**
     * Verify caller with proper permission can call stopSoftAp.
     */
    @Test
    public void testStopSoftApWithPermissions() {
        boolean result = mWifiServiceImpl.stopSoftAp();
        assertTrue(result);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify SecurityException is thrown when a caller without the correct permission attempts to
     * stop softap.
     */
    @Test(expected = SecurityException.class)
    public void testStopSoftApWithoutPermissionThrowsException() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_STACK),
                                                eq("WifiService"));
        mWifiServiceImpl.stopSoftAp();
    }

    /**
     * Ensure that we handle app ops check failure when handling scan request.
     */
    @Test
    public void testStartScanFailureAppOpsIgnored() {
        setupClientModeImplHandlerForRunWithScissors();
        doReturn(AppOpsManager.MODE_IGNORED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), SCAN_PACKAGE_NAME);
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME));
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Ensure that we handle scan access permission check failure when handling scan request.
     */
    @Test
    public void testStartScanFailureInCanAccessScanResultsPermission() {
        setupClientModeImplHandlerForRunWithScissors();
        doThrow(new SecurityException()).when(mWifiPermissionsUtil)
                .enforceCanAccessScanResults(SCAN_PACKAGE_NAME, Process.myUid());
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME));
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Ensure that we handle scan request failure when posting the runnable to handler fails.
     */
    @Test
    public void testStartScanFailureInRunWithScissors() {
        setupClientModeImplHandlerForRunWithScissors();
        doReturn(false).when(mHandlerSpyForCmiRunWithScissors)
                .runWithScissors(any(), anyLong());
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME));
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Ensure that we handle scan request failure from ScanRequestProxy fails.
     */
    @Test
    public void testStartScanFailureFromScanRequestProxy() {
        setupClientModeImplHandlerForRunWithScissors();
        when(mScanRequestProxy.startScan(anyInt(), anyString())).thenReturn(false);
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME));
        verify(mScanRequestProxy).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    static final String TEST_SSID = "Sid's Place";
    static final String TEST_SSID_WITH_QUOTES = "\"" + TEST_SSID + "\"";
    static final String TEST_BSSID = "01:02:03:04:05:06";
    static final String TEST_PACKAGE = "package";

    private void setupForGetConnectionInfo() {
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        wifiInfo.setBSSID(TEST_BSSID);
        when(mClientModeImpl.syncRequestConnectionInfo()).thenReturn(wifiInfo);
    }

    /**
     * Test that connected SSID and BSSID are not exposed to an app that does not have the
     * appropriate permissions.
     */
    @Test
    public void testConnectedIdsAreHiddenFromAppWithoutPermission() throws Exception {
        setupForGetConnectionInfo();

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), anyInt());

        WifiInfo connectionInfo = mWifiServiceImpl.getConnectionInfo(TEST_PACKAGE);

        assertEquals(WifiSsid.NONE, connectionInfo.getSSID());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, connectionInfo.getBSSID());
    }

    /**
     * Test that connected SSID and BSSID are not exposed to an app that does not have the
     * appropriate permissions, when enforceCanAccessScanResults raises a SecurityException.
     */
    @Test
    public void testConnectedIdsAreHiddenOnSecurityException() throws Exception {
        setupForGetConnectionInfo();

        doThrow(new SecurityException()).when(mWifiPermissionsUtil).enforceCanAccessScanResults(
                anyString(), anyInt());

        WifiInfo connectionInfo = mWifiServiceImpl.getConnectionInfo(TEST_PACKAGE);

        assertEquals(WifiSsid.NONE, connectionInfo.getSSID());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, connectionInfo.getBSSID());
    }

    /**
     * Test that connected SSID and BSSID are exposed to an app that does have the
     * appropriate permissions.
     */
    @Test
    public void testConnectedIdsAreVisibleFromPermittedApp() throws Exception {
        setupForGetConnectionInfo();

        WifiInfo connectionInfo = mWifiServiceImpl.getConnectionInfo(TEST_PACKAGE);

        assertEquals(TEST_SSID_WITH_QUOTES, connectionInfo.getSSID());
        assertEquals(TEST_BSSID, connectionInfo.getBSSID());
    }

    /**
     * Test fetching of scan results.
     */
    @Test
    public void testGetScanResults() {
        setupClientModeImplHandlerForRunWithScissors();

        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        when(mScanRequestProxy.getScanResults()).thenReturn(scanResultList);

        String packageName = "test.com";
        List<ScanResult> retrievedScanResultList = mWifiServiceImpl.getScanResults(packageName);
        verify(mScanRequestProxy).getScanResults();

        ScanTestUtil.assertScanResultsEquals(scanResults,
                retrievedScanResultList.toArray(new ScanResult[retrievedScanResultList.size()]));
    }

    /**
     * Ensure that we handle scan results failure when posting the runnable to handler fails.
     */
    @Test
    public void testGetScanResultsFailureInRunWithScissors() {
        setupClientModeImplHandlerForRunWithScissors();
        doReturn(false).when(mHandlerSpyForCmiRunWithScissors)
                .runWithScissors(any(), anyLong());

        ScanResult[] scanResults =
                ScanTestUtil.createScanDatas(new int[][]{{2417, 2427, 5180, 5170}})[0]
                        .getResults();
        List<ScanResult> scanResultList =
                new ArrayList<>(Arrays.asList(scanResults));
        when(mScanRequestProxy.getScanResults()).thenReturn(scanResultList);

        String packageName = "test.com";
        List<ScanResult> retrievedScanResultList = mWifiServiceImpl.getScanResults(packageName);
        verify(mScanRequestProxy, never()).getScanResults();

        assertTrue(retrievedScanResultList.isEmpty());
    }

    private void registerLOHSRequestFull() {
        // allow test to proceed without a permission check failure
        when(mSettingsStore.getLocationModeSetting(mContext))
                .thenReturn(LOCATION_MODE_HIGH_ACCURACY);
        try {
            when(mFrameworkFacade.isAppForeground(anyInt())).thenReturn(true);
        } catch (RemoteException e) { }
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING))
                .thenReturn(false);
        int result = mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder,
                TEST_PACKAGE_NAME);
        assertEquals(LocalOnlyHotspotCallback.REQUEST_REGISTERED, result);
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
    }

    /**
     * Verify that the call to startLocalOnlyHotspot returns REQUEST_REGISTERED when successfully
     * called.
     */
    @Test
    public void testStartLocalOnlyHotspotSingleRegistrationReturnsRequestRegistered() {
        registerLOHSRequestFull();
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if the caller does not
     * have the CHANGE_WIFI_STATE permission.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutCorrectPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder, TEST_PACKAGE_NAME);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if the caller does not
     * have Location permission.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutLocationPermission() {
        doThrow(new SecurityException())
                .when(mWifiPermissionsUtil).enforceLocationPermission(eq(TEST_PACKAGE_NAME),
                                                                      anyInt());
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder, TEST_PACKAGE_NAME);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if Location mode is
     * disabled.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutLocationEnabled() {
        when(mSettingsStore.getLocationModeSetting(mContext)).thenReturn(LOCATION_MODE_OFF);
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder, TEST_PACKAGE_NAME);
    }

    /**
     * Only start LocalOnlyHotspot if the caller is the foreground app at the time of the request.
     */
    @Test
    public void testStartLocalOnlyHotspotFailsIfRequestorNotForegroundApp() throws Exception {
        when(mSettingsStore.getLocationModeSetting(mContext))
                .thenReturn(LOCATION_MODE_HIGH_ACCURACY);

        when(mFrameworkFacade.isAppForeground(anyInt())).thenReturn(false);
        int result = mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder,
                TEST_PACKAGE_NAME);
        assertEquals(LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE, result);
    }

    /**
     * Do not register the LocalOnlyHotspot request if the caller app cannot be verified as the
     * foreground app at the time of the request (ie, throws an exception in the check).
     */
    @Test
    public void testStartLocalOnlyHotspotFailsIfForegroundAppCheckThrowsRemoteException()
            throws Exception {
        when(mSettingsStore.getLocationModeSetting(mContext))
                .thenReturn(LOCATION_MODE_HIGH_ACCURACY);

        when(mFrameworkFacade.isAppForeground(anyInt())).thenThrow(new RemoteException());
        int result = mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder,
                TEST_PACKAGE_NAME);
        assertEquals(LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE, result);
    }

    /**
     * Only start LocalOnlyHotspot if we are not tethering.
     */
    @Test
    public void testHotspotDoesNotStartWhenAlreadyTethering() throws Exception {
        when(mSettingsStore.getLocationModeSetting(mContext))
                            .thenReturn(LOCATION_MODE_HIGH_ACCURACY);
        when(mFrameworkFacade.isAppForeground(anyInt())).thenReturn(true);
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        int returnCode = mWifiServiceImpl.startLocalOnlyHotspot(
                mAppMessenger, mAppBinder, TEST_PACKAGE_NAME);
        assertEquals(ERROR_INCOMPATIBLE_MODE, returnCode);
    }

    /**
     * Only start LocalOnlyHotspot if admin setting does not disallow tethering.
     */
    @Test
    public void testHotspotDoesNotStartWhenTetheringDisallowed() throws Exception {
        when(mSettingsStore.getLocationModeSetting(mContext))
                .thenReturn(LOCATION_MODE_HIGH_ACCURACY);
        when(mFrameworkFacade.isAppForeground(anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING))
                .thenReturn(true);
        int returnCode = mWifiServiceImpl.startLocalOnlyHotspot(
                mAppMessenger, mAppBinder, TEST_PACKAGE_NAME);
        assertEquals(ERROR_TETHERING_DISALLOWED, returnCode);
    }

    /**
     * Verify that callers can only have one registered LOHS request.
     */
    @Test(expected = IllegalStateException.class)
    public void testStartLocalOnlyHotspotThrowsExceptionWhenCallerAlreadyRegistered() {
        registerLOHSRequestFull();

        // now do the second request that will fail
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder, TEST_PACKAGE_NAME);
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot does not do anything when there aren't any
     * registered callers.
     */
    @Test
    public void testStopLocalOnlyHotspotDoesNothingWithoutRegisteredRequests() {
        // allow test to proceed without a permission check failure
        mWifiServiceImpl.stopLocalOnlyHotspot();
        // there is nothing registered, so this shouldn't do anything
        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), anyInt(), anyInt());
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot does not do anything when one caller unregisters
     * but there is still an active request
     */
    @Test
    public void testStopLocalOnlyHotspotDoesNothingWithARemainingRegisteredRequest() {
        // register a request that will remain after the stopLOHS call
        mWifiServiceImpl.registerLOHSForTest(mPid, mRequestInfo);

        registerLOHSRequestFull();

        // Since we are calling with the same pid, the second register call will be removed
        mWifiServiceImpl.stopLocalOnlyHotspot();
        // there is still a valid registered request - do not tear down LOHS
        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), anyInt(), anyInt());
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot sends a message to WifiController to stop
     * the softAp when there is one registered caller when that caller is removed.
     */
    @Test
    public void testStopLocalOnlyHotspotTriggersSoftApStopWithOneRegisteredRequest() {
        registerLOHSRequestFull();
        verify(mWifiController)
                .sendMessage(eq(CMD_SET_AP), eq(1), eq(0), any(SoftApModeConfiguration.class));

        // No permission check required for change_wifi_state.
        verify(mContext, never()).enforceCallingOrSelfPermission(
                eq("android.Manifest.permission.CHANGE_WIFI_STATE"), anyString());

        mWifiServiceImpl.stopLocalOnlyHotspot();
        // there is was only one request registered, we should tear down softap
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify that by default startLocalOnlyHotspot starts access point at 2 GHz.
     */
    @Test
    public void testStartLocalOnlyHotspotAt2Ghz() {
        registerLOHSRequestFull();
        verifyLohsBand(WifiConfiguration.AP_BAND_2GHZ);
    }

    /**
     * Verify that startLocalOnlyHotspot will start access point at 5 GHz if properly configured.
     */
    @Test
    public void testStartLocalOnlyHotspotAt5Ghz() {
        when(mResources.getBoolean(
                eq(com.android.internal.R.bool.config_wifi_local_only_hotspot_5ghz)))
                .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).thenReturn(true);
        when(mClientModeImpl.syncGetSupportedFeatures(any(AsyncChannel.class)))
                .thenReturn(WIFI_FEATURE_INFRA_5G);

        verify(mAsyncChannel).connect(any(), mHandlerCaptor.capture(), any(Handler.class));
        final Handler handler = mHandlerCaptor.getValue();
        handler.handleMessage(handler.obtainMessage(
                AsyncChannel.CMD_CHANNEL_HALF_CONNECTED, AsyncChannel.STATUS_SUCCESSFUL, 0));

        registerLOHSRequestFull();
        verifyLohsBand(WifiConfiguration.AP_BAND_5GHZ);
    }

    private void verifyLohsBand(int expectedBand) {
        verify(mWifiController)
                .sendMessage(eq(CMD_SET_AP), eq(1), eq(0), mSoftApModeConfigCaptor.capture());
        final WifiConfiguration configuration = mSoftApModeConfigCaptor.getValue().mConfig;
        assertNotNull(configuration);
        assertEquals(expectedBand, configuration.apBand);
    }

    /**
         * Verify that WifiServiceImpl does not send the stop ap message if there were no
         * pending LOHS requests upon a binder death callback.
         */
    @Test
    public void testServiceImplNotCalledWhenBinderDeathTriggeredNoRequests() {
        LocalOnlyRequestorCallback binderDeathCallback =
                mWifiServiceImpl.new LocalOnlyRequestorCallback();

        binderDeathCallback.onLocalOnlyHotspotRequestorDeath(mRequestInfo);
        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify that WifiServiceImpl does not send the stop ap message if there are remaining
     * registered LOHS requests upon a binder death callback.  Additionally verify that softap mode
     * will be stopped if that remaining request is removed (to verify the binder death properly
     * cleared the requestor that died).
     */
    @Test
    public void testServiceImplNotCalledWhenBinderDeathTriggeredWithRegisteredRequests() {
        LocalOnlyRequestorCallback binderDeathCallback =
                mWifiServiceImpl.new LocalOnlyRequestorCallback();

        // registering a request directly from the test will not trigger a message to start
        // softap mode
        mWifiServiceImpl.registerLOHSForTest(mPid, mRequestInfo);

        registerLOHSRequestFull();

        binderDeathCallback.onLocalOnlyHotspotRequestorDeath(mRequestInfo);
        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), anyInt(), anyInt());

        reset(mWifiController);

        // now stop as the second request and confirm CMD_SET_AP will be sent to make sure binder
        // death requestor was removed
        mWifiServiceImpl.stopLocalOnlyHotspot();
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify that a call to registerSoftApCallback throws a SecurityException if the caller does
     * not have NETWORK_SETTINGS permission.
     */
    @Test
    public void registerSoftApCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        try {
            final int callbackIdentifier = 1;
            mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback,
                    callbackIdentifier);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to registerSoftApCallback throws an IllegalArgumentException if the
     * parameters are not provided.
     */
    @Test
    public void registerSoftApCallbackThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            final int callbackIdentifier = 1;
            mWifiServiceImpl.registerSoftApCallback(mAppBinder, null, callbackIdentifier);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to unregisterSoftApCallback throws a SecurityException if the caller does
     * not have NETWORK_SETTINGS permission.
     */
    @Test
    public void unregisterSoftApCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        try {
            final int callbackIdentifier = 1;
            mWifiServiceImpl.unregisterSoftApCallback(callbackIdentifier);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verifies that we handle softap callback registration failure if we encounter an exception
     * while linking to death.
     */
    @Test
    public void registerSoftApCallbackFailureOnLinkToDeath() throws Exception {
        doThrow(new RemoteException())
                .when(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback, 1);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onStateChanged(WIFI_AP_STATE_DISABLED, 0);
        verify(mClientSoftApCallback, never()).onNumClientsChanged(0);
    }


    /**
     * Registers a soft AP callback, then verifies that the current soft AP state and num clients
     * are sent to caller immediately after callback is registered.
     */
    private void registerSoftApCallbackAndVerify(ISoftApCallback callback, int callbackIdentifier)
            throws Exception {
        registerSoftApCallbackAndVerify(mAppBinder, callback, callbackIdentifier);
    }

    /**
     * Registers a soft AP callback, then verifies that the current soft AP state and num clients
     * are sent to caller immediately after callback is registered.
     */
    private void registerSoftApCallbackAndVerify(IBinder binder, ISoftApCallback callback,
                                                 int callbackIdentifier) throws Exception {
        mWifiServiceImpl.registerSoftApCallback(binder, callback, callbackIdentifier);
        mLooper.dispatchAll();
        verify(callback).onStateChanged(WIFI_AP_STATE_DISABLED, 0);
        verify(callback).onNumClientsChanged(0);
    }

    /**
     * Verify that registering twice with same callbackIdentifier will replace the first callback.
     */
    @Test
    public void replacesOldCallbackWithNewCallbackWhenRegisteringTwice() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mAppBinder, mClientSoftApCallback, callbackIdentifier);
        registerSoftApCallbackAndVerify(
                mAnotherAppBinder, mAnotherSoftApCallback, callbackIdentifier);

        verify(mAppBinder).linkToDeath(any(), anyInt());
        verify(mAppBinder).unlinkToDeath(any(), anyInt());
        verify(mAnotherAppBinder).linkToDeath(any(), anyInt());
        verify(mAnotherAppBinder, never()).unlinkToDeath(any(), anyInt());

        final int testNumClients = 4;
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();
        // Verify only the second callback is being called
        verify(mClientSoftApCallback, never()).onNumClientsChanged(testNumClients);
        verify(mAnotherSoftApCallback).onNumClientsChanged(testNumClients);
    }

    /**
     * Verify that unregisterSoftApCallback removes callback from registered callbacks list
     */
    @Test
    public void unregisterSoftApCallbackRemovesCallback() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        mWifiServiceImpl.unregisterSoftApCallback(callbackIdentifier);
        mLooper.dispatchAll();

        final int testNumClients = 4;
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onNumClientsChanged(testNumClients);
    }

    /**
     * Verify that unregisterSoftApCallback is no-op if callbackIdentifier not registered.
     */
    @Test
    public void unregisterSoftApCallbackDoesNotRemoveCallbackIfCallbackIdentifierNotMatching()
            throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        final int differentCallbackIdentifier = 2;
        mWifiServiceImpl.unregisterSoftApCallback(differentCallbackIdentifier);
        mLooper.dispatchAll();

        final int testNumClients = 4;
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onNumClientsChanged(testNumClients);
    }

    /**
     * Registers two callbacks, remove one then verify the right callback is being called on events.
     */
    @Test
    public void correctCallbackIsCalledAfterAddingTwoCallbacksAndRemovingOne() throws Exception {
        final int callbackIdentifier = 1;
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback,
                callbackIdentifier);

        // Change state from default before registering the second callback
        final int testNumClients = 4;
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);

        // Register another callback and verify the new state is returned in the immediate callback
        final int anotherUid = 2;
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mAnotherSoftApCallback, anotherUid);
        mLooper.dispatchAll();
        verify(mAnotherSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        verify(mAnotherSoftApCallback).onNumClientsChanged(testNumClients);

        // unregister the fisrt callback
        mWifiServiceImpl.unregisterSoftApCallback(callbackIdentifier);
        mLooper.dispatchAll();

        // Update soft AP state and verify the remaining callback receives the event
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_FAILED,
                SAP_START_FAILURE_NO_CHANNEL);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onStateChanged(WIFI_AP_STATE_FAILED,
                SAP_START_FAILURE_NO_CHANNEL);
        verify(mAnotherSoftApCallback).onStateChanged(WIFI_AP_STATE_FAILED,
                SAP_START_FAILURE_NO_CHANNEL);
    }

    /**
     * Verify that wifi service registers for callers BinderDeath event
     */
    @Test
    public void registersForBinderDeathOnRegisterSoftApCallback() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);
        verify(mAppBinder).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
    }

    /**
     * Verify that we un-register the soft AP callback on receiving BinderDied event.
     */
    @Test
    public void unregistersSoftApCallbackOnBinderDied() throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> drCaptor =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);
        verify(mAppBinder).linkToDeath(drCaptor.capture(), anyInt());

        drCaptor.getValue().binderDied();
        mLooper.dispatchAll();
        verify(mAppBinder).unlinkToDeath(drCaptor.getValue(), 0);

        // Verify callback is removed from the list as well
        final int testNumClients = 4;
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback, never()).onNumClientsChanged(testNumClients);
    }

    /**
     * Verify that soft AP callback is called on NumClientsChanged event
     */
    @Test
    public void callsRegisteredCallbacksOnNumClientsChangedEvent() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        final int testNumClients = 4;
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onNumClientsChanged(testNumClients);
    }

    /**
     * Verify that soft AP callback is called on SoftApStateChanged event
     */
    @Test
    public void callsRegisteredCallbacksOnSoftApStateChangedEvent() throws Exception {
        final int callbackIdentifier = 1;
        registerSoftApCallbackAndVerify(mClientSoftApCallback, callbackIdentifier);

        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
    }

    /**
     * Verify that mSoftApState and mSoftApNumClients in WifiServiceImpl are being updated on soft
     * Ap events, even when no callbacks are registered.
     */
    @Test
    public void updatesSoftApStateAndNumClientsOnSoftApEvents() throws Exception {
        final int testNumClients = 4;
        mStateMachineSoftApCallback.onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        mStateMachineSoftApCallback.onNumClientsChanged(testNumClients);

        // Register callback after num clients and soft AP are changed.
        final int callbackIdentifier = 1;
        mWifiServiceImpl.registerSoftApCallback(mAppBinder, mClientSoftApCallback,
                callbackIdentifier);
        mLooper.dispatchAll();
        verify(mClientSoftApCallback).onStateChanged(WIFI_AP_STATE_ENABLED, 0);
        verify(mClientSoftApCallback).onNumClientsChanged(testNumClients);
    }

    private class IntentFilterMatcher implements ArgumentMatcher<IntentFilter> {
        @Override
        public boolean matches(IntentFilter filter) {
            return filter.hasAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        }
    }

    /**
     * Verify that onFailed is called for registered LOHS callers when a WIFI_AP_STATE_CHANGE
     * broadcast is received.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApFailureGeneric() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_DISABLED, SAP_START_FAILURE_GENERAL,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_FAILED, message.what);
        assertEquals(ERROR_GENERIC, message.arg1);
    }

    /**
     * Verify that onFailed is called for registered LOHS callers when a WIFI_AP_STATE_CHANGE
     * broadcast is received with the SAP_START_FAILURE_NO_CHANNEL error.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApFailureNoChannel() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_DISABLED, SAP_START_FAILURE_NO_CHANNEL,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_FAILED, message.what);
        assertEquals(ERROR_NO_CHANNEL, message.arg1);
    }

    /**
     * Verify that onStopped is called for registered LOHS callers when a WIFI_AP_STATE_CHANGE
     * broadcast is received with WIFI_AP_STATE_DISABLING and LOHS was active.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApDisabling() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STARTED, message.what);
        reset(mHandler);

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STOPPED, message.what);
    }


    /**
     * Verify that onStopped is called for registered LOHS callers when a WIFI_AP_STATE_CHANGE
     * broadcast is received with WIFI_AP_STATE_DISABLED and LOHS was enabled.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnSoftApDisabled() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STARTED, message.what);
        reset(mHandler);

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STOPPED, message.what);
    }

    /**
     * Verify that no callbacks are called for registered LOHS callers when a WIFI_AP_STATE_CHANGE
     * broadcast is received and the softap started.
     */
    @Test
    public void testRegisteredCallbacksNotTriggeredOnSoftApStart() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_ENABLED, WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, WIFI_IFACE_NAME,
                IFACE_IP_MODE_LOCAL_ONLY);

        mLooper.dispatchAll();
        verify(mHandler, never()).handleMessage(any(Message.class));
    }

    /**
     * Verify that onStopped is called only once for registered LOHS callers when
     * WIFI_AP_STATE_CHANGE broadcasts are received with WIFI_AP_STATE_DISABLING and
     * WIFI_AP_STATE_DISABLED when LOHS was enabled.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnlyOnceWhenSoftApDisabling() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STARTED, message.what);
        reset(mHandler);

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STOPPED, message.what);
    }

    /**
     * Verify that onFailed is called only once for registered LOHS callers when
     * WIFI_AP_STATE_CHANGE broadcasts are received with WIFI_AP_STATE_FAILED twice.
     */
    @Test
    public void testRegisteredCallbacksTriggeredOnlyOnceWhenSoftApFailsTwice() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        registerLOHSRequestFull();

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_FAILED, message.what);
        assertEquals(ERROR_GENERIC, message.arg1);
    }

    /**
     * Verify that onFailed is called for all registered LOHS callers when
     * WIFI_AP_STATE_CHANGE broadcasts are received with WIFI_AP_STATE_FAILED.
     */
    @Test
    public void testAllRegisteredCallbacksTriggeredWhenSoftApFails() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        // make an additional request for this test
        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        registerLOHSRequestFull();

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_FAILED, WIFI_AP_STATE_FAILED, ERROR_GENERIC,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        verify(mRequestInfo).sendHotspotFailedMessage(ERROR_GENERIC);
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_FAILED, message.what);
        assertEquals(ERROR_GENERIC, message.arg1);
    }

    /**
     * Verify that onStopped is called for all registered LOHS callers when
     * WIFI_AP_STATE_CHANGE broadcasts are received with WIFI_AP_STATE_DISABLED when LOHS was
     * active.
     */
    @Test
    public void testAllRegisteredCallbacksTriggeredWhenSoftApStops() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mRequestInfo).sendHotspotStartedMessage(any());
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STARTED, message.what);
        reset(mHandler);

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        verify(mRequestInfo).sendHotspotStoppedMessage();
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STOPPED, message.what);
    }

    /**
     * Verify that onFailed is called for all registered LOHS callers when
     * WIFI_AP_STATE_CHANGE broadcasts are received with WIFI_AP_STATE_DISABLED when LOHS was
     * not active.
     */
    @Test
    public void testAllRegisteredCallbacksTriggeredWhenSoftApStopsLOHSNotActive() throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);
        mWifiServiceImpl.registerLOHSForTest(TEST_PID2, mRequestInfo2);

        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);

        verify(mRequestInfo).sendHotspotFailedMessage(ERROR_GENERIC);
        verify(mRequestInfo2).sendHotspotFailedMessage(ERROR_GENERIC);
    }

    /**
     * Verify that if we do not have registered LOHS requestors and we receive an update that LOHS
     * is up and ready for use, we tell WifiController to tear it down.  This can happen if softap
     * mode fails to come up properly and we get an onFailed message for a tethering call and we
     * had registered callers for LOHS.
     */
    @Test
    public void testLOHSReadyWithoutRegisteredRequestsStopsSoftApMode() {
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify that all registered LOHS requestors are notified via a HOTSPOT_STARTED message that
     * the hotspot is up and ready to use.
     */
    @Test
    public void testRegisteredLocalOnlyHotspotRequestorsGetOnStartedCallbackWhenReady()
            throws Exception {
        registerLOHSRequestFull();

        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mRequestInfo).sendHotspotStartedMessage(any(WifiConfiguration.class));

        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STARTED, message.what);
        assertNotNull((WifiConfiguration) message.obj);
    }

    /**
     * Verify that if a LOHS is already active, a new call to register a request will trigger the
     * onStarted callback.
     */
    @Test
    public void testRegisterLocalOnlyHotspotRequestAfterAlreadyStartedGetsOnStartedCallback()
            throws Exception {
        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        registerLOHSRequestFull();

        mLooper.dispatchAll();

        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_STARTED, message.what);
        // since the first request was registered out of band, the config will be null
        assertNull((WifiConfiguration) message.obj);
    }

    /**
     * Verify that if a LOHS request is active and we receive an update with an ip mode
     * configuration error, callers are notified via the onFailed callback with the generic
     * error and are unregistered.
     */
    @Test
    public void testCallOnFailedLocalOnlyHotspotRequestWhenIpConfigFails() throws Exception {
        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_CONFIGURATION_ERROR);
        mLooper.dispatchAll();

        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_FAILED, message.what);
        assertEquals(ERROR_GENERIC, message.arg1);

        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));

        // sendMessage should only happen once since the requestor should be unregistered
        reset(mHandler);

        // send HOTSPOT_FAILED message should only happen once since the requestor should be
        // unregistered
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_CONFIGURATION_ERROR);
        mLooper.dispatchAll();
        verify(mHandler, never()).handleMessage(any(Message.class));
    }

    /**
     * Verify that softap mode is stopped for tethering if we receive an update with an ip mode
     * configuration error.
     */
    @Test
    public void testStopSoftApWhenIpConfigFails() throws Exception {
        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_CONFIGURATION_ERROR);
        mLooper.dispatchAll();

        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify that if a LOHS request is active and tethering starts, callers are notified on the
     * incompatible mode and are unregistered.
     */
    @Test
    public void testCallOnFailedLocalOnlyHotspotRequestWhenTetheringStarts() throws Exception {
        registerLOHSRequestFull();

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();

        verify(mHandler).handleMessage(mMessageCaptor.capture());
        Message message = mMessageCaptor.getValue();
        assertEquals(HOTSPOT_FAILED, message.what);
        assertEquals(ERROR_INCOMPATIBLE_MODE, message.arg1);

        // sendMessage should only happen once since the requestor should be unregistered
        reset(mHandler);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_TETHERED);
        mLooper.dispatchAll();
        verify(mHandler, never()).handleMessage(any(Message.class));
    }

    /**
     * Verify that if LOHS is disabled, a new call to register a request will not trigger the
     * onStopped callback.
     */
    @Test
    public void testRegisterLocalOnlyHotspotRequestWhenStoppedDoesNotGetOnStoppedCallback()
            throws Exception {
        registerLOHSRequestFull();
        mLooper.dispatchAll();

        verify(mHandler, never()).handleMessage(any(Message.class));
    }

    /**
     * Verify that if a LOHS was active and then stopped, a new call to register a request will
     * not trigger the onStarted callback.
     */
    @Test
    public void testRegisterLocalOnlyHotspotRequestAfterStoppedNoOnStartedCallback()
            throws Exception {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IntentFilterMatcher()));

        // register a request so we don't drop the LOHS interface ip update
        mWifiServiceImpl.registerLOHSForTest(TEST_PID, mRequestInfo);

        mWifiServiceImpl.updateInterfaceIpState(WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();

        registerLOHSRequestFull();
        mLooper.dispatchAll();

        verify(mHandler).handleMessage(mMessageCaptor.capture());
        assertEquals(HOTSPOT_STARTED, mMessageCaptor.getValue().what);

        reset(mHandler);

        // now stop the hotspot
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiverCaptor.getValue(), mContext,
                WIFI_AP_STATE_DISABLED, WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR,
                WIFI_IFACE_NAME, IFACE_IP_MODE_LOCAL_ONLY);
        mLooper.dispatchAll();
        verify(mHandler).handleMessage(mMessageCaptor.capture());
        assertEquals(HOTSPOT_STOPPED, mMessageCaptor.getValue().what);

        reset(mHandler);

        // now register a new caller - they should not get the onStarted callback
        Messenger messenger2 = new Messenger(mHandler);
        IBinder binder2 = mock(IBinder.class);

        int result = mWifiServiceImpl.startLocalOnlyHotspot(messenger2, binder2, TEST_PACKAGE_NAME);
        assertEquals(LocalOnlyHotspotCallback.REQUEST_REGISTERED, result);
        mLooper.dispatchAll();

        verify(mHandler, never()).handleMessage(any(Message.class));
    }

    /**
     * Verify that a call to startWatchLocalOnlyHotspot is only allowed from callers with the
     * signature only NETWORK_SETTINGS permission.
     *
     * This test is expecting the permission check to enforce the permission and throw a
     * SecurityException for callers without the permission.  This exception should be bubbled up to
     * the caller of startLocalOnlyHotspot.
     */
    @Test(expected = SecurityException.class)
    public void testStartWatchLocalOnlyHotspotNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        mWifiServiceImpl.startWatchLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Verify that the call to startWatchLocalOnlyHotspot throws the UnsupportedOperationException
     * when called until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStartWatchLocalOnlyHotspotNotSupported() {
        mWifiServiceImpl.startWatchLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Verify that a call to stopWatchLocalOnlyHotspot is only allowed from callers with the
     * signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testStopWatchLocalOnlyHotspotNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        mWifiServiceImpl.stopWatchLocalOnlyHotspot();
    }

    /**
     * Verify that the call to stopWatchLocalOnlyHotspot throws the UnsupportedOperationException
     * until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStopWatchLocalOnlyHotspotNotSupported() {
        mWifiServiceImpl.stopWatchLocalOnlyHotspot();
    }

    /**
     * Verify that the call to addOrUpdateNetwork for installing Passpoint profile is redirected
     * to the Passpoint specific API addOrUpdatePasspointConfiguration.
     */
    @Test
    public void testAddPasspointProfileViaAddNetwork() throws Exception {
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
        WifiConfiguration config = WifiConfigurationTestUtil.createPasspointNetwork();
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);

        PackageManager pm = mock(PackageManager.class);
        when(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)).thenReturn(true);
        when(mContext.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(any(), anyInt())).thenReturn(mApplicationInfo);

        when(mClientModeImpl.syncAddOrUpdatePasspointConfig(any(),
                any(PasspointConfiguration.class), anyInt())).thenReturn(true);
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeImpl).syncAddOrUpdatePasspointConfig(any(),
                any(PasspointConfiguration.class), anyInt());
        reset(mClientModeImpl);

        when(mClientModeImpl.syncAddOrUpdatePasspointConfig(any(),
                any(PasspointConfiguration.class), anyInt())).thenReturn(false);
        assertEquals(-1, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));
        verify(mClientModeImpl).syncAddOrUpdatePasspointConfig(any(),
                any(PasspointConfiguration.class), anyInt());
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller has the right permissions.
     */
    @Test
    public void testStartSubscriptionProvisioningWithPermission() throws Exception {
        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, mProvisioningCallback);
        verify(mClientModeImpl).syncStartSubscriptionProvisioning(anyInt(),
                eq(mOsuProvider), eq(mProvisioningCallback), any());
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is not directed to the Passpoint
     * specific API startSubscriptionProvisioning when the feature is not supported.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStartSubscriptionProvisioniningPasspointUnsupported() throws Exception {
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)).thenReturn(false);
        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, mProvisioningCallback);
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is not redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller provides invalid arguments
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStartSubscriptionProvisioningWithInvalidProvider() throws Exception {
        mWifiServiceImpl.startSubscriptionProvisioning(null, mProvisioningCallback);
    }


    /**
     * Verify that the call to startSubscriptionProvisioning is not redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller provides invalid callback
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStartSubscriptionProvisioningWithInvalidCallback() throws Exception {
        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, null);
    }

    /**
     * Verify that the call to startSubscriptionProvisioning is not redirected to the Passpoint
     * specific API startSubscriptionProvisioning when the caller doesn't have NETWORK_SETTINGS
     * permissions.
     */
    @Test(expected = SecurityException.class)
    public void testStartSubscriptionProvisioningWithoutPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.startSubscriptionProvisioning(mOsuProvider, mProvisioningCallback);
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#restoreBackupData(byte[])} is only allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRestoreBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.restoreBackupData(null);
        verify(mWifiBackupRestore, never()).retrieveConfigurationsFromBackupData(any(byte[].class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#restoreSupplicantBackupData(byte[], byte[])} is
     * only allowed from callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRestoreSupplicantBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.restoreSupplicantBackupData(null, null);
        verify(mWifiBackupRestore, never()).retrieveConfigurationsFromSupplicantBackupData(
                any(byte[].class), any(byte[].class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#retrieveBackupData()} is only allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testRetrieveBackupDataNotApprovedCaller() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        mWifiServiceImpl.retrieveBackupData();
        verify(mWifiBackupRestore, never()).retrieveBackupDataFromConfigurations(any(List.class));
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#enableVerboseLogging(int)} is allowed from
     * callers with the signature only NETWORK_SETTINGS permission.
     */
    @Test
    public void testEnableVerboseLoggingWithNetworkSettingsPermission() {
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        // Vebose logging is enabled first in the constructor for WifiServiceImpl, so reset
        // before invocation.
        reset(mClientModeImpl);
        mWifiServiceImpl.enableVerboseLogging(1);
        verify(mClientModeImpl).enableVerboseLogging(anyInt());
    }

    /**
     * Verify that a call to {@link WifiServiceImpl#enableVerboseLogging(int)} is not allowed from
     * callers without the signature only NETWORK_SETTINGS permission.
     */
    @Test(expected = SecurityException.class)
    public void testEnableVerboseLoggingWithNoNetworkSettingsPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        // Vebose logging is enabled first in the constructor for WifiServiceImpl, so reset
        // before invocation.
        reset(mClientModeImpl);
        mWifiServiceImpl.enableVerboseLogging(1);
        verify(mClientModeImpl, never()).enableVerboseLogging(anyInt());
    }

    /**
     * Helper to test handling of async messages by wifi service when the message comes from an
     * app without {@link android.Manifest.permission#CHANGE_WIFI_STATE} permission.
     */
    private void verifyAsyncChannelMessageHandlingWithoutChangePermisson(
            int requestMsgWhat, int expectedReplyMsgwhat) throws RemoteException {
        WifiAsyncChannelTester tester = verifyAsyncChannelHalfConnected();

        int uidWithoutPermission = 5;
        when(mWifiPermissionsUtil.checkChangePermission(eq(uidWithoutPermission)))
                .thenReturn(false);

        Message request = Message.obtain();
        request.what = requestMsgWhat;
        request.sendingUid = uidWithoutPermission;

        mLooper.startAutoDispatch();
        Message reply = tester.sendMessageSynchronously(request);
        mLooper.stopAutoDispatch();

        verify(mClientModeImpl, never()).sendMessage(any(Message.class));
        assertEquals(expectedReplyMsgwhat, reply.what);
        assertEquals(WifiManager.NOT_AUTHORIZED, reply.arg1);
    }

    /**
     * Helper to test handling of async messages by wifi service when the message comes from an
     * app without one of the privileged permissions.
     */
    private void verifyAsyncChannelMessageHandlingWithoutPrivilegedPermissons(
            int requestMsgWhat, int expectedReplyMsgwhat) throws RemoteException {
        WifiAsyncChannelTester tester = verifyAsyncChannelHalfConnected();

        int uidWithoutPermission = 5;
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETUP_WIZARD),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_STACK),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);

        Message request = Message.obtain();
        request.what = requestMsgWhat;
        request.sendingUid = uidWithoutPermission;

        mLooper.startAutoDispatch();
        Message reply = tester.sendMessageSynchronously(request);
        mLooper.stopAutoDispatch();

        verify(mClientModeImpl, never()).sendMessage(any(Message.class));
        assertEquals(expectedReplyMsgwhat, reply.what);
        assertEquals(WifiManager.NOT_AUTHORIZED, reply.arg1);
    }

    /**
     * Verify that the CONNECT_NETWORK message received from an app without
     * one of the privileged permission is rejected with the correct error code.
     */
    @Test
    public void testConnectNetworkWithoutPrivilegedPermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithoutPrivilegedPermissons(
                WifiManager.CONNECT_NETWORK, WifiManager.CONNECT_NETWORK_FAILED);
    }

    /**
     * Verify that the FORGET_NETWORK message received from an app without
     * one of the privileged permission is rejected with the correct error code.
     */
    @Test
    public void testForgetNetworkWithoutPrivilegedPermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithoutPrivilegedPermissons(
                WifiManager.SAVE_NETWORK, WifiManager.SAVE_NETWORK_FAILED);
    }

    /**
     * Verify that the DISABLE_NETWORK message received from an app without
     * one of the privileged permission is rejected with the correct error code.
     */
    @Test
    public void testDisableNetworkWithoutPrivilegedPermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithoutPrivilegedPermissons(
                WifiManager.DISABLE_NETWORK, WifiManager.DISABLE_NETWORK_FAILED);
    }

    /**
     * Verify that the RSSI_PKTCNT_FETCH message received from an app without
     * {@link android.Manifest.permission#CHANGE_WIFI_STATE} permission is rejected with the correct
     * error code.
     */
    @Test
    public void testRssiPktcntFetchWithoutChangePermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithoutChangePermisson(
                WifiManager.RSSI_PKTCNT_FETCH, WifiManager.RSSI_PKTCNT_FETCH_FAILED);
    }

    /**
     * Helper to test handling of async messages by wifi service when the message comes from an
     * app with {@link android.Manifest.permission#CHANGE_WIFI_STATE} permission.
     */
    private void verifyAsyncChannelMessageHandlingWithChangePermisson(
            int requestMsgWhat, Object requestMsgObj) throws RemoteException {
        WifiAsyncChannelTester tester = verifyAsyncChannelHalfConnected();

        when(mWifiPermissionsUtil.checkChangePermission(anyInt())).thenReturn(true);

        Message request = Message.obtain();
        request.what = requestMsgWhat;
        request.obj = requestMsgObj;

        tester.sendMessage(request);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientModeImpl).sendMessage(messageArgumentCaptor.capture());
        assertEquals(requestMsgWhat, messageArgumentCaptor.getValue().what);
    }

    /**
     * Helper to test handling of async messages by wifi service when the message comes from an
     * app with one of the  privileged permissions.
     */
    private void verifyAsyncChannelMessageHandlingWithPrivilegedPermissions(
            int requestMsgWhat, Object requestMsgObj) throws RemoteException {
        WifiAsyncChannelTester tester = verifyAsyncChannelHalfConnected();

        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);

        Message request = Message.obtain();
        request.what = requestMsgWhat;
        request.obj = requestMsgObj;

        tester.sendMessage(request);
        mLooper.dispatchAll();

        ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mClientModeImpl).sendMessage(messageArgumentCaptor.capture());
        assertEquals(requestMsgWhat, messageArgumentCaptor.getValue().what);
    }

    /**
     * Verify that the CONNECT_NETWORK message received from an app with
     * one of the privileged permission is forwarded to ClientModeImpl.
     */
    @Test
    public void testConnectNetworkWithPrivilegedPermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithPrivilegedPermissions(
                WifiManager.CONNECT_NETWORK, new WifiConfiguration());
    }

    /**
     * Verify that the SAVE_NETWORK message received from an app with
     * one of the privileged permission is forwarded to ClientModeImpl.
     */
    @Test
    public void testSaveNetworkWithPrivilegedPermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithPrivilegedPermissions(
                WifiManager.SAVE_NETWORK, new WifiConfiguration());
    }

    /**
     * Verify that the DISABLE_NETWORK message received from an app with
     * one of the privileged permission is forwarded to ClientModeImpl.
     */
    @Test
    public void testDisableNetworkWithPrivilegedPermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithPrivilegedPermissions(
                WifiManager.DISABLE_NETWORK, new Object());
    }

    /**
     * Verify that the RSSI_PKTCNT_FETCH message received from an app with
     * one of the privileged permission is forwarded to ClientModeImpl.
     */
    @Test
    public void testRssiPktcntFetchWithChangePermission() throws Exception {
        verifyAsyncChannelMessageHandlingWithChangePermisson(
                WifiManager.RSSI_PKTCNT_FETCH, new Object());
    }

    /**
     * Verify that setCountryCode() calls WifiCountryCode object on succeess.
     */
    @Test
    public void testSetCountryCode() throws Exception {
        mWifiServiceImpl.setCountryCode(TEST_COUNTRY_CODE);
        verify(mWifiCountryCode).setCountryCode(TEST_COUNTRY_CODE);
    }

    /**
     * Verify that setCountryCode() fails and doesn't call WifiCountryCode object
     * if the caller doesn't have CONNECTIVITY_INTERNAL permission.
     */
    @Test(expected = SecurityException.class)
    public void testSetCountryCodeFailsWithoutConnectivityInternalPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.CONNECTIVITY_INTERNAL),
                        eq("ConnectivityService"));
        mWifiServiceImpl.setCountryCode(TEST_COUNTRY_CODE);
        verify(mWifiCountryCode, never()).setCountryCode(TEST_COUNTRY_CODE);
    }

    /**
     * Set the wifi state machine mock to return a handler created on test thread.
     */
    private void setupClientModeImplHandlerForRunWithScissors() {
        HandlerThread handlerThread = createAndStartHandlerThreadForRunWithScissors();
        mHandlerSpyForCmiRunWithScissors = spy(handlerThread.getThreadHandler());
        when(mWifiInjector.getClientModeImplHandler())
                .thenReturn(mHandlerSpyForCmiRunWithScissors);
    }

    private HandlerThread createAndStartHandlerThreadForRunWithScissors() {
        HandlerThread handlerThread = new HandlerThread("ServiceHandlerThreadForTest");
        handlerThread.start();
        return handlerThread;
    }

    /**
     * Tests the scenario when a scan request arrives while the device is idle. In this case
     * the scan is done when idle mode ends.
     */
    @Test
    public void testHandleDelayedScanAfterIdleMode() throws Exception {
        setupClientModeImplHandlerForRunWithScissors();
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat(new IdleModeIntentMatcher()));

        // Tell the wifi service that the device became idle.
        when(mPowerManager.isDeviceIdleMode()).thenReturn(true);
        TestUtil.sendIdleModeChanged(mBroadcastReceiverCaptor.getValue(), mContext);

        // Send a scan request while the device is idle.
        assertFalse(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME));
        // No scans must be made yet as the device is idle.
        verify(mScanRequestProxy, never()).startScan(Process.myUid(), SCAN_PACKAGE_NAME);

        // Tell the wifi service that idle mode ended.
        when(mPowerManager.isDeviceIdleMode()).thenReturn(false);
        TestUtil.sendIdleModeChanged(mBroadcastReceiverCaptor.getValue(), mContext);

        // Must scan now.
        verify(mScanRequestProxy).startScan(Process.myUid(), TEST_PACKAGE_NAME);
        // The app ops check is executed with this package's identity (not the identity of the
        // original remote caller who requested the scan while idle).
        verify(mAppOpsManager).noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);

        // Send another scan request. The device is not idle anymore, so it must be executed
        // immediately.
        assertTrue(mWifiServiceImpl.startScan(SCAN_PACKAGE_NAME));
        verify(mScanRequestProxy).startScan(Process.myUid(), SCAN_PACKAGE_NAME);
    }

    /**
     * Verify that if the caller has NETWORK_SETTINGS permission, then it doesn't need
     * CHANGE_WIFI_STATE permission.
     * @throws Exception
     */
    @Test
    public void testDisconnectWithNetworkSettingsPerm() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "WifiService");
        doThrow(new SecurityException()).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        assertTrue(mWifiServiceImpl.disconnect(TEST_PACKAGE_NAME));
        verify(mClientModeImpl).disconnectCommand();
    }

    /**
     * Verify that if the caller doesn't have NETWORK_SETTINGS permission, it could still
     * get access with the CHANGE_WIFI_STATE permission.
     * @throws Exception
     */
    @Test
    public void testDisconnectWithChangeWifiStatePerm() throws Exception {
        assertFalse(mWifiServiceImpl.disconnect(TEST_PACKAGE_NAME));
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeImpl, never()).disconnectCommand();
    }

    /**
     * Verify that the operation fails if the caller has neither NETWORK_SETTINGS or
     * CHANGE_WIFI_STATE permissions.
     * @throws Exception
     */
    @Test
    public void testDisconnectRejected() throws Exception {
        doThrow(new SecurityException()).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        try {
            mWifiServiceImpl.disconnect(TEST_PACKAGE_NAME);
            fail();
        } catch (SecurityException e) {

        }
        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeImpl, never()).disconnectCommand();
    }

    @Test
    public void testPackageRemovedBroadcastHandling() {
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)));

        int uid = TEST_UID;
        String packageName = TEST_PACKAGE_NAME;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.setData(Uri.fromParts("package", packageName, ""));
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mClientModeImpl).removeAppConfigs(packageName, uid);

        mLooper.dispatchAll();
        verify(mScanRequestProxy).clearScanRequestTimestampsForApp(packageName, uid);
    }

    @Test
    public void testPackageRemovedBroadcastHandlingWithNoUid() {
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)));

        String packageName = TEST_PACKAGE_NAME;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.setData(Uri.fromParts("package", packageName, ""));
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mClientModeImpl, never()).removeAppConfigs(anyString(), anyInt());

        mLooper.dispatchAll();
        verify(mScanRequestProxy, never()).clearScanRequestTimestampsForApp(anyString(), anyInt());
    }

    @Test
    public void testPackageRemovedBroadcastHandlingWithNoPackageName() {
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)));

        int uid = TEST_UID;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, uid);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mClientModeImpl, never()).removeAppConfigs(anyString(), anyInt());

        mLooper.dispatchAll();
        verify(mScanRequestProxy, never()).clearScanRequestTimestampsForApp(anyString(), anyInt());
    }

    @Test
    public void testUserRemovedBroadcastHandling() {
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_USER_REMOVED)));

        int userHandle = TEST_USER_HANDLE;
        // Send the broadcast
        Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mClientModeImpl).removeUserConfigs(userHandle);
    }

    @Test
    public void testUserRemovedBroadcastHandlingWithWrongIntentAction() {
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(Intent.ACTION_USER_REMOVED)));

        int userHandle = TEST_USER_HANDLE;
        // Send the broadcast with wrong action
        Intent intent = new Intent(Intent.ACTION_USER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);

        verify(mClientModeImpl, never()).removeUserConfigs(userHandle);
    }

    /**
     * Test for needs5GHzToAnyApBandConversion returns true.  Requires the NETWORK_SETTINGS
     * permission.
     */
    @Test
    public void testNeeds5GHzToAnyApBandConversionReturnedTrue() {
        when(mResources.getBoolean(
                eq(com.android.internal.R.bool.config_wifi_convert_apband_5ghz_to_any)))
                .thenReturn(true);
        assertTrue(mWifiServiceImpl.needs5GHzToAnyApBandConversion());

        verify(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS), eq("WifiService"));
    }

    /**
     * Test for needs5GHzToAnyApBandConversion returns false.  Requires the NETWORK_SETTINGS
     * permission.
     */
    @Test
    public void testNeeds5GHzToAnyApBandConversionReturnedFalse() {
        when(mResources.getBoolean(
                eq(com.android.internal.R.bool.config_wifi_convert_apband_5ghz_to_any)))
                .thenReturn(false);

        assertFalse(mWifiServiceImpl.needs5GHzToAnyApBandConversion());

        verify(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS), eq("WifiService"));
    }

    /**
     * The API impl for needs5GHzToAnyApBandConversion requires the NETWORK_SETTINGS permission,
     * verify an exception is thrown without holding the permission.
     */
    @Test
    public void testNeeds5GHzToAnyApBandConversionThrowsWithoutProperPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));

        try {
            mWifiServiceImpl.needs5GHzToAnyApBandConversion();
            // should have thrown an exception - fail test
            fail();
        } catch (SecurityException e) {
            // expected
        }
    }


    private class IdleModeIntentMatcher implements ArgumentMatcher<IntentFilter> {
        @Override
        public boolean matches(IntentFilter filter) {
            return filter.hasAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        }
    }

    /**
     * Verifies that enforceChangePermission(String package) is called and the caller doesn't
     * have NETWORK_SETTINGS permission
     */
    private void verifyCheckChangePermission(String callingPackageName) {
        verify(mContext, atLeastOnce())
                .checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        anyInt(), anyInt());
        verify(mContext, atLeastOnce()).enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "WifiService");
        verify(mAppOpsManager, atLeastOnce()).noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), callingPackageName);
    }

    private WifiConfiguration createValidSoftApConfiguration() {
        WifiConfiguration apConfig = new WifiConfiguration();
        apConfig.SSID = "TestAp";
        apConfig.preSharedKey = "thisIsABadPassword";
        apConfig.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        apConfig.apBand = WifiConfiguration.AP_BAND_2GHZ;

        return apConfig;
    }

    /**
     * Verifies that sim state change does not set or reset the country code
     */
    @Test
    public void testSimStateChangeDoesNotResetCountryCode() {
        mWifiServiceImpl.checkAndStartWifi();
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                (IntentFilter) argThat((IntentFilter filter) ->
                        filter.hasAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED)));

        int userHandle = TEST_USER_HANDLE;
        // Send the broadcast
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, intent);
        verifyNoMoreInteractions(mWifiCountryCode);
    }

    /**
     * Verify calls to notify users of a softap config change check the NETWORK_SETTINGS permission.
     */
    @Test
    public void testNotifyUserOfApBandConversionChecksNetworkSettingsPermission() {
        mWifiServiceImpl.notifyUserOfApBandConversion(TEST_PACKAGE_NAME);
        verify(mContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.NETWORK_SETTINGS),
                eq("WifiService"));
        verify(mWifiApConfigStore).notifyUserOfApBandConversion(eq(TEST_PACKAGE_NAME));
    }

    /**
     * Verify calls to notify users do not trigger a notification when NETWORK_SETTINGS is not held
     * by the caller.
     */
    @Test
    public void testNotifyUserOfApBandConversionThrowsExceptionWithoutNetworkSettingsPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                                                eq("WifiService"));
        try {
            mWifiServiceImpl.notifyUserOfApBandConversion(TEST_PACKAGE_NAME);
            fail("Expected Security exception");
        } catch (SecurityException e) { }
    }

    /**
     * Verify that a call to registerTrafficStateCallback throws a SecurityException if the caller
     * does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void registerTrafficStateCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.registerTrafficStateCallback(mAppBinder, mTrafficStateCallback,
                    TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to registerTrafficStateCallback throws an IllegalArgumentException if the
     * parameters are not provided.
     */
    @Test
    public void registerTrafficStateCallbackThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            mWifiServiceImpl.registerTrafficStateCallback(
                    mAppBinder, null, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to unregisterTrafficStateCallback throws a SecurityException if the caller
     * does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void unregisterTrafficStateCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.unregisterTrafficStateCallback(TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that registerTrafficStateCallback adds callback to {@link WifiTrafficPoller}.
     */
    @Test
    public void registerTrafficStateCallbackAndVerify() throws Exception {
        mWifiServiceImpl.registerTrafficStateCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mWifiTrafficPoller).addCallback(
                mAppBinder, mTrafficStateCallback, TEST_TRAFFIC_STATE_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify that unregisterTrafficStateCallback removes callback from {@link WifiTrafficPoller}.
     */
    @Test
    public void unregisterTrafficStateCallbackAndVerify() throws Exception {
        mWifiServiceImpl.unregisterTrafficStateCallback(0);
        mLooper.dispatchAll();
        verify(mWifiTrafficPoller).removeCallback(0);
    }

    /**
     * Verify that a call to registerNetworkRequestMatchCallback throws a SecurityException if the
     * caller does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void registerNetworkRequestMatchCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.registerNetworkRequestMatchCallback(mAppBinder,
                    mNetworkRequestMatchCallback,
                    TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that a call to registerNetworkRequestMatchCallback throws an IllegalArgumentException
     * if the parameters are not provided.
     */
    @Test
    public void
            registerNetworkRequestMatchCallbackThrowsIllegalArgumentExceptionOnInvalidArguments() {
        try {
            mWifiServiceImpl.registerNetworkRequestMatchCallback(
                    mAppBinder, null, TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Verify that a call to unregisterNetworkRequestMatchCallback throws a SecurityException if the
     * caller does not have NETWORK_SETTINGS permission.
     */
    @Test
    public void unregisterNetworkRequestMatchCallbackThrowsSecurityExceptionOnMissingPermissions() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                        eq("WifiService"));
        try {
            mWifiServiceImpl.unregisterNetworkRequestMatchCallback(
                    TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
            fail("expected SecurityException");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Verify that registerNetworkRequestMatchCallback adds callback to
     * {@link ClientModeImpl}.
     */
    @Test
    public void registerNetworkRequestMatchCallbackAndVerify() throws Exception {
        mWifiServiceImpl.registerNetworkRequestMatchCallback(
                mAppBinder, mNetworkRequestMatchCallback,
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mClientModeImpl).addNetworkRequestMatchCallback(
                mAppBinder, mNetworkRequestMatchCallback,
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify that unregisterNetworkRequestMatchCallback removes callback from
     * {@link ClientModeImpl}.
     */
    @Test
    public void unregisterNetworkRequestMatchCallbackAndVerify() throws Exception {
        mWifiServiceImpl.unregisterNetworkRequestMatchCallback(
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
        mLooper.dispatchAll();
        verify(mClientModeImpl).removeNetworkRequestMatchCallback(
                TEST_NETWORK_REQUEST_MATCH_CALLBACK_IDENTIFIER);
    }

    /**
     * Verify that Wifi configuration and Passpoint configuration are removed in factoryReset.
     */
    @Test
    public void testFactoryReset() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        final String fqdn = "example.com";
        WifiConfiguration network = WifiConfigurationTestUtil.createOpenNetwork();
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        config.setHomeSp(homeSp);

        mWifiServiceImpl.mClientModeImplChannel = mAsyncChannel;
        when(mClientModeImpl.syncGetConfiguredNetworks(anyInt(), any()))
                .thenReturn(Arrays.asList(network));
        when(mClientModeImpl.syncGetPasspointConfigs(any())).thenReturn(Arrays.asList(config));

        mWifiServiceImpl.factoryReset(TEST_PACKAGE_NAME);

        verify(mClientModeImpl).syncRemoveNetwork(mAsyncChannel, network.networkId);
        verify(mClientModeImpl).syncRemovePasspointConfig(mAsyncChannel, fqdn);
    }

    /**
     * Verify that Passpoint configuration is not removed in factoryReset if Passpoint feature
     * is not supported.
     */
    @Test
    public void testFactoryResetWithoutPasspointSupport() throws Exception {
        mWifiServiceImpl.mClientModeImplChannel = mAsyncChannel;
        when(mPackageManager.hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)).thenReturn(false);

        mWifiServiceImpl.factoryReset(TEST_PACKAGE_NAME);

        verify(mClientModeImpl).syncGetConfiguredNetworks(anyInt(), any());
        verify(mClientModeImpl, never()).syncGetPasspointConfigs(any());
        verify(mClientModeImpl, never()).syncRemovePasspointConfig(any(), anyString());
    }

    /**
     * Verify that a call to factoryReset throws a SecurityException if the caller does not have
     * the CONNECTIVITY_INTERNAL permission.
     */
    @Test
    public void testFactoryResetWithoutConnectivityInternalPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(Manifest.permission.CONNECTIVITY_INTERNAL),
                        eq("ConnectivityService"));
        mWifiServiceImpl.mClientModeImplChannel = mAsyncChannel;

        try {
            mWifiServiceImpl.factoryReset(TEST_PACKAGE_NAME);
            fail();
        } catch (SecurityException e) {
        }
        verify(mClientModeImpl, never()).syncGetConfiguredNetworks(anyInt(), any());
        verify(mClientModeImpl, never()).syncGetPasspointConfigs(any());
    }

    /**
     * Verify that add or update networks is not allowed for apps targeting Q SDK.
     */
    @Test
    public void testAddOrUpdateNetworkIsNotAllowedForAppsTargetingQSDK() throws Exception {
        mLooper.dispatchAll();
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        when(mClientModeImpl.syncAddOrUpdateNetwork(any(), any())).thenReturn(0);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        assertEquals(-1, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeImpl, never()).syncAddOrUpdateNetwork(any(), any());
    }

    /**
     * Verify that add or update networks is allowed for apps targeting below Q SDK.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForAppsTargetingBelowQSDK() throws Exception {
        mLooper.dispatchAll();
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mClientModeImpl.syncAddOrUpdateNetwork(any(), any())).thenReturn(0);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeImpl).syncAddOrUpdateNetwork(any(), any());
    }

    /**
     * Verify that add or update networks is allowed for settings app.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForSettingsApp() throws Exception {
        mLooper.dispatchAll();
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mApplicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mClientModeImpl.syncAddOrUpdateNetwork(any(), any())).thenReturn(0);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));

        // Ensure that we don't check for change permission.
        verify(mContext, never()).enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_STATE, "WifiService");
        verify(mAppOpsManager, never()).noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        verify(mClientModeImpl).syncAddOrUpdateNetwork(any(), any());
    }

    /**
     * Verify that add or update networks is allowed for system apps.
     */
    @Test
    public void testAddOrUpdateNetworkIsAllowedForSystemApp() throws Exception {
        mLooper.dispatchAll();
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOpsManager)
                .noteOp(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Process.myUid(), TEST_PACKAGE_NAME);
        mApplicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mClientModeImpl.syncAddOrUpdateNetwork(any(), any())).thenReturn(0);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        assertEquals(0, mWifiServiceImpl.addOrUpdateNetwork(config, TEST_PACKAGE_NAME));

        verifyCheckChangePermission(TEST_PACKAGE_NAME);
        verify(mClientModeImpl).syncAddOrUpdateNetwork(any(), any());
    }

    /**
     * Ensure that we invoke {@link WifiNetworkSuggestionsManager} to add network
     * suggestions.
     */
    @Test
    public void testAddNetworkSuggestions() {
        setupClientModeImplHandlerForRunWithScissors();

        when(mWifiNetworkSuggestionsManager.add(any(), anyString())).thenReturn(true);
        assertTrue(mWifiServiceImpl.addNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));

        when(mWifiNetworkSuggestionsManager.add(any(), anyString())).thenReturn(false);
        assertFalse(mWifiServiceImpl.addNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));

        verify(mWifiNetworkSuggestionsManager, times(2)).add(any(), eq(TEST_PACKAGE_NAME));
    }

    /**
     * Ensure that we invoke {@link WifiNetworkSuggestionsManager} to remove network
     * suggestions.
     */
    @Test
    public void testRemoveNetworkSuggestions() {
        setupClientModeImplHandlerForRunWithScissors();

        when(mWifiNetworkSuggestionsManager.remove(any(), anyString())).thenReturn(true);
        assertTrue(mWifiServiceImpl.removeNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));

        when(mWifiNetworkSuggestionsManager.remove(any(), anyString())).thenReturn(false);
        assertFalse(mWifiServiceImpl.removeNetworkSuggestions(mock(List.class), TEST_PACKAGE_NAME));

        verify(mWifiNetworkSuggestionsManager, times(2)).remove(any(), eq(TEST_PACKAGE_NAME));
    }

    /**
     * Verify that if the caller has NETWORK_SETTINGS permission, then it can invoke
     * {@link WifiManager#disableEphemeralNetwork(String)}.
     */
    @Test
    public void testDisableEphemeralNetworkWithNetworkSettingsPerm() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED);
        mWifiServiceImpl.disableEphemeralNetwork(new String(), TEST_PACKAGE_NAME);
        verify(mClientModeImpl).disableEphemeralNetwork(anyString());
    }

    /**
     * Verify that if the caller does not have NETWORK_SETTINGS permission, then it cannot invoke
     * {@link WifiManager#disableEphemeralNetwork(String)}.
     */
    @Test
    public void testDisableEphemeralNetworkWithoutNetworkSettingsPerm() throws Exception {
        when(mContext.checkPermission(eq(android.Manifest.permission.NETWORK_SETTINGS),
                anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED);
        mWifiServiceImpl.disableEphemeralNetwork(new String(), TEST_PACKAGE_NAME);
        verify(mClientModeImpl, never()).disableEphemeralNetwork(anyString());
    }
}
