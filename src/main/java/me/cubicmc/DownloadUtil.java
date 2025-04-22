package me.cubicmc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;


public class DownloadUtil {
    private static final Logger LOGGER = Logger.getLogger(DownloadUtil.class.getName());
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Descarga un fichero desde la URL indicada a la ruta absoluta outputPath.
     */
    private static void downloadFile(String urlStr, String outputPath, String expectedSha1)
            throws IOException, InterruptedException {
        Path output = Paths.get(outputPath);
        boolean verified = false;
        int retries = 0;
        final int MAX_RETRIES = 3;
        
        while (!verified && retries < MAX_RETRIES) {
            try {
                // Crea todos los directorios padre si no existen
                Files.createDirectories(output.getParent());
                
                URI uri = URI.create(urlStr);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .GET()
                        .build();
                
                HttpResponse<Path> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(output));
                
                if (response.statusCode() != 200) {
                    throw new IOException("Error en la descarga: HTTP " + response.statusCode());
                }
                
                // Verificar el hash si se proporcionó uno
                if (expectedSha1 != null && !expectedSha1.isEmpty()) {
                    String actualSha1 = calculateSHA1(output);
                    if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
                        retries++;
                        LOGGER.warning("❌ Verificación de hash SHA-1 fallida para " + output);
                        LOGGER.warning("   Esperado: " + expectedSha1);
                        LOGGER.warning("   Actual:   " + actualSha1);
                        LOGGER.warning("   Reintentando descarga (" + retries + "/" + MAX_RETRIES + ")");
                        continue;
                    }
                    LOGGER.info("✅ Hash SHA-1 verificado para " + output);
                }
                
                verified = true;
            } catch (IOException e) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw e;
                }
                LOGGER.warning("❌ Error descargando " + urlStr + ": " + e.getMessage());
                LOGGER.warning("   Reintentando en 2 segundos... (" + retries + "/" + MAX_RETRIES + ")");
                Thread.sleep(2000); // Esperar 2 segundos antes de reintentar
            }
        }
        
        if (!verified) {
            throw new IOException("No se pudo verificar la descarga después de " + MAX_RETRIES + " intentos");
        }
    }

    /**
     * Sobrecarga del método downloadFile sin verificación de hash
     */
    private static void downloadFile(String urlStr, String outputPath)
            throws IOException, InterruptedException {
        downloadFile(urlStr, outputPath, null);
    }

    /**
     * Descarga todos los assets de la versión dada, usando rutas absolutas
     * para el directorio base "game/assets".
     */
    public static void downloadAssets(String version, int threadCount, String gameDir)
            throws IOException, InterruptedException {
        JsonObject assetIndex = HttpUtils.getAssetIndex(version);
        if (assetIndex == null || assetIndex.has("Error")) {
            LOGGER.severe("❌ Error obteniendo asset index para versión " + version);
            return;
        }
        
        JsonObject objects = assetIndex.getAsJsonObject("objects");
        String urlBase = "https://resources.download.minecraft.net/";

        // 1) Contruir path absoluto
        Path basePath = Paths.get(gameDir, "shared", "assets", "objects")
                .toAbsolutePath()
                .normalize();

        // 2) Asegurar existencia del directorio
        Files.createDirectories(basePath);
        
        // Contador para llevar registro de los archivos
        final AtomicInteger totalFiles = new AtomicInteger(objects.size());
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failCount = new AtomicInteger(0);
        
        LOGGER.info("⬇️ Descargando " + totalFiles.get() + " assets para versión " + version);

        // 3) Creamos un ExecutorService y lo cerramos con try-with-resources
        try (CloseableExecutorService ces =
                     new CloseableExecutorService(
                             Executors.newFixedThreadPool(threadCount))) {

            ExecutorService executor = ces.get();

            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String assetName = entry.getKey();
                JsonObject asset = entry.getValue().getAsJsonObject();
                String hash = asset.get("hash").getAsString();
                long size = asset.get("size").getAsLong();
                
                String prefix = hash.substring(0, 2);
                Path folder = basePath.resolve(prefix);
                Path dest = folder.resolve(hash);
                String fileUrl = urlBase + prefix + "/" + hash;

                // Carpeta del prefix
                Files.createDirectories(folder);
                
                // Verificar si ya existe y tiene el tamaño correcto
                if (Files.exists(dest) && Files.size(dest) == size) {
                    successCount.incrementAndGet();
                    continue;
                }

                executor.submit(() -> {
                    try {
                        downloadFile(fileUrl, dest.toString(), hash);
                        LOGGER.info("✅ Asset descargado y verificado: " + assetName + " (" + successCount.incrementAndGet() + "/" + totalFiles.get() + ")");
                    } catch (IOException | InterruptedException e) {
                        LOGGER.log(Level.SEVERE,
                                "❌ Error descargando asset " + assetName + ": " + e.getMessage(), e);
                        failCount.incrementAndGet();
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        LOGGER.info("✅ Proceso de descarga de assets completado: " + 
                successCount.get() + " éxitos, " + 
                failCount.get() + " fallos, de un total de " + 
                totalFiles.get() + " archivos");
    }

    public static void downloadClient(String version, int threadCount, String gameDir)
            throws IOException, InterruptedException {

        // 1) Extraer el objeto "client" de downloads
        JsonObject versionData = HttpUtils.getVersionData(version);
        if (versionData == null || versionData.has("Error")) {
            LOGGER.severe("❌ Error obteniendo datos de versión para " + version);
            return;
        }
        
        JsonObject downloads = versionData.getAsJsonObject("downloads");
        JsonObject clientDownload = downloads.getAsJsonObject("client");

        String url = clientDownload.get("url").getAsString();
        String sha1 = clientDownload.get("sha1").getAsString();
        long size = clientDownload.get("size").getAsLong();

        LOGGER.info(() -> String.format(
                "→ Cliente SHA1=%s, size=%d bytes, URL=%s",
                sha1, size, url
        ));

        // 2) Crear el directorio absoluto .../shared/versions/<version>/
        Path versionFolder = Paths.get(gameDir, "shared", "versions", version)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(versionFolder);

        // 3) Definir destino fijo como "client.jar"
        Path dest = versionFolder.resolve(version + ".jar");
        
        // Verificar si ya existe y tiene el tamaño y hash correctos
        if (Files.exists(dest) && Files.size(dest) == size) {
            String actualSha1 = calculateSHA1(dest);
            if (sha1.equalsIgnoreCase(actualSha1)) {
                LOGGER.info("✅ client.jar ya existe y es válido: " + dest);
                return;
            }
        }

        // 4) Lanzar descarga en un hilo y cerrar el pool limpiamente
        try (CloseableExecutorService ces =
                     new CloseableExecutorService(
                             Executors.newFixedThreadPool(threadCount))) {

            ExecutorService executor = ces.get();
            
            LOGGER.info("⬇️ Descargando client.jar para versión " + version);
            
            executor.submit(() -> {
                try {
                    downloadFile(url, dest.toString(), sha1);
                    LOGGER.info("✅ client.jar descargado y verificado: " + dest);
                } catch (IOException | InterruptedException e) {
                    LOGGER.log(Level.SEVERE,
                            "❌ Error descargando client.jar: " + e.getMessage(), e);
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    public static void downloadLibraries(String version, int threadCount, String gameDir) throws IOException {
        JsonObject versionData = HttpUtils.getVersionData(version);
        if (versionData == null || versionData.has("Error")) {
            LOGGER.severe("❌ Error obteniendo datos de versión para " + version);
            return;
        }
        
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        if (libraries == null || libraries.size() == 0) {
            LOGGER.warning("⚠️ No se encontraron bibliotecas para la versión " + version);
            return;
        }

        Path basePath = Paths.get(gameDir, "shared", "libraries").toAbsolutePath().normalize();
        Files.createDirectories(basePath);
        
        // Contadores para estadísticas
        final AtomicInteger totalLibs = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failCount = new AtomicInteger(0);
        final AtomicInteger skipCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (JsonElement libElement : libraries) {
                JsonObject lib = libElement.getAsJsonObject();
                String libName = lib.has("name") ? lib.get("name").getAsString() : "desconocido";
                
                // Verificar reglas si existen
                if (lib.has("rules")) {
                    boolean allowed = false;
                    JsonArray rules = lib.getAsJsonArray("rules");
                    
                    for (JsonElement ruleElement : rules) {
                        JsonObject rule = ruleElement.getAsJsonObject();
                        String action = rule.get("action").getAsString();
                        
                        if (rule.has("os") && rule.getAsJsonObject("os").has("name")) {
                            String osName = rule.getAsJsonObject("os").get("name").getAsString();
                            if (osName.equals(getOS())) {
                                allowed = "allow".equals(action);
                            }
                        } else {
                            allowed = "allow".equals(action);
                        }
                    }
                    
                    if (!allowed) {
                        skipCount.incrementAndGet();
                        LOGGER.fine("⏩ Saltando biblioteca por reglas: " + libName);
                        continue;
                    }
                }
                
                // Procesar la biblioteca
                if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("artifact")) {
                    skipCount.incrementAndGet();
                    continue;
                }

                JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                String url = artifact.get("url").getAsString();
                String sha1 = artifact.get("sha1").getAsString();
                long size = artifact.get("size").getAsLong();
                
                Path dest = basePath.resolve(path);
                totalLibs.incrementAndGet();
                
                // Verificar si ya existe y es válida
                if (Files.exists(dest) && Files.size(dest) == size) {
                    try {
                        String actualSha1 = calculateSHA1(dest);
                        if (sha1.equalsIgnoreCase(actualSha1)) {
                            successCount.incrementAndGet();
                            LOGGER.fine("✓ Biblioteca ya existe y es válida: " + path);
                            continue;
                        }
                    } catch (IOException e) {
                        // Continuar con la descarga si hay error al verificar
                    }
                }

                executor.submit(() -> {
                    try {
                        Files.createDirectories(dest.getParent());
                        downloadFile(url, dest.toString(), sha1);
                        LOGGER.info("✅ Biblioteca descargada y verificada: " + path + 
                                " (" + successCount.incrementAndGet() + "/" + totalLibs.get() + ")");
                    } catch (IOException | InterruptedException e) {
                        LOGGER.log(Level.SEVERE, "❌ Error descargando: " + path, e);
                        failCount.incrementAndGet();
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.log(Level.SEVERE, "❌ Executor interrumpido mientras esperaba", e);
            }
        }

        LOGGER.info("✅ Proceso de descarga de bibliotecas completado: " + 
                successCount.get() + " éxitos, " + 
                failCount.get() + " fallos, " +
                skipCount.get() + " omitidas, de un total de " + 
                totalLibs.get() + " bibliotecas.");
    }

    public static void downloadNatives(String version, int threadCount, String gameDir) throws IOException {
        JsonObject versionData = HttpUtils.getVersionData(version);
        
        // Verificar si hay error en la respuesta
        if (versionData == null || versionData.has("Error")) {
            LOGGER.severe("❌ Error: " + (versionData != null ? versionData.get("Error").getAsString() : "versionData es null"));
            return;
        }
        
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        if (libraries == null || libraries.size() == 0) {
            LOGGER.severe("❌ No se encontraron bibliotecas para la versión " + version);
            return;
        }

        String os = getOS();
        LOGGER.info("📦 Sistema operativo detectado: " + os);
        Path basePath = Paths.get(gameDir, "shared", "natives", version).toAbsolutePath().normalize();
        Path libBasePath = Paths.get(gameDir, "shared", "libraries").toAbsolutePath().normalize();
        Files.createDirectories(basePath);

        // Contar bibliotecas con natives para este sistema
        int nativesCount = 0;
        
        // Recorremos todas las bibliotecas
        for (JsonElement libElement : libraries) {
            JsonObject lib = libElement.getAsJsonObject();
            String libName = lib.has("name") ? lib.get("name").getAsString() : "desconocido";
            
            LOGGER.fine("Analizando biblioteca: " + libName);
            
            // Verificar reglas primero
            boolean allowed = true; // Por defecto, permitido si no hay reglas
            
            if (lib.has("rules")) {
                allowed = false; // Si hay reglas, inicialmente no permitido hasta que se diga lo contrario
                JsonArray rules = lib.getAsJsonArray("rules");
                
                // Procesar reglas
                for (JsonElement ruleElement : rules) {
                    JsonObject rule = ruleElement.getAsJsonObject();
                    String action = rule.get("action").getAsString();
                    
                    // Si tiene condiciones de OS
                    if (rule.has("os") && rule.getAsJsonObject("os").has("name")) {
                        String osName = rule.getAsJsonObject("os").get("name").getAsString();
                        
                        if (osName.equals(os)) {
                            // Esta regla aplica a nuestro OS
                            allowed = "allow".equals(action);
                            LOGGER.fine("Regla encontrada para " + os + ": " + action);
                        }
                    } else {
                        // Regla general sin OS específico
                        allowed = "allow".equals(action);
                        LOGGER.fine("Regla general encontrada: " + action);
                    }
                }
                
                if (!allowed) {
                    LOGGER.fine("⏩ Saltando biblioteca por reglas: " + libName);
                    continue;
                }
            }

            // Comprueba si es una biblioteca nativa basada en el nombre
            boolean isNative = libName.contains(":natives-" + os);
            boolean hasNativesSection = lib.has("natives") && lib.getAsJsonObject("natives").has(os);
            
            // Si no es nativa y no tiene sección de natives, saltamos
            if (!isNative && !hasNativesSection) {
                continue;
            }
            
            // A partir de aquí verificamos si tiene la descarga adecuada
            if (!lib.has("downloads")) {
                LOGGER.warning("⚠️ Biblioteca sin sección de descargas: " + libName);
                continue;
            }
            
            JsonObject downloads = lib.getAsJsonObject("downloads");
            JsonObject artifact = null;
            String nativeKey = null;
            
            // Primero intentamos con la estructura donde el native está en classifiers
            if (hasNativesSection) {
                nativeKey = lib.getAsJsonObject("natives").get(os).getAsString();
                LOGGER.info("🔍 Procesando native con clave: " + nativeKey);
                
                if (downloads.has("classifiers") && downloads.getAsJsonObject("classifiers").has(nativeKey)) {
                    artifact = downloads.getAsJsonObject("classifiers").getAsJsonObject(nativeKey);
                }
            }
            
            // Si no encontramos en classifiers o si el nombre indica que es un native directo
            if (artifact == null && isNative && downloads.has("artifact")) {
                artifact = downloads.getAsJsonObject("artifact");
                LOGGER.info("🔍 Procesando native directo: " + libName);
            }
            
            // Si no encontramos un artifact válido, saltamos
            if (artifact == null) {
                LOGGER.warning("⚠️ No se encontró artifact para native: " + libName);
                continue;
            }

            // Extraemos información necesaria
            String jarPathStr = artifact.get("path").getAsString();
            String url = artifact.get("url").getAsString();
            
            LOGGER.info("📥 Encontrado native para descargar: " + libName + " (" + url + ")");
            
            Path fullJarPath = libBasePath.resolve(jarPathStr);

            // Descargamos y extraemos
            try {
                if (!Files.exists(fullJarPath)) {
                    try {
                        Files.createDirectories(fullJarPath.getParent());
                        downloadFile(url, fullJarPath.toString());
                        LOGGER.info("✅ Native jar descargado: " + fullJarPath);
                    } catch (IOException | InterruptedException e) {
                        LOGGER.log(Level.SEVERE, "❌ Error descargando native jar: " + fullJarPath, e);
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                }
                
                try {
                    extractNativeJar(fullJarPath, basePath);
                    nativesCount++;
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "❌ Error extrayendo native jar: " + fullJarPath, e);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "❌ Error inesperado procesando: " + libName, e);
            }
        }

        if (nativesCount > 0) {
            LOGGER.info("✅ Se procesaron " + nativesCount + " natives para la versión " + version + " y sistema " + os);
        } else {
            LOGGER.warning("⚠️ No se encontraron natives para la versión " + version + " y sistema " + os);
        }
    }

    private static String getOS() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) return "windows";
        if (osName.contains("mac")) return "osx";
        if (osName.contains("linux")) return "linux";
        return "unknown";
    }

    public static void downloadVersionData(String version, String gameDir) {
        JsonObject Version = ManifestParser.getVersionMeta(version);
        Path filePath = Paths.get(gameDir, "shared", "versions", version, version + ".json")
                .toAbsolutePath()
                .normalize();
        try {
            downloadFile(Version.get("url").getAsString(), filePath.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void downloadVersionIndex(String version, String gameDir) {
        JsonObject versionData = HttpUtils.getVersionData(version);
        JsonObject assetIndex = versionData.getAsJsonObject("assetIndex");
        
        // Obtener la URL y el ID del assetIndex
        String assetIndexUrl = assetIndex.get("url").getAsString();
        String assetIndexId = assetIndex.get("id").getAsString();
        
        LOGGER.info("📥 Descargando índice de assets: " + assetIndexId + " para la versión " + version);
        
        // Usar el ID del assetIndex como nombre del archivo
        Path filePath = Paths.get(gameDir, "shared", "assets", "indexes", assetIndexId + ".json")
                .toAbsolutePath()
                .normalize();
                
        try {
            Files.createDirectories(filePath.getParent());
            downloadFile(assetIndexUrl, filePath.toString());
            LOGGER.info("✅ Índice de assets descargado: " + filePath);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "❌ Error descargando índice de assets", e);
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "❌ Descarga interrumpida", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void extractNativeJar(Path jarPath, Path destDir) throws IOException {
        AtomicInteger extractedCount = new AtomicInteger(0);
        LOGGER.info("📦 Extrayendo natives de " + jarPath + " hacia " + destDir);
        
        // Asegurar que el directorio de destino existe
        Files.createDirectories(destDir);
        
        try (FileSystem zipFs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            // Primero listar todos los archivos para diagnóstico
            StringBuilder jarContents = new StringBuilder();
            jarContents.append("📋 Contenido del JAR " + jarPath + ":\n");
            
            for (Path root : zipFs.getRootDirectories()) {
                Files.walk(root).forEach(path -> {
                    if (!Files.isDirectory(path)) {
                        jarContents.append("  - ").append(path).append("\n");
                    }
                });
            }
            
            LOGGER.info(jarContents.toString());
            
            // Luego extraer los archivos nativos
            for (Path root : zipFs.getRootDirectories()) {
                Files.walk(root).forEach(path -> {
                    try {
                        if (Files.isDirectory(path)) return;

                        // Extraer el nombre del archivo sin la ruta
                        String fileName = path.getFileName().toString();
                        
                        // Saltar META-INF y archivos no nativos comunes
                        if (path.toString().contains("META-INF")) {
                            LOGGER.fine("Ignorando META-INF: " + path);
                            return;
                        }
                        
                        // Verificar si es un nativo por su extensión
                        String lowerFileName = fileName.toLowerCase();
                        boolean isDll = lowerFileName.endsWith(".dll");
                        boolean isSo = lowerFileName.endsWith(".so");
                        boolean isDylib = lowerFileName.endsWith(".dylib") || lowerFileName.endsWith(".jnilib");
                        
                        // Extraer todos los archivos binarios, incluso si no son nativos reconocibles
                        if (isDll || isSo || isDylib) {
                            LOGGER.info("🔧 Archivo nativo encontrado: " + fileName);
                        } else if (lowerFileName.endsWith(".class") || 
                                  lowerFileName.endsWith(".txt") || 
                                  lowerFileName.endsWith(".git") ||
                                  lowerFileName.endsWith(".java") || 
                                  lowerFileName.endsWith(".md") ||
                                  lowerFileName.endsWith(".html") ||
                                  lowerFileName.endsWith(".properties")) {
                            // Ignorar archivos de código y texto
                            LOGGER.fine("Ignorando archivo de texto/código: " + fileName);
                            return;
                        } else {
                            // Para otros tipos de archivos desconocidos, extraerlos igualmente
                            LOGGER.info("📄 Extrayendo archivo desconocido: " + fileName);
                        }
                        
                        // Destino con solo el nombre del archivo, no toda la ruta
                        Path dest = destDir.resolve(fileName);
                        
                        // Copiar el archivo
                        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("📦 Extraído: " + dest.getFileName() + " (de " + path + ")");
                        extractedCount.getAndIncrement();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "⚠️ No se pudo extraer: " + path, e);
                    }
                });
            }
        }
        
        if (extractedCount.get() > 0) {
            LOGGER.info("✅ Se extrajeron " + extractedCount.get() + " archivos de " + jarPath);
            // Listar los archivos extraídos
            try {
                LOGGER.info("📂 Archivos en el directorio de destino " + destDir + ":");
                Files.list(destDir).forEach(file -> 
                    LOGGER.info("  - " + file.getFileName())
                );
            } catch (IOException e) {
                LOGGER.warning("⚠️ No se pudo listar el contenido del directorio de destino: " + e.getMessage());
            }
        } else {
            LOGGER.warning("⚠️ No se encontraron archivos válidos en " + jarPath);
        }
    }

    // Añadir método para calcular hash SHA-1
    private static String calculateSHA1(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            
            try (InputStream is = Files.newInputStream(filePath)) {
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            
            byte[] sha1sum = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : sha1sum) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            LOGGER.severe("SHA-1 algoritmo no disponible: " + e.getMessage());
            throw new RuntimeException("SHA-1 no disponible", e);
        }
    }

    /**
     * Wrapper AutoCloseable para gestionar correctamente el shutdown
     * de un ExecutorService.
     */
    private static class CloseableExecutorService implements AutoCloseable {
        private final ExecutorService executor;

        CloseableExecutorService(ExecutorService executor) {
            this.executor = executor;
        }

        ExecutorService get() {
            return executor;
        }

        @Override
        public void close() throws InterruptedException {
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                executor.shutdownNow();
            }
        }
    }

}