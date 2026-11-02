package org.gc.bz3x.huadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final String CONSENT_COMPONENT = "org.gc.bz3x.huadb/.ConsentService";
    private static final String CLOCK_PACKAGE = NativeAdbClockInstaller.CLOCK_PACKAGE;
    private static final String CLOCK_COMPONENT = NativeAdbClockInstaller.CLOCK_COMPONENT;
    private final Handler handler = new Handler();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private TextView badge, address, command, note, helper, localeStatus, timeStatus, appsStatus, sectionTitle, sectionSubtitle;
    private Button wifiButton, applyLocaleButton, cancelLocaleButton, timezoneButton, dateButton, timeButton, applyTimeButton, cancelTimeButton, applyAppsButton, cancelAppsButton, backButton;
    private RadioGroup localeModes, gpsSyncModes, clockModes, internetModes;
    private RadioButton russianMode, chineseMode, gpsSyncOn, gpsSyncOff, clockOn, clockOff, clockRemove, internetOn, internetRestricted;
    private LinearLayout homeMenu, connectionCard, localeCard, timeCard, appsCard;
    private ScrollView mainScroll;
    private TimeSyncController timeSync;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean wifiPending, resolving, destroyed, probing, refreshingLocale, refreshingTime, clockOperationRunning;
    private String currentLocaleTags = "", pendingLocaleTags = "";
    private String currentTimezone = "", pendingTimezone = "";
    private Boolean currentGpsSync = null, pendingGpsSync = null;
    private Boolean currentClockEnabled = null, pendingClockEnabled = null;
    private Boolean currentInternetEnabled = null, pendingInternetEnabled = null;
    private boolean pendingClockRemove;
    private boolean appsOperationRunning, refreshingApps;
    private long pendingManualTime = -1L;
    private long wifiRequestedAt, nextSearch, lastProbe;
    private String endpoint = "", ownService = "";
    private String currentSection = "home";
    private int generation;
    private static final String[] TIMEZONES = {
            "Europe/Kaliningrad", "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg",
            "Asia/Omsk", "Asia/Novosibirsk", "Asia/Krasnoyarsk", "Asia/Irkutsk",
            "Asia/Yakutsk", "Asia/Vladivostok", "Asia/Magadan", "Asia/Kamchatka"
    };
    private static final String[] TIMEZONE_LABELS = {
            "Калининград · UTC+2", "Москва · UTC+3", "Самара · UTC+4", "Екатеринбург · UTC+5",
            "Омск · UTC+6", "Новосибирск · UTC+7", "Красноярск · UTC+7", "Иркутск · UTC+8",
            "Якутск · UTC+9", "Владивосток · UTC+10", "Магадан · UTC+11", "Камчатка · UTC+12"
    };
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
        mainScroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,28,36,32);
        root.setBackgroundColor(Color.rgb(17,25,37));
        mainScroll.setFillViewport(true);
        mainScroll.addView(root);
        setContentView(mainScroll);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView appTitle = text("Toyota bZ3X",30,white);
        appTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(appTitle,new LinearLayout.LayoutParams(0,-2,1f));
        backButton = compactButton("← Все разделы",() -> requestSection("home"));
        header.addView(backButton,new LinearLayout.LayoutParams(260,64));
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        sectionTitle = text("Настройки и инструменты",24,white);
        sectionTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        root.addView(sectionTitle);
        sectionSubtitle = text("Выберите раздел. Новые функции будут добавляться сюда отдельными категориями.",16,muted);
        root.addView(sectionSubtitle);

        note = text("",17,amber);
        note.setPadding(16,10,16,10);
        root.addView(note,new LinearLayout.LayoutParams(-1,-2));

        homeMenu = new LinearLayout(this);
        homeMenu.setOrientation(LinearLayout.VERTICAL);
        homeMenu.setPadding(0,8,0,8);
        root.addView(homeMenu,new LinearLayout.LayoutParams(-1,-2));
        addHomeRow(homeMenu,
                menuTile("Подключение","Wi-Fi ADB и адрес подключения",() -> showSection("connection")),
                menuTile("Экран и язык","Язык центрального Android-экрана",() -> showSection("display")));
        addHomeRow(homeMenu,
                menuTile("Дата и время","Часовой пояс, синхронизация и часы",() -> showSection("time")),
                menuTile("Приложения","Права и доступ приложений к интернету",() -> showSection("apps")));

        badge = text("",19,amber);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(20,14,20,14);
        connectionCard = card(root);
        connectionCard.addView(badge,new LinearLayout.LayoutParams(-1,-2));
        connectionCard.addView(text("Подключение по Wi-Fi ADB",24,white));
        address = text("",25,green); connectionCard.addView(address);
        command = text("",19,white);
        command.setTypeface(Typeface.MONOSPACE);
        command.setPadding(16,18,16,18);
        command.setBackground(bg(Color.rgb(17,25,37)));
        command.setOnClickListener(v -> copyCommand());
        connectionCard.addView(command);
        connectionCard.addView(text("Нажмите на команду, чтобы скопировать её в буфер ГУ.",15,muted));
        wifiButton = button(connectionCard,"",() -> { if (wifiOn()) confirmOff(); else requestWifi(); });
        helper = text("",16,muted);
        connectionCard.addView(helper);
        button(connectionCard,"Перезапустить помощник подтверждения",this::confirmRepairHelper);
        TextView help = text("Помощник нужен только для подтверждения системного запроса при включении Wi-Fi ADB.",15,muted);
        help.setPadding(0,8,0,0);
        connectionCard.addView(help);

        localeCard = card(root);
        localeCard.addView(text("Центральный экран",24,white));
        localeCard.addView(text("Смена языка Android-интерфейса ГУ. Приборная панель этим действием не изменяется.",16,muted));
        localeStatus = text("",17,green);
        localeCard.addView(localeStatus);
        localeModes = new RadioGroup(this);
        localeModes.setOrientation(RadioGroup.VERTICAL);
        russianMode = radio("Русский", "ru-RU · en-US · zh-CN");
        chineseMode = radio("Китайский", "zh-CN");
        localeModes.addView(russianMode);
        localeModes.addView(chineseMode);
        localeCard.addView(localeModes,new LinearLayout.LayoutParams(-1,-2));
        localeModes.setOnCheckedChangeListener((group,checkedId) -> {
            if (refreshingLocale || checkedId == View.NO_ID) return;
            pendingLocaleTags=selectedLocaleTags();
            updateLocaleApplyState();
        });
        LinearLayout localeActions=new LinearLayout(this);
        localeActions.setOrientation(LinearLayout.HORIZONTAL);
        localeActions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        localeActions.setPadding(0,12,0,0);
        cancelLocaleButton=compactActionButton("Отмена",this::cancelLocaleChanges);
        applyLocaleButton=compactActionButton("Применить",this::applySelectedLocale);
        cancelLocaleButton.setVisibility(View.GONE);
        applyLocaleButton.setVisibility(View.GONE);
        localeActions.addView(cancelLocaleButton);
        localeActions.addView(applyLocaleButton);
        localeCard.addView(localeActions,new LinearLayout.LayoutParams(-1,-2));

        timeCard = card(root);
        timeCard.addView(text("Дата и время",24,white));
        timeCard.addView(text("Часовой пояс, ручная установка часов и GPS-синхронизация через штатный Toyota CarTimeSync.",16,muted));
        timeStatus = text("",17,green);
        timeCard.addView(timeStatus);
        timezoneButton = button(timeCard,"Выбрать часовой пояс",this::chooseTimezone);
        TextView manualTitle=text("Ручная дата и время",20,white);
        manualTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        manualTitle.setPadding(0,18,0,2);
        timeCard.addView(manualTitle);
        timeCard.addView(text("Используются стандартные системные редакторы Android. Изменение применится только после подтверждения.",15,muted));
        LinearLayout dateTimeActions=new LinearLayout(this);
        dateTimeActions.setOrientation(LinearLayout.HORIZONTAL);
        dateTimeActions.setGravity(Gravity.START);
        dateButton=compactActionButton("",this::chooseDate);
        timeButton=compactActionButton("",this::chooseTime);
        dateTimeActions.addView(dateButton);
        dateTimeActions.addView(timeButton);
        timeCard.addView(dateTimeActions,new LinearLayout.LayoutParams(-1,-2));
        gpsSyncModes = new RadioGroup(this);
        gpsSyncModes.setOrientation(RadioGroup.VERTICAL);
        gpsSyncOn = radio("GPS-синхронизация включена", "CarTimeSync получает время от GPS");
        gpsSyncOff = radio("GPS-синхронизация выключена", "время не корректируется по GPS");
        gpsSyncModes.addView(gpsSyncOn);
        gpsSyncModes.addView(gpsSyncOff);
        timeCard.addView(gpsSyncModes,new LinearLayout.LayoutParams(-1,-2));
        gpsSyncModes.setOnCheckedChangeListener((group,checkedId) -> {
            if (refreshingTime || checkedId == View.NO_ID) return;
            pendingGpsSync=checkedId==gpsSyncOn.getId();
            updateTimeApplyState();
            refreshTimeStatusText();
        });

        TextView clockTitle=text("Часы на центральном экране",22,white);
        clockTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        clockTitle.setPadding(0,20,0,4);
        timeCard.addView(clockTitle);
        timeCard.addView(text("Оригинальный ClockOverlay 1.4.5 в правой части штатной верхней панели. При первом включении Toyota bZ3X установит и настроит его через локальный Wi-Fi ADB; затем приложение часов работает независимо.",16,muted));
        clockModes=new RadioGroup(this);
        clockModes.setOrientation(RadioGroup.VERTICAL);
        clockOn=radio("Часы включены","служба отдельного приложения часов активна");
        clockOff=radio("Часы выключены","служба часов отключена, APK остаётся установлен");
        clockRemove=radio("Удалить ClockOverlay","полностью удалить APK и откатить его настройки при применении");
        clockModes.addView(clockOn);
        clockModes.addView(clockOff);
        clockModes.addView(clockRemove);
        timeCard.addView(clockModes,new LinearLayout.LayoutParams(-1,-2));
        clockModes.setOnCheckedChangeListener((group,checkedId) -> {
            if (refreshingTime || checkedId==View.NO_ID) return;
            pendingClockRemove=checkedId==clockRemove.getId();
            pendingClockEnabled=pendingClockRemove ? null : checkedId==clockOn.getId();
            updateTimeApplyState();
            refreshTimeStatusText();
        });
        LinearLayout timeActions=new LinearLayout(this);
        timeActions.setOrientation(LinearLayout.HORIZONTAL);
        timeActions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        timeActions.setPadding(0,12,0,0);
        cancelTimeButton=compactActionButton("Отмена",this::cancelTimeChanges);
        applyTimeButton=compactActionButton("Применить",this::confirmApplyTimeChanges);
        cancelTimeButton.setVisibility(View.GONE);
        applyTimeButton.setVisibility(View.GONE);
        timeActions.addView(cancelTimeButton);
        timeActions.addView(applyTimeButton);
        timeCard.addView(timeActions,new LinearLayout.LayoutParams(-1,-2));

        appsCard = card(root);
        appsCard.addView(text("Приложения",24,white));
        appsCard.addView(text("Безопасные разрешения для сторонних приложений и штатное ограничение доступа к интернету Toyota bZ3X.",16,muted));
        appsStatus=text("",17,green);
        appsCard.addView(appsStatus);
        TextView permissionsTitle=text("Разрешения приложений",21,white);
        permissionsTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        permissionsTitle.setPadding(0,18,0,2);
        appsCard.addView(permissionsTitle);
        appsCard.addView(text("Выдаются только безопасные разрешения из манифеста: уведомления, Bluetooth, медиа/файлы и фоновая работа. Геолокация, микрофон, камера, SMS, контакты и телефон не выдаются.",15,muted));
        button(appsCard,"Выдать безопасные права приложению",this::chooseAppForSafePermissions);
        TextView internetTitle=text("Интернет для приложений",21,white);
        internetTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        internetTitle.setPadding(0,22,0,2);
        appsCard.addView(internetTitle);
        appsCard.addView(text("Toyota bZ3X ограничивает сторонние приложения через com.gxa.firewall. Изменение применяется только после нажатия «Применить».",15,muted));
        internetModes=new RadioGroup(this);
        internetModes.setOrientation(RadioGroup.VERTICAL);
        internetOn=radio("Интернет для приложений включён","com.gxa.firewall отключён для пользователя 0");
        internetRestricted=radio("Штатное ограничение интернета","com.gxa.firewall включён");
        internetModes.addView(internetOn);
        internetModes.addView(internetRestricted);
        appsCard.addView(internetModes,new LinearLayout.LayoutParams(-1,-2));
        internetModes.setOnCheckedChangeListener((group,checkedId) -> {
            if (appsOperationRunning || refreshingApps || checkedId==View.NO_ID) return;
            pendingInternetEnabled=checkedId==internetOn.getId();
            updateAppsApplyState();
            refreshAppsStatusText();
        });
        LinearLayout appsActions=new LinearLayout(this);
        appsActions.setOrientation(LinearLayout.HORIZONTAL);
        appsActions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        appsActions.setPadding(0,12,0,0);
        cancelAppsButton=compactActionButton("Отмена",this::cancelAppsChanges);
        applyAppsButton=compactActionButton("Применить",this::confirmApplyAppsChanges);
        cancelAppsButton.setVisibility(View.GONE);
        applyAppsButton.setVisibility(View.GONE);
        appsActions.addView(cancelAppsButton);
        appsActions.addView(applyAppsButton);
        appsCard.addView(appsActions,new LinearLayout.LayoutParams(-1,-2));
        nsd = (NsdManager)getSystemService(Context.NSD_SERVICE);
        timeSync = new TimeSyncController(this,() -> runOnUiThread(this::refreshTime));
        timeSync.connect();
        showSection("home");
    }

    private TextView text(String value,int size,int color) { TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,8,0,8); return v; }
    private GradientDrawable bg(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(18); return d; }
    private LinearLayout card(LinearLayout root) { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(24,18,24,22); v.setBackground(bg(Color.rgb(28,39,54))); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,18,0,12); root.addView(v,p); return v; }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(180);
        b.setPadding(22,8,22,8);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,62);
        p.gravity=Gravity.START;
        p.setMargins(0,8,10,4);
        parent.addView(b,p);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private Button compactButton(String label,Runnable action) { Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setOnClickListener(v -> action.run()); return b; }
    private Button compactActionButton(String label,Runnable action) {
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(20,8,20,8);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,62);
        p.setMargins(0,8,10,4);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Calendar editableTime() {
        Calendar value=Calendar.getInstance();
        if (pendingManualTime>0) value.setTimeInMillis(pendingManualTime);
        value.set(Calendar.SECOND,0);
        value.set(Calendar.MILLISECOND,0);
        return value;
    }

    private void chooseDate() {
        Calendar value=editableTime();
        new DatePickerDialog(this,(view,year,month,day) -> {
            Calendar updated=editableTime();
            updated.set(Calendar.YEAR,year);
            updated.set(Calendar.MONTH,month);
            updated.set(Calendar.DAY_OF_MONTH,day);
            pendingManualTime=updated.getTimeInMillis();
            refreshDateTimeButtons();
            updateTimeApplyState();
            refreshTimeStatusText();
        },value.get(Calendar.YEAR),value.get(Calendar.MONTH),value.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void chooseTime() {
        Calendar value=editableTime();
        new TimePickerDialog(this,(view,hour,minute) -> {
            Calendar updated=editableTime();
            updated.set(Calendar.HOUR_OF_DAY,hour);
            updated.set(Calendar.MINUTE,minute);
            pendingManualTime=updated.getTimeInMillis();
            refreshDateTimeButtons();
            updateTimeApplyState();
            refreshTimeStatusText();
        },value.get(Calendar.HOUR_OF_DAY),value.get(Calendar.MINUTE),true).show();
    }

    private void refreshDateTimeButtons() {
        if (dateButton==null || timeButton==null) return;
        Calendar value=editableTime();
        dateButton.setText(String.format(Locale.getDefault(),"Дата: %02d.%02d.%04d",value.get(Calendar.DAY_OF_MONTH),value.get(Calendar.MONTH)+1,value.get(Calendar.YEAR)));
        timeButton.setText(String.format(Locale.getDefault(),"Время: %02d:%02d",value.get(Calendar.HOUR_OF_DAY),value.get(Calendar.MINUTE)));
    }
    private LinearLayout menuTile(String title,String details,Runnable action) {
        LinearLayout tile=new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(24,20,24,20);
        tile.setBackground(bg(Color.rgb(28,39,54)));
        TextView heading=text(title,22,white);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        tile.addView(heading);
        tile.addView(text(details,16,muted));
        TextView open=text("Открыть  →",16,green);
        tile.addView(open);
        tile.setOnClickListener(v -> action.run());
        tile.setClickable(true);
        tile.setFocusable(true);
        return tile;
    }
    private void addHomeRow(LinearLayout parent,View left,View right) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);
        rowParams.setMargins(0,8,0,8);
        parent.addView(row,rowParams);
        LinearLayout.LayoutParams leftParams=new LinearLayout.LayoutParams(0,190,1f);
        leftParams.setMargins(0,0,8,0);
        row.addView(left,leftParams);
        LinearLayout.LayoutParams rightParams=new LinearLayout.LayoutParams(0,190,1f);
        rightParams.setMargins(8,0,0,0);
        row.addView(right,rightParams);
    }
    private void showSection(String section) {
        currentSection=section;
        boolean home="home".equals(section);
        homeMenu.setVisibility(home?View.VISIBLE:View.GONE);
        connectionCard.setVisibility("connection".equals(section)?View.VISIBLE:View.GONE);
        localeCard.setVisibility("display".equals(section)?View.VISIBLE:View.GONE);
        timeCard.setVisibility("time".equals(section)?View.VISIBLE:View.GONE);
        appsCard.setVisibility("apps".equals(section)?View.VISIBLE:View.GONE);
        backButton.setVisibility(home?View.GONE:View.VISIBLE);
        if (home) {
            sectionTitle.setText("Настройки и инструменты");
            sectionSubtitle.setText("Выберите раздел. Новые функции будут добавляться сюда отдельными категориями.");
        } else if ("connection".equals(section)) {
            sectionTitle.setText("Подключение");
            sectionSubtitle.setText("Wi-Fi ADB и адрес для подключения к головному устройству.");
        } else if ("display".equals(section)) {
            sectionTitle.setText("Экран и язык");
            sectionSubtitle.setText("Настройки Android-интерфейса центрального экрана.");
        } else if ("time".equals(section)) {
            sectionTitle.setText("Дата и время");
            sectionSubtitle.setText("Часовой пояс, ручная установка времени, синхронизация и часы на экране.");
        } else if ("apps".equals(section)) {
            sectionTitle.setText("Приложения");
            sectionSubtitle.setText("Разрешения приложений и штатное ограничение доступа к интернету.");
        }
        mainScroll.post(() -> mainScroll.scrollTo(0,0));
    }

    private void requestSection(String section) {
        if (section.equals(currentSection)) return;
        if (!hasPendingChanges(currentSection)) {
            showSection(section);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Несохранённые изменения")
                .setMessage("Есть изменения, которые ещё не применены. Если выйти со страницы, они не будут сохранены.")
                .setNegativeButton("Остаться",null)
                .setPositiveButton("Выйти без сохранения",(d,w) -> {
                    discardPendingChanges(currentSection);
                    showSection(section);
                })
                .show();
    }

    private boolean hasPendingChanges(String section) {
        if ("display".equals(section)) {
            return !pendingLocaleTags.isEmpty() && !pendingLocaleTags.equals(currentLocaleTags);
        }
        if ("time".equals(section)) {
            boolean timezoneChanged=!pendingTimezone.isEmpty() && !pendingTimezone.equals(currentTimezone);
            boolean gpsChanged=pendingGpsSync!=null && !pendingGpsSync.equals(currentGpsSync);
            boolean clockChanged=pendingClockEnabled!=null && !pendingClockEnabled.equals(currentClockEnabled);
            return timezoneChanged || gpsChanged || pendingManualTime>0 || clockChanged || pendingClockRemove;
        }
        if ("apps".equals(section)) return pendingInternetEnabled!=null && !pendingInternetEnabled.equals(currentInternetEnabled);
        return false;
    }

    private void discardPendingChanges(String section) {
        if ("display".equals(section)) {
            pendingLocaleTags="";
            refreshLocale();
        } else if ("time".equals(section)) {
            pendingTimezone="";
            pendingGpsSync=null;
            pendingClockEnabled=null;
            pendingClockRemove=false;
            pendingManualTime=-1L;
            refreshTime();
        } else if ("apps".equals(section)) {
            pendingInternetEnabled=null;
            refreshApps();
        }
    }
    private RadioButton radio(String title,String details) { RadioButton b=new RadioButton(this); b.setId(View.generateViewId()); b.setText(title+"\n"+details); b.setTextColor(white); b.setTextSize(18); b.setPadding(8,12,8,12); return b; }
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

    private String selectedLocaleTags() {
        int checkedId=localeModes.getCheckedRadioButtonId();
        if (checkedId==russianMode.getId()) return LocaleController.RUSSIAN;
        if (checkedId==chineseMode.getId()) return LocaleController.CHINESE;
        return "";
    }

    private void updateLocaleApplyState() {
        boolean hasPending=!pendingLocaleTags.isEmpty() && !pendingLocaleTags.equals(currentLocaleTags);
        applyLocaleButton.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        cancelLocaleButton.setVisibility(hasPending ? View.VISIBLE : View.GONE);
    }

    private void cancelLocaleChanges() {
        pendingLocaleTags="";
        refreshLocale();
        message("Несохранённые изменения языка отменены.");
    }

    private void applySelectedLocale() {
        String tags=pendingLocaleTags;
        if (tags.isEmpty() || tags.equals(currentLocaleTags)) {
            pendingLocaleTags="";
            updateLocaleApplyState();
            return;
        }
        if (checkSelfPermission("android.permission.CHANGE_CONFIGURATION") != PackageManager.PERMISSION_GRANTED) {
            message("Нет CHANGE_CONFIGURATION. Выдайте право через tools/device/app-permissions.");
            refreshLocale();
            return;
        }
        if (!Settings.System.canWrite(this)) {
            message("Нет WRITE_SETTINGS. Для русификации нужен AppOp WRITE_SETTINGS=allow.");
            refreshLocale();
            return;
        }
        String title=tags.equals(LocaleController.RUSSIAN) ? "Применить русский режим?" : "Применить китайский режим?";
        String prompt=tags.equals(LocaleController.RUSSIAN)
                ? "Центральный экран будет переключён на русский язык с английским и китайским в качестве дополнительных языков."
                : "Центральный экран будет переключён на китайский язык.";
        new AlertDialog.Builder(this).setTitle(title).setMessage(prompt)
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Применить",(d,w) -> applyLocale(tags)).show();
    }

    private void applyLocale(String tags) {
        try {
            LocaleController.apply(tags);
            pendingLocaleTags="";
            message("Язык центрального экрана изменён: "+tags);
            refreshLocale();
        } catch (Exception e) {
            message("Не удалось изменить язык: "+e.getClass().getSimpleName()+": "+e.getMessage());
            Log.e("HuAdbLocale","apply locale "+tags,e);
            refreshLocale();
        }
    }

    private void refreshLocale() {
        try {
            String current=LocaleController.currentLocales();
            currentLocaleTags=current;
            refreshingLocale=true;
            if (!pendingLocaleTags.isEmpty() && !pendingLocaleTags.equals(current)) {
                if (pendingLocaleTags.equals(LocaleController.RUSSIAN)) russianMode.setChecked(true);
                else if (pendingLocaleTags.equals(LocaleController.CHINESE)) chineseMode.setChecked(true);
                localeStatus.setText(current.equals(LocaleController.RUSSIAN) ? "Текущий режим: Русский · выбрано: Китайский"
                        : current.equals(LocaleController.CHINESE) ? "Текущий режим: Китайский · выбрано: Русский"
                        : "Текущая конфигурация: "+current+" · выбран новый режим");
            } else {
                pendingLocaleTags="";
                if (current.equals(LocaleController.RUSSIAN)) {
                    russianMode.setChecked(true);
                    localeStatus.setText("Текущий режим: Русский");
                } else if (current.equals(LocaleController.CHINESE)) {
                    chineseMode.setChecked(true);
                    localeStatus.setText("Текущий режим: Китайский");
                } else {
                    localeModes.clearCheck();
                    localeStatus.setText("Текущая конфигурация не совпадает с известными режимами: "+current);
                }
            }
        } catch (Exception e) {
            currentLocaleTags="";
            refreshingLocale=true;
            if (pendingLocaleTags.isEmpty()) localeModes.clearCheck();
            localeStatus.setText("Текущие языки: не удалось прочитать ("+e.getClass().getSimpleName()+")");
        } finally {
            refreshingLocale=false;
            updateLocaleApplyState();
        }
    }

    private void chooseTimezone() {
        int selected=0;
        String value=pendingTimezone.isEmpty()?currentTimezone:pendingTimezone;
        for (int i=0;i<TIMEZONES.length;i++) if (TIMEZONES[i].equals(value)) { selected=i; break; }
        new AlertDialog.Builder(this).setTitle("Часовой пояс")
                .setSingleChoiceItems(TIMEZONE_LABELS,selected,(dialog,which) -> {
                    pendingTimezone=TIMEZONES[which];
                    dialog.dismiss();
                    updateTimeApplyState();
                    refreshTimeStatusText();
                }).setNegativeButton("Отмена",null).show();
    }

    private void updateTimeApplyState() {
        boolean timezoneChanged=!pendingTimezone.isEmpty() && !pendingTimezone.equals(currentTimezone);
        boolean gpsChanged=pendingGpsSync!=null && !pendingGpsSync.equals(currentGpsSync);
        boolean clockChanged=pendingClockEnabled!=null && !pendingClockEnabled.equals(currentClockEnabled);
        boolean hasPending=timezoneChanged || gpsChanged || pendingManualTime>0 || clockChanged || pendingClockRemove;
        applyTimeButton.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        cancelTimeButton.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        applyTimeButton.setEnabled(!clockOperationRunning);
        cancelTimeButton.setEnabled(!clockOperationRunning);
    }

    private void cancelTimeChanges() {
        pendingTimezone="";
        pendingGpsSync=null;
        pendingClockEnabled=null;
        pendingClockRemove=false;
        pendingManualTime=-1L;
        refreshTime();
        message("Несохранённые изменения времени отменены.");
    }

    private String formatTime(long millis) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(new Date(millis));
    }

    private void refreshTimeStatusText() {
        if (timeStatus==null) return;
        StringBuilder value=new StringBuilder();
        value.append("Сейчас: ").append(formatTime(System.currentTimeMillis()));
        value.append("\nЧасовой пояс: ").append(currentTimezone.isEmpty()?"неизвестен":currentTimezone);
        value.append("\nGPS-синхронизация: ").append(currentGpsSync==null?"не удалось прочитать":currentGpsSync?"включена":"выключена");
        value.append("\nClockOverlay 1.4.5: ").append(clockPackageInstalled()?"установлен":"не установлен");
        value.append("\nЧасы: ").append(Boolean.TRUE.equals(currentClockEnabled)?"включены":"выключены");
        if (!clockPackageInstalled() && Boolean.TRUE.equals(pendingClockEnabled)) value.append("\nПри применении: установка через локальный Wi-Fi ADB");
        if (!pendingTimezone.isEmpty() && !pendingTimezone.equals(currentTimezone)) value.append("\nВыбран пояс: ").append(pendingTimezone);
        if (pendingManualTime>0) value.append("\nВыбрано время: ").append(formatTime(pendingManualTime));
        if (pendingGpsSync!=null && !pendingGpsSync.equals(currentGpsSync)) value.append("\nВыбрана GPS-синхронизация: ").append(pendingGpsSync?"вкл.":"выкл.");
        if (pendingClockRemove) value.append("\nПри применении: полное удаление ClockOverlay");
        else if (pendingClockEnabled!=null && !pendingClockEnabled.equals(currentClockEnabled)) value.append("\nВыбраны часы: ").append(pendingClockEnabled?"вкл.":"выкл.");
        timeStatus.setText(value.toString());
    }

    private void refreshTime() {
        currentTimezone=TimeZone.getDefault().getID();
        currentClockEnabled=clockServiceEnabled();
        if (timeSync!=null && timeSync.isConnected()) {
            try { currentGpsSync=timeSync.isGpsSyncEnabled(); }
            catch (Exception e) { currentGpsSync=null; Log.w("HuAdbTime","read gps sync",e); }
        } else currentGpsSync=null;
        refreshingTime=true;
        try {
            if (pendingGpsSync!=null && !pendingGpsSync.equals(currentGpsSync)) {
                if (pendingGpsSync) gpsSyncOn.setChecked(true); else gpsSyncOff.setChecked(true);
            } else {
                pendingGpsSync=null;
                if (Boolean.TRUE.equals(currentGpsSync)) gpsSyncOn.setChecked(true);
                else if (Boolean.FALSE.equals(currentGpsSync)) gpsSyncOff.setChecked(true);
                else gpsSyncModes.clearCheck();
            }
            if (pendingTimezone.equals(currentTimezone)) pendingTimezone="";
            if (!pendingClockRemove && pendingClockEnabled!=null && pendingClockEnabled.equals(currentClockEnabled)) pendingClockEnabled=null;
            if (pendingClockRemove) clockRemove.setChecked(true);
            else if (pendingClockEnabled!=null) {
                if (pendingClockEnabled) clockOn.setChecked(true); else clockOff.setChecked(true);
            } else if (Boolean.TRUE.equals(currentClockEnabled)) clockOn.setChecked(true);
            else clockOff.setChecked(true);
        } finally { refreshingTime=false; }
        timezoneButton.setText("Часовой пояс: "+(pendingTimezone.isEmpty()?currentTimezone:pendingTimezone));
        refreshDateTimeButtons();
        refreshTimeStatusText();
        updateTimeApplyState();
        clockRemove.setEnabled(clockPackageInstalled());
    }

    private void confirmApplyTimeChanges() {
        if (applyTimeButton.getVisibility()!=View.VISIBLE) return;
        boolean timezoneChanged=!pendingTimezone.isEmpty() && !pendingTimezone.equals(currentTimezone);
        boolean gpsChanged=pendingGpsSync!=null && !pendingGpsSync.equals(currentGpsSync);
        boolean clockChanged=pendingClockEnabled!=null && !pendingClockEnabled.equals(currentClockEnabled);
        boolean onlyClock=(clockChanged || pendingClockRemove) && !timezoneChanged && !gpsChanged && pendingManualTime<=0;
        String prompt=pendingClockRemove
                ? "ClockOverlay 1.4.5 будет полностью удалён: служба отключится, SYSTEM_ALERT_WINDOW вернётся в default, APK будет удалён."
                : onlyClock
                ? (Boolean.TRUE.equals(pendingClockEnabled)
                    ? (clockPackageInstalled()
                        ? "Оригинальный ClockOverlay 1.4.5 будет включён через локальный Wi-Fi ADB."
                        : "Оригинальный ClockOverlay 1.4.5 будет установлен из Toyota bZ3X через локальный Wi-Fi ADB и его служба часов будет включена.")
                    : "Служба ClockOverlay будет выключена через локальный Wi-Fi ADB. Сам ClockOverlay останется установленным и независимым от Toyota bZ3X.")
                : "Будут применены только выбранные изменения. Системное время может измениться сразу после подтверждения.";
        new AlertDialog.Builder(this).setTitle("Применить настройки времени?")
                .setMessage(prompt)
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Применить",(d,w) -> applyTimeChanges()).show();
    }

    private void applyTimeChanges() {
        boolean timezoneChanged=!pendingTimezone.isEmpty() && !pendingTimezone.equals(currentTimezone);
        boolean gpsChanged=pendingGpsSync!=null && !pendingGpsSync.equals(currentGpsSync);
        boolean clockChanged=pendingClockEnabled!=null && !pendingClockEnabled.equals(currentClockEnabled);
        boolean needsTimeSync=timezoneChanged || gpsChanged || pendingManualTime>0;
        if (needsTimeSync && (timeSync==null || !timeSync.isConnected())) { message("Штатный Toyota CarTimeSync недоступен."); return; }
        if (clockChanged || pendingClockRemove) {
            int port=adbPort();
            if (!wifiOn() || port<1) {
                message("Для установки или настройки ClockOverlay сначала включите Wi-Fi ADB и дождитесь появления порта.");
                return;
            }
            final boolean desiredClock=Boolean.TRUE.equals(pendingClockEnabled);
            final boolean removeClock=pendingClockRemove;
            clockOperationRunning=true;
            updateTimeApplyState();
            new Thread(() -> {
                try {
                    NativeAdbClockInstaller installer=new NativeAdbClockInstaller(this,value -> runOnUiThread(() -> message(value)));
                    if (removeClock) installer.removeCompletely(port); else installer.setEnabled(port,desiredClock);
                    runOnUiThread(() -> {
                        clockOperationRunning=false;
                        try { finishTimeChanges(timezoneChanged,gpsChanged,needsTimeSync,true,removeClock); }
                        catch (Exception e) { handleTimeApplyError(e); }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        clockOperationRunning=false;
                        message("Не удалось установить/настроить ClockOverlay: "+e.getClass().getSimpleName()+": "+e.getMessage());
                        Log.e("HuAdbClock","clock adb workflow",e);
                        refreshTime();
                    });
                }
            },"clock-adb-install").start();
            return;
        }
        try {
            finishTimeChanges(timezoneChanged,gpsChanged,needsTimeSync,false,false);
        } catch (Exception e) {
            handleTimeApplyError(e);
        }
    }

    private void finishTimeChanges(boolean timezoneChanged,boolean gpsChanged,boolean needsTimeSync,boolean clockChanged,boolean clockRemoved) throws Exception {
        if (gpsChanged) timeSync.setGpsSyncEnabled(pendingGpsSync);
        if (timezoneChanged || pendingManualTime>0) {
            long millis=pendingManualTime>0?pendingManualTime:System.currentTimeMillis();
            timeSync.setTime(millis,timezoneChanged?pendingTimezone:"");
        }
        pendingGpsSync=null; pendingTimezone=""; pendingClockEnabled=null; pendingClockRemove=false; pendingManualTime=-1L;
        if (clockRemoved && needsTimeSync) message("Настройки времени применены; ClockOverlay 1.4.5 полностью удалён.");
        else if (clockRemoved) message("ClockOverlay 1.4.5 полностью удалён, его настройки сброшены.");
        else if (clockChanged && needsTimeSync) message("Настройки времени применены; ClockOverlay 1.4.5 настроен через локальный ADB.");
        else if (clockChanged) message("ClockOverlay 1.4.5 настроен через локальный ADB.");
        else message("Настройки времени применены через Toyota CarTimeSync.");
        handler.postDelayed(this::refreshTime,700);
    }

    private void handleTimeApplyError(Exception e) {
        message("Не удалось изменить настройки времени: "+e.getClass().getSimpleName()+": "+e.getMessage());
        Log.e("HuAdbTime","apply time changes",e);
        refreshTime();
    }

    private void chooseAppForSafePermissions() {
        if (appsOperationRunning) return;
        int port=adbPort();
        if (!wifiOn() || port<1) {
            message("Для выдачи прав сначала включите Wi-Fi ADB и дождитесь появления порта.");
            return;
        }
        PackageManager pm=getPackageManager();
        List<ApplicationInfo> apps=new ArrayList<>();
        for (ApplicationInfo info:pm.getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM)!=0) continue;
            if (getPackageName().equals(info.packageName)) continue;
            apps.add(info);
        }
        Collections.sort(apps,new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a,ApplicationInfo b) {
                return String.valueOf(pm.getApplicationLabel(a)).compareToIgnoreCase(String.valueOf(pm.getApplicationLabel(b)));
            }
        });
        if (apps.isEmpty()) {
            message("Сторонние приложения не найдены.");
            return;
        }
        String[] labels=new String[apps.size()];
        for (int i=0;i<apps.size();i++) labels[i]=pm.getApplicationLabel(apps.get(i))+"\n"+apps.get(i).packageName;
        new AlertDialog.Builder(this).setTitle("Выберите приложение")
                .setItems(labels,(dialog,which) -> confirmGrantSafePermissions(apps.get(which).packageName,labels[which]))
                .setNegativeButton("Отмена",null).show();
    }

    private void confirmGrantSafePermissions(String packageName,String label) {
        new AlertDialog.Builder(this).setTitle("Выдать безопасные права?")
                .setMessage(label+"\n\nБудут выданы только безопасные разрешения, которые приложение само запросило в манифесте, а также разрешена фоновая работа.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Выдать",(d,w) -> grantSafePermissions(packageName)).show();
    }

    private void grantSafePermissions(String packageName) {
        final int port=adbPort();
        if (!wifiOn() || port<1) { message("Wi-Fi ADB сейчас недоступен."); return; }
        final String[] requested;
        try {
            requested=getPackageManager().getPackageInfo(packageName,PackageManager.GET_PERMISSIONS).requestedPermissions;
        } catch (Exception e) {
            message("Не удалось прочитать манифест "+packageName+": "+e.getMessage());
            return;
        }
        appsOperationRunning=true;
        updateAppsApplyState();
        new Thread(() -> {
            try {
                NativeAdbAppTools tools=new NativeAdbAppTools(this,value -> runOnUiThread(() -> message(value)));
                tools.grantSafePermissions(port,packageName,requested);
                runOnUiThread(() -> {
                    appsOperationRunning=false;
                    updateAppsApplyState();
                    refreshAppsStatusText();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appsOperationRunning=false;
                    message("Не удалось выдать права: "+e.getClass().getSimpleName()+": "+e.getMessage());
                    Log.e("HuAdbApps","grant safe permissions "+packageName,e);
                    updateAppsApplyState();
                });
            }
        },"app-safe-permissions").start();
    }

    private Boolean readInternetEnabled() {
        try {
            int state=getPackageManager().getApplicationEnabledSetting(NativeAdbAppTools.FIREWALL_PACKAGE);
            return state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED || state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (Exception e) {
            Log.w("HuAdbApps","read firewall state",e);
            return null;
        }
    }

    private void refreshApps() {
        currentInternetEnabled=readInternetEnabled();
        refreshingApps=true;
        try {
            if (pendingInternetEnabled!=null && !pendingInternetEnabled.equals(currentInternetEnabled)) {
                if (pendingInternetEnabled) internetOn.setChecked(true); else internetRestricted.setChecked(true);
            } else {
                pendingInternetEnabled=null;
                if (Boolean.TRUE.equals(currentInternetEnabled)) internetOn.setChecked(true);
                else if (Boolean.FALSE.equals(currentInternetEnabled)) internetRestricted.setChecked(true);
                else internetModes.clearCheck();
            }
        } finally { refreshingApps=false; }
        refreshAppsStatusText();
        updateAppsApplyState();
    }

    private void refreshAppsStatusText() {
        if (appsStatus==null) return;
        String current=currentInternetEnabled==null ? "не удалось определить"
                : currentInternetEnabled ? "интернет для приложений включён" : "штатное ограничение включено";
        StringBuilder value=new StringBuilder("Текущее состояние: ").append(current);
        if (pendingInternetEnabled!=null && !pendingInternetEnabled.equals(currentInternetEnabled)) {
            value.append("\nВыбрано: ").append(pendingInternetEnabled ? "включить интернет для приложений" : "вернуть штатное ограничение");
        }
        appsStatus.setText(value.toString());
    }

    private void updateAppsApplyState() {
        if (applyAppsButton==null) return;
        boolean hasPending=pendingInternetEnabled!=null && !pendingInternetEnabled.equals(currentInternetEnabled);
        applyAppsButton.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        cancelAppsButton.setVisibility(hasPending ? View.VISIBLE : View.GONE);
        applyAppsButton.setEnabled(!appsOperationRunning);
        cancelAppsButton.setEnabled(!appsOperationRunning);
    }

    private void cancelAppsChanges() {
        pendingInternetEnabled=null;
        refreshApps();
        message("Несохранённые изменения доступа к интернету отменены.");
    }

    private void confirmApplyAppsChanges() {
        if (pendingInternetEnabled==null || pendingInternetEnabled.equals(currentInternetEnabled)) return;
        boolean enable=pendingInternetEnabled;
        String prompt=enable
                ? "OEM-пакет com.gxa.firewall будет отключён для пользователя 0. Это включает интернет для сторонних приложений."
                : "OEM-пакет com.gxa.firewall будет снова включён. Штатное ограничение интернета для сторонних приложений вернётся.";
        new AlertDialog.Builder(this).setTitle("Применить настройку интернета?")
                .setMessage(prompt).setNegativeButton("Отмена",null)
                .setPositiveButton("Применить",(d,w) -> applyInternetSetting(enable)).show();
    }

    private void applyInternetSetting(boolean enabled) {
        final int port=adbPort();
        if (!wifiOn() || port<1) { message("Для изменения доступа к интернету сначала включите Wi-Fi ADB и дождитесь появления порта."); return; }
        appsOperationRunning=true;
        updateAppsApplyState();
        new Thread(() -> {
            try {
                NativeAdbAppTools tools=new NativeAdbAppTools(this,value -> runOnUiThread(() -> message(value)));
                tools.setInternetEnabled(port,enabled);
                runOnUiThread(() -> {
                    appsOperationRunning=false;
                    pendingInternetEnabled=null;
                    refreshApps();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appsOperationRunning=false;
                    message("Не удалось изменить доступ к интернету: "+e.getClass().getSimpleName()+": "+e.getMessage());
                    Log.e("HuAdbApps","set internet "+enabled,e);
                    refreshApps();
                });
            }
        },"app-internet-setting").start();
    }

    private int adbPort() {
        if (endpoint==null || endpoint.isEmpty()) return 0;
        int separator=endpoint.lastIndexOf(':');
        if (separator<0 || separator==endpoint.length()-1) return 0;
        try { int port=Integer.parseInt(endpoint.substring(separator+1)); return port>0 && port<=65535 ? port : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private void message(String value) { note.setText(value); }
    private boolean ourService(String entry) { ComponentName n=ComponentName.unflattenFromString(entry); return n!=null && n.equals(new ComponentName(this,ConsentService.class)); }
    private boolean hasOurService(String setting) { if (setting==null) return false; for (String entry:setting.split(":")) if (ourService(entry)) return true; return false; }
    private String withoutOurService(String setting) { if (setting==null || setting.isEmpty()) return ""; StringBuilder value=new StringBuilder(); for (String entry:setting.split(":")) { if (entry.isEmpty() || ourService(entry)) continue; if (value.length()>0) value.append(':'); value.append(entry); } return value.toString(); }

    private boolean clockPackageInstalled() {
        try { getPackageManager().getPackageInfo(CLOCK_PACKAGE,0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private boolean clockServiceEnabled() {
        String current=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (current==null) return false;
        ComponentName target=ComponentName.unflattenFromString(CLOCK_COMPONENT);
        for (String entry:current.split(":")) {
            ComponentName component=ComponentName.unflattenFromString(entry);
            if (target!=null && target.equals(component)) return true;
        }
        return false;
    }

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
        refreshLocale();
        refreshTime();
        refreshApps();
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

    @Override public void onBackPressed() {
        if (!"home".equals(currentSection)) {
            requestSection("home");
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); nextSearch=0; handler.removeCallbacks(tick); handler.post(tick); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { destroyed=true; generation++; handler.removeCallbacksAndMessages(null); if (timeSync!=null) timeSync.close(); if (discovery!=null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {} super.onDestroy(); }
}
