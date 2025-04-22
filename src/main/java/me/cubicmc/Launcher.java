package me.cubicmc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class Launcher {

    private static final Logger LOGGER = LogManager.getLogger(Launcher.class);

    public static void launch(String version, String gameDir, String username, String javaPath,
                              String minRam, String maxRam, int width, int height, boolean cracked) throws IOException, InterruptedException {

        LOGGER.info("Iniciando el proceso de lanzamiento de Minecraft...");

        // Normalizar directorios para Windows
        gameDir = new File(gameDir).getAbsolutePath();

        // Preparar directorios importantes
        Path nativesDir = Paths.get(gameDir, "shared", "natives", version).toAbsolutePath();
        Path assetsDir = Paths.get(gameDir, "shared", "assets").toAbsolutePath();

        // Cargar los datos de la versión desde el archivo JSON
        JsonObject versionData = loadJsonFromFile(Paths.get(gameDir, "shared", "versions", version, version + ".json").toString());
        if (versionData == null) {
            LOGGER.error("Error al cargar el archivo de versión desde el path: {}", gameDir);
            return;
        }

        // Obtener la clase principal desde el JSON
        String mainClass = versionData.get("mainClass").getAsString();
        if (mainClass == null || mainClass.isEmpty()) {
            LOGGER.error("No se encontró la clase principal en el archivo version.json.");
            return;
        }
        LOGGER.info("Clase principal identificada: {}", mainClass);

        // Construir el classpath para el lanzamiento
        String classpath = buildClasspath(versionData, gameDir);
        if (classpath.isEmpty()) {
            LOGGER.error("El classpath está vacío, no se puede lanzar el juego.");
            return;
        }

        LOGGER.info("Classpath preparado correctamente:\n{}", classpath);

        // Mostrar la ubicación del cliente
        Path clientJar = Paths.get(gameDir, "shared", "versions", versionData.get("id").getAsString(), versionData.get("id").getAsString() + ".jar");
        LOGGER.info("Ubicación del cliente JAR: {}", clientJar.toString());

        // Configurar las variables del entorno para el lanzamiento
        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", version);
        vars.put("game_directory", gameDir);
        vars.put("assets_root", assetsDir.toString());
        vars.put("assets_index_name", getAssetsIndex(versionData));
        vars.put("auth_uuid", UUID.randomUUID().toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("user_type", "mojang");
        vars.put("user_properties", "{}");
        vars.put("version_type", "release");

        // Preparar el comando para lanzar Minecraft
        List<String> command = new ArrayList<>();
        command.add(getJavaBin(javaPath));

        // Agregar argumento específico para bibliotecas nativas
        command.add("-Djava.library.path=" + nativesDir);

        // Agregar argumentos de identificación del lanzador
        command.add("-Dminecraft.launcher.brand=CubicLauncher");
        command.add("-Dminecraft.launcher.version=1.0");

        // Agregar argumentos específicos para modo cracked
        if (cracked) {
            LOGGER.info("Agregando argumentos para modo cracked");
            command.add("-Dminecraft.api.env=custom");
            command.add("-Dminecraft.api.auth.host=https://invalid.invalid");
            command.add("-Dminecraft.api.account.host=https://invalid.invalid");
            command.add("-Dminecraft.api.session.host=https://invalid.invalid");
            command.add("-Dminecraft.api.services.host=https://invalid.invalid");
        }

        // Agregar argumentos de memoria y classpath
        command.add("-Xms" + minRam);
        command.add("-Xmx" + maxRam);

        // Agregar classpath (solo una vez)
        command.add("-cp");
        command.add(classpath);

        // Agregar la clase principal
        command.add(mainClass);

        // Agregar los argumentos del juego
        if (versionData.has("arguments") && versionData.getAsJsonObject("arguments").has("game")) {
            JsonArray gameArgs = versionData.getAsJsonObject("arguments").getAsJsonArray("game");
            processGameArguments(gameArgs, command, vars);
        } else if (versionData.has("minecraftArguments")) {
            // Formato antiguo de argumentos para versiones anteriores
            String minecraftArgs = versionData.get("minecraftArguments").getAsString();
            String[] args = minecraftArgs.split(" ");
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("${") && arg.endsWith("}")) {
                    String key = arg.substring(2, arg.length() - 1);
                    if (vars.containsKey(key)) {
                        command.add(vars.get(key));
                    } else {
                        command.add(arg); // Mantener el placeholder si no lo tenemos
                    }
                } else {
                    command.add(arg);
                }
            }
        }

        // Agregar argumentos específicos si no están incluidos
        if (!command.contains("--width")) {
            command.add("--width");
            command.add(String.valueOf(width));
        }

        if (!command.contains("--height")) {
            command.add("--height");
            command.add(String.valueOf(height));
        }

        // Mostrar el comando final que se va a ejecutar
        LOGGER.debug("Comando de ejecución preparado: ");
        command.forEach(arg -> LOGGER.debug("  {}", arg));

        // Para propósitos de depuración, mostrar el comando completo
        StringBuilder fullCommand = new StringBuilder();
        for (String cmd : command) {
            fullCommand.append(cmd).append(" ");
        }
        LOGGER.info("Comando completo: {}", fullCommand.toString());

        // Iniciar el proceso
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(gameDir));
        builder.inheritIO();

        // Configurar el entorno de variables
        Map<String, String> env = builder.environment();
        env.put("JAVA_HOME", new File(javaPath).getParent());

        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            LOGGER.info("Minecraft lanzado correctamente.");
        } else {
            LOGGER.error("Error al ejecutar el proceso. Código de salida: {}", exitCode);
        }
    }

    private static void processJvmArguments(JsonArray jvmArgs, List<String> command, String gameDir, String version) {
        for (JsonElement arg : jvmArgs) {
            if (arg.isJsonPrimitive()) {
                String argStr = arg.getAsString();

                // Omitir argumentos de classpath porque lo agregamos manualmente
                if (argStr.equals("-cp") || argStr.equals("-classpath") || argStr.contains("${classpath}")) {
                    continue;
                }

                if (argStr.contains("${natives_directory}")) {
                    argStr = argStr.replace("${natives_directory}",
                            Paths.get(gameDir, "shared", "natives", version).toString());
                }
                if (argStr.contains("${launcher_name}")) {
                    argStr = argStr.replace("${launcher_name}", "CubicLauncher");
                }
                if (argStr.contains("${launcher_version}")) {
                    argStr = argStr.replace("${launcher_version}", "1.0");
                }

                command.add(argStr);
            }
        }
    }

    private static void processGameArguments(JsonArray gameArgs, List<String> command, Map<String, String> vars) {
        for (JsonElement arg : gameArgs) {
            if (arg.isJsonPrimitive()) {
                String argStr = arg.getAsString();
                if (argStr.startsWith("${") && argStr.endsWith("}")) {
                    String key = argStr.substring(2, argStr.length() - 1);
                    if (vars.containsKey(key)) {
                        command.add(vars.get(key));
                    } else {
                        command.add(argStr); // Mantener el placeholder si no lo tenemos
                    }
                } else {
                    command.add(argStr);
                }
            }
        }
    }

    private static String getAssetsIndex(JsonObject versionData) {
        if (versionData.has("assets")) {
            return versionData.get("assets").getAsString();
        }
        return "legacy"; // Valor por defecto para versiones antiguas
    }

    private static JsonObject loadJsonFromFile(String filePath) throws IOException {
        LOGGER.debug("Cargando archivo JSON desde la ruta: {}", filePath);
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            LOGGER.error("El archivo JSON no existe: {}", filePath);
            return null;
        }
        String content = Files.readString(path);
        try {
            // Para compatibilidad con diferentes versiones de Gson
            return new com.google.gson.JsonParser().parse(content).getAsJsonObject();
        } catch (Exception e) {
            // En versiones más nuevas de Gson
            return com.google.gson.JsonParser.parseString(content).getAsJsonObject();
        }
    }

    private static String getJavaBin(String javaPath) {
        Path path = Paths.get(javaPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            LOGGER.error("Ruta de Java inválida: {}", javaPath);
            throw new IllegalArgumentException("Ruta de Java inválida: " + javaPath);
        }
        LOGGER.debug("Ruta de Java válida encontrada: {}", javaPath);
        return javaPath;
    }

    private static String buildClasspath(JsonObject versionData, String gameDir) {
        LOGGER.info("Construyendo el classpath...");

        Set<String> paths = new LinkedHashSet<>();
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        Path libDir = Paths.get(gameDir, "shared", "libraries").toAbsolutePath().normalize();
        Path nativesDir = Paths.get(gameDir, "shared", "natives", versionData.get("id").getAsString()).toAbsolutePath().normalize();

        // Contador de bibliotecas cargadas
        int libraryCount = 0;
        int nativesCount = 0;

        // Recorrer las bibliotecas de la versión
        for (JsonElement libElement : libraries) {
            JsonObject lib = libElement.getAsJsonObject();

            // Verificar si la biblioteca tiene reglas y si debe ser incluida
            if (lib.has("rules") && !shouldIncludeLibrary(lib.getAsJsonArray("rules"))) {
                continue;
            }

            // Procesar bibliotecas normales
            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                if (artifact.has("path")) {
                    String relPath = artifact.get("path").getAsString();
                    Path jarPath = libDir.resolve(relPath);
                    if (Files.exists(jarPath)) {
                        paths.add(jarPath.toString());
                        libraryCount++;
                        LOGGER.debug("Biblioteca cargada: {}", jarPath);
                    } else {
                        LOGGER.warn("Biblioteca no encontrada: {}", jarPath);
                    }
                }
            } else if (lib.has("name")) {
                // Formato alternativo para bibliotecas: "name" = "groupId:artifactId:version"
                String name = lib.get("name").getAsString();
                String[] parts = name.split(":");
                if (parts.length >= 3) {
                    String groupId = parts[0].replace(".", "/");
                    String artifactId = parts[1];
                    String version = parts[2];

                    // Manejar clasificador si existe (partes[3])
                    String classifier = "";
                    if (parts.length > 3) {
                        classifier = "-" + parts[3];
                    }

                    Path jarPath = libDir.resolve(
                            Paths.get(groupId, artifactId, version, artifactId + "-" + version + classifier + ".jar"));

                    if (Files.exists(jarPath)) {
                        paths.add(jarPath.toString());
                        libraryCount++;
                        LOGGER.debug("Biblioteca cargada (formato alternativo): {}", jarPath);
                    } else {
                        LOGGER.warn("Biblioteca no encontrada (formato alternativo): {}", jarPath);
                    }
                }
            }
        }

        // Agregar el archivo cliente JAR
        Path clientJar = Paths.get(gameDir, "shared", "versions", versionData.get("id").getAsString(), versionData.get("id").getAsString() + ".jar");
        if (Files.exists(clientJar)) {
            paths.add(clientJar.toString());
            LOGGER.debug("Cliente JAR cargado: {}", clientJar);
            libraryCount++;
        } else {
            LOGGER.error("Cliente JAR no encontrado: {}", clientJar);
            return "";
        }

        // Reportar la cantidad total de bibliotecas cargadas
        LOGGER.info("Total de bibliotecas cargadas en el classpath: {}", libraryCount);

        return String.join(File.pathSeparator, paths);
    }

    private static boolean shouldIncludeLibrary(JsonArray rules) {
        boolean allow = false;

        for (JsonElement ruleElement : rules) {
            JsonObject rule = ruleElement.getAsJsonObject();
            String action = rule.get("action").getAsString();

            // Si la regla tiene una condición de sistema operativo
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                String name = os.get("name").getAsString();

                // Detectar el sistema operativo actual
                String currentOs = System.getProperty("os.name").toLowerCase();
                boolean osMatch = false;

                if (name.equals("windows") && currentOs.contains("win")) {
                    osMatch = true;
                } else if (name.equals("linux") && currentOs.contains("linux")) {
                    osMatch = true;
                } else if (name.equals("osx") && (currentOs.contains("mac") || currentOs.contains("darwin"))) {
                    osMatch = true;
                }

                if (osMatch) {
                    allow = action.equals("allow");
                }
            } else {
                // Regla sin condición de sistema operativo
                allow = action.equals("allow");
            }
        }

        return allow;
    }

    private static void extractNatives(Path nativeJar, Path nativesDir, JsonObject lib) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(nativeJar.toFile())) {
            // Filtrar archivos a extraer (excluir META-INF y otros)
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Filtrar solo archivos .dll, .so, .dylib, etc.
                boolean isValidNative = name.endsWith(".dll") || name.endsWith(".so") ||
                        name.endsWith(".dylib") || name.endsWith(".jnilib");

                // Excluir directorios META-INF
                if (isValidNative && !name.startsWith("META-INF/")) {
                    // Extraer solo si el archivo no existe o es más nuevo
                    Path targetFile = nativesDir.resolve(name);

                    // Crear directorio padre si no existe
                    Files.createDirectories(targetFile.getParent());

                    // Solo extraer si el archivo no existe o si la fecha de modificación es más reciente
                    if (!Files.exists(targetFile) || entry.getLastModifiedTime().toMillis() > Files.getLastModifiedTime(targetFile).toMillis()) {
                        try (java.io.InputStream in = jar.getInputStream(entry)) {
                            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            LOGGER.debug("Archivo nativo extraído: {}", targetFile);
                        }
                    }
                }
            }
        }
    }
}