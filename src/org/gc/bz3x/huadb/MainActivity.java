package org.gc.bz3x.huadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {
    private static final String CONSENT_COMPONENT = "org.gc.bz3x.huadb/.ConsentService";
    private final Handler handler = new Handler();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private TextView badge, address, command, note, helper;
    private Button wifiButton;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean wifiPending, resolving, destroyed, probing;
    private long wifiRequestedAt, nextSearch, lastProbe;
    private String endpoint = "", ownService = "";
    private int generation;
    private final int white = Color.rgb(236,241,247), muted = Color.rgb(161,176,195), green = Color.rgb(106,211,191), amber = Color.rgb(255,201,105);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            if (endpoint.isEmpty() && wifiOn() && SystemClock.elapsedRealtime() > nextSearch) {
                nextSearch = SystemClock.elapsedRealtime() + 15000;
                restartDiscovery();
            }
            probeSavedPort();
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,28,36,32);
        root.setBackgroundColor(Color.rgb(17,25,37));
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("Toyota bZ3X · Wi-Fi ADB",30,white));
        badge = text("",19,amber);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(20,14,20,14);
        root.addView(badge,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout card = card(root);
        card.addView(text("Подключение",24,white));
        address = text("",25,green); card.addView(address);
        command = text("",19,white);
        command.setTypeface(Typeface.MONOSPACE);
        command.setPadding(16,18,16,18);
        command.setBackground(bg(Color.rgb(17,25,37)));
        command.setOnClickListener(v -> copyCommand());
        card.addView(command);
        card.addView(text("Нажмите на команду, чтобы скопировать её в буфер ГУ.",15,muted));
        wifiButton = button(card,"",() -> { if (wifiOn()) confirmOff(); else requestWifi(); });
        note = text("",17,amber); root.addView(note);
        helper = text("",16,muted); root.addView(helper);
        button(root,"Перезапустить помощник подтверждения",this::confirmRepairHelper);
        TextView help = text("ГУ и подключаемое устройство должны быть в одной Wi-Fi-сети. После работы выключите Wi-Fi ADB кнопкой выше.",16,muted);
        help.setPadding(0,18,0,0); root.addView(help);
        nsd = (NsdManager)getSystemService(Context.NSD_SERVICE);
    }

    private TextView text(String value,int size,int color) { TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,8,0,8); return v; }
    private GradientDrawable bg(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(18); return d; }
    private LinearLayout card(LinearLayout root) { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24,18,24,22); v.setBackground(bg(Color.rgb(28,39,54))); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,18,0,12); root.addView(v,p); return v; }
    private Button button(LinearLayout parent,String label,Runnable action) { Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(19); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,76); p.setMargins(0,10,0,6); parent.addView(b,p); b.setOnClickListener(v -> action.run()); return b; }
    private boolean wifiOn() { return Settings.Global.getInt(getContentResolver(),"adb_wifi_enabled",0)==1; }

    private void requestWifi() {
        if (ConsentService.live == null) { message("Помощник не подключён. Перезапустите помощник подтверждения и повторите."); return; }
        new AlertDialog.Builder(this).setTitle("Разрешить Wi-Fi ADB?")
                .setMessage("Открыть отладку в текущей Wi-Fi-сети? Помощник подтвердит системный запрос в течение 20 секунд.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Разрешить",(d,w) -> { if (ConsentService.arm()) setWifi(1); else message("Помощник отключился. Перезапустите его и повторите."); }).show();
    }

    private void confirmOff() { new AlertDialog.Builder(this).setTitle("Выключить Wi-Fi ADB?").setMessage("Текущие ADB-подключения по Wi-Fi будут разорваны.").setNegativeButton("Отмена",null).setPositiveButton("Выключить",(d,w) -> setWifi(0)).show(); }

    private void setWifi(int value) {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) { message("Нет WRITE_SECURE_SETTINGS. Выдайте право через tools/device/app-permissions."); return; }
            if (!Settings.Global.putInt(getContentResolver(),"adb_wifi_enabled",value)) throw new IllegalStateException("настройка не сохранена");
            endpoint=""; ownService=""; nextSearch=0; wifiPending=value==1; wifiRequestedAt=SystemClock.elapsedRealtime();
            message(value==1 ? "Ожидаем системное разрешение и запуск ADB…" : "Wi-Fi ADB выключен.");
            refresh();
        } catch (Exception e) { wifiPending=false; message("Не удалось изменить настройку: "+e.getMessage()); }
    }

    private void copyCommand() {
        if (endpoint.isEmpty()) return;
        String value="adb connect "+endpoint;
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Команда ADB",value));
        message("Скопировано: "+value); Toast.makeText(this,"Команда скопирована",Toast.LENGTH_SHORT).show();
    }

    private void message(String value) { note.setText(value); }
    private boolean ourService(String entry) { ComponentName n=ComponentName.unflattenFromString(entry); return n!=null && n.equals(new ComponentName(this,ConsentService.class)); }
    private boolean hasOurService(String setting) { if (setting==null) return false; for (String entry:setting.split(":")) if (ourService(entry)) return true; return false; }
    private String withoutOurService(String setting) { if (setting==null || setting.isEmpty()) return ""; StringBuilder value=new StringBuilder(); for (String entry:setting.split(":")) { if (entry.isEmpty() || ourService(entry)) continue; if (value.length()>0) value.append(':'); value.append(entry); } return value.toString(); }

    private void confirmRepairHelper() {
        new AlertDialog.Builder(this).setTitle("Перезапустить помощник?")
                .setMessage("Переподключить только службу подтверждения Wi-Fi ADB? Остальные службы специальных возможностей сохранятся.")
                .setNegativeButton("Отмена",null).setPositiveButton("Перезапустить",(d,w) -> repairHelper()).show();
    }

    private void repairHelper() {
        try {
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) throw new SecurityException("нет WRITE_SECURE_SETTINGS");
            if (Settings.Secure.getInt(getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)!=1) throw new IllegalStateException("системная служба специальных возможностей выключена");
            String current=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean present=hasOurService(current);
            if (present && !Settings.Secure.putString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,withoutOurService(current))) throw new IllegalStateException("не удалось отключить службу");
            wifiPending=false; message("Перезапускаем помощник…");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    String latest=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    if (!hasOurService(latest)) {
                        String restored=(latest==null || latest.isEmpty()) ? CONSENT_COMPONENT : latest+":"+CONSENT_COMPONENT;
                        if (!Settings.Secure.putString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,restored)) throw new IllegalStateException("не удалось включить службу");
                    }
                    if (!hasOurService(Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))) throw new IllegalStateException("служба отсутствует после включения");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> { if (destroyed) return; message(ConsentService.live!=null ? "Помощник подключён." : "Служба включена в настройках, но ещё не подключилась."); refresh(); },1500);
                } catch (Exception e) { if (!destroyed) message("Не удалось восстановить помощник: "+e.getMessage()); Log.e("HuAdbConsent","restore helper",e); }
            },present ? 800 : 0);
        } catch (Exception e) { message("Не удалось перезапустить помощник: "+e.getMessage()); Log.e("HuAdbConsent","restart helper",e); }
    }

    private List<InetAddress> localAddresses(boolean wifiOnly) { List<InetAddress> out=new ArrayList<>(); try { Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces(); while (all.hasMoreElements()) { NetworkInterface n=all.nextElement(); if (!wifiOnly || n.getName().startsWith("wlan")) out.addAll(Collections.list(n.getInetAddresses())); } } catch (Exception ignored) {} return out; }
    private String ipv4() { for (InetAddress a:localAddresses(true)) if (a instanceof Inet4Address) return a.getHostAddress(); return ""; }
    private boolean isLocal(InetAddress a) { for (InetAddress b:localAddresses(false)) if (Arrays.equals(a.getAddress(),b.getAddress())) return true; return false; }

    private void refresh() {
        boolean on=wifiOn(); String ip=ipv4();
        if (!on || ip.isEmpty() || (!endpoint.isEmpty() && !endpoint.startsWith(ip+":"))) { endpoint=""; ownService=""; }
        badge.setText(on ? "● Wi-Fi ADB включён · отключите после работы" : "● Wi-Fi ADB выключен");
        badge.setTextColor(on ? amber : muted); badge.setBackground(bg(on ? Color.rgb(65,47,25) : Color.rgb(28,39,54)));
        address.setText(ip.isEmpty() ? "Нет подключения к Wi-Fi" : endpoint.isEmpty() ? "IP: "+ip+(on ? "\nИщем порт…" : "\nADB выключен") : "IP: "+ip+"\nПорт: "+endpoint.substring(endpoint.lastIndexOf(':')+1));
        command.setText(endpoint.isEmpty() ? (on ? "Если поиск затянулся: adb mdns services" : "Включите Wi-Fi ADB") : "adb connect "+endpoint);
        command.setEnabled(!endpoint.isEmpty()); wifiButton.setText(on ? "Выключить Wi-Fi ADB" : "Включить Wi-Fi ADB");
        String helperStatus=ConsentService.live==null ? "не подключён" : ConsentService.status; helper.setText("Помощник: "+helperStatus);
        if (wifiPending) {
            if (!endpoint.isEmpty()) { wifiPending=false; message("Адрес подключения найден."); }
            else if (helperStatus.startsWith("время ожидания") || helperStatus.startsWith("окно SystemUI") || helperStatus.startsWith("запрос найден") || helperStatus.startsWith("система отклонила") || helperStatus.equals("не подключён") || helperStatus.equals("прервано")) { wifiPending=false; message("Помощник: "+helperStatus+"."); }
            else if (SystemClock.elapsedRealtime()-wifiRequestedAt>45000) { wifiPending=false; message("Порт Wi-Fi ADB не появился за 45 секунд."); }
            else message("Ожидаем системное разрешение и запуск ADB…\nПомощник: "+helperStatus);
        }
    }

    private void restartDiscovery() {
        if (discovery!=null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        final int g=++generation; resolving=false; pending.clear();
        discovery=new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {} public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t,int e) { Log.w("HuAdbPort","discovery failed "+e); }
            public void onStopDiscoveryFailed(String t,int e) {}
            public void onServiceLost(NsdServiceInfo s) { runOnUiThread(() -> { if (g==generation && s.getServiceName().equals(ownService)) { endpoint=""; ownService=""; refresh(); } }); }
            public void onServiceFound(NsdServiceInfo s) { runOnUiThread(() -> { if (g!=generation || destroyed) return; if (pending.size()<8) pending.add(s); resolveNext(g); }); }
        };
        try { nsd.discoverServices("_adb-tls-connect._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery); } catch (Exception e) { Log.w("HuAdbPort","discover",e); }
    }

    private void resolveNext(final int g) {
        if (g!=generation || destroyed || resolving || pending.isEmpty()) return;
        resolving=true; NsdServiceInfo service=pending.remove();
        nsd.resolveService(service,new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo s,int e) { runOnUiThread(() -> { if (g!=generation) return; resolving=false; resolveNext(g); }); }
            public void onServiceResolved(NsdServiceInfo s) { runOnUiThread(() -> { if (g!=generation || destroyed) return; resolving=false; if (wifiOn() && s.getHost()!=null && isLocal(s.getHost()) && s.getPort()>0 && s.getPort()<=65535 && !ipv4().isEmpty()) { endpoint=ipv4()+":"+s.getPort(); ownService=s.getServiceName(); getPreferences(0).edit().putString("serviceName",ownService).putInt("port",s.getPort()).apply(); message("Адрес подключения найден."); refresh(); } resolveNext(g); }); }
        });
    }

    private void probeSavedPort() {
        if (!wifiOn() || probing || SystemClock.elapsedRealtime()-lastProbe<10000) return;
        final int port=getPreferences(0).getInt("port",0); final String host=ipv4();
        if (port<1 || port>65535 || host.isEmpty()) return;
        probing=true; lastProbe=SystemClock.elapsedRealtime();
        new Thread(() -> {
            boolean valid=false;
            try (Socket socket=new Socket()) {
                socket.connect(new InetSocketAddress(host,port),1500); socket.setSoTimeout(1500);
                byte[] payload=new byte[]{104,111,115,116,58,58,0}; int sum=0; for (byte b:payload) sum+=b&255;
                ByteBuffer packet=ByteBuffer.allocate(24+payload.length).order(ByteOrder.LITTLE_ENDIAN);
                packet.putInt(0x4e584e43).putInt(0x01000001).putInt(1024*1024).putInt(payload.length).putInt(sum).putInt(~0x4e584e43).put(payload);
                socket.getOutputStream().write(packet.array()); byte[] reply=new byte[24]; new DataInputStream(socket.getInputStream()).readFully(reply);
                ByteBuffer response=ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN); int type=response.getInt(); valid=type==0x534c5453 && response.getInt(20)==~type;
            } catch (Exception ignored) {}
            final boolean ok=valid; runOnUiThread(() -> { probing=false; if (destroyed || !wifiOn() || !host.equals(ipv4()) || port!=getPreferences(0).getInt("port",0)) return; if (ok) { endpoint=host+":"+port; refresh(); } else if (endpoint.equals(host+":"+port)) { endpoint=""; nextSearch=0; refresh(); } });
        },"ADB-port-check").start();
    }

    @Override protected void onResume() { super.onResume(); nextSearch=0; handler.removeCallbacks(tick); handler.post(tick); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { destroyed=true; generation++; handler.removeCallbacksAndMessages(null); if (discovery!=null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {} super.onDestroy(); }
}
