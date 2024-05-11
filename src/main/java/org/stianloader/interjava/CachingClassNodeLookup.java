package org.stianloader.interjava;

import java.util.Map;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

final class CachingClassNodeLookup implements Function<String, ClassNode> {

    @NotNull
    private final Map<String, byte[]> rawData;
    private final Cache<String, ClassNode> classnodeCache = Caffeine.newBuilder().maximumSize(64).build();

    public CachingClassNodeLookup(@NotNull Map<String, byte[]> rawData) {
        this.rawData = rawData;
    }

    @Override
    public ClassNode apply(String t) {
        if (t.codePointAt(0) == '[') {
            LoggerFactory.getLogger(CachingClassNodeLookup.class).warn("Attempted to query ClassNode of array type '{}'. Consider it a bug.", t);
            return null;
        }
        if (t.indexOf('.') >= 0) {
            throw new IllegalStateException("input string expected to be a name of a class as an internal name, but instead got '" + t + "'");
        }

        ClassNode node = this.classnodeCache.getIfPresent(t);
        if (node != null) {
            return node;
        }

        byte[] data = this.rawData.get(t + ".class");
        if (data == null) {
            return null;
//            throw new IllegalStateException("Class '" + t + "' is undefined.");
        }

        ClassReader reader = new ClassReader(data);
        reader.accept((node = new ClassNode()), 0);
        this.classnodeCache.put(t, node);
        return node;
    }
}
