package io.github.konove.notmytodo.server;

import java.lang.reflect.InvocationTargetException;

/**
 * The server's entry point, compiled for Java 8 so that an older Java can run it and say what is wrong:
 * the server needs Java 21, and an older Java would only fail to load its first class, which a client
 * shows as a connection that closed.
 */
public final class Launcher {
    static final int NEEDED = 21;

    private Launcher() {
    }

    public static void main(String[] args) throws Throwable {
        String problem = problem(System.getProperty("java.specification.version"), System.getProperty("java.home"));
        if (problem != null) {
            System.err.println(problem);
            System.exit(1);
        }
        try {
            Class.forName("io.github.konove.notmytodo.server.MainKt").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** What to tell the user when {@code version}, a java.specification.version, is too old for the server; null when it is not. */
    static String problem(String version, String home) {
        if (major(version) >= NEEDED) return null;
        return "notmytodo-store needs Java " + NEEDED + " or newer, and was started with Java " + version + " at " + home + ". "
            + "Run ./gradlew installServer again with a Java " + NEEDED + " or newer, which registers the server with that Java as JAVA_HOME, "
            + "or set JAVA_HOME in its registration: claude mcp add notmytodo-store --scope user -e JAVA_HOME=<java> -- <this script>.";
    }

    /** The major version of a java.specification.version, "1.8" or "21"; one it cannot read counts as new enough. */
    static int major(String version) {
        String rest = version.startsWith("1.") ? version.substring(2) : version;
        int dot = rest.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? rest : rest.substring(0, dot));
        } catch (NumberFormatException e) {
            return NEEDED;
        }
    }
}
