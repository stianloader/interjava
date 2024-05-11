package org.stianloader.interjava.supertypes;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ClassWrapperProvider {
    @Nullable
    ClassWrapper provide(@NotNull String name, @NotNull ClassWrapperPool pool);
}
