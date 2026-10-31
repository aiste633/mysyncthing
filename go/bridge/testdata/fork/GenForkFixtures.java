// Copyright (C) 2026 mysyncthing contributors.
// SPDX-License-Identifier: AGPL-3.0-or-later

// Writes Syncthing-Fork backup files the way Fork does (SyncthingService.exportConfig):
// the preferences map with ObjectOutputStream, the zip with zip4j. Synthetic content
// only. Run through gen.sh, which supplies zip4j on the class path.
//
//   prefs <out.dat> <variant>                  variants: full, minimal, proxy, typed
//   zip <dir> <out.zip> [password]             Fork's export of a config dir
//   golden <outdir>                            AES zips of known text for the reader tests

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.AesVersion;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

public class GenForkFixtures {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "prefs" -> prefs(a[1], a[2]);
            case "zip" -> forkZip(new File(a[1]), new File(a[2]), a.length > 3 ? a[3] : "");
            case "golden" -> golden(new File(a[1]));
            case "unzip" -> unzip(new File(a[1]), new File(a[2]), a.length > 3 ? a[3] : ""); // reads the app's export, as Fork would
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    /** The peer's ID in the fixtures: what Go's protocol.NewDeviceID([]byte(PEER_SEED)) gives. */
    static final String PEER_SEED = "fork-fixture-peer";

