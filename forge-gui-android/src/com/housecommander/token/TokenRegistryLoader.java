package com.housecommander.token;

import com.google.gson.Gson;
import com.housecommander.core.AssetSource;
import com.housecommander.forgebridge.LiveGameState;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Catalog of the exact token scripts shipped with this Forge version. */
public final class TokenRegistryLoader {
    private final Map<String, TokenDefinition> byId = new LinkedHashMap<>();
    private final Map<String, List<TokenDefinition>> byName = new HashMap<>();

    public static TokenRegistryLoader load(AssetSource assets) throws IOException {
        try (InputStreamReader in = new InputStreamReader(
                assets.open("tokens/token-registry.json"), StandardCharsets.UTF_8)) {
            TokenDefinition[] definitions = new Gson().fromJson(in, TokenDefinition[].class);
            if (definitions == null || definitions.length == 0) throw new IOException("Empty token catalog");
            return new TokenRegistryLoader(Arrays.asList(definitions));
        } catch (RuntimeException bad) {
            throw new IOException("Invalid token catalog", bad);
        }
    }

    public TokenRegistryLoader(Collection<TokenDefinition> definitions) {
        for (TokenDefinition d : definitions) {
            if (d == null || d.tokenId == null || d.name == null || d.artProfile == null
                    || d.tokenId.isEmpty() || d.name.isEmpty()) {
                throw new IllegalArgumentException("Incomplete token definition");
            }
            if (byId.put(d.tokenId, d) != null) throw new IllegalArgumentException("Duplicate token: " + d.tokenId);
            byName.computeIfAbsent(normalize(d.name), k -> new ArrayList<>()).add(d);
        }
    }

    public int size() { return byId.size(); }
    public Collection<TokenDefinition> definitions() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public TokenDefinition resolve(LiveGameState.CardState card) {
        String key = card.imageKey().replaceFirst("^t:", "");
        TokenDefinition exact = byId.get(key);
        if (exact != null) return exact;
        List<TokenDefinition> candidates = byName.get(normalize(card.name()));
        if (candidates == null) return null;
        // Counters can change P/T. Type and abilities take precedence over current P/T.
        TokenDefinition best = null;
        int bestScore = Integer.MIN_VALUE;
        for (TokenDefinition d : candidates) {
            int score = 0;
            String type = card.typeLine().toLowerCase(Locale.ROOT);
            for (String subtype : d.subtypes) score += type.contains(subtype.toLowerCase(Locale.ROOT)) ? 12 : -12;
            for (String keyword : d.keywords) score += card.keywords().contains(keyword) ? 4 : -2;
            if (!card.oracle().isEmpty() && d.oracle.equals(card.oracle())) score += 25;
            if (d.power.equals(String.valueOf(card.power()))) score += 2;
            if (d.toughness.equals(String.valueOf(card.toughness()))) score += 2;
            if (score > bestScore) { best = d; bestScore = score; }
        }
        return best;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+token$", "").trim();
    }
}
