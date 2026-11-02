package org.gc.bz3x.huadb;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

final class TimeSyncController {
    private static final String DESCRIPTOR = "gxa.car.timesync.ITimeSyncService";
    private static final int SET_TIME = 1, SET_GPS_SYNC = 2, GET_GPS_SYNC = 3;
    interface Listener { void onConnectionChanged(); }

    private final Context context;
    private final Listener listener;
    private IBinder binder;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) { binder=service; listener.onConnectionChanged(); }
        @Override public void onServiceDisconnected(ComponentName name) { binder=null; listener.onConnectionChanged(); }
    };

    TimeSyncController(Context context, Listener listener) { this.context=context.getApplicationContext(); this.listener=listener; }
    boolean connect() {
        if (bound) return true;
        Intent i=new Intent(); i.setClassName("com.gxa.car.timesync","com.gxa.car.timesync.TimeSyncService");
        bound=context.bindService(i,connection,Context.BIND_AUTO_CREATE); return bound;
    }
    boolean isConnected() { return binder!=null; }
    boolean isGpsSyncEnabled() throws RemoteException {
        Parcel d=Parcel.obtain(), r=Parcel.obtain(); try { d.writeInterfaceToken(DESCRIPTOR); transact(GET_GPS_SYNC,d,r); return r.readInt()!=0; } finally { r.recycle(); d.recycle(); }
    }
    void setGpsSyncEnabled(boolean enabled) throws RemoteException {
        Parcel d=Parcel.obtain(), r=Parcel.obtain(); try { d.writeInterfaceToken(DESCRIPTOR); d.writeInt(enabled?1:0); transact(SET_GPS_SYNC,d,r); } finally { r.recycle(); d.recycle(); }
    }
    void setTime(long millis,String timezone) throws RemoteException {
        Parcel d=Parcel.obtain(), r=Parcel.obtain(); try { d.writeInterfaceToken(DESCRIPTOR); d.writeLong(millis); d.writeString(timezone==null?"":timezone); transact(SET_TIME,d,r); } finally { r.recycle(); d.recycle(); }
    }
    private void transact(int code,Parcel data,Parcel reply) throws RemoteException {
        if (binder==null) throw new RemoteException("TimeSyncService is not connected");
        if (!binder.transact(code,data,reply,0)) throw new RemoteException("TimeSyncService transact failed: "+code);
        reply.readException();
    }
    void close() { if (bound) try { context.unbindService(connection); } catch (Exception ignored) {} bound=false; binder=null; }
}
