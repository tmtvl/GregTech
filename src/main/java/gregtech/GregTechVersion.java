package gregtech;

import java.util.regex.Pattern;

/**
 * @deprecated Use {@link GTInternalTags} instead. Any needed information can be added there
 *
 * </p> This class was deprecated in 2.6 and will be removed in 2.8
 */
@SuppressWarnings("unused")
@Deprecated
public final class GregTechVersion {

    /**
     * This number is incremented every complete compatibility break, such as full world/save incompatibility.
     */
    public static final int MAJOR;
    /**
     * This number is incremented every major feature update
     */
    public static final int MINOR;
    /**
     * This number is incremented every time the feature is added, or bug is fixed. Resets every major version change
     */
    public static final int REVISION;
    /**
     * This String is for additional info on the release version if needed (like alpha, beta, rc).
     */
    public static final String EXTRA;
    /**
     * This is the String formatted version, minus extra information, used for the @Mod annotation version
     */
    public static final String VERSION;
    /**
     * This is the String formatted version, used in builds and dependencies
     */
    public static final String DEP_VERSION;

    static {
        // parse the version data from the autogenerated InternalTags class, inserted with gradle

        // [MAJOR, MINOR, REVISION+EXTRA]
        String[] digits = Pattern.compile("\\.").split(GTInternalTags.VERSION);
        assert digits.length == 3;
        MAJOR = Integer.parseInt(digits[0]);
        MINOR = Integer.parseInt(digits[1]);

        // [REVISION] or [REVISION, EXTRA]
        String[] trailing = Pattern.compile("-").split(digits[2]);
        assert trailing.length > 0;
        REVISION = Integer.parseInt(trailing[0]);
        if (trailing.length == 1) EXTRA = "";
        else EXTRA = trailing[1];

        VERSION = MAJOR + "." + MINOR + "." + REVISION;
        DEP_VERSION = VERSION + "-" + EXTRA;
    }

    private GregTechVersion() {}
}
