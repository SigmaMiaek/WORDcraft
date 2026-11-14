package pl.wordcraft.core;

import java.nio.file.Path;
import java.util.Objects;

public record MediaAsset(String name, Path target, long downloadBytes, long diskBytes) {
    public MediaAsset {
        Objects.requireNonNull(name);
        Objects.requireNonNull(target);
        if (name.isBlank() || downloadBytes < 0 || diskBytes < 0) throw new IllegalArgumentException("Invalid media asset");
        target = target.toAbsolutePath().normalize();
    }
}