    static String deviceID(byte[] raw) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
        StringBuilder b32 = new StringBuilder();
        for (int bits = 0, buf = 0, i = 0; i < h.length || bits > 0; ) { // RFC 4648, no padding
            if (bits < 5) {
                if (i < h.length) { buf = (buf << 8) | (h[i++] & 0xff); bits += 8; }
                else { buf <<= 5 - bits; bits = 5; }
            }
            b32.append(alphabet.charAt((buf >> (bits - 5)) & 31));
            bits -= 5;
        }
        StringBuilder id = new StringBuilder();
        for (int g = 0; g < 4; g++) { // each 13-character group gets a Luhn mod-32 check character
            String part = b32.substring(g * 13, g * 13 + 13);
            int factor = 1, sum = 0;
            for (char c : part.toCharArray()) {
                int addend = factor * alphabet.indexOf(c);
                factor = factor == 2 ? 1 : 2;
                sum += addend / 32 + addend % 32;
            }
            id.append(part).append(alphabet.charAt((32 - sum % 32) % 32));
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 56; i += 7) out.append(i == 0 ? "" : "-").append(id, i, i + 7);
        return out.toString();
    }

    static void prefs(String out, String variant) throws Exception {
        String peer = deviceID(PEER_SEED.getBytes(StandardCharsets.UTF_8));
        HashMap<String, Object> m = new HashMap<>();
        switch (variant) {
            case "minimal" -> { // like a recent Fork that stores changed keys only
                m.put("app_start_counter", 3);
                m.put("btnStateForceStartStop", 0);
                m.put("knownWifiSsids", new HashSet<String>());
                m.put("last_run_time", 1234L);
                m.put("run_on_mobile_data", false);
            }
            case "full" -> { // every kind of key an older Fork keeps
                m.put("always_run_in_background", true);
                m.put("app_theme", "-1");
                m.put("backup_folder_name", "backups");
                m.put("btnStateForceStartStop", 2);
                m.put("debug_facilities_enabled", new HashSet<String>());
                m.put("expert_mode", true);
                m.put("power_source", "ac_power");
                m.put("respect_battery_saving", false);
                m.put("respect_master_sync", true);
                m.put("run_in_flight_mode", true);
                m.put("run_on_metered_wifi", true);
                m.put("run_on_mobile_data", true);
                m.put("run_on_roaming", true);
                m.put("run_on_time_schedule", false);
                m.put("run_on_wifi", true);
                m.put("sync_duration_minutes", "5");
                m.put("use_wifi_whitelist", true);
                m.put("wifi_ssid_whitelist", new HashSet<>(List.of("\"ExampleNet\"", "\"Example 5G\"")));
                m.put("webui_password", "not-a-real-password-0123456789ab");
                m.put("environment_variables", "STTRACE=model");
                // A folder ID with underscores, as Fork allows.
                m.put("sc_folder_photo_backup_01_custom_sync_conditions", true);
                m.put("sc_folder_photo_backup_01_run_on_wifi", true);
                m.put("sc_folder_photo_backup_01_run_script", true);
                m.put("sc_folder_photo_backup_01_wifi_ssid_whitelist", new HashSet<>(List.of("\"ExampleNet\"")));
                m.put("sc_device_" + peer + "_custom_sync_conditions", true);
                m.put("device_lastseen_" + peer, "2026-01-02T03:04:05+0100");
            }
            case "proxy" -> { // Fork with a SOCKS proxy (adb reverse makes the PC's port 1080 local), on battery only
                m.put("power_source", "battery_power");
                m.put("run_on_metered_wifi", false);
                m.put("run_on_mobile_data", true);
                m.put("run_on_roaming", false);
                m.put("socks_proxy_address", "socks5://127.0.0.1:1080");
            }
            case "typed" -> { // each value type once, for the reader
                m.put("bool", true);
                m.put("int", -7);
                m.put("long", 1L << 40);
                m.put("float", 1.5f);
                m.put("string", "grüße ✓");
                m.put("empty", "");
                m.put("set", new HashSet<>(List.of("a", "b")));
                m.put("same", "string"); // a repeated value is written as a back-reference
            }
            default -> throw new IllegalArgumentException(variant);
        }
        try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(out))) {
            o.writeObject(m);
        }
    }

    // The entries and settings of Fork's exportConfig.
    static void forkZip(File dir, File out, String password) throws Exception {
        out.delete();
        ZipParameters p = new ZipParameters();
        p.setCompressionMethod(CompressionMethod.DEFLATE);
        p.setCompressionLevel(CompressionLevel.NORMAL);
        ZipFile z;
        if (password.isEmpty()) {
            z = new ZipFile(out);
        } else {
            z = new ZipFile(out, password.toCharArray());
            p.setEncryptFiles(true);
            p.setEncryptionMethod(EncryptionMethod.AES);
            p.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }
        for (String name : Arrays.asList("config.xml", "key.pem", "cert.pem", "https-cert.pem", "https-key.pem",
                "sharedpreferences.dat", "index-v2")) {
            File f = new File(dir, name);
            if (f.isFile()) z.addFile(f, p);
            else if (f.isDirectory()) z.addFolder(f, p);
        }
        z.close();
    }

    static void golden(File dir) throws Exception {
        dir.mkdirs();
        byte[] text = "Syncthing-Fork backup reader test\n".getBytes(StandardCharsets.UTF_8);
        byte[] big = new byte[100_000]; // many AES blocks, compressible
        for (int i = 0; i < big.length; i++) big[i] = (byte) ("0123456789abcdef".charAt(i % 16) ^ (i / 997));
        golden(new File(dir, "aes256-ae2.zip"), AesKeyStrength.KEY_STRENGTH_256, AesVersion.TWO, text, big);
        golden(new File(dir, "aes128-ae1.zip"), AesKeyStrength.KEY_STRENGTH_128, AesVersion.ONE, text, big);
    }

    static void golden(File out, AesKeyStrength strength, AesVersion version, byte[] text, byte[] big) throws Exception {
        out.delete();
        try (ZipFile z = new ZipFile(out, "test-password".toCharArray())) {
            for (Object[] e : new Object[][] {
                {"deflated.txt", CompressionMethod.DEFLATE, text},
                {"stored.txt", CompressionMethod.STORE, text},
                {"big.bin", CompressionMethod.DEFLATE, big},
            }) {
                ZipParameters p = new ZipParameters();
                p.setFileNameInZip((String) e[0]);
                p.setCompressionMethod((CompressionMethod) e[1]);
                p.setEncryptFiles(true);
                p.setEncryptionMethod(EncryptionMethod.AES);
                p.setAesKeyStrength(strength);
                p.setAesVersion(version);
                z.addStream(new java.io.ByteArrayInputStream((byte[]) e[2]), p);
            }
        }
    }

    static void unzip(File zip, File dir, String password) throws Exception {
        try (ZipFile z = password.isEmpty() ? new ZipFile(zip) : new ZipFile(zip, password.toCharArray())) {
            z.extractAll(dir.getPath());
        }
    }
}
