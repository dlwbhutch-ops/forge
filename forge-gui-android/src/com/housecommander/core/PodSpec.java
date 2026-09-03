package com.housecommander.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PodSpec {
    private final int round;
    private final int pod;
    private final List<String> members;

    public PodSpec(int round, int pod, List<String> members) {
        this.round = round;
        this.pod = pod;
        this.members = Collections.unmodifiableList(new ArrayList<String>(members));
    }

    public int round() { return round; }
    public int pod() { return pod; }
    public List<String> members() { return members; }
}
