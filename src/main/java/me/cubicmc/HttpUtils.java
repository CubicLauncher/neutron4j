package me.cubicmc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpUtils {
    public static JsonObject getManifest() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://launchermeta.mojang.com/mc/game/version_manifest.json"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            return jsonObject;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("error");
            JsonObject notFound = new JsonObject();
            notFound.addProperty("Error", "Version doesn't exist");
            return notFound;
        }
    }

    public static JsonObject getVersionData(String version) {
        try {
            JsonObject Version = ManifestParser.getVersionMeta(version);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Version.get("url").getAsString()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            return jsonObject;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("error");
            JsonObject notFound = new JsonObject();
            notFound.addProperty("Error", "Version doesn't exist");
            return notFound;
        }
    }

    public static JsonObject getAssetIndex(String version) {
        JsonObject versionData = HttpUtils.getVersionData(version);
        JsonObject assetIndexObject = versionData.getAsJsonObject("assetIndex");
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(assetIndexObject.get("url").getAsString()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            return jsonObject;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.out.println("error");
            JsonObject notFound = new JsonObject();
            notFound.addProperty("Error", "Version doesn't exist");
            return notFound;
        }
    }
}
