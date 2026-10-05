package com.housecommander.token;

import java.util.List;
import java.util.Collections;

public final class TokenDefinition {
    public String tokenId;
    public String name;
    public List<String> types = Collections.emptyList();
    public List<String> subtypes = Collections.emptyList();
    public String power = "";
    public String toughness = "";
    public List<String> keywords = Collections.emptyList();
    public List<String> colors = Collections.emptyList();
    public String artProfile;
    public String animationProfile;
    public String oracle = "";
}
