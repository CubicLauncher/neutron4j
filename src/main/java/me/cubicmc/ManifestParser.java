package me.cubicmc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ManifestParser {
    public static JsonObject getVersionMeta(String versionId) {
        JsonObject data = HttpUtils.getManifest();

        JsonArray versions = data.getAsJsonArray("versions");

        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if (version.get("id").getAsString().equals(versionId)) {
                return version;
            }
        }

        JsonObject notFound = new JsonObject();
        notFound.addProperty("Error", "Version doesn't exist");
        return notFound;
    }
}
