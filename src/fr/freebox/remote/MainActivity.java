package fr.freebox.remote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashSet;
import java.util.Set;

/** Écran unique : configuration/appairage, puis télécommande. */
public class MainActivity extends Activity {

    private static final int COLOR_BG = 0xFF0B0D12;
    private static final int COLOR_PANEL = 0xFF141821;
    private static final int COLOR_PANEL_ALT = 0xFF1B2130;
    private static final int COLOR_LINE = 0xFF262D3B;
    private static final int COLOR_TEXT = 0xFFF2F4F8;
    private static final int COLOR_MUTED = 0xFF8B93A7;
    private static final int COLOR_ACCENT = 0xFFFF2B46;
    private static final int COLOR_GREEN = 0xFF3DDC84;
    private static final int COLOR_ORANGE = 0xFFFFA726;
    private static final int COLOR_RED = 0xFFFF5C6C;

    private SharedPreferences prefs;
    private ClientCert certificates;
    private RemoteClient client;
    private PairingClient pairing;

    private FrameLayout root;
    private View pairingOverlay;
    private View sheetOverlay;

    private LinearLayout setupScreen;
    private LinearLayout deviceList;
    private ProgressBar scanProgress;
    private TextView scanStatus;
    private TextView scanButton;
    private EditText manualInput;

    private LinearLayout remoteScreen;
    private TextView deviceNameView;
    private TextView statusView;
    private View statusDot;

    private TextView pairingCodeView;
    private LinearLayout pairingKeypad;
    private TextView pairingValidate;
    private final StringBuilder pairingCode = new StringBuilder();
    private boolean pairingCodeEnabled;

    private String currentHost;
    private String currentName;
    private String currentMac;

    private boolean scanning;
    private int clientGeneration;
    private long lastOfflineToast;
    private Runnable repeatRunnable;
    private View repeatTarget;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> knownHosts = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("freebox_remote", MODE_PRIVATE);
        certificates = new ClientCert(getFilesDir());

