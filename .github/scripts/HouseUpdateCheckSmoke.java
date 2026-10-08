import com.housecommander.core.CardDataUpdates;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;

public final class HouseUpdateCheckSmoke {
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String metadata = Files.readString(Path.of(args[0]));
        JsonObject object = JsonParser.parseString(metadata).getAsJsonObject();
        String same = CardDataUpdates.check(metadata, url -> {
            for (JsonElement e : object.getAsJsonArray("categories")) {
                JsonObject category = e.getAsJsonObject();
                String path = category.get("path").getAsString();
                if (url.contains(path.substring(path.lastIndexOf('/') + 1))) {
                    return "[{\"sha\":\"" + category.get("revision").getAsString()
                            + "\",\"commit\":{\"committer\":{\"date\":\"2026-10-08T00:00:00Z\"}}}]";
                }
            }
            throw new IOException("Unknown category");
        });
        require(same.contains("are current with"), "Matching revision was not recognized");
        String changed = CardDataUpdates.check(metadata, url -> "[{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"commit\":{\"committer\":{\"date\":\"2026-10-09T00:00:00Z\"}}}]");
        require(changed.contains("newer data available") && changed.contains("compatible HOUSE app update"), "New release was not recognized");
        String offline = CardDataUpdates.check(metadata, url -> { throw new IOException("offline"); });
        require(offline.contains("Check incomplete") && !offline.contains("are current with"), "Failed checks falsely reported current data");
        String invalid = CardDataUpdates.check(metadata, url -> "{}");
        require(invalid.contains("Check incomplete"), "Malformed server response was accepted");
        System.out.println("UPDATE_CHECK_FIXTURES_PASS current/new/offline/malformed");
        if (args.length > 1 && args[1].equals("live")) {
            String live = CardDataUpdates.check(metadata);
            require(!live.contains("Check incomplete"), live);
            System.out.println("LIVE_GITHUB_UPDATE_CHECK_PASS\n" + live);
        }
    }
}
