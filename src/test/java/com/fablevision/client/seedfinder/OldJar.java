package com.fablevision.client.seedfinder;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE SHIPPED 1.47.2 JAR, LOADED BESIDE THIS BUILD, FOR CONTROLS (1.47.3).
 *
 * A check that only ever passes proves nothing. The strongest control for a bug fix is the code that had the
 * bug: run the same check against it and it must fail. This loads FableVision's own classes from
 * releases/26.3-fabric/fablevision-1.47.2.jar in a class loader of their own — child first for com.fablevision
 * and com.tensec, everything else (Minecraft, the libraries) from the test classpath — so the old engine runs
 * on the same game classes as the new one, without either seeing the other's statics.
 *
 * Reflection only, by name: the old classes are not the new ones, so nothing here can be cast across.
 */
public final class OldJar {

   public static final String VERSION = "1.47.2";

   private static URLClassLoader loader;

   /** A static field of an old class. */
   public static Object staticField(Class<?> c, String name) throws ReflectiveOperationException {
      Field f = c.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(null);
   }

   /** The 1.47.2 jar's class loader, or null (with the reason printed) when the jar is not there. */
   public static synchronized ClassLoader get() {
      if (loader != null) {
         return loader;
      }
      Path jar = Path.of("..", "releases", "26.3-fabric", "fablevision-" + VERSION + ".jar").toAbsolutePath().normalize();
      if (!Files.isRegularFile(jar)) {
         System.out.println("  (control skipped: " + jar + " not found)");
         return null;
      }
      try {
         ClassLoader parent = OldJar.class.getClassLoader();
         loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
               // FableVisionClient is the one shared: its class initialiser registers the key category with
               // the game, which refuses a second registration, and the old classes only use its logger.
               if (!name.startsWith("com.fablevision.") && !name.startsWith("com.tensec.")
                     || name.equals("com.fablevision.client.FableVisionClient")
                     || name.startsWith("com.fablevision.client.FableVisionClient$")) {
                  return super.loadClass(name, resolve);
               }
               synchronized (getClassLoadingLock(name)) {
                  Class<?> c = findLoadedClass(name);
                  if (c == null) {
                     c = findClass(name);   // the old jar's copy, never the build's
                  }
                  if (resolve) {
                     resolveClass(c);
                  }
                  return c;
               }
            }
         };
         return loader;
      } catch (IOException e) {
         System.out.println("  (control skipped: " + e + ")");
         return null;
      }
   }

   public static Class<?> cls(String name) throws ClassNotFoundException {
      return Class.forName(name, true, get());
   }

   public static Object make(Class<?> c, Class<?>[] types, Object... args) throws ReflectiveOperationException {
      Constructor<?> k = c.getDeclaredConstructor(types);
      k.setAccessible(true);
      return k.newInstance(args);
   }

   public static Object call(Class<?> c, Object on, String method, Class<?>[] types, Object... args)
         throws ReflectiveOperationException {
      Method m = c.getDeclaredMethod(method, types);
      m.setAccessible(true);
      return m.invoke(on, args);
   }

   public static Object field(Object on, String name) throws ReflectiveOperationException {
      Field f = on.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(on);
   }

   /** What actually went wrong inside a reflective call: the cause of an InvocationTargetException, with its first frames. */
   public static String why(Throwable e) {
      Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
      StringBuilder sb = new StringBuilder(c.toString());
      StackTraceElement[] st = c.getStackTrace();
      for (int i = 0; i < Math.min(4, st.length); i++) {
         sb.append(" at ").append(st[i]);
      }
      return sb.toString();
   }

   private OldJar() {
   }
}
