package vrsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import necraft.mod.Mod;
import necraft.mod.ModContext;
import necraft.xr.Xr;

/** VR Support: подключает OpenXR к игре (api 2) и даёт команду /vr для настроек. Настройки лежат в mods/vr-support/config.properties. */
public final class VrSupportMod implements Mod {
    private ModContext ctx;
    private OpenXrBackend backend;
    private Path configFile;

    @Override
    public void onLoad(ModContext ctx) throws Exception {
        this.ctx = ctx;
        configFile = ctx.dataDir().resolve("config.properties");
        loadConfig();
        backend = new OpenXrBackend(ctx::log);
        ctx.registerXr(backend);
        ctx.registerCommand("vr", "/vr [status|snap <градусы|0>|smooth <град/с>|move head|hand|vignette on|off|scale <0.3-1.5>|hand left|right]", this::command);
    }

    @Override
    public void onUnload() {
        Xr.releaseOwner("vr-support");
    }

    private void loadConfig() {
        Properties p = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                p.load(in);
            } catch (IOException e) {
                ctx.log("не прочитал настройки: " + e.getMessage());
            }
        }
        Xr.snapDegrees = clamp(num(p, "snap", Xr.snapDegrees), 0, 90);
        Xr.smoothDegPerSec = clamp(num(p, "smooth", Xr.smoothDegPerSec), 30, 300);
        Xr.moveByHand = "hand".equals(p.getProperty("move", "head"));
        Xr.vignette = !"off".equals(p.getProperty("vignette", "on"));
        Xr.handScale = clamp(num(p, "scale", Xr.handScale), 0.3, 1.5);
        Xr.leftHanded = "left".equals(p.getProperty("hand", "right"));
    }

    private void saveConfig() {
        Properties p = new Properties();
        p.setProperty("snap", String.valueOf(Xr.snapDegrees));
        p.setProperty("smooth", String.valueOf(Xr.smoothDegPerSec));
        p.setProperty("move", Xr.moveByHand ? "hand" : "head");
        p.setProperty("vignette", Xr.vignette ? "on" : "off");
        p.setProperty("scale", String.valueOf(Xr.handScale));
        p.setProperty("hand", Xr.leftHanded ? "left" : "right");
        try (OutputStream out = Files.newOutputStream(configFile)) {
            p.store(out, "VR Support");
        } catch (IOException e) {
            ctx.log("не сохранил настройки: " + e.getMessage());
        }
    }

    private static double num(Properties p, String key, double def) {
        try {
            return Double.parseDouble(p.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String status() {
        return "VR: " + (Xr.backend() != null ? Xr.backend().name() + (Xr.backend().running() ? ", шлем работает" : ", шлем не запущен") : "выключен")
                + "\nПоворот: " + (Xr.snapDegrees > 0 ? "щелчками по " + (int) Xr.snapDegrees + "°" : "плавный, " + (int) Xr.smoothDegPerSec + "°/с")
                + "; ходьба: по " + (Xr.moveByHand ? "левому контроллеру" : "взгляду")
                + "; виньетка: " + (Xr.vignette ? "вкл" : "выкл") + "; масштаб рук: " + Xr.handScale
                + "; основная рука: " + (Xr.leftHanded ? "левая" : "правая");
    }

    private String command(String[] a) {
        if (a.length == 0 || a[0].equals("status")) return status();
        String v = a.length > 1 ? a[1] : "";
        switch (a[0]) {
            case "snap":
                Xr.snapDegrees = clamp(parse(v, 30), 0, 90);
                break;
            case "smooth":
                Xr.snapDegrees = 0;
                Xr.smoothDegPerSec = clamp(parse(v, 120), 30, 300);
                break;
            case "move":
                Xr.moveByHand = v.equals("hand");
                break;
            case "vignette":
                Xr.vignette = !v.equals("off");
                break;
            case "scale":
                Xr.handScale = clamp(parse(v, 0.75), 0.3, 1.5);
                break;
            case "hand":
                Xr.leftHanded = v.equals("left");
                break;
            default:
                return "Не знаю такой настройки. /vr — показать текущие; список в описании команды.";
        }
        saveConfig();
        return status();
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
