package org.stianloader.interjava.supertypes;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;
import org.slf4j.LoggerFactory;

public class J8CWPComputingClassWriter extends ClassWriter {
    @NotNull
    private final ClassWrapperPool cwp;

    public J8CWPComputingClassWriter(@NotNull ClassWrapperPool cwp, int flags) {
        super(flags);
        this.cwp = cwp;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1 == null || type2 == null) {
            throw new NullPointerException("One of the child classes is null: " + (type1 == null) + ", " + (type2 == null));
        }

        String ret = this.cwp.getCommonSuperClass(this.cwp.get(type1), this.cwp.get(type2)).getName();
        if (ret.equals("java/lang/Record")) {
            LoggerFactory.getLogger(J8CWPComputingClassWriter.class).warn("Filtering out java 8-incompatible superclass: '{}' for child types '{}' and '{}'. Replacing it with the closest equivalent instead.", ret, type1, type2);
            ret = "java/lang/Object";
        }

        return ret;
    }
}
