// src/main/java/me/dagxam/treefall/RealisticSeasonsHook.java

package me.dagxam.treefall;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Enumeration;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

final class RealisticSeasonsHook {

    private final Plugin realisticSeasons;
    private final Logger log;

    private Class<?> seasonsApiClass;
    private Method getSeason;
    private Object seasonsApiInstance;

    RealisticSeasonsHook(Plugin realisticSeasons, Logger log) {
        this.realisticSeasons = realisticSeasons;
        this.log = log;
    }

    boolean init() {
        try {
            ClassLoader rsLoader = realisticSeasons.getClass().getClassLoader();

            String seasonsApiFqcn = findClassInJar(realisticSeasons, "SeasonsAPI.class");
            if (seasonsApiFqcn == null) {
                log.warning("[TreeFall] Could not find SeasonsAPI.class inside RealisticSeasons jar");
                return false;
            }

            seasonsApiClass = Class.forName(seasonsApiFqcn, true, rsLoader);
            Method getInstance = seasonsApiClass.getMethod("getInstance");
            seasonsApiInstance = getInstance.invoke(null);
            getSeason = seasonsApiClass.getMethod("getSeason", World.class);

            return true;
        } catch (Throwable t) {
            log.warning("[TreeFall] RealisticSeasons hook error: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    String getSeasonName(World w) {
        try {
            Object seasonObj = getSeason.invoke(seasonsApiInstance, w);
            if (seasonObj == null) return null;
            return seasonObj.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String findClassInJar(Plugin plugin, String classFileName) {
        try {
            URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File jarFile = new File(url.toURI());
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    String name = e.getName();
                    if (name.endsWith(classFileName)) {
                        String fqcn = name.replace('/', '.');
                        return fqcn.substring(0, fqcn.length() - ".class".length());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