        root = new FrameLayout(this);
        root.setBackground(screenBackground());
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            EdgeToEdge.apply(root);
        }
        showLoading();

        new Thread(() -> {
            try {
                certificates.ensure();
                runOnUiThread(this::onCertificateReady);
            } catch (Exception error) {
                runOnUiThread(() -> showFatal(error));
            }
        }, "certificate-init").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopClient();
        if (pairing != null) {
            pairing.cancel();
            pairing = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (pairingOverlay != null) {
            cancelPairing();
            return;
        }
        if (sheetOverlay != null) {
            closeSheet();
            return;
        }
        super.onBackPressed();
    }

    // ── démarrage ─────────────────────────────────────────────────────

    private void onCertificateReady() {
        currentHost = prefs.getString("host", null);
        currentName = prefs.getString("name", null);
        currentMac = prefs.getString("mac", null);
        if (currentHost == null) {
            showSetup();
        } else {
            showRemote();
            startClient();
        }
    }

    private void showLoading() {
        root.removeAllViews();
        LinearLayout column = column();
        column.setGravity(Gravity.CENTER);
        TextView title = text("Freebox Remote", 26, COLOR_TEXT, Typeface.BOLD);
        column.addView(title);
        column.addView(space(18));
        ProgressBar progress = new ProgressBar(this);
        column.addView(progress);
        root.addView(column, matchWrap());
    }

    private void showFatal(Exception error) {
        root.removeAllViews();
        LinearLayout column = column();
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(24), dp(24), dp(24), dp(24));
        column.addView(text("Impossible de préparer l'application", 20, COLOR_TEXT, Typeface.BOLD));
        column.addView(space(12));
        TextView detail = text(PairingClient.safeMessage(error), 14, COLOR_MUTED, Typeface.NORMAL);
        detail.setGravity(Gravity.CENTER);
        column.addView(detail);
        root.addView(column, matchWrap());
    }

    // ── écran de configuration ────────────────────────────────────────

    private void showSetup() {
        stopClient();
        detachOverlays();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(null);
        LinearLayout content = column();
        content.setPadding(dp(22), dp(36), dp(22), dp(28));
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER);
        View dot = circle(10, COLOR_ACCENT);
        brand.addView(dot);
        TextView title = text("Freebox Remote", 28, COLOR_TEXT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapWrap();
        titleParams.leftMargin = dp(10);
        brand.addView(title, titleParams);
        content.addView(brand);

        TextView subtitle = text("Télécommande pour Freebox Player (Android TV)", 14, COLOR_MUTED,
                Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(8);
        content.addView(subtitle, subtitleParams);
        content.addView(space(30));

        scanButton = text("Rechercher mon Player", 16, Color.WHITE, Typeface.BOLD);
        styleButton(scanButton, COLOR_ACCENT, dp(14), dp(54));
        scanButton.setOnClickListener(view -> startScan());
        content.addView(scanButton, matchHeight(dp(54)));

        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER);
        scanProgress.setVisibility(View.GONE);
        scanRow.addView(scanProgress, wrapWrap());
        scanStatus = text("", 13, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams scanTextParams = wrapWrap();
        scanTextParams.leftMargin = dp(8);
        scanStatus.setVisibility(View.GONE);
        scanRow.addView(scanStatus, scanTextParams);
        LinearLayout.LayoutParams scanRowParams = matchWrap();
        scanRowParams.topMargin = dp(12);
        content.addView(scanRow, scanRowParams);

        deviceList = column();
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = dp(10);
        content.addView(deviceList, listParams);

        content.addView(space(22));
        content.addView(divider("ou"));
        content.addView(space(16));

        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setOrientation(LinearLayout.HORIZONTAL);
        manualRow.setGravity(Gravity.CENTER_VERTICAL);
        manualInput = new EditText(this);
        manualInput.setHint("Adresse IP du Player");
        manualInput.setHintTextColor(0xFF5A6377);
        manualInput.setTextColor(COLOR_TEXT);
        manualInput.setTextSize(15);
        manualInput.setSingleLine(true);
        manualInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualInput.setBackground(shape(COLOR_PANEL, dp(12)));
        manualInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        manualRow.addView(manualInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView connect = text("OK", 15, Color.WHITE, Typeface.BOLD);
        styleButton(connect, COLOR_PANEL_ALT, dp(12), dp(48));
        LinearLayout.LayoutParams connectParams = wrapHeight(dp(48));
        connectParams.leftMargin = dp(8);
        connectButtonAction(connect);
        manualRow.addView(connect, connectParams);
        content.addView(manualRow, matchWrap());

        content.addView(space(24));
        TextView hint = text(
                "L'application parle directement au Player, sans agent ni compte : "
                        + "le téléphone et la TV doivent être sur le même Wi-Fi.",
                12, COLOR_MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        content.addView(hint, matchWrap());

        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void connectButtonAction(TextView button) {
        button.setOnClickListener(view -> {
            String host = manualInput.getText().toString().trim()
                    .replace("http://", "")
                    .replace("https://", "");
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            if (!isIpv4(host)) {
                toast("Saisissez une adresse IPv4, par exemple 192.168.1.20");
                return;
            }
            beginPairing(new Discovery.Device(host, null, null));
        });
    }

    private void startScan() {
        if (scanning) {
            return;
        }
        scanning = true;
        knownHosts.clear();
        deviceList.removeAllViews();
        scanButton.setEnabled(false);
        scanButton.setAlpha(0.6f);
        scanProgress.setVisibility(View.VISIBLE);
        scanStatus.setVisibility(View.VISIBLE);
        scanStatus.setText("Recherche sur le réseau…");

        new Thread(() -> {
            Discovery.scan(certificates, device ->
                    runOnUiThread(() -> addDevice(device, false)));
            runOnUiThread(() -> {
                scanning = false;
                if (scanButton != null) {
                    scanButton.setEnabled(true);
                    scanButton.setAlpha(1f);
                }
                if (scanProgress != null) {
                    scanProgress.setVisibility(View.GONE);
                }
                if (scanStatus != null) {
                    scanStatus.setText(knownHosts.isEmpty()
                            ? "Aucun Player trouvé. Vérifiez que la TV est allumée et sur le même Wi-Fi."
                            : "Touchez votre Player dans la liste.");
                }
            });
        }, "discovery").start();
    }

    private void addDevice(Discovery.Device device, boolean fromScan) {
        if (deviceList == null || knownHosts.contains(device.host)) {
            return;
        }
        knownHosts.add(device.host);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(ripple(COLOR_PANEL, dp(12)));

        LinearLayout labels = column();
        labels.addView(text(device.label(), 15, COLOR_TEXT, Typeface.BOLD));
        TextView address = text(device.mac == null ? device.host : device.host + "  ·  " + device.mac,
                11, COLOR_MUTED, Typeface.NORMAL);
        labels.addView(address);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text("›", 22, COLOR_MUTED, Typeface.NORMAL);
        row.addView(chevron, wrapWrap());

        row.setOnClickListener(view -> beginPairing(device));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        deviceList.addView(row, params);
    }

    // ── appairage ─────────────────────────────────────────────────────

    private void beginPairing(Discovery.Device device) {
        detachOverlays();
        if (pairing != null) {
            pairing.cancel();
        }
        pairingCode.setLength(0);
        pairingCodeEnabled = false;

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xD9000000);
        overlay.setClickable(true);

        LinearLayout panel = column();
        panel.setBackground(topRounded(COLOR_PANEL, dp(24)));
        panel.setPadding(dp(20), dp(18), dp(20), dp(20));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Appairage", 19, COLOR_TEXT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 18, COLOR_MUTED, Typeface.NORMAL);
        close.setPadding(dp(10), dp(4), dp(2), dp(4));
        close.setOnClickListener(view -> cancelPairing());
        titleRow.addView(close, wrapWrap());
        panel.addView(titleRow, matchWrap());

        TextView target = text("Player " + device.label(), 13, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams targetParams = matchWrap();
        targetParams.topMargin = dp(4);
        panel.addView(target, targetParams);

        // Affichage du code : plus de clavier système, uniquement un pavé hexadécimal.
        LinearLayout codeRow = new LinearLayout(this);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams codeRowParams = matchWrap();
        codeRowParams.topMargin = dp(14);

        pairingCodeView = text("", 26, COLOR_TEXT, Typeface.BOLD);
        pairingCodeView.setTypeface(Typeface.MONOSPACE);
        pairingCodeView.setGravity(Gravity.CENTER);
        pairingCodeView.setLetterSpacing(0.16f);
        pairingCodeView.setBackground(shape(COLOR_PANEL_ALT, dp(14)));
        pairingCodeView.setPadding(dp(10), dp(12), dp(10), dp(12));
        codeRow.addView(pairingCodeView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView erase = text("⌫", 20, COLOR_MUTED, Typeface.NORMAL);
        styleButton(erase, COLOR_PANEL_ALT, dp(14), dp(52));
        erase.setWidth(dp(52));
        erase.setOnClickListener(view -> deletePairingDigit());
        erase.setOnLongClickListener(view -> {
            if (pairingCodeEnabled) {
                pairingCode.setLength(0);
                updatePairingCode();
            }
            return true;
        });
        LinearLayout.LayoutParams eraseParams = wrapHeight(dp(52));
        eraseParams.leftMargin = dp(8);
        codeRow.addView(erase, eraseParams);
        panel.addView(codeRow, codeRowParams);

        pairingKeypad = column();
        LinearLayout.LayoutParams keypadParams = matchWrap();
        keypadParams.topMargin = dp(8);
        panel.addView(pairingKeypad, keypadParams);

        final String hex = "0123456789ABCDEF";
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 4; column++) {
                final char digit = hex.charAt(row * 4 + column);
                TextView key = text(String.valueOf(digit), 18, COLOR_TEXT, Typeface.BOLD);
                styleButton(key, COLOR_PANEL_ALT, dp(10), dp(44));
                key.setOnClickListener(view -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    appendPairingDigit(digit);
                });
                LinearLayout.LayoutParams keyParams =
                        new LinearLayout.LayoutParams(0, dp(44), 1f);
                keyParams.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(key, keyParams);
            }
            pairingKeypad.addView(line, matchWrap());
        }

        pairingValidate = text("Valider le code", 15, Color.WHITE, Typeface.BOLD);
        styleButton(pairingValidate, COLOR_ACCENT, dp(12), dp(50));
        pairingValidate.setOnClickListener(view -> submitPairingCode());
        LinearLayout.LayoutParams validateParams = matchHeight(dp(50));
        validateParams.topMargin = dp(10);
        panel.addView(pairingValidate, validateParams);

        final TextView status = text("Préparation de la session…", 12, COLOR_MUTED,
                Typeface.NORMAL);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(10);
        panel.addView(status, statusParams);

        final TextView error = text("", 12, COLOR_RED, Typeface.NORMAL);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = matchWrap();
        errorParams.topMargin = dp(4);
        panel.addView(error, errorParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(12);
        final TextView retry = text("Recommencer", 14, COLOR_TEXT, Typeface.BOLD);
        styleButton(retry, COLOR_PANEL_ALT, dp(12), dp(44));
        retry.setVisibility(View.GONE);
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView cancel = text("Annuler", 14, COLOR_MUTED, Typeface.BOLD);
        styleButton(cancel, 0x00000000, dp(12), dp(44));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        cancelParams.leftMargin = dp(8);
        cancel.setOnClickListener(view -> cancelPairing());
        actions.addView(cancel, cancelParams);
        panel.addView(actions, actionsParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.BOTTOM;
        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setFillViewport(false);
        panelScroll.setVerticalScrollBarEnabled(false);
        panelScroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(panelScroll, panelParams);
        overlay.setOnClickListener(view -> {
            // Un appui hors du panneau n'annule pas l'appairage par erreur.
        });

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pairingOverlay = overlay;

        final PairingClient session = new PairingClient(device.host, certificates,
                new PairingClient.Listener() {
                    @Override
                    public void onReady(String serverName) {
                        runOnUiThread(() -> {
                            if (pairingOverlay != overlay) {
                                return;
                            }
                            if (serverName != null && !serverName.isEmpty()) {
                                currentName = serverName;
                            }
                            status.setText("Saisissez le code à 6 caractères (0-9, A-F) "
                                    + "affiché sur la TV.");
                            error.setVisibility(View.GONE);
                            setPairingCodeEnabled(true);
                        });
                    }

                    @Override
                    public void onPaired() {
                        runOnUiThread(() -> {
                            if (pairingOverlay != overlay) {
                                return;
                            }
                            finishPairing(device, pairingCode.toString());
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (pairingOverlay != overlay) {
                                return;
                            }
                            error.setText(message);
                            error.setVisibility(View.VISIBLE);
                            retry.setVisibility(View.VISIBLE);
                            if (pairing != null && pairing.isAlive()) {
                                pairingCode.setLength(0);
                                setPairingCodeEnabled(true);
                                status.setText("Code effacé : vérifiez les caractères "
                                        + "affichés sur la TV et réessayez.");
                            } else {
                                setPairingCodeEnabled(false);
                                status.setText("Session terminée : recommencez pour obtenir "
                                        + "un nouveau code.");
                            }
                        });
                    }
                });
        pairing = session;
        session.start();

        retry.setOnClickListener(view -> beginPairing(device));
        updatePairingCode();
    }

    private void appendPairingDigit(char digit) {
        if (!pairingCodeEnabled || pairingCode.length() >= 6) {
            return;
        }
        pairingCode.append(digit);
        updatePairingCode();
    }

    private void deletePairingDigit() {
        if (!pairingCodeEnabled || pairingCode.length() == 0) {
            return;
        }
        pairingCode.setLength(pairingCode.length() - 1);
        updatePairingCode();
    }

    private void setPairingCodeEnabled(boolean enabled) {
        pairingCodeEnabled = enabled;
        updatePairingCode();
    }

    private void updatePairingCode() {
        if (pairingCodeView != null) {
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) {
                    display.append(' ');
                }
                display.append(i < pairingCode.length() ? pairingCode.charAt(i) : '·');
            }
            pairingCodeView.setText(display.toString());
        }
        boolean ready = pairingCodeEnabled && pairingCode.length() == 6;
        if (pairingValidate != null) {
            pairingValidate.setEnabled(ready);
            pairingValidate.setAlpha(ready ? 1f : 0.45f);
        }
        if (pairingKeypad != null) {
            pairingKeypad.setAlpha(pairingCodeEnabled ? 1f : 0.4f);
        }
    }

    private void submitPairingCode() {
        if (!pairingCodeEnabled || pairingCode.length() != 6
                || pairing == null || !pairing.isAlive()) {
            return;
        }
        final PairingClient current = pairing;
        final String code = pairingCode.toString();
        setPairingCodeEnabled(false);
        current.submitCode(code);
    }

    private void finishPairing(Discovery.Device device, String code) {
        pairing = null;
        String name = currentName != null ? currentName : device.label();
        prefs.edit()
                .putString("host", device.host)
                .putString("name", name)
                .putString("mac", device.mac)
                .apply();
        currentHost = device.host;
        currentName = name;
        currentMac = device.mac;
        hideKeyboard();
        detachOverlays();
        toast("Player appairé. Bonne séance !");
        showRemote();
        // Laisse au Player le temps d'enregistrer le secret avant la reconnexion.
        handler.postDelayed(this::startClient, 350);
    }

    private void cancelPairing() {
        if (pairing != null) {
            pairing.cancel();
            pairing = null;
        }
        hideKeyboard();
        detachOverlays();
    }

    // ── télécommande ──────────────────────────────────────────────────

    private void showRemote() {
        detachOverlays();
        root.removeAllViews();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout screen = column();

        // En-tête : tuile logo, nom et état du Player, accès aux réglages.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(14), dp(12));

        FrameLayout logoTile = new FrameLayout(this);
        logoTile.setBackground(rippleWithStroke(COLOR_PANEL_ALT, 0x40FF2B46, dp(15), dp(1)));
        IconView logo = new IconView(this).icon(IconView.LOGO).tint(COLOR_ACCENT)
                .content(0.62f);
        logoTile.addView(logo, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        header.addView(logoTile, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout names = column();
        deviceNameView = text(currentName != null ? currentName : "Freebox Player", 16,
                COLOR_TEXT, Typeface.BOLD);
        names.addView(deviceNameView);

        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setBackground(shape(COLOR_PANEL_ALT, dp(999)));
        statusPill.setPadding(dp(9), dp(3), dp(11), dp(3));
        statusDot = circle(7, COLOR_ORANGE);
        statusPill.addView(statusDot);
        statusView = text("Connexion…", 11, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams statusParams = wrapWrap();
        statusParams.leftMargin = dp(6);
        statusPill.addView(statusView, statusParams);
        LinearLayout.LayoutParams pillParams = wrapWrap();
        pillParams.topMargin = dp(5);
        names.addView(statusPill, pillParams);

        LinearLayout.LayoutParams namesParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesParams.leftMargin = dp(12);
        header.addView(names, namesParams);

        IconView gear = new IconView(this).icon(IconView.GEAR).tint(COLOR_MUTED)
                .content(0.44f);
        gear.setBackground(ripple(0x00000000, dp(999)));
        gear.setOnClickListener(view -> showSettingsSheet());
        header.addView(gear, new LinearLayout.LayoutParams(dp(42), dp(42)));
        screen.addView(header, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(16), dp(2), dp(16), dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Rangée rapide : marche/arrêt, TV, muet, touches spéciales.
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER);
        quick.addView(iconKey(IconView.POWER, 58, COLOR_ACCENT, Color.WHITE, false,
                KeyCodes.POWER, false));
        quick.addView(iconKey(IconView.TV, 58, COLOR_PANEL, COLOR_TEXT, false,
                KeyCodes.TV, false));
        quick.addView(iconKey(IconView.MUTE, 58, COLOR_PANEL, COLOR_TEXT, false,
                KeyCodes.MUTE, false));
        quick.addView(iconAction(IconView.MORE, 58, COLOR_PANEL, COLOR_TEXT, false,
                this::showExtrasSheet));
        content.addView(quick, matchWrap());
        content.addView(space(14));

        // Carte principale : volume, pavé directionnel, chaînes.
        LinearLayout card = column();
        card.setBackground(cardDrawable(dp(30), 0xFF171C27, 0xFF11151E));
        card.setPadding(dp(10), dp(14), dp(10), dp(14));
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER);
        middle.addView(rocker("VOL", KeyCodes.VOLUME_UP, KeyCodes.VOLUME_DOWN), wrapWrap());

        DpadView dpad = new DpadView(this, this::sendKey);
        LinearLayout.LayoutParams dpadParams = new LinearLayout.LayoutParams(0, dp(240), 1f);
        dpadParams.leftMargin = dp(4);
        dpadParams.rightMargin = dp(4);
        middle.addView(dpad, dpadParams);
        middle.addView(rocker("CHA", KeyCodes.CHANNEL_UP, KeyCodes.CHANNEL_DOWN), wrapWrap());
        card.addView(middle, matchWrap());
        content.addView(card, matchWrap());
        content.addView(space(14));

        // Navigation.
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.addView(navButton(IconView.BACK, "Retour", KeyCodes.BACK), navParams());
        navigation.addView(navButton(IconView.HOME, "Accueil", KeyCodes.HOME), navParams());
        navigation.addView(navButton(IconView.MENU, "Menu", KeyCodes.MENU), navParams());
        content.addView(navigation, matchWrap());
        content.addView(space(12));

        // Média.
        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.HORIZONTAL);
        media.setGravity(Gravity.CENTER);
        media.addView(iconKey(IconView.REWIND, 46, COLOR_PANEL, COLOR_TEXT, false,
                KeyCodes.REWIND, true));
        media.addView(iconKey(IconView.PLAY_PAUSE, 58, COLOR_PANEL_ALT, COLOR_ACCENT, true,
                KeyCodes.PLAY_PAUSE, false));
        media.addView(iconKey(IconView.FORWARD, 46, COLOR_PANEL, COLOR_TEXT, false,
                KeyCodes.FORWARD, true));
        media.addView(iconKey(IconView.STOP, 46, COLOR_PANEL, COLOR_TEXT, false,
                KeyCodes.STOP, false));
        media.addView(iconKey(IconView.RECORD, 46, COLOR_PANEL, COLOR_RED, false,
                KeyCodes.RECORD, false));
        content.addView(media, matchWrap());

        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Dock flottant.
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(dp(6), dp(6), dp(6), dp(6));
        dock.setBackground(cardDrawable(dp(24), 0xFF181D29, 0xFF11151E));
        dock.setElevation(dp(10));
        dock.addView(dockItem(IconView.REMOTE, "Télécommande", true, null), dockParams());
        dock.addView(dockItem(IconView.KEYPAD, "Clavier", false, this::showNumpadSheet),
                dockParams());
        dock.addView(dockItem(IconView.APPS, "Apps", false, this::showAppsSheet), dockParams());
        dock.addView(dockItem(IconView.GEAR, "Réglages", false, this::showSettingsSheet),
                dockParams());
        LinearLayout.LayoutParams dockMargins = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dockMargins.setMargins(dp(16), 0, dp(16), dp(12));
        screen.addView(dock, dockMargins);

        root.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        remoteScreen = screen;
        if (client != null && client.isConnected()) {
            updateStatus(RemoteClient.STATUS_CONNECTED, null);
        }
    }

    private void stopClient() {
        clientGeneration++;
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    private void startClient() {
        if (currentHost == null) {
            return;
        }
        stopClient();
        final int generation = ++clientGeneration;
        client = new RemoteClient(currentHost, certificates, new RemoteClient.Listener() {
            @Override
            public void onStatus(int status, String detail) {
                if (generation != clientGeneration) {
                    return;
                }
                runOnUiThread(() -> updateStatus(status, detail));
            }

            @Override
            public void onPower(boolean on) {
                if (generation != clientGeneration) {
                    return;
                }
                runOnUiThread(() -> {
                    if (statusView == null) {
                        return;
                    }
                    if (on) {
                        statusView.setText("Allumé");
                    } else {
                        statusView.setText("En veille");
                    }
                    if (statusDot != null) {
                        statusDot.setBackground(circleDrawable(9, COLOR_GREEN));
                    }
                });
            }

            @Override
            public void onVolume(int level, int max, boolean muted) {
                // Le volume est affiché par la TV elle-même ; rien à faire ici.
            }
        });
        client.start();
    }

    private void updateStatus(int status, String detail) {
        if (statusView == null || statusDot == null) {
            return;
        }
        switch (status) {
            case RemoteClient.STATUS_CONNECTING:
                statusDot.setBackground(circleDrawable(9, COLOR_ORANGE));
                statusView.setText("Connexion…");
                break;
            case RemoteClient.STATUS_CONNECTED:
                statusDot.setBackground(circleDrawable(9, COLOR_GREEN));
                statusView.setText(Boolean.FALSE.equals(client == null ? null : client.isPowerOn())
                        ? "En veille" : "Connecté");
                break;
            case RemoteClient.STATUS_NEEDS_PAIRING:
                statusDot.setBackground(circleDrawable(9, COLOR_RED));
                statusView.setText("Appairage requis");
                toast("Ce Player doit être appairé de nouveau (Réglages).");
                break;
            case RemoteClient.STATUS_OFFLINE:
            default:
                statusDot.setBackground(circleDrawable(9, COLOR_ORANGE));
                statusView.setText("Hors ligne — reconnexion…");
                break;
        }
    }

    /** Bouton circulaire à icône, relié directement à une touche du Player. */
    private View iconKey(int icon, int sizeDp, int background, int tint, boolean accentBorder,
                         int keyCode, boolean repeatable) {
        IconView button = icon(icon, sizeDp, background, tint, accentBorder);
        bindKey(button, keyCode, repeatable);
        return button;
    }

    /** Bouton circulaire à icône qui ouvre un panneau local. */
    private View iconAction(int icon, int sizeDp, int background, int tint,
                            boolean accentBorder, Runnable action) {
        IconView button = icon(icon, sizeDp, background, tint, accentBorder);
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
        return button;
    }

    private IconView icon(int icon, int sizeDp, int background, int tint,
                          boolean accentBorder) {
        IconView view = new IconView(this).icon(icon).tint(tint).content(0.46f);
        view.setBackground(accentBorder
                ? rippleWithStroke(background, COLOR_ACCENT, dp(999), dp(1))
                : ripple(background, dp(999)));
        view.setClickable(true);
        view.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(sizeDp), dp(sizeDp));
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        view.setLayoutParams(params);
        return view;
    }

    /** Colonne VOL/CHA : pastille verticale avec + en haut et − en bas. */
    private View rocker(String label, int plus, int minus) {
        LinearLayout column = column();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(label, 9, COLOR_MUTED, Typeface.BOLD);
        title.setLetterSpacing(0.18f);
        column.addView(title);
        column.addView(space(7));

        LinearLayout pill = column();
        pill.setBackground(ripple(COLOR_PANEL_ALT, dp(999)));
        pill.addView(rockButton(IconView.PLUS, plus));
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(22), dp(1));
        dividerParams.gravity = Gravity.CENTER_HORIZONTAL;
        pill.addView(divider, dividerParams);
        pill.addView(rockButton(IconView.MINUS, minus));
        column.addView(pill, wrapWrap());
        return column;
    }

    private View rockButton(int icon, int keyCode) {
        IconView button = new IconView(this).icon(icon).tint(COLOR_TEXT).content(0.40f);
        button.setBackground(ripple(0x12FFFFFF, dp(999)));
        button.setClickable(true);
        button.setFocusable(true);
        bindKey(button, keyCode, true);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(54)));
        return button;
    }

    private View navButton(int icon, String caption, int keyCode) {
        LinearLayout button = column();
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(COLOR_PANEL, dp(18)));
        button.setClickable(true);
        button.setFocusable(true);
        IconView glyph = new IconView(this).icon(icon).tint(COLOR_TEXT).content(0.44f);
        button.addView(glyph, new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView label = text(caption, 10, COLOR_MUTED, Typeface.NORMAL);
        label.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams labelParams = wrapWrap();
        labelParams.topMargin = dp(3);
        button.addView(label, labelParams);
        bindKey(button, keyCode, false);
        return button;
    }

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private View dockItem(int icon, String caption, boolean active, Runnable action) {
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        item.setBackground(ripple(active ? 0x14FF2B46 : 0x00000000, dp(16)));
        IconView glyph = new IconView(this).icon(icon)
                .tint(active ? COLOR_ACCENT : COLOR_MUTED).content(0.46f);
        item.addView(glyph, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView label = text(caption, 9.5f, active ? COLOR_ACCENT : COLOR_MUTED,
                active ? Typeface.BOLD : Typeface.NORMAL);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = wrapWrap();
        labelParams.topMargin = dp(2);
        item.addView(label, labelParams);
        item.setClickable(action != null);
        if (action != null) {
            item.setOnClickListener(view -> action.run());
        }
        return item;
    }

    private LinearLayout.LayoutParams dockParams() {
        return new LinearLayout.LayoutParams(0, dp(56), 1f);
    }

    private void bindKey(View view, int keyCode, boolean repeatable) {
        if (!repeatable) {
            view.setOnClickListener(clicked -> {
                clicked.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                sendKey(keyCode);
            });
            return;
        }
        view.setOnTouchListener((touched, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touched.setPressed(true);
                    touched.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    sendKey(keyCode);
                    startRepeat(touched, keyCode);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touched.setPressed(false);
                    stopRepeat();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void startRepeat(View view, int keyCode) {
        stopRepeat();
        repeatTarget = view;
        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (repeatTarget == view) {
                    sendKey(keyCode);
                    handler.postDelayed(this, 110);
                }
            }
        };
        handler.postDelayed(repeatRunnable, 420);
    }

    private void stopRepeat() {
        if (repeatRunnable != null) {
            handler.removeCallbacks(repeatRunnable);
            repeatRunnable = null;
        }
        repeatTarget = null;
    }

    private void sendKey(int keyCode) {
        if (client == null || !client.isConnected()) {
            long now = System.currentTimeMillis();
            if (now - lastOfflineToast > 2500) {
                lastOfflineToast = now;
                toast(currentHost == null
                        ? "Aucun Player configuré"
                        : "Player hors ligne — reconnexion en cours…");
            }
            return;
        }
        if (!client.sendKey(keyCode, RemoteClient.DIRECTION_SHORT)) {
            toast("Commande non envoyée (connexion perdue).");
        }
    }

    // ── panneaux ──────────────────────────────────────────────────────

    private void showNumpadSheet() {
        LinearLayout content = column();
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                int index = row * 3 + column;
                TextView key;
                int code;
                if (index < 9) {
                    key = text(String.valueOf(index + 1), 20, COLOR_TEXT, Typeface.BOLD);
                    code = KeyCodes.numeric(index + 1);
                } else if (index == 9) {
                    key = text("⌕", 22, COLOR_TEXT, Typeface.NORMAL);
                    code = KeyCodes.SEARCH;
                } else if (index == 10) {
                    key = text("0", 20, COLOR_TEXT, Typeface.BOLD);
                    code = KeyCodes.numeric(0);
                } else {
                    key = text("⌫", 20, COLOR_TEXT, Typeface.NORMAL);
                    code = KeyCodes.DEL;
                }
                styleButton(key, COLOR_PANEL_ALT, dp(12), dp(54));
                bindKey(key, code, false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1f);
                params.setMargins(dp(4), dp(4), dp(4), dp(4));
                line.addView(key, params);
            }
            content.addView(line, matchWrap());
        }

        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(Gravity.CENTER);
        addColor(colors, 0xFFE53935, KeyCodes.RED);
        addColor(colors, 0xFF43A047, KeyCodes.GREEN);
        addColor(colors, 0xFFFDD835, KeyCodes.YELLOW);
        addColor(colors, 0xFF1E88E5, KeyCodes.BLUE);
        LinearLayout.LayoutParams colorParams = matchWrap();
        colorParams.topMargin = dp(10);
        content.addView(colors, colorParams);

        showSheet("Clavier", content);
    }

    private void addColor(LinearLayout parent, int color, int keyCode) {
        View button = new View(this);
        button.setBackground(ripple(color, dp(999)));
        button.setClickable(true);
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sendKey(keyCode);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.leftMargin = dp(10);
        params.rightMargin = dp(10);
        parent.addView(button, params);
    }

    private void showAppsSheet() {
        LinearLayout content = column();
        for (Apps.App app : Apps.ALL) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(ripple(COLOR_PANEL_ALT, dp(12)));
            row.addView(text(app.name, 15, COLOR_TEXT, Typeface.BOLD),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text("›", 20, COLOR_MUTED, Typeface.NORMAL));
            row.setOnClickListener(view -> {
                if (client != null && client.launch(app.link)) {
                    toast("Ouverture de " + app.name + "…");
                } else {
                    toast("Player hors ligne");
                }
                closeSheet();
            });
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(6);
            content.addView(row, params);
        }
        TextView hint = text("Les applications doivent être installées sur le Player.", 11,
                COLOR_MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(12);
        content.addView(hint, hintParams);
        showSheet("Applications", content);
    }

    private void showExtrasSheet() {
        LinearLayout content = column();
        content.addView(extraRow(IconView.GUIDE, "Guide", KeyCodes.GUIDE,
                IconView.INFO, "Info", KeyCodes.INFO), extraRowParams());
        content.addView(extraRow(IconView.MENU, "Menu", KeyCodes.MENU,
                IconView.SEARCH, "Recherche", KeyCodes.SEARCH), extraRowParams());
        content.addView(extraRow(IconView.RECORD, "Enregistrer", KeyCodes.RECORD,
                IconView.GEAR, "Réglages TV", KeyCodes.SETTINGS), extraRowParams());

        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(Gravity.CENTER);
        addColor(colors, 0xFFE53935, KeyCodes.RED);
        addColor(colors, 0xFF43A047, KeyCodes.GREEN);
        addColor(colors, 0xFFFDD835, KeyCodes.YELLOW);
        addColor(colors, 0xFF1E88E5, KeyCodes.BLUE);
        LinearLayout.LayoutParams colorParams = matchWrap();
        colorParams.topMargin = dp(16);
        content.addView(colors, colorParams);

        TextView note = text("Guide ouvre le programme TV de l'application affichée, "
                + "Info montre les détails de l'émission en cours. Ces touches ne "
                + "répondent que si l'application les prend en charge : Netflix, YouTube "
                + "ou Prime Video, par exemple, les ignorent.", 11, COLOR_MUTED,
                Typeface.NORMAL);
        note.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(14);
        content.addView(note, noteParams);

        showSheet("Touches spéciales", content);
    }

    private View extraRow(int icon1, String label1, int code1,
                          int icon2, String label2, int code2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(extraButton(icon1, label1, code1), extraParams(4));
        row.addView(extraButton(icon2, label2, code2), extraParams(4));
        return row;
    }

    private View extraButton(int icon, String label, int keyCode) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(ripple(COLOR_PANEL_ALT, dp(14)));
        IconView glyph = new IconView(this).icon(icon).tint(COLOR_ACCENT).content(0.40f);
        button.addView(glyph, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView caption = text(label, 13, COLOR_TEXT, Typeface.BOLD);
        LinearLayout.LayoutParams captionParams = wrapWrap();
        captionParams.leftMargin = dp(10);
        button.addView(caption, captionParams);
        bindKey(button, keyCode, false);
        return button;
    }

    private LinearLayout.LayoutParams extraParams(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.leftMargin = dp(margin);
        params.rightMargin = dp(margin);
        return params;
    }

    private LinearLayout.LayoutParams extraRowParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(4);
        return params;
    }

    private void showSettingsSheet() {
        LinearLayout content = column();

        TextView player = text(currentName != null ? currentName : "Aucun Player", 17,
                COLOR_TEXT, Typeface.BOLD);
        content.addView(player);
        TextView address = text(currentHost == null ? "—" : currentHost
                + (currentMac == null ? "" : "  ·  " + currentMac), 12, COLOR_MUTED,
                Typeface.NORMAL);
        content.addView(address);

        TextView state = text(client != null && client.isConnected() ? "Connecté" : "Hors ligne",
                12, client != null && client.isConnected() ? COLOR_GREEN : COLOR_ORANGE,
                Typeface.BOLD);
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(6);
        content.addView(state, stateParams);

        TextView reconnect = text("Reconnecter", 14, COLOR_TEXT, Typeface.BOLD);
        styleButton(reconnect, COLOR_PANEL_ALT, dp(12), dp(48));
        reconnect.setOnClickListener(view -> {
            closeSheet();
            toast("Reconnexion…");
            startClient();
        });
        LinearLayout.LayoutParams reconnectParams = matchHeight(dp(48));
        reconnectParams.topMargin = dp(18);
        content.addView(reconnect, reconnectParams);

        TextView forget = text("Changer de Player", 14, COLOR_RED, Typeface.BOLD);
        styleButton(forget, COLOR_PANEL_ALT, dp(12), dp(48));
        forget.setOnClickListener(view -> confirmForget());
        LinearLayout.LayoutParams forgetParams = matchHeight(dp(48));
        forgetParams.topMargin = dp(8);
        content.addView(forget, forgetParams);

        TextView about = text("Freebox Remote " + appVersion()
                        + " — Android TV Remote v2\nAucune donnée ne quitte le réseau local.",
                11, COLOR_MUTED, Typeface.NORMAL);
        about.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aboutParams = matchWrap();
        aboutParams.topMargin = dp(16);
        content.addView(about, aboutParams);

        showSheet("Réglages", content);
    }

    private void confirmForget() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle("Changer de Player ?");
        builder.setMessage("Le Player actuel sera oublié. Il faudra refaire l'appairage.");
        builder.setPositiveButton("Oublier", (dialog, which) -> forgetPlayer());
        builder.setNegativeButton("Annuler", null);
        builder.show();
    }

    private void forgetPlayer() {
        stopClient();
        if (pairing != null) {
            pairing.cancel();
            pairing = null;
        }
        prefs.edit().remove("host").remove("name").remove("mac").apply();
        currentHost = null;
        currentName = null;
        currentMac = null;
        closeSheet();
        showSetup();
        toast("Player oublié");
    }

    private void showSheet(String title, View content) {
        if (sheetOverlay != null) {
            root.removeView(sheetOverlay);
        }
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB3000000);
        overlay.setClickable(true);
        overlay.setOnClickListener(view -> closeSheet());

        LinearLayout panel = column();
        panel.setBackground(topRoundedWithStroke(COLOR_PANEL, dp(26), COLOR_LINE));
        panel.setPadding(dp(20), dp(10), dp(20), dp(24));
        panel.setClickable(true);
        panel.setElevation(dp(16));

        View grabber = new View(this);
        grabber.setBackground(shape(COLOR_LINE, dp(999)));
        LinearLayout.LayoutParams grabberParams = new LinearLayout.LayoutParams(dp(38), dp(4));
        grabberParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabberParams.bottomMargin = dp(14);
        panel.addView(grabber, grabberParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(title, 18, COLOR_TEXT, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 18, COLOR_MUTED, Typeface.NORMAL);
        close.setPadding(dp(10), dp(4), dp(2), dp(4));
        close.setOnClickListener(view -> closeSheet());
        titleRow.addView(close, wrapWrap());
        panel.addView(titleRow, matchWrap());

        LinearLayout.LayoutParams contentParams = matchWrap();
        contentParams.topMargin = dp(12);
        panel.addView(content, contentParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.BOTTOM;
        overlay.addView(panel, panelParams);

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheetOverlay = overlay;
    }

    private void closeSheet() {
        if (sheetOverlay != null) {
            root.removeView(sheetOverlay);
            sheetOverlay = null;
        }
    }

    private void detachOverlays() {
        closeSheet();
        if (pairingOverlay != null) {
            root.removeView(pairingOverlay);
            pairingOverlay = null;
        }
        pairingCodeView = null;
        pairingKeypad = null;
        pairingValidate = null;
        pairingCode.setLength(0);
        pairingCodeEnabled = false;
    }

    // ── outils UI ─────────────────────────────────────────────────────

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, float sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private Space space(int heightDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return space;
    }

    private View divider(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View left = new View(this);
        left.setBackgroundColor(COLOR_LINE);
        row.addView(left, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView text = text(label, 11, COLOR_MUTED, Typeface.NORMAL);
        text.setPadding(dp(12), 0, dp(12), 0);
        row.addView(text, wrapWrap());
        View right = new View(this);
        right.setBackgroundColor(COLOR_LINE);
        row.addView(right, new LinearLayout.LayoutParams(0, dp(1), 1f));
        return row;
    }

    private void styleButton(TextView button, int background, int radius, int height) {
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(background, radius));
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height));
    }

    /** Dégradé très sombre qui sert de fond à toute l'application. */
    private android.graphics.drawable.Drawable screenBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF131828, 0xFF0C0F18, 0xFF07090D});
    }

    /** Carte arrondie avec un dégradé vertical et un liseré discret. */
    private android.graphics.drawable.Drawable cardDrawable(int radius, int top, int bottom) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), COLOR_LINE);
        return drawable;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "1.4";
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height);
    }

    private android.graphics.drawable.Drawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private android.graphics.drawable.Drawable topRounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    private android.graphics.drawable.Drawable topRoundedWithStroke(int color, int radius,
                                                                   int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private GradientDrawable circleDrawable(int sizeDp, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setSize(dp(sizeDp), dp(sizeDp));
        return drawable;
    }

    private View circle(int sizeDp, int color) {
        View view = new View(this);
        view.setBackground(circleDrawable(sizeDp, color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        view.setLayoutParams(params);
        return view;
    }

    private android.graphics.drawable.Drawable ripple(int color, int radius) {
        android.graphics.drawable.Drawable background = shape(color, radius);
        if (color == 0) {
            background = shape(0x00000000, radius);
        }
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                background, null);
    }

    private android.graphics.drawable.Drawable rippleWithStroke(int color, int stroke, int radius,
                                                                 int strokeWidth) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        background.setStroke(strokeWidth, stroke);
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                background, null);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    private boolean isIpv4(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            try {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) {
                    return false;
                }
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * À partir d'Android 15 (API 35), l'affichage bord à bord est imposé aux
     * applications ciblant cette version. On décale le contenu sous les barres
     * système et le clavier. Classe séparée pour ne pas charger les API
     * récentes sur les anciens Android.
     */
    private static final class EdgeToEdge {
        static void apply(final View root) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
    }

}
