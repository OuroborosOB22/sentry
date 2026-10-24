package demo;

import org.apache.commons.lang3.StringUtils;

public class Words {
    public static String shout(String text) {
        return StringUtils.upperCase(StringUtils.trim(text));
    }
}
