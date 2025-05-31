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

    // Enum para identificar el tipo de loader
    public enum ModLoader {
        VANILLA, FORGE, FABRIC
    }

    public static void launch(String version, String gameDir, String username, String javaPath,
                              String minRam, String maxRam, int width, int height, boolean cracked) throws IOException, InterruptedException {
        // Por compatibilidad, llamamos al método con el loader por defecto (VANILLA)
        launch(version, gameDir, username, javaPath, minRam, maxRam, width, height, cracked, ModLoader.VANILLA, null);
    }

    public static void launch(String version, String gameDir, String username, String javaPath,
                              String minRam, String maxRam, int width, int height, boolean cracked, 
                              ModLoader loader, String loaderVersion) throws IOException, InterruptedException {

        LOGGER.info("Iniciando el proceso de lanzamiento de Minecraft...");
        LOGGER.info("Loader seleccionado: {}", loader);
        if (loaderVersion != null) {
            LOGGER.info("Versión del loader: {}", loaderVersion);
        }

        // Normalizar directorios para Windows
        gameDir = new File(gameDir).getAbsolutePath();

        // Preparar directorios importantes
        Path assetsDir = Paths.get(gameDir, "shared", "assets").toAbsolutePath();
        
        // Cargar los datos de la versión desde el archivo JSON
        JsonObject versionData;
        String versionId;
        
        // Construir la ruta del archivo de versión dependiendo del loader
        String versionJsonPath;
        switch (loader) {
            case FORGE:
                versionJsonPath = Paths.get(gameDir, "shared", "versions", version + "-forge-" + loaderVersion, 
                                   version + "-forge-" + loaderVersion + ".json").toString();
                break;
            case FABRIC:
                // Actualizado al formato correcto: fabric-loader-[versión_loader]-[versión_minecraft]
                String fabricDirName = "fabric-loader-" + loaderVersion + "-" + version;
                versionJsonPath = Paths.get(gameDir, "shared", "versions", fabricDirName, fabricDirName + ".json").toString();
                break;
            default: // VANILLA
                versionJsonPath = Paths.get(gameDir, "shared", "versions", version, version + ".json").toString();
        }
        
        versionData = loadJsonFromFile(versionJsonPath);
        if (versionData == null) {
            LOGGER.error("Error al cargar el archivo de versión desde el path: {}", versionJsonPath);
            return;
        }

        // Obtener ID de la versión del JSON
        versionId = versionData.get("id").getAsString();
        
        // Para Fabric y potencialmente Forge, manejar la herencia de versiones
        JsonObject baseVersionData = null;
        if (versionData.has("inheritsFrom")) {
            String baseVersion = versionData.get("inheritsFrom").getAsString();
            LOGGER.info("Versión base encontrada: {}", baseVersion);
            
            // Cargar los datos de la versión base
            String baseVersionJsonPath = Paths.get(gameDir, "shared", "versions", baseVersion, baseVersion + ".json").toString();
            baseVersionData = loadJsonFromFile(baseVersionJsonPath);
            
            if (baseVersionData == null) {
                LOGGER.error("Error al cargar el archivo de versión base desde: {}", baseVersionJsonPath);
                return;
            }
            
            // Para versiones que heredan, usamos la versión base para ciertas configuraciones
            if (!versionData.has("assets") && baseVersionData.has("assets")) {
                LOGGER.info("Usando assets de la versión base");
            }
        }
        
        // Obtener el índice de assets
        String assetsIndexName = getAssetsIndex(versionData, baseVersionData);
        
        // Asegurarse de que existan los directorios virtuales de assets necesarios
        Path assetsVirtualDir = Paths.get(gameDir, "shared", "assets", "virtual", assetsIndexName);
        try {
            Files.createDirectories(assetsVirtualDir);
        } catch (IOException e) {
            LOGGER.error("No se pudo crear directorio virtual de assets: {}", e.getMessage());
        }

        // Preparar directorios nativos
        Path nativesDir = Paths.get(gameDir, "shared", "natives", versionId).toAbsolutePath();
        
        // Obtener la clase principal desde el JSON
        String mainClass;
        if (versionData.has("mainClass")) {
            mainClass = versionData.get("mainClass").getAsString();
        } else if (baseVersionData != null && baseVersionData.has("mainClass")) {
            mainClass = baseVersionData.get("mainClass").getAsString();
        } else {
            LOGGER.error("No se encontró la clase principal en ningún archivo version.json.");
            return;
        }
        
        if (mainClass == null || mainClass.isEmpty()) {
            LOGGER.error("La clase principal está vacía.");
            return;
        }
        LOGGER.info("Clase principal identificada: {}", mainClass);

        // Construir el classpath para el lanzamiento, considerando el loader y la versión base
        String classpath = buildClasspath(versionData, baseVersionData, gameDir, loader, versionId);
        if (classpath.isEmpty()) {
            LOGGER.error("El classpath está vacío, no se puede lanzar el juego.");
            return;
        }

        LOGGER.info("Classpath preparado correctamente.");

        // Mostrar la ubicación del cliente
        String clientVersionId = (baseVersionData != null) ? baseVersionData.get("id").getAsString() : versionId;
        Path clientJar = Paths.get(gameDir, "shared", "versions", clientVersionId, clientVersionId + ".jar");
        LOGGER.info("Ubicación del cliente JAR: {}", clientJar.toString());

        // Configurar las variables del entorno para el lanzamiento
        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", version);
        vars.put("game_directory", gameDir);
        vars.put("assets_root", assetsDir.toString());
        vars.put("assets_index_name", assetsIndexName);
        vars.put("auth_uuid", UUID.randomUUID().toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("user_type", "mojang");
        vars.put("user_properties", "{}");
        vars.put("version_type", "release");
        
        // Registro del índice de assets para depuración
        LOGGER.info("Usando índice de assets: {}", assetsIndexName);

        // Agregar variables específicas para loaders
        if (loader == ModLoader.FORGE) {
            vars.put("forge_version", loaderVersion);
            vars.put("mc_version", version);
            // Directorio para mods Forge
            createDirectory(Paths.get(gameDir, "mods"), "mods");
            createDirectory(Paths.get(gameDir, "config"), "configuración Forge");
            vars.put("forge_mods_dir", Paths.get(gameDir, "mods").toString());
        } else if (loader == ModLoader.FABRIC) {
            vars.put("fabric_version", loaderVersion);
            vars.put("mc_version", version);
            // Directorio para mods Fabric
            createDirectory(Paths.get(gameDir, "mods"), "mods");
            createDirectory(Paths.get(gameDir, "config"), "configuración Fabric");
            vars.put("fabric_mods_dir", Paths.get(gameDir, "mods").toString());
        }

        // Preparar el comando para lanzar Minecraft
        List<String> command = new ArrayList<>();
        command.add(getJavaBin(javaPath));

        // Agregar argumento específico para bibliotecas nativas
        command.add("-Djava.library.path=" + nativesDir);

        // Agregar argumentos de identificación del lanzador
        command.add("-Dminecraft.launcher.brand=CubicLauncher");
        command.add("-Dminecraft.launcher.version=1.0");

        // Agregar argumentos específicos para loaders
        if (loader == ModLoader.FORGE) {
            command.add("-Dforge.logging.console.level=info");
            command.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
            command.add("-Dfml.ignorePatchDiscrepancies=true");
        } else if (loader == ModLoader.FABRIC) {
            command.add("-Dfabric.development=false");
            command.add("-Dfabric.debug.disableClassPathIsolation=false");
            // Agregar argumentos adicionales para mejorar la compatibilidad con assets
            command.add("-Dfabric.classPathGroups=");
        }

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

        // Agregar argumentos JVM específicos para loaders
        processJvmArguments(versionData, baseVersionData, command, gameDir, versionId);

        // Agregar classpath (solo una vez)
        command.add("-cp");
        command.add(classpath);

        // Agregar la clase principal
        command.add(mainClass);
        
        // Para Fabric, agregar argumentos extra si son necesarios
        if (loader == ModLoader.FABRIC) {
            // Argumentos especiales para ayudar a Fabric a encontrar recursos
            if (!command.contains("--assetIndex")) {
                command.add("--assetIndex");
                command.add(assetsIndexName);
            }
            
            // Garantizar que se use la ruta correcta de assets
            if (!command.contains("--assetsDir")) {
                command.add("--assetsDir");
                command.add(assetsDir.toString());
            }
        }

        // Agregar los argumentos del juego desde el JSON de la versión o la versión base
        processGameArguments(versionData, baseVersionData, command, vars);

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

    private static void createDirectory(Path dir, String descripcion) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                LOGGER.info("Directorio de {} creado: {}", descripcion, dir);
            } catch (IOException e) {
                LOGGER.error("No se pudo crear el directorio de {}: {}", descripcion, e.getMessage());
            }
        }
    }

    private static void processJvmArguments(JsonObject versionData, JsonObject baseVersionData, 
                                           List<String> command, String gameDir, String version) {
        // Procesar argumentos JVM desde el JSON de versión principal
        if (versionData.has("arguments") && versionData.getAsJsonObject("arguments").has("jvm")) {
            JsonArray jvmArgs = versionData.getAsJsonObject("arguments").getAsJsonArray("jvm");
            processJvmArgumentsArray(jvmArgs, command, gameDir, version);
        }
        
        // Si tenemos versión base y el principal no tiene argumentos JVM, usar los de la base
        if (baseVersionData != null && 
            (!versionData.has("arguments") || !versionData.getAsJsonObject("arguments").has("jvm"))) {
            if (baseVersionData.has("arguments") && baseVersionData.getAsJsonObject("arguments").has("jvm")) {
                JsonArray baseJvmArgs = baseVersionData.getAsJsonObject("arguments").getAsJsonArray("jvm");
                processJvmArgumentsArray(baseJvmArgs, command, gameDir, version);
            }
        }
    }
    
    private static void processJvmArgumentsArray(JsonArray jvmArgs, List<String> command, String gameDir, String version) {
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

    private static void processGameArguments(JsonObject versionData, JsonObject baseVersionData, 
                                            List<String> command, Map<String, String> vars) {
        // Primero intentamos con el JSON principal
        if (versionData.has("arguments") && versionData.getAsJsonObject("arguments").has("game")) {
            JsonArray gameArgs = versionData.getAsJsonObject("arguments").getAsJsonArray("game");
            processGameArgumentsArray(gameArgs, command, vars);
        } else if (versionData.has("minecraftArguments")) {
            processMinecraftArguments(versionData.get("minecraftArguments").getAsString(), command, vars);
        }
        // Si no hay argumentos en el principal y hay versión base, usar los de la base
        else if (baseVersionData != null) {
            if (baseVersionData.has("arguments") && baseVersionData.getAsJsonObject("arguments").has("game")) {
                JsonArray baseGameArgs = baseVersionData.getAsJsonObject("arguments").getAsJsonArray("game");
                processGameArgumentsArray(baseGameArgs, command, vars);
            } else if (baseVersionData.has("minecraftArguments")) {
                processMinecraftArguments(baseVersionData.get("minecraftArguments").getAsString(), command, vars);
            }
        }
    }
    
    private static void processGameArgumentsArray(JsonArray gameArgs, List<String> command, Map<String, String> vars) {
        for (JsonElement arg : gameArgs) {
            if (arg.isJsonPrimitive()) {
                String argStr = arg.getAsString();
                if (argStr.startsWith("${") && argStr.endsWith("}")) {
                    String key = argStr.substring(2, arg.getAsString().length() - 1);
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
    
    private static void processMinecraftArguments(String minecraftArgs, List<String> command, Map<String, String> vars) {
        // Formato antiguo de argumentos para versiones anteriores
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

    private static String getAssetsIndex(JsonObject versionData, JsonObject baseVersionData) {
        // Primero intentar obtener del JSON principal
        if (versionData.has("assets")) {
            return versionData.get("assets").getAsString();
        }
        // Si no está en el principal y hay versión base, intentar de ahí
        else if (baseVersionData != null && baseVersionData.has("assets")) {
            return baseVersionData.get("assets").getAsString();
        }
        // Valor por defecto si no se encuentra en ninguno
        return "legacy";
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

    private static String buildClasspath(JsonObject versionData, JsonObject baseVersionData, String gameDir, 
                                          ModLoader loader, String versionId) {
        LOGGER.info("Construyendo el classpath para loader: {}", loader);

        Set<String> paths = new LinkedHashSet<>();
        Set<String> asmVersions = new HashSet<>();
        Path libDir = Paths.get(gameDir, "shared", "libraries").toAbsolutePath().normalize();
        
        String baseVersionId = null;
        if (baseVersionData != null) {
            baseVersionId = baseVersionData.get("id").getAsString();
        }
        
        Path nativesDir = Paths.get(gameDir, "shared", "natives", versionId).toAbsolutePath().normalize();

        // Biblioteca de las dos versiones, dando prioridad a las del loader
        processLibrariesForClasspath(versionData, paths, libDir, nativesDir, versionId, asmVersions, true);
        
        if (baseVersionData != null) {
            processLibrariesForClasspath(baseVersionData, paths, libDir, nativesDir,
                    baseVersionId != null ? baseVersionId : versionId, asmVersions, false);
        }

        // Agregar el archivo cliente JAR
        Path clientJar;
        if (baseVersionData != null) {
            // Si hay versión base, usar su JAR
            clientJar = Paths.get(gameDir, "shared", "versions", baseVersionId, baseVersionId + ".jar");
        } else {
            // Si no hay versión base, usar el JAR principal
            clientJar = Paths.get(gameDir, "shared", "versions", versionId, versionId + ".jar");
        }
        
        if (Files.exists(clientJar)) {
            paths.add(clientJar.toString());
            LOGGER.debug("Cliente JAR cargado: {}", clientJar);
        } else {
            LOGGER.error("Cliente JAR no encontrado: {}", clientJar);
            return "";
        }

        // Depuración de versiones ASM
        if (!asmVersions.isEmpty()) {
            LOGGER.info("Versiones de ASM encontradas: {}", asmVersions);
        }
        
        // Filtrar classpath para eliminar duplicados y problemas conocidos
        List<String> filteredClasspath = new ArrayList<>(paths);
        // Filtrar ASM conflictivas (mantener versión de Fabric)
        if (loader == ModLoader.FABRIC) {
            filteredClasspath.removeIf(path -> 
                path.contains("org\\ow2\\asm\\asm\\9.6\\asm-9.6.jar") ||
                path.contains("org/ow2/asm/asm/9.6/asm-9.6.jar"));
        }
        
        LOGGER.info("Total de bibliotecas cargadas en el classpath: {}", filteredClasspath.size());
        
        return String.join(File.pathSeparator, filteredClasspath);
    }
    
    private static void processLibrariesForClasspath(JsonObject versionData, Set<String> paths, 
                                                    Path libDir, Path nativesDir, String versionId, 
                                                    Set<String> asmVersions, boolean isPrimaryLibrary) {
        if (!versionData.has("libraries")) {
            return;
        }
        
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        
        for (JsonElement libElement : libraries) {
            JsonObject lib = libElement.getAsJsonObject();
            
            // Verificar si la biblioteca tiene reglas y si debe ser incluida
            if (lib.has("rules") && !shouldIncludeLibrary(lib.getAsJsonArray("rules"))) {
                continue;
            }
            
            // Si es ASM y no es librería del loader principal, saltarla
            String libName = lib.has("name") ? lib.get("name").getAsString() : "";
            if (!isPrimaryLibrary && libName.startsWith("org.ow2.asm:")) {
                continue;
            }

            // Procesar bibliotecas normales
            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                if (artifact.has("path")) {
                    String relPath = artifact.get("path").getAsString();
                    Path jarPath = libDir.resolve(relPath);
                    
                    // Registrar versiones de ASM para depuración
                    if (relPath.contains("org/ow2/asm") || relPath.contains("org\\ow2\\asm")) {
                        asmVersions.add(relPath);
                    }
                    
                    if (Files.exists(jarPath)) {
                        paths.add(jarPath.toString());
                        LOGGER.debug("Biblioteca cargada: {}", jarPath);
                    } else {
                        LOGGER.warn("Biblioteca no encontrada: {}", jarPath);
                    }
                }
            } else if (lib.has("name")) {
                // Formato alternativo para bibliotecas: "name" = "groupId:artifactId:version"
                String[] parts = libName.split(":");
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
                    
                    // Registrar versiones de ASM para depuración
                    if (libName.startsWith("org.ow2.asm:")) {
                        asmVersions.add(libName);
                    }
                    
                    if (Files.exists(jarPath)) {
                        paths.add(jarPath.toString());
                        LOGGER.debug("Biblioteca cargada (formato alternativo): {}", jarPath);
                    } else if (lib.has("url")) {
                        LOGGER.warn("Biblioteca no encontrada localmente, disponible en URL: {}", lib.get("url").getAsString());
                    } else {
                        LOGGER.warn("Biblioteca no encontrada (formato alternativo): {}", jarPath);
                    }
                }
            }
            
            // Procesar bibliotecas nativas si existen
            processNatives(lib, libDir, nativesDir);
        }
    }
    
    private static void processNatives(JsonObject lib, Path libDir, Path nativesDir) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
            JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
            
            // Determinar el clasificador nativo para el sistema operativo actual
            String nativeClassifier = null;
            String osName = System.getProperty("os.name").toLowerCase();
            
            if (osName.contains("win")) {
                nativeClassifier = "natives-windows";
            } else if (osName.contains("linux")) {
                nativeClassifier = "natives-linux";
            } else if (osName.contains("mac")) {
                nativeClassifier = "natives-macos";
            }
            
            // Si tenemos un clasificador válido y existe en el JSON
            if (nativeClassifier != null && classifiers.has(nativeClassifier)) {
                JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeClassifier);
                if (nativeArtifact.has("path")) {
                    String nativePath = nativeArtifact.get("path").getAsString();
                    Path nativeJar = libDir.resolve(nativePath);
                    
                    if (Files.exists(nativeJar)) {
                        // Extraer archivos nativos
                        try {
                            extractNatives(nativeJar, nativesDir, lib);
                            LOGGER.debug("Archivos nativos extraídos de: {}", nativeJar);
                        } catch (IOException e) {
                            LOGGER.error("Error al extraer archivos nativos: {}", e.getMessage());
                        }
                    } else {
                        LOGGER.warn("Biblioteca nativa no encontrada: {}", nativeJar);
                    }
                }
            }
        }
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