package com.dalong.dataguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.dalong.dataguard.core.WifiPolicy;

/** Reads the system's current default route; no SSID, location or active probes are collected. */
final class WifiState {
  private WifiState() {}

  static boolean connected(Context context) {
    try {
      ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
      Network network = cm.getActiveNetwork();
      NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
      return caps != null
          && WifiPolicy.pausesLimits(
              true,
              caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
              caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
              caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
              caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  static ConnectivityManager.NetworkCallback observe(Context context, Runnable changed) {
    ConnectivityManager.NetworkCallback callback =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(Network network) {
            changed.run();
          }

          @Override
          public void onLost(Network network) {
            changed.run();
          }

          @Override
          public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            changed.run();
          }
        };
    context.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
    return callback;
  }
}
