package dev.tggamesyt.xaeroseedmap.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class StructureVisibility {
    private static final Set<String> disabled = Collections.synchronizedSet(new HashSet<>());

    private StructureVisibility() {}

    public static boolean isVisible(String setKey) {
        return !disabled.contains(setKey);
    }

    public static void setVisible(String setKey, boolean visible) {
        if (visible) disabled.remove(setKey);
        else         disabled.add(setKey);
    }
}
